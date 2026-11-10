package com.sdk.glassessdksample.activities;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.sdk.glassessdksample.GlassApp;
import com.sdk.glassessdksample.R;
import com.sdk.glassessdksample.events.BluetoothEvent;
import com.sdk.glassessdksample.events.BtPairEvent;
import com.sdk.glassessdksample.managers.AiDialogueManager;
import com.sdk.glassessdksample.managers.AiReplyAudioManager;
import com.sdk.glassessdksample.managers.AiUdpManager;
import com.sdk.glassessdksample.managers.AiUploader;
import com.sdk.glassessdksample.managers.AudioPlaybackManager;
import com.sdk.glassessdksample.managers.BleConnectionManager;
import com.sdk.glassessdksample.managers.MediaSyncManager;
import com.sdk.glassessdksample.managers.OnCaptureCompleteListener;
import com.sdk.glassessdksample.managers.PhotoManager;
import com.sdk.glassessdksample.managers.RecordingManager;
import com.sdk.glassessdksample.utils.PermissionHelper;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

/**
 * 主控制界面 — 仅负责UI布局和按钮点击委托。
 * 所有业务逻辑已拆分到 managers/ 包下的各个Manager类中。
 *
 * 职责：
 * - 作为整个App的"编排层"：创建并持有各 Manager 实例（BLE连接、拍照、录音、
 *   AI对话、媒体同步、音频播放），将界面按钮点击事件委托给对应 Manager。
 * - 通过 EventBus 订阅蓝牙连接事件（BluetoothEvent）和经典蓝牙配对事件（BtPairEvent），
 *   在连接成功后驱动设备名解析、媒体同步监听器重建、状态UI刷新等后续流程。
 * - 负责权限申请与蓝牙启用（ActivityResultLauncher + onRequestPermissionsResult）。
 *
 * 关键流程：
 * onCreate → 注册EventBus → initViews() 绑定控件 → createManagers() 依赖注入
 *          → mediaSyncManager.initialize() → requestPermissionsIfNeeded()。
 * 蓝牙连接成功 → onBluetoothEvent() → 解析设备名 → 延迟重建媒体同步 → 刷新状态UI。
 *
 * 协作关系：
 * - BleConnectionManager：连接状态/电量显示/经典蓝牙配对；
 * - PhotoManager：拍照与照片预览；RecordingManager：录音开始/停止；
 * - MediaSyncManager：WiFi媒体同步（依赖上述所有 Manager）；
 * - AiDialogueManager / AudioPlaybackManager：AI对话与音频播放；
 * - DeviceScanActivity / GalleryActivity：设备扫描入口 / 照片图库。
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    // ============ UI ============
    private TextView tvConnectionStatus;
    private TextView tvBatteryInfo;
    private TextView tvBtStatus;
    private TextView tvAiBackendStatus;
    private Button btnAiMode;
    private Button btnScanDevice;
    private Button btnDisconnect;
    private Button btnTakePhoto;
    private Button btnRecord;
    private Button btnSyncMedia;
    private Button btnBtPair;
    private Button btnViewAllPhotos;
    private ImageView ivPhotoPreview;
    private RecyclerView rvAudioFiles;

    // ============ Managers ============
    private BleConnectionManager bleConnectionManager;
    private PhotoManager photoManager;
    private RecordingManager recordingManager;
    private AiDialogueManager aiDialogueManager;
    private MediaSyncManager mediaSyncManager;
    private AudioPlaybackManager audioPlaybackManager;

    // ============ 基础设施 ============
    /** 主线程 Handler：用于把后台线程/SDK回调切回主线程更新UI */
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // ============ 权限启动器（必须留在Activity中） ============
    /** 通用权限启动器：一次性申请全部必需权限，回调判断是否全部授予 */
    private final ActivityResultLauncher<String[]> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                boolean allGranted = true;
                for (Boolean granted : result.values()) {
                    if (granted == null || !granted) { allGranted = false; break; }
                }
                if (allGranted) {
                    Log.i(TAG, "所有权限已授予");
                } else {
                    Toast.makeText(this, "部分权限被拒绝，可能影响功能", Toast.LENGTH_LONG).show();
                }
            });

    /** 蓝牙开启启动器：调起系统蓝牙开启界面，回调判断用户是否已开启 */
    private final ActivityResultLauncher<Intent> bluetoothLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == Activity.RESULT_OK) {
                    Log.i(TAG, "蓝牙已启用");
                }
            });

    /** WiFi权限启动器：授权成功则立即开始媒体同步，否则提示用户 */
    private final ActivityResultLauncher<String[]> wifiPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                boolean allGranted = true;
                for (Boolean granted : result.values()) {
                    if (granted == null || !granted) { allGranted = false; break; }
                }
                if (allGranted) {
                    mediaSyncManager.startAlbumImport();
                } else {
                    Toast.makeText(this, "需要WiFi权限才能同步媒体", Toast.LENGTH_SHORT).show();
                }
            });

    // ==================== 生命周期 ====================

    /** 创建主界面：注册EventBus、绑定控件、创建Manager、初始化媒体同步、申请权限 */
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 订阅事件总线：接收蓝牙连接事件 / BT经典配对事件，驱动后续流程
        EventBus.getDefault().register(this);

        initViews();
        createManagers();
        mediaSyncManager.initialize();
        requestPermissionsIfNeeded();
    }

    /** 回到前台：刷新连接状态UI、检查/请求开启蓝牙、刷新音频文件列表、刷新AI后端状态 */
    @Override
    protected void onResume() {
        super.onResume();
        bleConnectionManager.updateConnectionStatus();
        enableBluetoothIfNeeded();
        audioPlaybackManager.refreshAudioFileList();
        updateAiBackendStatus();
    }

    /**
     * 刷新主界面的「AI 后端」状态显示。
     * 连接由 {@link AiUdpManager} 单例跨页面持有，因此每次回到前台都要重新读取。
     */
    private void updateAiBackendStatus() {
        AiUdpManager udp = AiUdpManager.getInstance();
        if (udp.isConnected()) {
            String host = udp.getTargetHost();
            int port = udp.getTargetPort();
            tvAiBackendStatus.setText(udp.isVerified()
                    ? "AI 后端: ● 已连接 " + host + ":" + port
                    : "AI 后端: ● 已连接(未校验) " + host + ":" + port);
            tvAiBackendStatus.setTextColor(udp.isVerified() ? 0xFF2E7D32 : 0xFFF57C00);
        } else {
            tvAiBackendStatus.setText("AI 后端: ○ 未连接");
            tvAiBackendStatus.setTextColor(0xFF888888);
        }
    }

    /** 销毁：停止AI对话PCM监控、反注册EventBus、释放各Manager资源 */
    @Override
    protected void onDestroy() {
        super.onDestroy();
        aiDialogueManager.stopPcmMonitor();
        EventBus.getDefault().unregister(this);
        // 释放回传播报资源（AudioTrack + 播放线程）并注销其 UDP 监听器
        AiReplyAudioManager.release();
        audioPlaybackManager.onDestroy();
        bleConnectionManager.onDestroy();
        mediaSyncManager.onDestroy();
        aiDialogueManager.onDestroy();
    }

    // ==================== Manager 创建与依赖注入 ====================

    /**
     * 创建所有 Manager 实例并按依赖关系注入。
     * 注意注入顺序：无依赖的先生成，被依赖的后生成（mediaSyncManager 最晚创建）。
     */
    private void createManagers() {
        // 1. 无依赖的 Manager
        audioPlaybackManager = new AudioPlaybackManager(this, mainHandler, rvAudioFiles);
        // AI对话管理：对话录音结束时通过回调刷新音频文件列表
        aiDialogueManager = new AiDialogueManager(this, mainHandler,
                () -> audioPlaybackManager.refreshAudioFileList());

        // 2. 需要 OnCaptureCompleteListener 的 Manager
        //    使用延迟引用：lambda 在调用时才访问 mediaSyncManager 字段（此时已赋值）
        // 拍照完成回调：延迟到主线程执行媒体同步，确保照片已写入本地后再同步
        OnCaptureCompleteListener onCapture = () ->
                mainHandler.postDelayed(() -> mediaSyncManager.syncAfterCapture(), 0);

        photoManager = new PhotoManager(this, mainHandler, ivPhotoPreview, onCapture);
        recordingManager = new RecordingManager(this, mainHandler, btnRecord);

        // 5. 后端上传钩子：仅在 UDP 已连接时上传，未连接则静默跳过
        setupBackendUploadHooks();

        // 6. AI 语音回复的接收与播报（0x32/0x33）+ 用户抢话打断
        setupReplyAudio();

        // 3. BLE连接管理：持有连接状态/电量/配对相关UI控件，负责状态刷新
        bleConnectionManager = new BleConnectionManager(this, mainHandler,
                tvConnectionStatus, tvBatteryInfo, tvBtStatus,
                btnDisconnect, btnTakePhoto, btnRecord, btnSyncMedia, btnBtPair);

        // 4. 媒体同步中心（依赖所有其他Manager）：协调WiFi媒体传输
        mediaSyncManager = new MediaSyncManager(this, mainHandler,
                photoManager, bleConnectionManager, recordingManager,
                aiDialogueManager, audioPlaybackManager);
    }

    // ==================== AI 回复播报 ====================

    /**
     * 初始化 AI 语音回复的接收与播报，并把"用户抢话"接到打断动作上。
     *
     * <p>{@link AiReplyAudioManager} 是单例，构造时会把自身注册为 UDP 监听器，
     * 因此 0x32/0x33 的接收<b>不再依赖某个页面是否在前台</b>——连接页销毁时只注销它自己的
     * 监听器，不会影响回传音频（详见 {@link AiUdpManager#addListener}）。</p>
     */
    private void setupReplyAudio() {
        // 触发单例创建：注册 UDP 监听 + 启动播放线程
        AiReplyAudioManager.getInstance();

        // 用户抢话 → 打断正在播报的回复。打断信号有三条，全部接上（互为冗余：
        // 同一物理按键会触发多条，而"无内容在播时为空操作"保证了重复调用无副作用）：
        //   ① 眼镜事件 0x03 麦克风启动  ② 眼镜事件 0x0c 暂停事件（均在 MediaSyncManager 分发）
        //   ③ 本回调 voiceFromGlassesStatus(1)——SDK 在 speakToPcm() 里发出，源头其实就是 0x03
        aiDialogueManager.setOnUserSpeechStartListener(
                () -> AiReplyAudioManager.interruptIfSpeaking("用户开始说话"));
    }

    // ==================== 后端上传钩子 ====================

    /**
     * 挂接"数据就绪即上传后端"的两个钩子：
     * <ul>
     *   <li>AI 对话：一轮语音的 PCM 拼接完成后 → 上传（{@link AiUdpManager#TYPE_VOICE_PCM}），
     *       并且<b>无论上传成败都立即删除本地 PCM</b>——它只是送给后端做语音识别的过路数据，
     *       不长期保存，也不出现在音频列表里</li>
     *   <li>智能拍照：缩略图保存为 JPEG 后 → 上传（{@link AiUdpManager#TYPE_PHOTO_JPEG}），
     *       照片属于用户媒体，本地长期保留</li>
     * </ul>
     *
     * <p>两个回调都在后台线程触发，此处只发起异步上传与日志，不做 UI 操作（Toast 仅在完成时弹一次）。
     * 未连接后端时 {@link AiUploader#uploadAsync} 会直接跳过（一次性数据仍会清理），不影响本地功能。</p>
     */
    private void setupBackendUploadHooks() {
        // 钩子一：AI 对话语音（衔接 AiDialogueManager.mergePcmChunks 的完成点）
        aiDialogueManager.setOnPcmMergedListener((pcmFile, durationMs) -> {
            boolean connected = AiUdpManager.getInstance().isConnected();
            Log.i(TAG, "PCM 就绪: " + pcmFile.getName() + " (" + durationMs + "ms)"
                    + (connected ? "，开始上传" : "，后端未连接，跳过上传"));
            // deleteAfterUpload=true：上传流程结束后（成功/断线/异常）由 AiUploader 删除本地文件
            AiUploader.uploadAsync(pcmFile, AiUdpManager.TYPE_VOICE_PCM,
                    new AiUploader.ProgressListener() {
                        @Override
                        public void onUploadFinished(String fileName, int sentChunks, int totalBytes) {
                            Log.i(TAG, "语音上传完成: " + fileName + "，"
                                    + sentChunks + " 片 / " + totalBytes + " 字节");
                            mainHandler.post(() -> Toast.makeText(MainActivity.this,
                                    "语音已上传（" + sentChunks + " 片）",
                                    Toast.LENGTH_SHORT).show());
                        }

                        @Override
                        public void onUploadFailed(String fileName, String reason,
                                                   AiUploader.FailureKind kind) {
                            Log.w(TAG, "语音上传失败: " + fileName + " — " + reason
                                    + "（类别=" + kind + "）");
                            // 传输侧失败 = 这轮语音没能送到后端。该 PCM 已在上传流程结束时被清理、
                            // 无法补传，用户必须立刻知道，否则会以为 AI 只是没回应。
                            // 本地侧失败（文件为空/读取异常）与后端无关，不能报成"服务器连接失败"。
                            final String msg = kind == AiUploader.FailureKind.TRANSPORT
                                    ? "服务器连接失败：" + reason
                                    : "语音发送失败：" + reason;
                            mainHandler.post(() -> Toast.makeText(MainActivity.this,
                                    msg, Toast.LENGTH_LONG).show());
                        }
                    }, true);
        });

        // 钩子二：智能拍照缩略图（衔接 PhotoManager.saveThumbnail 的完成点）
        photoManager.setOnThumbnailReadyListener(jpegFile -> {
            if (!AiUdpManager.getInstance().isConnected()) {
                Log.i(TAG, "缩略图就绪但后端未连接，跳过上传: " + jpegFile.getName());
                return;
            }
            Log.i(TAG, "缩略图就绪，开始上传: " + jpegFile.getName()
                    + " (" + jpegFile.length() + " 字节)");
            AiUploader.uploadAsync(jpegFile, AiUdpManager.TYPE_PHOTO_JPEG,
                    new AiUploader.ProgressListener() {
                        @Override
                        public void onUploadFinished(String fileName, int sentChunks, int totalBytes) {
                            Log.i(TAG, "缩略图上传完成: " + fileName + "，"
                                    + totalBytes + " 字节");
                            mainHandler.post(() -> Toast.makeText(MainActivity.this,
                                    "缩略图已上传", Toast.LENGTH_SHORT).show());
                        }

                        @Override
                        public void onUploadFailed(String fileName, String reason,
                                                   AiUploader.FailureKind kind) {
                            // 缩略图本地长期保留，传不出去不会丢数据，因此只记日志、不打扰用户
                            Log.w(TAG, "缩略图上传失败: " + fileName + " — " + reason
                                    + "（类别=" + kind + "）");
                        }
                    });
        });
    }

    // ==================== UI 初始化 ====================

    /** 绑定布局控件并注册全部按钮点击监听 */
    private void initViews() {
        tvConnectionStatus = findViewById(R.id.tvConnectionStatus);
        tvBatteryInfo = findViewById(R.id.tvBatteryInfo);
        tvBtStatus = findViewById(R.id.tvBtStatus);
        tvAiBackendStatus = findViewById(R.id.tvAiBackendStatus);

        btnScanDevice = findViewById(R.id.btnScanDevice);
        btnAiMode = findViewById(R.id.btnAiMode);
        btnDisconnect = findViewById(R.id.btnDisconnect);
        btnTakePhoto = findViewById(R.id.btnTakePhoto);
        btnRecord = findViewById(R.id.btnRecord);
        btnSyncMedia = findViewById(R.id.btnSyncMedia);
        btnBtPair = findViewById(R.id.btnBtPair);
        ivPhotoPreview = findViewById(R.id.ivPhotoPreview);
        rvAudioFiles = findViewById(R.id.rvAudioFiles);

        // 查看所有照片 → 打开照片图库界面
        btnViewAllPhotos = findViewById(R.id.btnViewAllPhotos);
        btnViewAllPhotos.setOnClickListener(v ->
                startActivity(new Intent(this, GalleryActivity.class)));

        // 扫描设备 → 打开BLE设备扫描界面
        btnScanDevice.setOnClickListener(v ->
                startActivity(new Intent(this, DeviceScanActivity.class)));

        // 断开连接（委托给BLE连接管理器）
        btnDisconnect.setOnClickListener(v -> bleConnectionManager.disconnect());

        // 拍照（委托给拍照管理器）。长按改为「智能拍照」——普通拍照不触发缩略图事件，
        // 只有智能拍照能拿到缩略图并上传后端用于识图。
        btnTakePhoto.setOnClickListener(v -> photoManager.takePhoto());
        btnTakePhoto.setOnLongClickListener(v -> {
            photoManager.takeSmartPhoto();
            return true;  // 消费事件，避免长按后再触发单击的普通拍照
        });

        // 录音（开始/停止切换，状态由录音管理器维护）
        btnRecord.setOnClickListener(v -> recordingManager.toggleRecording());

        // WiFi同步媒体：前置校验——先确认已连接眼镜，再检查WiFi权限
        btnSyncMedia.setOnClickListener(v -> {
            if (!bleConnectionManager.isConnected()) {
                Toast.makeText(this, "请先连接眼镜设备", Toast.LENGTH_SHORT).show();
                return;
            }
            if (mediaSyncManager.hasWifiPermission()) {
                mediaSyncManager.startAlbumImport();
            } else {
                requestWifiPermissions();
            }
        });

        // BT经典蓝牙配对（让眼镜通过A2DP播放手机音频）
        btnBtPair.setOnClickListener(v -> bleConnectionManager.startBtPairing());

        // AI 交互模式 → 进入后端连接页面（首选手动输入/历史地址自动连接）
        btnAiMode.setOnClickListener(v ->
                startActivity(new Intent(this, AiConnectionActivity.class)));

        // 点击照片全屏查看（当前为占位提示，完整查看在图库界面）
        ivPhotoPreview.setOnClickListener(v -> {
            if (photoManager.getLastPhotoPath() != null) {
                Toast.makeText(this, "查看照片", Toast.LENGTH_SHORT).show();
            }
        });
    }

    // ==================== 权限管理 ====================

    /** 启动时检查权限：缺失则一次性申请全部必需权限（结果在 onRequestPermissionsResult 处理） */
    private void requestPermissionsIfNeeded() {
        if (!PermissionHelper.hasAllPermissions(this)) {
            String[] permissions = PermissionHelper.getAllRequiredPermissions();
            ActivityCompat.requestPermissions(this, permissions, PermissionHelper.REQUEST_CODE_ALL);
        }
    }

    /** 申请媒体同步所需的WiFi相关权限（如精确定位/附近WiFi），授权结果由启动器回调驱动 */
    private void requestWifiPermissions() {
        String[] needed = mediaSyncManager.getNeededWifiPermissions();
        if (needed.length > 0) {
            wifiPermissionLauncher.launch(needed);
        }
    }

    /** 系统权限申请结果回调：仅处理启动时全部权限的申请结果，并Toast提示 */
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PermissionHelper.REQUEST_CODE_ALL) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            Toast.makeText(this, allGranted ? "权限已授予" : "部分权限被拒绝", Toast.LENGTH_SHORT).show();
        }
    }

    // ==================== 蓝牙启用 ====================

    /**
     * 蓝牙未开启时调起系统开启界面。
     * Android 12(S)+ 需要在申请前校验 BLUETOOTH_CONNECT 权限。
     */
    private void enableBluetoothIfNeeded() {
        if (!bleConnectionManager.isBluetoothEnabled()) {
            Intent intent = bleConnectionManager.getEnableBluetoothIntent();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                        == PackageManager.PERMISSION_GRANTED) {
                    bluetoothLauncher.launch(intent);
                }
            } else {
                bluetoothLauncher.launch(intent);
            }
        }
    }

    // ==================== EventBus — 编排层 ====================

    /**
     * 订阅蓝牙连接事件（主线程）。
     * 首次连接成功：解析并设置设备名 → 延迟1.5s重试 → 重建媒体同步监听器 → 刷新状态UI。
     * 连接断开：停止AI对话的PCM录音监控。
     */
    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onBluetoothEvent(BluetoothEvent event) {
        bleConnectionManager.handleBluetoothEvent(event);

        if (event.isConnected()) {
            if (!bleConnectionManager.isConnectionHandled()) {
                // 首次连接：解析设备名 → 延迟后重新初始化所有监听器
                bleConnectionManager.resolveAndSetDeviceName();
                bleConnectionManager.markConnectionHandled();
                mainHandler.postDelayed(() -> {
                    // 1.5s后重试获取设备名（SDK此时应该已缓存）
                    if (GlassApp.getInstance().getCurrentDeviceName() == null) {
                        bleConnectionManager.resolveAndSetDeviceName();
                    }
                    // 设备名就绪后重建媒体同步监听器，保证文件保存到正确目录
                    mediaSyncManager.reinitializeAfterConnection();
                    bleConnectionManager.updateConnectionStatus();
                }, 1500);
                // 兜底刷新：3s后再次刷新连接状态，防止状态显示滞后
                mainHandler.postDelayed(() -> bleConnectionManager.updateConnectionStatus(), 3000);
            }
        } else {
            // 断开连接：停止AI对话的PCM分片轮询监控
            aiDialogueManager.stopPcmMonitor();
        }
    }

    /** 订阅经典蓝牙配对事件（主线程），委托给BLE连接管理器处理 */
    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onBtPairEvent(BtPairEvent event) {
        bleConnectionManager.handleBtPairEvent(event);
    }
}
