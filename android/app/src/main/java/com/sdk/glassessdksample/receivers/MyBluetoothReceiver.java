package com.sdk.glassessdksample.receivers;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.oudmon.ble.base.bluetooth.BleAction;
import com.oudmon.ble.base.bluetooth.BleOperateManager;
import com.oudmon.ble.base.communication.LargeDataHandler;
import com.sdk.glassessdksample.events.BluetoothEvent;

import org.greenrobot.eventbus.EventBus;

/**
 * 本地广播接收器 —— 监听SDK内部的BLE连接状态变化
 * 在 SERVICE_DISCOVERED 时解锁SDK写入门控并启用BLE Notification通道
 * 仅在 connect/disconnect 状态变化时通过 EventBus 通知 UI
 *
 * 职责与协作说明：
 * - 接收 SDK（本地广播）发出的 BLE GATT 连接事件，并做两件事：
 *   1) 连接/断开状态变化时通过 {@link BluetoothEvent} 通知 UI；
 *   2) 服务发现完成后调用 SDK 接口初始化读写通道
 *      （setReady + initEnable + packageLength），这是后续收发指令、
 *      接收大数据的必要前提，缺失会导致指令无响应。
 * - 协作类：BleOperateManager（SDK 连接管理）、LargeDataHandler（SDK 大数据传输）、
 *   EventBus（事件总线）、BluetoothEvent（连接状态事件）。
 */
public class MyBluetoothReceiver extends BroadcastReceiver {

    private static final String TAG = "MyBleReceiver";

    // 上一次已知的连接状态，用于状态变化去重
    private boolean lastConnectedState = false;

    /**
     * 本地广播入口（主线程回调，应快速返回，不做耗时操作）
     *
     * @param context 接收器上下文
     * @param intent  SDK 发送的本地广播 Intent
     */
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;

        // 获取广播动作标识，据此判断事件类型
        String action = intent.getAction();

        if (action.equals(BleAction.BLE_GATT_CONNECTED)) {
            // BLE GATT 连接成功：刷新连接状态并通知 UI
            checkAndNotifyConnection();
            return;
        }

        if (action.equals(BleAction.BLE_GATT_DISCONNECTED)) {
            // BLE GATT 断开：刷新连接状态并通知 UI
            checkAndNotifyConnection();
            return;
        }

        if (action.equals(BleAction.BLE_SERVICE_DISCOVERED)) {
            // 服务发现完成：此时可以开始初始化读写通道
            boolean connected = BleOperateManager.getInstance().isConnected();
            boolean wasReady = BleOperateManager.getInstance().isReady();

            // SDK 要求 ready=true + initEnable() 才能正常收发指令
            if (connected && !wasReady) {
                // 解锁写入门控：setReady 标记就绪，initEnable 开启 Notification，
                // packageLength 设置分片长度，三者配合才能收发指令与大数据
                BleOperateManager.getInstance().setReady(true);
                LargeDataHandler.getInstance().initEnable();
                LargeDataHandler.getInstance().packageLength();
                Log.i(TAG, "BLE通道就绪: setReady + initEnable + packageLength 完成");
            }

            checkAndNotifyConnection();
            return;
        }
    }

    /**
     * 检查当前 BLE 连接状态，仅在状态发生变化时通过 EventBus 通知 UI（去重）
     */
    private void checkAndNotifyConnection() {
        try {
            boolean connected = BleOperateManager.getInstance().isConnected();
            // 状态变化时才发事件，避免每次广播都重复刷新 UI
            if (connected != lastConnectedState) {
                lastConnectedState = connected;
                Log.i(TAG, "BLE连接状态变化: " + (connected ? "已连接" : "未连接"));
                EventBus.getDefault().post(new BluetoothEvent(connected));
            }
        } catch (Exception e) {
            Log.e(TAG, "检查BLE连接状态失败", e);
        }
    }
}
