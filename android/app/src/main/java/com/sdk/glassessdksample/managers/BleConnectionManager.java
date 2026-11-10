package com.sdk.glassessdksample.managers;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothProfile;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;

import com.oudmon.ble.base.bluetooth.BleOperateManager;
import com.oudmon.ble.base.bluetooth.DeviceManager;
import com.oudmon.ble.base.communication.LargeDataHandler;
import com.sdk.glassessdksample.events.BluetoothEvent;
import com.sdk.glassessdksample.events.BtPairEvent;
import com.sdk.glassessdksample.GlassApp;
import com.sdk.glassessdksample.ui.BluetoothUtils;

/**
 * BLE连接状态管理、BT经典蓝牙配对(A2DP)、电量显示、设备名解析。
 *
 * <p>职责：</p>
 * <ul>
 *   <li>BLE连接状态：查询/显示 BLE 连接状态，联动控制断开、拍照、录音、媒体同步、BT配对等按钮的可用性。</li>
 *   <li>BT经典蓝牙配对：调用 SDK 打开经典蓝牙并启动扫描，发现眼镜后自动配对，让眼镜播放手机音频（A2DP）。</li>
 *   <li>电量显示：接收设备主动上报或主动查询的电量，统一刷新电量文本。</li>
 *   <li>设备名解析：三级回退获取设备名称，确认后由 {@link GlassApp} 创建按设备名分文件夹的目录结构。</li>
 * </ul>
 *
 * <p>连接状态机：</p>
 * <ol>
 *   <li>BLE 扫描/连接由 SDK 的 {@link BleOperateManager} 负责，本类监听 {@link BluetoothEvent} 更新 UI。</li>
 *   <li>首次连接成功后的初始化编排由 MainActivity 完成，并通过 {@link #markConnectionHandled()} 置位标记；
 *       断开连接时在 {@link #handleBluetoothEvent(BluetoothEvent)} 中复位该标记。</li>
 *   <li>BT 经典蓝牙(A2DP) 配对状态通过 {@link BtPairEvent} 驱动 isBtPaired 标记并刷新显示。</li>
 * </ol>
 *
 * <p>协作类：{@link BleOperateManager}（BLE指令/连接）、{@link DeviceManager}（设备名缓存）、
 * {@link LargeDataHandler}（指令通道）、{@link GlassApp}（设备目录）、{@link BluetoothUtils}（蓝牙开关）。</p>
 */
public class BleConnectionManager {

    private static final String TAG = "BleConnectionManager";

    private final Activity activity;
    private final Handler mainHandler;

    // UI 视图
    private final TextView tvConnectionStatus;
    private final TextView tvBatteryInfo;
    private final TextView tvBtStatus;
    private final Button btnDisconnect;
    private final Button btnTakePhoto;
    private final Button btnRecord;
    private final Button btnSyncMedia;
    private final Button btnBtPair;

    // 状态
    private boolean connectionHandled = false; // 首次连接后的初始化编排是否已完成（由 MainActivity 置位/复位）
    private boolean isBtPaired = false;        // BT 经典蓝牙(A2DP) 是否已配对成功

    public BleConnectionManager(Activity activity, Handler mainHandler,
                                TextView tvConnectionStatus, TextView tvBatteryInfo,
                                TextView tvBtStatus, Button btnDisconnect,
                                Button btnTakePhoto, Button btnRecord,
                                Button btnSyncMedia, Button btnBtPair) {
        this.activity = activity;
        this.mainHandler = mainHandler;
        this.tvConnectionStatus = tvConnectionStatus;
        this.tvBatteryInfo = tvBatteryInfo;
        this.tvBtStatus = tvBtStatus;
        this.btnDisconnect = btnDisconnect;
        this.btnTakePhoto = btnTakePhoto;
        this.btnRecord = btnRecord;
        this.btnSyncMedia = btnSyncMedia;
        this.btnBtPair = btnBtPair;
    }

    // ==================== 蓝牙启用 ====================

    /**
     * 系统蓝牙是否已开启。
     * @return true 已开启
     */
    public boolean isBluetoothEnabled() {
        return BluetoothUtils.isEnabledBluetooth(activity);
    }

    /**
     * 构建请求开启蓝牙的 Intent（用于弹出系统蓝牙授权框）。
     * @return ACTION_REQUEST_ENABLE Intent，交给 startActivityForResult 使用
     */
    public Intent getEnableBluetoothIntent() {
        return new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
    }

    // ==================== 连接状态 ====================

    /**
     * BLE 是否已连接（直接查询 SDK 单例）。
     */
    public boolean isConnected() {
        return BleOperateManager.getInstance().isConnected();
    }

    /**
     * 首次连接的初始化编排是否已完成（防止连接成功流程被重复触发）。
     */
    public boolean isConnectionHandled() {
        return connectionHandled;
    }

    /**
     * 更新连接状态显示及所有功能按钮的 enable 状态。
     */
    public void updateConnectionStatus() {
        boolean connected = BleOperateManager.getInstance().isConnected();
        String status = connected ? "● 已连接" : "○ 未连接";
        if (tvConnectionStatus != null) tvConnectionStatus.setText("蓝牙: " + status);
        if (btnDisconnect != null) btnDisconnect.setEnabled(connected);
        if (btnTakePhoto != null) btnTakePhoto.setEnabled(connected);
        if (btnRecord != null) btnRecord.setEnabled(connected);
        if (btnSyncMedia != null) btnSyncMedia.setEnabled(connected);
        if (btnBtPair != null) btnBtPair.setEnabled(connected && !isBtPaired);
        updateBtStatusDisplay();
    }

    /**
     * 断开BLE连接。
     */
    public void disconnect() {
        // 调用 SDK 解绑设备（断开 BLE），随后刷新 UI 并提示
        BleOperateManager.getInstance().unBindDevice();
        updateConnectionStatus();
        Toast.makeText(activity, "已断开连接", Toast.LENGTH_SHORT).show();
    }

    // ==================== 电量 ====================

    /**
     * 统一更新电量显示。
     * @param battery  电量百分比 (0-100)
     * @param charging -1=未知, 0=未充电, 1=充电中
     */
    public void updateBattery(int battery, int charging) {
        String info;
        if (charging == -1) {
            info = "电量: " + battery + "%";
        } else {
            info = "电量: " + battery + "% " + (charging == 1 ? "(充电中)" : "");
        }
        if (tvBatteryInfo != null) tvBatteryInfo.setText(info);
    }

    // ==================== BT音频状态显示 ====================

    /**
     * 更新BT经典蓝牙/A2DP状态显示。
     */
    public void updateBtStatusDisplay() {
        if (tvBtStatus == null) return;
        if (isBtPaired) {
            tvBtStatus.setText("音频: ● 已连接 (A2DP)");
            tvBtStatus.setTextColor(0xFF4CAF50);
        } else if (BleOperateManager.getInstance().isConnected()) {
            tvBtStatus.setText("音频: ○ 未配对 — 点击下方按钮配对");
            tvBtStatus.setTextColor(0xFFFF9800);
        } else {
            tvBtStatus.setText("音频: ○ 未配对");
            tvBtStatus.setTextColor(0xFF888888);
        }
    }

    // ==================== 设备名解析 ====================

    /**
     * 三级回退获取设备名称，确认后立即创建完整目录结构：
     *   1. DeviceManager.getDeviceName()（SDK 缓存）
     *   2. consumePendingDeviceName()（DeviceScanActivity 扫描时预存）
     *   3. getLastDeviceName()（SharedPreferences 上次连接记录）
     */
    public void resolveAndSetDeviceName() {
        String deviceName = DeviceManager.getInstance().getDeviceName();
        if (deviceName != null && !deviceName.isEmpty()) {
            GlassApp.getInstance().setCurrentDevice(deviceName);
            GlassApp.getInstance().ensureDeviceDirectories();
            Log.i(TAG, "设备名称(来源: SDK): " + deviceName);
            return;
        }

        deviceName = GlassApp.getInstance().consumePendingDeviceName();
        if (deviceName != null && !deviceName.isEmpty()) {
            GlassApp.getInstance().setCurrentDevice(deviceName);
            GlassApp.getInstance().ensureDeviceDirectories();
            Log.i(TAG, "设备名称(来源: 扫描预存): " + deviceName);
            return;
        }

        deviceName = GlassApp.getInstance().getLastDeviceName();
        if (deviceName != null && !deviceName.isEmpty()) {
            GlassApp.getInstance().setCurrentDevice(deviceName);
            GlassApp.getInstance().ensureDeviceDirectories();
            Log.i(TAG, "设备名称(来源: 历史记录): " + deviceName);
            return;
        }

        Log.w(TAG, "所有来源均无法获取设备名称！");
    }

    // ==================== BLE连接事件处理 ====================

    /**
     * 处理 BLE 连接/断开事件（仅做UI状态更新和断开重置）。
     * 连接时的 connectionHandled 标记由 MainActivity 通过 {@link #markConnectionHandled()} 管理。
     */
    public void handleBluetoothEvent(BluetoothEvent event) {
        // 断开连接时复位“已处理”标记，允许下次连接重新走初始化编排
        if (!event.isConnected()) {
            connectionHandled = false;
        }
        // 无论连接还是断开，都刷新状态栏与各功能按钮的可用性
        updateConnectionStatus();
    }

    /**
     * 标记首次连接已处理（由 MainActivity 在完成初始化编排后调用）。
     */
    public void markConnectionHandled() {
        connectionHandled = true;
    }

    // ==================== BT配对事件处理 ====================

    /**
     * 处理 BT 经典蓝牙配对状态事件。
     */
    public void handleBtPairEvent(BtPairEvent event) {
        if (event == null) return;

        Log.i(TAG, "BT配对事件: " + event.state + ", device=" + event.deviceName);

        // 配对状态机：PAIRING→PAIRED/FAILED；A2DP 通道建立/断开由 ACL_CONNECTED/ACL_DISCONNECTED 驱动
        switch (event.state) {
            case PAIRING:
                if (tvBtStatus != null) tvBtStatus.setText("音频: ⟳ 正在配对 " + event.deviceName + "...");
                if (tvBtStatus != null) tvBtStatus.setTextColor(0xFFFF9800);
                break;

            case PAIRED:
                isBtPaired = true;
                updateBtStatusDisplay();
                if (btnBtPair != null) btnBtPair.setEnabled(false);
                Toast.makeText(activity, "音频配对成功: " + event.deviceName, Toast.LENGTH_SHORT).show();
                break;

            case FAILED:
                isBtPaired = false;
                updateBtStatusDisplay();
                if (btnBtPair != null) btnBtPair.setEnabled(true);
                Toast.makeText(activity, "音频配对失败，请重试", Toast.LENGTH_SHORT).show();
                break;

            case UNPAIRED:
                isBtPaired = false;
                updateBtStatusDisplay();
                if (btnBtPair != null) btnBtPair.setEnabled(BleOperateManager.getInstance().isConnected());
                Toast.makeText(activity, "音频已断开: " + event.deviceName, Toast.LENGTH_SHORT).show();
                break;

            case ACL_CONNECTED:
                isBtPaired = true;
                updateBtStatusDisplay();
                if (btnBtPair != null) btnBtPair.setEnabled(false);
                Log.i(TAG, "A2DP音频通道已连接: " + event.deviceName);
                break;

            case ACL_DISCONNECTED:
                isBtPaired = false;
                updateBtStatusDisplay();
                if (btnBtPair != null) btnBtPair.setEnabled(BleOperateManager.getInstance().isConnected());
                Log.i(TAG, "A2DP音频通道已断开: " + event.deviceName);
                break;

            case BT_OFF:
                isBtPaired = false;
                updateBtStatusDisplay();
                break;

            case BT_ON:
                // 蓝牙已开启，如果已连接BLE则可尝试配对
                break;
        }
    }

    // ==================== BT配对 ====================

    /**
     * 开始BT经典蓝牙配对流程（A2DP音频）。
     * 先开启BT功能，再启动经典蓝牙扫描，发现设备后自动配对。
     */
    public void startBtPairing() {
        if (!BleOperateManager.getInstance().isConnected()) {
            Toast.makeText(activity, "请先连接眼镜设备", Toast.LENGTH_SHORT).show();
            return;
        }

        Log.i(TAG, "开始BT经典蓝牙配对流程...");
        Toast.makeText(activity, "正在搜索眼镜音频设备...", Toast.LENGTH_SHORT).show();

        try {
            // 通过 SDK 指令让眼镜端开启经典蓝牙(BT)功能
            LargeDataHandler.getInstance().openBT();
            Log.i(TAG, "BT经典蓝牙功能已打开");

            // 延迟1s等经典蓝牙模块就绪后再启动扫描，避免扫描启动过早失败
            mainHandler.postDelayed(() -> {
                try {
                    BleOperateManager.getInstance().classicBluetoothStartScan();
                    Log.i(TAG, "经典蓝牙扫描已启动，等待发现设备...");
                    Toast.makeText(activity,
                            "正在配对中，请查看眼镜端是否有配对请求...", Toast.LENGTH_LONG).show();
                } catch (Exception e) {
                    Log.e(TAG, "启动经典蓝牙扫描失败", e);
                    Toast.makeText(activity,
                            "音频配对启动失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                }
            }, 1000);

        } catch (Exception e) {
            Log.e(TAG, "BT配对流程失败", e);
            Toast.makeText(activity,
                    "音频配对失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 检查A2DP是否已连接（眼镜音频通道）。
     */
    public boolean checkA2dpConnected() {
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null) return false;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ActivityCompat.checkSelfPermission(activity, android.Manifest.permission.BLUETOOTH_CONNECT)
                        != PackageManager.PERMISSION_GRANTED) {
                    return false;
                }
            }
            // A2DP(音频分发)配置文件的连接状态为已连接，表示眼镜可播放手机音频
            int state = adapter.getProfileConnectionState(BluetoothProfile.A2DP);
            return state == BluetoothAdapter.STATE_CONNECTED;
        } catch (SecurityException e) {
            Log.e(TAG, "无BLUETOOTH_CONNECT权限，跳过A2DP检查", e);
            return false;
        } catch (Exception e) {
            Log.e(TAG, "检查A2DP状态失败", e);
            return false;
        }
    }

    // ==================== 清理 ====================

    /**
     * 清理：复位连接编排标记，防止重建页面后状态残留。
     */
    public void onDestroy() {
        connectionHandled = false;
    }
}
