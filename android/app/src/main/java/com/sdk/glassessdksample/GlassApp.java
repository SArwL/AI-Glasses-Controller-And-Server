package com.sdk.glassessdksample;

import android.app.Application;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.IntentFilter;
import android.os.Build;
import android.util.Log;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.oudmon.ble.base.bluetooth.BleAction;
import com.oudmon.ble.base.bluetooth.BleBaseControl;
import com.oudmon.ble.base.bluetooth.BleOperateManager;
import com.oudmon.ble.base.communication.LargeDataHandler;
import com.sdk.glassessdksample.receivers.BluetoothReceiver;
import com.sdk.glassessdksample.receivers.MyBluetoothReceiver;

import java.io.File;

/**
 * AI眼镜 Application 类
 * 负责在应用启动时初始化BLE SDK框架
 *
 * 职责：
 * - 应用进程创建时初始化 BLE SDK（LargeDataHandler、BleOperateManager、BleBaseControl），
 *   并注册蓝牙系统广播与本地广播接收器（含经典蓝牙发现/配对所需的补充Action）。
 * - 全局持有设备状态：当前设备名、扫描预存设备名、上次连接设备名（持久化）、硬件/固件版本号。
 * - 统一管理眼镜媒体文件的本地存储目录（按设备名分文件夹，内含 picture/video/audio 子目录）。
 *
 * 关键流程：
 * onCreate → 保存单例与上下文 → initBle() 初始化SDK并注册广播。
 * 设备名确定路径：setCurrentDevice()（连接成功后）/ setPendingDeviceName()（扫描时预存）
 *                / getLastDeviceName()（SharedPreferences 缓存，App重启过渡期使用）。
 *
 * 协作关系：
 * - MainActivity：连接成功后调用 setCurrentDevice() 设置设备名；
 * - DeviceScanActivity：连接前调用 setPendingDeviceName() 预存设备名；
 * - 各 Manager（MediaSyncManager / PhotoManager 等）：通过 getPhotoDir()/getAudioDir()
 *   等获取媒体存储目录；
 * - BluetoothReceiver / MyBluetoothReceiver：处理蓝牙系统广播与SDK本地广播。
 */
public class GlassApp extends Application {

    private static final String TAG = "GlassApp";

    /** 全局应用上下文（Application级别，供全App任意位置使用） */
    private static Context appContext;
    /** 单例实例 */
    private static GlassApp instance;

    private String hardwareVersion = "";
    private String firmwareVersion = "";
    /** 当前连接设备的名称（连接成功后由 MainActivity 设置） */
    private String currentDeviceName = null;
    private String pendingDeviceName = null;  // 扫描时预存的设备名（SDK未缓存时的备用方案）

    /** 应用启动入口：保存上下文与单例，然后初始化BLE SDK框架 */
    @Override
    public void onCreate() {
        super.onCreate();
        appContext = getApplicationContext();
        instance = this;
        initBle();
        Log.i(TAG, "GlassApp 初始化完成");
    }

    /**
     * 初始化 BLE SDK 框架
     * 只被 onCreate 调用一次，包含异常捕获，初始化失败不会导致崩溃
     */
    private void initBle() {
        try {
            // 初始化核心单例
            LargeDataHandler.getInstance();  // 大数据分包处理器（媒体文件等大文件传输依赖）
            BleOperateManager bleOperateManager = BleOperateManager.getInstance(this);
            bleOperateManager.setApplication(this);
            bleOperateManager.init();        // SDK入口，必须先于其他SDK操作调用

            // 注册蓝牙系统广播（含经典蓝牙发现和配对）
            IntentFilter deviceFilter = BleAction.getDeviceIntentFilter();
            // 补充经典蓝牙所需Action（SDK的filter可能不包含）
            deviceFilter.addAction(BluetoothDevice.ACTION_FOUND);         // 经典蓝牙扫描发现设备
            deviceFilter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);// 蓝牙开关状态变化
            BluetoothReceiver deviceReceiver = new BluetoothReceiver();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Android 8.0+ 动态注册广播需显式声明导出标志
                registerReceiver(deviceReceiver, deviceFilter, RECEIVER_EXPORTED);
            } else {
                registerReceiver(deviceReceiver, deviceFilter);
            }

            // 注册本地广播（App内部广播，实现SDK与业务层解耦）
            IntentFilter localFilter = BleAction.getIntentFilter();
            MyBluetoothReceiver myBleReceiver = new MyBluetoothReceiver();
            LocalBroadcastManager.getInstance(appContext)
                    .registerReceiver(myBleReceiver, localFilter);

            // 设置BLE基础控制上下文
            BleBaseControl.getInstance(appContext).setmContext(this);

            Log.i(TAG, "BLE SDK 初始化成功");
        } catch (Exception e) {
            Log.e(TAG, "BLE SDK 初始化失败", e);
        }
    }

    /**
     * 设置当前连接的设备名称（连接成功后由 MainActivity 调用）
     */
    public void setCurrentDevice(String deviceName) {
        this.currentDeviceName = deviceName;
        // 持久化到 SharedPreferences，下次启动时可用
        if (deviceName != null && !deviceName.isEmpty()) {
            getSharedPreferences("glasses_prefs", MODE_PRIVATE)
                    .edit().putString("last_device_name", deviceName).apply();
        }
    }

    /**
     * 获取当前设备名称（优先 SDK 返回值，其次扫描预存值，最后 SharedPreferences 缓存）
     */
    public String getCurrentDeviceName() {
        return currentDeviceName;
    }

    /**
     * 预存设备名称（在 DeviceScanActivity 扫描到设备、连接前调用）。
     * 用于应对首次连接时 DeviceManager.getDeviceName() 返回 null 的情况。
     */
    public void setPendingDeviceName(String name) {
        this.pendingDeviceName = name;
    }

    /**
     * 获取预存的设备名称，读取后清除。
     */
    public String consumePendingDeviceName() {
        String name = pendingDeviceName;
        pendingDeviceName = null;
        return name;
    }

    /**
     * 获取上次连接过的设备名称（从 SharedPreferences 读取）。
     * 用于 App 重启后自动重连时，SDK 暂未返回名称的过渡期。
     */
    public String getLastDeviceName() {
        return getSharedPreferences("glasses_prefs", MODE_PRIVATE)
                .getString("last_device_name", null);
    }

    /**
     * 获取当前设备根目录（以设备名称命名）。
     * 必须在 setCurrentDevice() 之后调用，否则返回 null。
     */
    public File getDeviceDir() {
        if (currentDeviceName == null || currentDeviceName.isEmpty()) {
            Log.w(TAG, "getDeviceDir() 调用时设备名称未设置！请确保在连接成功后才调用此方法。");
            return null;
        }
        File dir = new File(getAppRootDir(), currentDeviceName);
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    /**
     * 获取照片存储目录。
     * 设备名称未设置时返回 null。
     */
    public File getPhotoDir() {
        File deviceDir = getDeviceDir();
        if (deviceDir == null) return null;
        File dir = new File(deviceDir, "picture");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    /**
     * 获取视频存储目录。
     * 设备名称未设置时返回 null。
     */
    public File getVideoDir() {
        File deviceDir = getDeviceDir();
        if (deviceDir == null) return null;
        File dir = new File(deviceDir, "video");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    /**
     * 获取音频存储目录。
     * 设备名称未设置时返回 null。
     */
    public File getAudioDir() {
        File deviceDir = getDeviceDir();
        if (deviceDir == null) return null;
        File dir = new File(deviceDir, "audio");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    /**
     * 获取眼镜媒体文件本地存储目录（SDK 下载文件的根目录）。
     * 设备名称未设置时返回 null。
     */
    public File getAlbumDir() {
        return getDeviceDir();
    }

    /**
     * 确保设备目录结构完整存在（设备文件夹 + picture/video/audio 子目录）。
     * 在设备名称确定后立即调用，不依赖懒创建。
     *
     * @return true 表示所有目录均已就绪，false 表示设备名称未设置
     */
    public boolean ensureDeviceDirectories() {
        if (currentDeviceName == null || currentDeviceName.isEmpty()) {
            Log.w(TAG, "ensureDeviceDirectories: 设备名称未设置，无法创建目录");
            return false;
        }
        File deviceDir = new File(getAppRootDir(), currentDeviceName);
        File photoDir = new File(deviceDir, "picture");
        File videoDir = new File(deviceDir, "video");
        File audioDir = new File(deviceDir, "audio");

        boolean ok = true;
        if (!deviceDir.exists() && !deviceDir.mkdirs()) ok = false;
        if (!photoDir.exists() && !photoDir.mkdirs()) ok = false;
        if (!videoDir.exists() && !videoDir.mkdirs()) ok = false;
        if (!audioDir.exists() && !audioDir.mkdirs()) ok = false;

        if (ok) {
            Log.i(TAG, "设备目录结构已就绪: " + deviceDir.getAbsolutePath()
                    + " (picture/ video/ audio/)");
        } else {
            Log.e(TAG, "设备目录创建失败！路径: " + deviceDir.getAbsolutePath());
        }
        return ok;
    }

    /**
     * 获取应用根目录
     */
    public File getAppRootDir() {
        if (getExternalFilesDir("") != null) {
            return getExternalFilesDir("");
        } else {
            File externalSaveDir = getExternalCacheDir();
            return externalSaveDir != null ? externalSaveDir : getCacheDir();
        }
    }

    /** 获取全局应用上下文 */
    public static Context getAppContext() {
        return appContext;
    }

    /** 获取 GlassApp 单例实例 */
    public static GlassApp getInstance() {
        return instance;
    }

    /** 获取眼镜硬件版本号 */
    public String getHardwareVersion() {
        return hardwareVersion;
    }

    /** 设置眼镜硬件版本号 */
    public void setHardwareVersion(String hardwareVersion) {
        this.hardwareVersion = hardwareVersion;
    }

    /** 获取眼镜固件版本号 */
    public String getFirmwareVersion() {
        return firmwareVersion;
    }

    /** 设置眼镜固件版本号 */
    public void setFirmwareVersion(String firmwareVersion) {
        this.firmwareVersion = firmwareVersion;
    }
}
