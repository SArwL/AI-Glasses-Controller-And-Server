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
 * <p><b>拼接产物不做长期保存</b>：AI 对话的 PCM 只是"送给后端做语音识别（ASR）的过路数据"，
 * 因此拼接结果写到<b>应用缓存目录</b>下的中转目录（而非 audio/ 媒体目录），既不出现在音频
 * 文件列表里，也不占用用户媒体存储；发送给后端后由 {@link AiUploader} 立即删除。
 * 需要长期保留的是普通录音/拍照/录像——它们经 WiFi 通道同步到 audio/、picture/、video/
 * 目录（见 MediaSyncManager），不受本类影响。</p>
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
    /** 中转目录名：AI 对话 PCM 的拼接产物存放于此（应用缓存目录下），非用户媒体 */
    private static final String TRANSIENT_DIR_NAME = "ai_dialogue_pcm";

    /** 宿主 Activity，用于弹 Toast、获取上下文等 UI 操作 */
    private final Activity activity;
    /** 主线程 Handler，用于将后台结果切回 UI 线程 */
    private final Handler mainHandler;
    /** 音频文件列表变化回调：分片数变化或拼接完成时触发列表刷新 */
    private final Runnable onAudioFilesChanged;

    /** 一轮对话的 PCM 拼接完成回调（用于上传后端），可为 null */
    private OnPcmMergedListener onPcmMergedListener;

    /**
     * 用户再次开始说话的回调（眼镜麦克风启动），可为 null。
     * 用途：播报 AI 回复时用户按眼镜按键抢话 → 需要打断当前播报。
     */
    private Runnable onUserSpeechStartListener;

    /**
     * PCM 拼接完成回调。
     * 在拼接子线程（"pcm-merge"）中触发，**非主线程**，实现方需自行处理线程切换。
     *
     * <p><b>文件所有权转移</b>：回调一旦触发，{@code pcmFile} 的清理责任就交给实现方
     * （本轮数据已不再需要本地保留）。实现方用完必须删除，否则会残留在缓存目录中。</p>
     */
    public interface OnPcmMergedListener {
        /**
         * @param pcmFile    拼接完成的完整 PCM 文件（位于中转目录，用完需删除）
         * @param durationMs 估算的语音时长（毫秒），按 32 字节/毫秒折算
         */
        void onPcmMerged(File pcmFile, int durationMs);
    }

    /** 设置 PCM 拼接完成回调 */
    public void setOnPcmMergedListener(OnPcmMergedListener listener) {
        this.onPcmMergedListener = listener;
    }

    /**
     * 设置"用户再次开始说话"回调（眼镜麦克风启动时触发）。
     * 实现方应在此打断正在播报的 AI 回复，见 {@code AiReplyAudioManager#interruptByUser}。
     */
    public void setOnUserSpeechStartListener(Runnable listener) {
        this.onUserSpeechStartListener = listener;
    }

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
            // 用户又开始说话 → 先通知外部打断正在播报的 AI 回复，再开始接收新一轮语音
            if (onUserSpeechStartListener != null) {
                try {
                    onUserSpeechStartListener.run();
                } catch (Exception e) {
                    Log.e(TAG, "用户开始说话回调异常", e);
                }
            }
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
     * 保存到中转目录（见 {@link #getTransientDir()}），然后删除 temp 目录。
     *
     * 注意：拼接在子线程 "pcm-merge" 中执行，避免大文件拼接阻塞主线程；
     * 完成后通过 mainHandler 切回主线程刷新列表并弹 Toast。
     * 产物由上传方（{@link #onPcmMergedListener}）负责删除，本类不长期保留。
     */
    private void mergePcmChunks() {
        if (pcmTempDir == null) return;

        final String tempDir = pcmTempDir;
        pcmTempDir = null;

        File dir = new File(tempDir);
        File outDir = getTransientDir();
        if (outDir == null) {
            // 中转目录不可用：丢弃分片，避免残留在 temp 里
            FileUtils.cleanupDir(dir);
            return;
        }

        final String finalPath = outDir.getAbsolutePath() + "/voice_"
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

            // 通知外部（如后端上传）；在拼接线程中直接回调，避免大文件操作影响主线程
            // 注意：回调成功后文件所有权转移给实现方（由其上传后删除）；
            // 下面两个 else 分支负责"无人接手"时的兜底清理，防止残留在缓存目录
            if (onPcmMergedListener != null && finalFile.exists() && finalFile.length() > 0) {
                int durationMs = (int) (finalFile.length() / 32);  // 16kHz*16bit*单声道 = 32 字节/毫秒
                try {
                    onPcmMergedListener.onPcmMerged(finalFile, durationMs);
                } catch (Exception e) {
                    Log.e(TAG, "PCM 拼接完成回调异常，丢弃产物", e);
                    FileUtils.deleteFile(finalFile);
                }
            } else if (onPcmMergedListener != null) {
                Log.w(TAG, "PCM拼接产物为空，已清理");
                FileUtils.deleteFile(finalFile);
            } else {
                Log.w(TAG, "未接PCM上传钩子，丢弃拼接产物: " + finalFile.getName());
                FileUtils.deleteFile(finalFile);
            }

            // 回到主线程：刷新音频列表并提示"说话完毕"
            mainHandler.post(() -> {
                if (onAudioFilesChanged != null) {
                    onAudioFilesChanged.run();
                }
                Toast.makeText(activity, "说话完毕", Toast.LENGTH_SHORT).show();
            });
        }, "pcm-merge").start();
    }

    // ==================== 中转目录 ====================

    /**
     * 获取 AI 对话 PCM 的中转目录：{@code <cacheDir>/ai_dialogue_pcm}。
     *
     * <p>选 cacheDir 而非 {@link GlassApp#getAudioDir()} 的原因：
     * 一是 AI 对话 PCM 不进入音频列表（列表只扫 audio/ 目录）；
     * 二是它属于"过路数据"，放在缓存目录可让系统在存储紧张时自行回收；
     * 三是与用户媒体（普通录音等）在语义和路径上彻底分开，避免误删。</p>
     *
     * @return 已确保存在的中转目录；cacheDir 不可用或创建失败时返回 null
     */
    private File getTransientDir() {
        File cacheDir = activity.getCacheDir();
        if (cacheDir == null) {
            Log.w(TAG, "cacheDir 不可用，无法获取PCM中转目录");
            return null;
        }
        File dir = new File(cacheDir, TRANSIENT_DIR_NAME);
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "PCM中转目录创建失败: " + dir.getAbsolutePath());
            return null;
        }
        return dir;
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
