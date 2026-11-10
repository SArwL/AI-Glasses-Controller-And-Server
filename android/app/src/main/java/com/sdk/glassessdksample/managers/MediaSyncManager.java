package com.sdk.glassessdksample.managers;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.util.Log;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;

import com.oudmon.ble.base.bluetooth.BleOperateManager;
import com.oudmon.ble.base.communication.LargeDataHandler;
import com.oudmon.ble.base.communication.bigData.resp.GlassesDeviceNotifyListener;
import com.oudmon.ble.base.communication.bigData.resp.GlassesDeviceNotifyRsp;
import com.oudmon.wifi.GlassesControl;
import com.oudmon.wifi.bean.GlassAlbumEntity;
import com.sdk.glassessdksample.GlassApp;
import com.sdk.glassessdksample.utils.PcmUtils;
import com.sdk.glassessdksample.utils.PermissionHelper;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * WiFi媒体同步、设备事件监听、媒体导入。
 * 这是所有Manager的中心枢纽，持有对其他所有Manager的引用。
 *
 * <p>职责：</p>
 * <ul>
 *   <li>WiFi媒体同步：拍照/录音完成后查询新媒体数量并触发 importAlbum()，由 SDK 内部通过
 *       WiFi(P2P) 将眼镜上的媒体文件下载到本地，再按扩展名分类到 picture/video/audio 子目录。</li>
 *   <li>设备事件监听：通过 {@link LargeDataHandler} 注册 {@link GlassesDeviceNotifyListener}，
 *       解析缩略图、电量、麦克风启动等事件并分发到对应 Manager。</li>
 *   <li>初始化编排：监听器采用两阶段注册——先在 {@link #initialize()} 中创建实例，
 *       待 BLE 连接成功且设备名确定后再由 {@link #reinitializeAfterConnection()} 统一注册。</li>
 * </ul>
 *
 * <p>关键流程（WiFi同步）：</p>
 * <ol>
 *   <li>{@link #syncAfterCapture()} 发送查询指令(0x02, 0x04)获取新照片/视频/录音数量。</li>
 *   <li>数量大于 0 时调用 {@link #startAlbumImport()} → {@code GlassesControl.importAlbum()}。</li>
 *   <li>SDK 通过 {@link GlassesControl.WifiFilesDownloadListener} 回调下载进度/完成/失败。</li>
 *   <li>文件下载成功后在回调中移动到分类目录，并刷新 UI；同步状态与收尾完全交给 SDK 内部处理。</li>
 * </ol>
 *
 * <p>协作类：{@link PhotoManager}（缩略图/预览）、{@link RecordingManager}（录音）、
 * {@link AiDialogueManager}（AI语音PCM流）、{@link AudioPlaybackManager}（音频列表）、
 * {@link BleConnectionManager}（电量显示）、{@link GlassApp}（设备目录结构）。</p>
 *
 * <p>经验教训：WiFi 同步曾因自定义超时/清理代码导致同步状态卡死，最终方案是精简代码、
 * 信任 SDK 内部流程——各回调中只做文件分类与 UI 展示，不主动中断或清理同步状态。</p>
 */
public class MediaSyncManager {

    private static final String TAG = "MediaSyncManager";

    private final Activity activity;
    private final Handler mainHandler;
    private final PhotoManager photoManager;
    private final BleConnectionManager bleConnectionManager;
    private final RecordingManager recordingManager;
    private final AiDialogueManager aiDialogueManager;
    private final AudioPlaybackManager audioPlaybackManager;

    private GlassesControl.WifiFilesDownloadListener wifiDownloadListener;
    private GlassesDeviceNotifyListener deviceNotifyListener;

    public MediaSyncManager(Activity activity, Handler mainHandler,
                            PhotoManager photoManager,
                            BleConnectionManager bleConnectionManager,
                            RecordingManager recordingManager,
                            AiDialogueManager aiDialogueManager,
                            AudioPlaybackManager audioPlaybackManager) {
        this.activity = activity;
        this.mainHandler = mainHandler;
        this.photoManager = photoManager;
        this.bleConnectionManager = bleConnectionManager;
        this.recordingManager = recordingManager;
        this.aiDialogueManager = aiDialogueManager;
        this.audioPlaybackManager = audioPlaybackManager;
    }

    // ==================== 初始化 ====================

    /**
     * 创建并注册所有监听器（在 onCreate 中调用，此时设备名可能尚未确定）。
     * 不调用 initGlasses() —— 等 BLE 连接成功后再调用。
     *
     * 两阶段初始化：此处只创建监听器实例，所有 SDK 注册动作统一放到
     * {@link #reinitializeAfterConnection()}（BLE 已连接 + 设备名确定 + initGlasses 之后）。
     */
    public void initialize() {
        // 仅创建监听器实例，不注册。
        // 所有SDK注册操作延迟到 reinitializeAfterConnection()，
        // 确保在 BLE 已连接 + 设备名已确定 + initGlasses() 之后才进行。
        createDeviceNotifyListener();
        createWifiDownloadListener();
        Log.i(TAG, "监听器实例已创建，等待BLE连接后注册");
    }

    // ==================== 重连后重新初始化 ====================

    /**
     * BLE连接成功后的延迟重初始化。
     * 重新注册监听器 + initGlasses + 初始媒体查询。
     *
     * 注意：initGlasses() 可能清空之前注册的下载监听器，因此必须先重新
     * setWifiDownloadListener，再发送初始媒体查询指令。
     */
    public void reinitializeAfterConnection() {
        // 双保险：再次确保设备目录结构已创建
        GlassApp.getInstance().ensureDeviceDirectories();

        // initGlasses 必须在设备名称确定后才调用
        File albumDir = GlassApp.getInstance().getAlbumDir();
        if (albumDir != null) {
            GlassesControl.Companion.getInstance(GlassApp.getInstance())
                    .initGlasses(albumDir.getAbsolutePath());
            Log.i(TAG, "initGlasses 目录: " + albumDir.getAbsolutePath());
        } else {
            Log.e(TAG, "设备名称仍为空，跳过 initGlasses！请检查 DeviceManager 状态。");
        }

        // 重新注册设备事件监听器
        LargeDataHandler.getInstance().addOutDeviceListener(100, deviceNotifyListener);

        // 重新注册电量主动查询回调
        LargeDataHandler.getInstance().addBatteryCallBack("MainActivity", (cmdType, response) -> {
            if (response != null) {
                bleConnectionManager.updateBattery(response.getBattery() & 0xFF, -1);
            }
        });
        // 主动向眼镜查询一次电量，通过上面的回调刷新 UI
        LargeDataHandler.getInstance().syncBattery();

        // 重新注册下载监听器（initGlasses 可能清除了之前注册的 listener）
        if (wifiDownloadListener != null) {
            GlassesControl.Companion.getInstance(GlassApp.getInstance())
                    .setWifiDownloadListener(wifiDownloadListener);
        }

        // 发送初始媒体查询指令（0x02,0x04 数量查询），用于刷新同步入口状态
        LargeDataHandler.getInstance().glassesControl(
                new byte[]{0x02, 0x04}, (cmdType, response) -> {});

        Log.i(TAG, "已重新注册设备事件监听器");
    }

    // ==================== 媒体同步 ====================

    /**
     * 拍照/录音完成后查询新媒体文件数量，有则触发导入。
     */
    public void syncAfterCapture() {
        if (!BleOperateManager.getInstance().isConnected()) {
            Log.w(TAG, "设备未连接，跳过媒体同步");
            return;
        }

        // 0x02,0x04 = 查询新媒体文件数量指令；SDK 会异步回调响应
        byte[] cmd = new byte[]{0x02, 0x04};
        LargeDataHandler.getInstance().glassesControl(cmd,
                (cmdType, response) -> {
                    // dataType=4 表示媒体数量统计响应，图片/视频/录音计数各占一个字段
                    if (response != null && response.getDataType() == 4) {
                        int mediaCount = response.getImageCount() + response.getVideoCount() + response.getRecordCount();
                        if (mediaCount > 0) {
                            Log.i(TAG, "检测到 " + mediaCount + " 个新媒体文件，开始同步");
                            startAlbumImport();
                        }
                    }
                }
        );
    }

    /**
     * 启动媒体导入（WiFi同步）。
     */
    public void startAlbumImport() {
        Log.i(TAG, "启动媒体同步...");
        Toast.makeText(activity, "开始同步媒体文件...", Toast.LENGTH_SHORT).show();
        try {
            // importAlbum() 由 SDK 内部完成：WiFi连接→下载→收尾。同步完成/失败都通过
            // wifiDownloadListener 回调上报，不做自定义超时/清理（信任 SDK 内部流程）。
            GlassesControl.Companion.getInstance(GlassApp.getInstance()).importAlbum();
        } catch (Exception e) {
            Log.e(TAG, "媒体同步异常", e);
            Toast.makeText(activity, "媒体同步异常: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    // ==================== WiFi权限 ====================

    /**
     * 当前是否已具备 WiFi 同步所需的权限。
     * @return true 已授权，false 未授权（可能需要请求权限）
     */
    public boolean hasWifiPermission() {
        return PermissionHelper.hasWifiPermission(activity);
    }

    /**
     * 返回需要请求的WiFi权限列表。如已全部授权则返回空数组。
     */
    public String[] getNeededWifiPermissions() {
        List<String> needed = new ArrayList<>();
        // Android 13+ 需要“附近WiFi设备”权限；Android 12 及以下以定位权限作为 WiFi 授权依据
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(activity, Manifest.permission.NEARBY_WIFI_DEVICES)
                    != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.NEARBY_WIFI_DEVICES);
            }
        } else {
            if (ActivityCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.ACCESS_FINE_LOCATION);
            }
        }
        return needed.toArray(new String[0]);
    }

    // ==================== 设备事件监听器 ====================

    /**
     * 创建设备事件监听器实例（只实例化，不注册）。
     * 解析眼镜主动上报的事件：缩略图就绪、麦克风启动、电量上报、WiFi IP、心跳、语音播报等，
     * 由 eventType 分发到对应 Manager。
     */
    private void createDeviceNotifyListener() {
        deviceNotifyListener = new GlassesDeviceNotifyListener() {
            @Override
            public void parseData(int cmdType, GlassesDeviceNotifyRsp response) {
              // 整段分发必须兜底：本方法在主线程执行，事件处理里任何一处异常都会让 App
              // 直接闪退（按键打断的 NetworkOnMainThreadException 就是这么崩的）。
              // 单个事件处理失败不该带走整个进程，记日志后忽略即可。
              try {
                // loadData[0..5] 为协议固定字段，loadData[6] 为事件类型，loadData[7..] 为事件参数
                int eventType = response.getLoadData()[6] & 0xFF;

                switch (eventType) {

                    case 0x02: // 智能识图/缩略图
                        Log.i(TAG, "收到缩略图事件，延迟1.5s后获取缩略图...");
                        mainHandler.postDelayed(() ->
                                LargeDataHandler.getInstance().getPictureThumbnails(
                                        (cmdType2, success, data) -> {
                                            if (data != null && data.length > 0) {
                                                Log.d(TAG, "缩略图分包: success=" + success + ", dataLen=" + data.length);
                                            }
                                            // 缩略图数据按分包回调，交由 PhotoManager 累积拼接成完整图片
                                            photoManager.receiveThumbnailChunk(data, success);
                                        }), 1500);
                        break;

                    case 0x03: // 麦克风启动：用户按下 AI 对话按键，眼镜开始收音
                        if (response.getLoadData()[7] == 1) {
                            Log.i(TAG, "眼镜麦克风已启动（用户按下 AI 对话按键）");
                            // 用户抢话 → 打断正在播报的 AI 回复。
                            // 没有在播报时是空操作，因此正常按键说话不会误发 0x40
                            AiReplyAudioManager.interruptIfSpeaking("眼镜0x03麦克风启动");
                        }
                        break;

                    case 0x05: // 电量上报
                        int battery = response.getLoadData()[7] & 0xFF;   // 电量百分比
                        int charging = response.getLoadData()[8] & 0xFF;  // 充电状态：1=充电中
                        // 切回主线程更新 UI
                        mainHandler.post(() -> bleConnectionManager.updateBattery(battery, charging));
                        break;

                    case 0x08: // WiFi IP地址（SDK内部使用）
                        break;

                    case 0x0b: // 设备心跳/文件同步状态事件
                        break;

                    case 0x0c: // 眼镜触发暂停事件（语音播报）—— SDK 文档第 6 页原文注释
                        // SDK 内部对这个事件的既有处理是：清语音心跳/超时任务 → aiVoicePlay(3)
                        // → 拆 P2P → 回调 voiceFromGlassesStatus(2)，即"播报被打断/说话结束"
                        if (response.getLoadData()[7] == 1) {
                            Log.i(TAG, "眼镜0x0c（暂停/语音播报）→ 打断播报");
                            AiReplyAudioManager.interruptIfSpeaking("眼镜0x0c暂停事件");
                        } else {
                            Log.i(TAG, "眼镜事件0x0c: loadData[7]="
                                    + (response.getLoadData()[7] & 0xFF) + "（非触发状态，忽略）");
                        }
                        break;

                    default:
                        break;
                }
              } catch (Exception e) {
                // 兜底：设备事件处理异常只记日志，不让它杀掉 App
                Log.e(TAG, "设备事件处理异常，已忽略该事件", e);
              }
            }
        };
    }

    // ==================== WiFi下载监听器 ====================

    /**
     * 创建 WiFi 下载监听器实例（只实例化，不注册）。
     * 承载媒体同步全流程回调：WiFi连接成功/失败、单文件下载成功/进度、批量完成、
     * 录音 OPUS→PCM 转换、AI 对话语音流与状态等。
     */
    private void createWifiDownloadListener() {
        wifiDownloadListener = new GlassesControl.WifiFilesDownloadListener() {

            @Override
            public void fileWasDownloadSuccessfully(GlassAlbumEntity entity) {
                String fName = entity.getFileName();
                String fPath = entity.getFilePath();
                Log.i(TAG, "文件下载成功: " + fName);

                // 按文件类型移动到对应子目录
                String fileName = fName.toLowerCase();
                File srcFile = new File(fPath);
                File destDir = null;
                if (fileName.endsWith(".jpg") || fileName.endsWith(".jpeg")
                        || fileName.endsWith(".png")) {
                    destDir = GlassApp.getInstance().getPhotoDir();
                } else if (fileName.endsWith(".mp4")) {
                    destDir = GlassApp.getInstance().getVideoDir();
                } else if (fileName.endsWith(".pcm") || fileName.endsWith(".wav")
                        || fileName.endsWith(".mp3") || fileName.endsWith(".aac")
                        || fileName.endsWith(".opus")) {
                    destDir = GlassApp.getInstance().getAudioDir();
                }

                // 移动文件到分类目录
                final String finalPath;
                if (destDir != null) {
                    File destFile = new File(destDir, fName);
                    if (srcFile.exists() && srcFile.renameTo(destFile)) {
                        finalPath = destFile.getAbsolutePath();
                        Log.i(TAG, "文件已分类: " + finalPath);
                    } else {
                        finalPath = fPath;
                        Log.w(TAG, "文件移动失败，保留原路径: " + fPath);
                    }
                } else {
                    finalPath = fPath;
                    Log.w(TAG, "设备名称未设置，文件保留在下载目录: " + fPath);
                }

                final String finalName = fName;
                mainHandler.post(() -> {
                    String lower = finalName.toLowerCase();
                    if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                            || lower.endsWith(".png")) {
                        Toast.makeText(activity,
                                "照片已下载: " + finalName, Toast.LENGTH_SHORT).show();
                        photoManager.loadPhotoPreview(finalPath);
                    } else if (lower.endsWith(".pcm") || lower.endsWith(".wav")
                            || lower.endsWith(".mp3") || lower.endsWith(".aac")
                            || lower.endsWith(".opus")) {
                        Toast.makeText(activity,
                                "录音已下载: " + finalName, Toast.LENGTH_SHORT).show();
                        audioPlaybackManager.refreshAudioFileList();
                    } else if (lower.endsWith(".mp4")) {
                        Toast.makeText(activity,
                                "视频已下载: " + finalName, Toast.LENGTH_SHORT).show();
                    }
                });
            }

            @Override
            public void fileProgress(String fileName, int progress) {
                // 下载进度回调（不记录日志以减少内存占用）
            }

            @Override
            public void fileCount(int index, int total) {
                Log.i(TAG, "批量下载: " + index + "/" + total);
            }

            @Override
            public void fileDownloadComplete() {
                Log.i(TAG, "所有文件下载完成");
                // 同步完成由 SDK 主动回调，此处只刷新 UI，不做任何清理/中断（信任 SDK 内部流程）
                mainHandler.post(() -> {
                    Toast.makeText(activity, "全部文件同步完成", Toast.LENGTH_SHORT).show();
                    audioPlaybackManager.refreshAudioFileList();
                });
            }

            @Override
            public void fileDownloadError(int fileType, int errorType) {
                Log.e(TAG, "文件下载失败: fileType=" + fileType + ", errorType=" + errorType);
            }

            @Override
            public void onGlassesControlSuccess() {
                Log.i(TAG, "WiFi连接成功，开始同步");
                mainHandler.post(() -> Toast.makeText(activity,
                        "WiFi连接成功，开始同步", Toast.LENGTH_SHORT).show());
            }

            @Override
            public void onGlassesFail(int errorCode) {
                Log.e(TAG, "WiFi同步失败: errorCode=" + errorCode);
                mainHandler.post(() -> Toast.makeText(activity,
                        "WiFi同步失败: " + errorCode, Toast.LENGTH_LONG).show());
            }

            @Override
            public void eisEnd(String fileName, String filePath) {
            }

            @Override
            public void eisError(String fileName, String sourcePath, String errorInfo) {
            }

            @Override
            public void recordingToPcm(String fileName, String filePath, int duration) {
                Log.i(TAG, "录音OPUS→PCM转换完成: " + fileName
                        + ", filePath=" + filePath + ", 时长=" + duration + "s");

                // 将SDK转换生成的PCM文件移动到audio/目录
                File srcFile = new File(filePath);
                File audioDir = GlassApp.getInstance().getAudioDir();
                File pcmFile;
                if (audioDir != null && srcFile.exists()) {
                    File destFile = new File(audioDir, fileName);
                    if (srcFile.renameTo(destFile)) {
                        pcmFile = destFile;
                        Log.i(TAG, "PCM录音已移动到音频目录: " + pcmFile.getAbsolutePath());
                    } else {
                        pcmFile = srcFile;
                        Log.w(TAG, "PCM文件移动失败，保留原路径: " + filePath);
                    }
                } else {
                    pcmFile = srcFile;
                    if (audioDir == null) {
                        Log.w(TAG, "音频目录为null（设备名未设置），PCM文件保留原路径");
                    }
                }

                // 立即将PCM转换为WAV（eager conversion），使文件同步完成后即可播放
                final File finalPcmFile = pcmFile;
                final boolean[] wavReady = {false};
                if (finalPcmFile.exists() && finalPcmFile.length() > 0) {
                    File wavFile = PcmUtils.convertPcmToWav(finalPcmFile);
                    if (wavFile != null && wavFile.exists()) {
                        wavReady[0] = true;
                        Log.i(TAG, "PCM→WAV转换完成: " + wavFile.getAbsolutePath()
                                + " (" + wavFile.length() + " bytes)");
                    } else {
                        Log.e(TAG, "PCM→WAV转换失败: " + finalPcmFile.getAbsolutePath());
                    }
                } else {
                    Log.w(TAG, "PCM文件为空或不存在，跳过WAV转换: "
                            + finalPcmFile.getAbsolutePath()
                            + " (size=" + finalPcmFile.length() + ")");
                }

                final boolean wavOk = wavReady[0];
                mainHandler.post(() -> {
                    String msg = "录音就绪 (" + duration + "秒): " + fileName;
                    if (!wavOk) {
                        msg += " [WAV转换失败，播放时自动重试]";
                    }
                    Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show();
                    audioPlaybackManager.refreshAudioFileList();
                });
            }

            @Override
            public void recordingToPcmError(String fileName, String errorInfo) {
                // OPUS→PCM解码失败：通常是因为录音被异常中断导致OPUS文件不完整
                Log.e(TAG, "录音OPUS→PCM转换失败: " + fileName + ", error=" + errorInfo);

                // 检查OPUS文件大小以辅助诊断
                File audioDir = GlassApp.getInstance().getAudioDir();
                long opusSize = -1;
                if (audioDir != null) {
                    File opusFile = new File(audioDir, fileName);
                    if (!opusFile.exists()) {
                        // 也尝试在设备根目录查找
                        File deviceDir = GlassApp.getInstance().getDeviceDir();
                        if (deviceDir != null) {
                            opusFile = new File(deviceDir, fileName);
                        }
                    }
                    if (opusFile.exists()) {
                        opusSize = opusFile.length();
                    }
                }

                final long finalOpusSize = opusSize;
                mainHandler.post(() -> {
                    String msg = "录音解码失败: " + fileName;
                    if (finalOpusSize == 0) {
                        msg += " (录音文件为空，可能未成功开始录音)";
                    } else if (finalOpusSize > 0) {
                        msg += " (文件" + finalOpusSize + "字节，数据可能不完整)";
                    }
                    Toast.makeText(activity, msg, Toast.LENGTH_LONG).show();
                });
            }

            @Override
            public void voiceFromGlasses(byte[] pcmData) {
                // AI对话：眼镜实时回传 PCM 音频流，交给 AiDialogueManager 分片保存
                aiDialogueManager.savePcmData(pcmData);
            }

            @Override
            public void voiceFromGlassesStatus(int status) {
                // AI对话状态回调（开始/结束等），转发给 AiDialogueManager
                aiDialogueManager.onVoiceFromGlassesStatus(status);
            }

            @Override
            public void wifiSpeed(String wifiSpeed) {
                // WiFi速率回调（不记录日志以减少内存占用）
            }
        };
    }

    // ==================== 清理 ====================

    /**
     * 清理占位：监听器生命周期由 SDK 内部管理，无需手动注销。
     */
    public void onDestroy() {
        // 监听器由SDK管理，此处占位
    }
}
