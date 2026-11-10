package com.sdk.glassessdksample.managers;

import android.app.Activity;
import android.os.Handler;
import android.util.Log;
import android.widget.Toast;

import com.sdk.glassessdksample.GlassApp;
import com.sdk.glassessdksample.utils.FileUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * AI对话PCM语音数据处理：
 * - 接收语音帧(voiceFromGlasses) → 保存为临时分片
 * - 文件轮询监控(startPcmMonitor/pcmPollLoop)
 * - 静默超时后拼接合并(mergePcmChunks)
 *
 * 职责：管理"AI语音对话"模式下眼镜麦克风回传的 PCM 音频数据流。
 * 核心流程：眼镜说话 → SDK 逐帧回调 voiceFromGlasses → 每帧保存为 temp 目录下
 * 一个带序号的 .pcm 分片 → 后台轮询监控分片数增长 → 连续 300ms 无新分片判定
 * 说话结束 → 按序号拼接为完整 .pcm 文件 → 删除 temp 并通知 UI 刷新。
 *
 * 设计动机：SDK 的 status(2)（说话结束）回调存在数十秒延迟，因此本类不依赖该回调，
 * 改用"文件数量静默超时"自行判断结束（见 {@link #mergePcmChunks()}）。
 *
 * 协作类：MediaSyncManager（voiceFromGlasses/voiceFromGlassesStatus 回调源）、
 * GlassApp（提供 audio 目录）、FileUtils（写分片/清理目录）。
 */
public class AiDialogueManager {

    private static final String TAG = "AiDialogueManager";

    /** 轮询间隔：每 100ms 检查一次 temp 目录的分片数量 */
    private static final int PCM_POLL_INTERVAL = 100;   // 轮询间隔 ms
    /** 静默超时：连续 300ms 没有新分片，判定说话结束 */
    private static final int PCM_SILENCE_MS = 300;      // 无新文件超时 ms
    /** 启动延迟：监控启动后等待 300ms 再开始轮询，给 SDK 一个缓冲 */
    private static final int PCM_START_DELAY = 300;     // 启动后延迟 ms

    /** 宿主 Activity，用于弹 Toast、获取上下文等 UI 操作 */
    private final Activity activity;
    /** 主线程 Handler，用于将后台结果切回 UI 线程 */
    private final Handler mainHandler;
    /** 音频文件列表变化回调：分片数变化或拼接完成时触发列表刷新 */
    private final Runnable onAudioFilesChanged;

    // PCM 文件监控状态
    private boolean pcmMonitorRunning = false;   // 轮询监控是否在运行
    private String pcmTempDir = null;            // 当前分片 temp 目录的绝对路径
    private int pcmLastFileCount = 0;            // 上一次轮询统计到的分片数量
    private long pcmLastChangeTime = 0;          // 最近一次发现新分片的时间戳(ms)
    private final AtomicInteger pcmChunkSeq = new AtomicInteger(0);  // 分片序号生成器（线程安全）

    /**
     * 构造 AI 对话管理器。
     *
     * @param activity            宿主 Activity
     * @param mainHandler         主线程 Handler
     * @param onAudioFilesChanged 音频文件变化回调
     */
    public AiDialogueManager(Activity activity, Handler mainHandler, Runnable onAudioFilesChanged) {
        this.activity = activity;
        this.mainHandler = mainHandler;
        this.onAudioFilesChanged = onAudioFilesChanged;
    }

    // ==================== 语音帧接收 ====================

    /**
     * 保存单个 PCM 语音帧到临时目录。
     * 由 MediaSyncManager 的 wifiDownloadListener.voiceFromGlasses 回调调用。
     *
     * 注意：分片按递增序号命名（0000.pcm、0001.pcm...），合并时按序号顺序
     * 读取即可还原完整音频流；临时目录未创建（未进入 AI 对话）时直接丢弃。
     *
     * @param data 一帧 PCM 原始音频数据（裸 PCM，不含 WAV 等文件头）
     */
    public void savePcmData(byte[] data) {
        if (pcmTempDir == null) {
            Log.w(TAG, "PCM临时目录未创建，无法保存语音帧");
            return;
        }
        int seq = pcmChunkSeq.getAndIncrement();
        String fileName = String.format("%04d", seq) + ".pcm";
        String filePath = pcmTempDir + "/" + fileName;
        FileUtils.writeToFile(data, filePath);
    }

    /**
     * 眼镜语音状态变化回调。
     * 由 MediaSyncManager 的 wifiDownloadListener.voiceFromGlassesStatus 调用。
     *
     * @param status 眼镜语音状态：1=开始说话，2=结束说话（此处不依赖 2，见下方注释）
     */
    public void onVoiceFromGlassesStatus(int status) {
        Log.i(TAG, status == 1 ? "眼镜开始说话" : "眼镜说话结束(来自SDK)");
        if (status == 1) {
            mainHandler.post(this::startPcmMonitor);
        }
        // status=2 不再依赖，由监控器自行判断结束
    }

    // ==================== PCM 文件监控 ====================

    /**
     * 启动 PCM 文件监控。
     * 创建 temp 目录，延迟 PCM_START_DELAY 后开始轮询。
     *
     * 流程：防重入 → 校验设备目录 → 清理旧 temp → 创建新 temp → 记录初始分片数
     * 与时间戳 → postDelayed 延迟启动 {@link #pcmPollLoop(File)}。
     */
    private void startPcmMonitor() {
        if (pcmMonitorRunning) return;

        File audioDir = GlassApp.getInstance().getAudioDir();
        if (audioDir == null) {
            Log.w(TAG, "设备名称未设置，无法启动PCM监控");
            return;
        }

        // 清理旧的 temp
        cleanupPcmTemp();

        // 创建临时目录
        pcmTempDir = audioDir.getAbsolutePath() + "/temp";
        File tempDir = new File(pcmTempDir);
        if (!tempDir.exists()) tempDir.mkdirs();

        pcmMonitorRunning = true;
        pcmChunkSeq.set(0);
        pcmLastFileCount = countPcmFiles(tempDir);
        pcmLastChangeTime = System.currentTimeMillis();
        Log.i(TAG, "PCM监控启动，临时目录: " + pcmTempDir
                + "，初始文件数: " + pcmLastFileCount
                + "，延迟 " + PCM_START_DELAY + "ms 后开始轮询");

        mainHandler.postDelayed(() -> {
            if (pcmMonitorRunning) {
                pcmPollLoop(tempDir);
            }
        }, PCM_START_DELAY);
    }

    /**
     * PCM 分片轮询循环（每 PCM_POLL_INTERVAL 毫秒执行一次）。
     * 统计 temp 目录中分片数量：
     * - 数量有增长 → 更新 lastFileCount/lastChangeTime，并通知 UI 刷新；
     * - 连续 PCM_SILENCE_MS 无增长 → 判定说话结束，停止监控并触发拼接合并。
     * 通过 mainHandler.postDelayed 自我循环调度，不阻塞调用线程。
     *
     * @param tempDir 分片临时目录
     */
    private void pcmPollLoop(final File tempDir) {
        if (!pcmMonitorRunning) return;

        // 统计当前分片数，与上次对比判断是否有新数据到达
        int currentCount = countPcmFiles(tempDir);
        long now = System.currentTimeMillis();
        int delta = currentCount - pcmLastFileCount;

        Log.d(TAG, "PCM轮询: 文件总数=" + currentCount
                + ", 变化=" + (delta >= 0 ? "+" : "") + delta
                + ", 距上次变化=" + (now - pcmLastChangeTime) + "ms");

        // 有新分片到达：更新计数与时间戳，并通知列表刷新
        if (currentCount > pcmLastFileCount) {
            pcmLastFileCount = currentCount;
            pcmLastChangeTime = now;
            if (onAudioFilesChanged != null) {
                mainHandler.post(onAudioFilesChanged);
            }
        }

        // 超过静默阈值：判定说话结束，停止轮询并拼接所有分片
        long silentDuration = now - pcmLastChangeTime;
        if (silentDuration >= PCM_SILENCE_MS) {
            pcmMonitorRunning = false;
            Log.i(TAG, "PCM监控：说话完毕（" + (silentDuration / 1000.0) + "秒无新文件，共 "
                    + pcmLastFileCount + " 个分片）");
            mergePcmChunks();
            return;
        }

        mainHandler.postDelayed(() -> pcmPollLoop(tempDir), PCM_POLL_INTERVAL);
    }

    // ==================== 分片合并 ====================

    /**
     * 将 temp 目录中所有分片按序号拼接为一个完整 .pcm 文件，
     * 保存到 audio 目录下，然后删除 temp 目录。
     *
     * 注意：拼接在子线程 "pcm-merge" 中执行，避免大文件拼接阻塞主线程；
     * 完成后通过 mainHandler 切回主线程刷新列表并弹 Toast。
     */
    private void mergePcmChunks() {
        if (pcmTempDir == null) return;

        final String tempDir = pcmTempDir;
        pcmTempDir = null;

        File dir = new File(tempDir);
        File audioDir = GlassApp.getInstance().getAudioDir();
        if (audioDir == null) {
            FileUtils.cleanupDir(dir);
            return;
        }

        final String finalPath = audioDir.getAbsolutePath() + "/voice_"
                + System.currentTimeMillis() + ".pcm";
        final int totalChunks = pcmLastFileCount;

        new Thread(() -> {
            // 在子线程中逐片写入：按 0000~totalChunks-1 顺序读取并追加到目标文件
            try (FileOutputStream fos = new FileOutputStream(finalPath, false)) {
                byte[] buf = new byte[4096];
                int read;
                for (int i = 0; i < totalChunks; i++) {
                    File chunk = new File(tempDir + "/"
                            + String.format("%04d", i) + ".pcm");
                    if (!chunk.exists()) continue;
                    try (FileInputStream fis = new FileInputStream(chunk)) {
                        while ((read = fis.read(buf)) != -1) {
                            fos.write(buf, 0, read);
                        }
                    }
                }
                fos.flush();
            } catch (IOException e) {
                Log.e(TAG, "PCM拼接失败", e);
            }

            // 删除 temp
            FileUtils.cleanupDir(dir);

            File finalFile = new File(finalPath);
            Log.i(TAG, "PCM拼接完成: " + finalFile.getName()
                    + ", " + finalFile.length() + " bytes ("
                    + String.format("%.1f", finalFile.length() / 1024.0) + " KB, ~"
                    + String.format("%.1f", finalFile.length() / 32000.0) + " 秒)");
            // 回到主线程：刷新音频列表并提示"说话完毕"
            mainHandler.post(() -> {
                if (onAudioFilesChanged != null) {
                    onAudioFilesChanged.run();
                }
                Toast.makeText(activity, "说话完毕", Toast.LENGTH_SHORT).show();
            });
        }, "pcm-merge").start();
    }

    // ==================== 清理 ====================

    /**
     * 停止 PCM 文件监控并清理临时目录。
     */
    public void stopPcmMonitor() {
        pcmMonitorRunning = false;
        cleanupPcmTemp();
    }

    /** 删除 temp 临时目录（若存在），并置空 pcmTempDir。 */
    private void cleanupPcmTemp() {
        if (pcmTempDir != null) {
            FileUtils.cleanupDir(new File(pcmTempDir));
            pcmTempDir = null;
        }
    }

    /** 统计目录下 .pcm 分片文件的数量（目录不存在或为空时返回 0）。 */
    private int countPcmFiles(File dir) {
        if (dir == null || !dir.exists()) return 0;
        String[] pcmFiles = dir.list((d, name) -> name.endsWith(".pcm"));
        return pcmFiles != null ? pcmFiles.length : 0;
    }

    /** 页面销毁时释放资源：停止监控并清理临时目录。 */
    public void onDestroy() {
        stopPcmMonitor();
    }
}
