package com.sdk.glassessdksample.receivers;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import androidx.core.content.ContextCompat;

import com.oudmon.ble.base.bluetooth.BleOperateManager;
import com.oudmon.ble.base.bluetooth.DeviceManager;
import com.sdk.glassessdksample.events.BluetoothEvent;
import com.sdk.glassessdksample.events.BtPairEvent;

import org.greenrobot.eventbus.EventBus;

/**
 * 蓝牙系统广播接收器
 * 监听蓝牙设备配对/连接/断开等系统事件，以及经典蓝牙发现和A2DP状态
 * 仅在连接状态实际变化时通过EventBus通知UI
 *
 * 职责与协作说明：
 * - 由 MainActivity 动态注册，接收系统蓝牙相关广播，集中处理 4 类事件：
 *   配对状态变更、ACL 链路连接/断开、经典蓝牙设备发现、系统蓝牙开关。
 * - 通过 EventBus 将事件派发给 UI 层：
 *   配对/ACL 类事件用 {@link BtPairEvent}，通用连接状态用 {@link BluetoothEvent}。
 * - 经典蓝牙扫描发现与当前 BLE 地址匹配的设备时，调用 SDK 的
 *   BleOperateManager#createBondBluetoothJieLi() 触发杰理芯片配对，
 *   实现"让眼镜播放手机音频（A2DP）"的自动/手动配对流程。
 * - 用 lastAclConnected 对 ACL 连接状态去重，避免重复推送相同事件。
 */
public class BluetoothReceiver extends BroadcastReceiver {

    private static final String TAG = "BluetoothReceiver";

    // 上一次 ACL 链路连接状态，用于状态变化去重，避免重复发事件
    private boolean lastAclConnected = false;

    /**
     * 系统蓝牙广播统一入口（主线程回调，应快速返回，不做耗时操作）
     *
     * @param context 接收器上下文（可能为 null，需判空）
     * @param intent  携带动作标识与附加数据的广播 Intent
     */
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;

        // Android 12(S)+ 上，读取设备信息（getName/getAddress）和发起配对
        // 都需要 BLUETOOTH_CONNECT 运行时权限。系统仍会投递这些广播，但无权限时
        // 调用会抛 SecurityException 导致进程崩溃，因此未授予前统一拦截跳过。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && (context == null
                    || ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
                        != PackageManager.PERMISSION_GRANTED)) {
            Log.w(TAG, "无 BLUETOOTH_CONNECT 权限，跳过蓝牙广播处理");
            return;
        }

        // 取出广播动作，据此分发到对应分支处理
        String action = intent.getAction();

        switch (action) {
            // 配对状态变更：正在配对 / 配对成功 / 失败或取消
            case BluetoothDevice.ACTION_BOND_STATE_CHANGED: {
                // 读取当前配对状态与上一配对状态，据此推断配对进展
                int bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE,
                        BluetoothDevice.BOND_NONE);
                int previousBondState = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE,
                        BluetoothDevice.BOND_NONE);
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                String name = device != null ? device.getName() : "未知";
                String addr = device != null ? device.getAddress() : "";

                if (bondState == BluetoothDevice.BOND_BONDING) {
                    // 正在发起配对（系统可能弹出配对确认框）
                    Log.i(TAG, "设备正在配对: " + name + " (" + addr + ")");
                    EventBus.getDefault().post(new BtPairEvent(BtPairEvent.State.PAIRING, name, addr));
                } else if (bondState == BluetoothDevice.BOND_BONDED) {
                    // 配对完成，已成为已配对设备
                    Log.i(TAG, "设备配对成功: " + name + " (" + addr + ")");
                    EventBus.getDefault().post(new BtPairEvent(BtPairEvent.State.PAIRED, name, addr));
                } else if (bondState == BluetoothDevice.BOND_NONE) {
                    // 未配对状态：结合上一状态区分"配对失败"还是"用户取消"
                    if (previousBondState == BluetoothDevice.BOND_BONDING) {
                        Log.w(TAG, "设备配对失败: " + name + " (" + addr + ")");
                        EventBus.getDefault().post(new BtPairEvent(BtPairEvent.State.FAILED, name, addr));
                    } else if (previousBondState == BluetoothDevice.BOND_BONDED) {
                        Log.i(TAG, "设备取消配对: " + name + " (" + addr + ")");
                        EventBus.getDefault().post(new BtPairEvent(BtPairEvent.State.UNPAIRED, name, addr));
                    }
                }
                break;
            }

            // ACL（异步无连接链路）已连接：经典蓝牙底层数据链路建立成功
            case BluetoothDevice.ACTION_ACL_CONNECTED: {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                String name = device != null ? device.getName() : "未知";
                String addr = device != null ? device.getAddress() : "";
                Log.i(TAG, "蓝牙设备ACL已连接: " + name + " (" + addr + ")");
                EventBus.getDefault().post(new BtPairEvent(BtPairEvent.State.ACL_CONNECTED, name, addr));
                // 仅当从未连接变为已连接时，才推送通用连接事件（去重）
                if (!lastAclConnected) {
                    lastAclConnected = true;
                    EventBus.getDefault().post(new BluetoothEvent(true));
                }
                break;
            }

            // ACL 已断开：经典蓝牙底层数据链路断开
            case BluetoothDevice.ACTION_ACL_DISCONNECTED: {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                String name = device != null ? device.getName() : "未知";
                String addr = device != null ? device.getAddress() : "";
                Log.i(TAG, "蓝牙设备ACL已断开: " + name + " (" + addr + ")");
                EventBus.getDefault().post(new BtPairEvent(BtPairEvent.State.ACL_DISCONNECTED, name, addr));
                // 仅当从已连接变为断开时，才推送通用连接事件（去重）
                if (lastAclConnected) {
                    lastAclConnected = false;
                    EventBus.getDefault().post(new BluetoothEvent(false));
                }
                break;
            }

            // 经典蓝牙扫描发现新设备（需要配合 startDiscovery 使用）
            case BluetoothDevice.ACTION_FOUND: {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (device != null) {
                    String deviceAddress = device.getAddress();
                    String deviceName = device.getName() != null ? device.getName() : "未知设备";
                    // 取当前已连接的 BLE 眼镜地址，用于与扫描到的经典蓝牙设备地址比对
                    String currentBleAddress = DeviceManager.getInstance().getDeviceAddress();

                    Log.d(TAG, "发现经典蓝牙设备: " + deviceName + " (" + deviceAddress
                            + "), 当前BLE地址: " + currentBleAddress);

                    // 当经典蓝牙地址与当前BLE地址匹配时，触发杰理芯片配对
                    if (currentBleAddress != null && !currentBleAddress.isEmpty()) {
                        // 同一台眼镜的经典蓝牙地址通常与 BLE 地址一致，据此确认目标设备
                        if (deviceAddress.equalsIgnoreCase(currentBleAddress)) {
                            Log.i(TAG, "找到匹配设备，开始杰理配对: " + deviceName + " (" + deviceAddress + ")");
                            try {
                                // 调用 SDK 提供的杰理专属配对接口发起经典蓝牙配对
                                BleOperateManager.getInstance().createBondBluetoothJieLi(device);
                                EventBus.getDefault().post(new BtPairEvent(BtPairEvent.State.PAIRING,
                                        deviceName, deviceAddress));
                            } catch (Exception e) {
                                // 配对接口调用异常（如权限不足、蓝牙关闭）时上报失败
                                Log.e(TAG, "杰理配对调用失败", e);
                                EventBus.getDefault().post(new BtPairEvent(BtPairEvent.State.FAILED,
                                        deviceName, deviceAddress));
                            }
                        }
                    } else {
                        Log.w(TAG, "当前没有连接的BLE设备地址，无法匹配经典蓝牙设备");
                    }
                }
                break;
            }

            // 系统蓝牙开关状态变化（用户关闭/打开系统蓝牙时触发）
            case BluetoothAdapter.ACTION_STATE_CHANGED: {
                int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1);
                if (state == BluetoothAdapter.STATE_OFF) {
                    Log.i(TAG, "系统蓝牙已关闭");
                    EventBus.getDefault().post(new BtPairEvent(BtPairEvent.State.BT_OFF, "", ""));
                } else if (state == BluetoothAdapter.STATE_ON) {
                    Log.i(TAG, "系统蓝牙已开启");
                    EventBus.getDefault().post(new BtPairEvent(BtPairEvent.State.BT_ON, "", ""));
                }
                break;
            }
        }
    }

    /**
     * 检查 A2DP（高质量音频播放 profile）是否已连接
     * 用于判断眼镜作为蓝牙音箱时，手机音频播放通路是否就绪
     *
     * @param context 上下文（保留参数，供后续扩展）
     * @return true 表示 A2DP 已连接；适配器不可用或查询异常时返回 false
     */
    public static boolean isA2dpConnected(Context context) {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        // 设备不支持蓝牙时直接返回 false
        if (adapter == null) return false;

        // Android 12(S)+ 查询 profile 状态需要 BLUETOOTH_CONNECT 运行时权限；
        // 未授予（或缺少上下文无法校验）时按未连接处理，避免 SecurityException
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && (context == null
                    || ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
                        != PackageManager.PERMISSION_GRANTED)) {
            Log.w(TAG, "无 BLUETOOTH_CONNECT 权限，跳过A2DP状态检查");
            return false;
        }

        try {
            // 查询 A2DP profile 的连接状态
            int state = adapter.getProfileConnectionState(BluetoothProfile.A2DP);
            return state == BluetoothAdapter.STATE_CONNECTED;
        } catch (Exception e) {
            // 缺少 BLUETOOTH_CONNECT 权限等情况会抛异常，捕获后按未连接处理
            Log.e(TAG, "检查A2DP状态失败", e);
            return false;
        }
    }
}
