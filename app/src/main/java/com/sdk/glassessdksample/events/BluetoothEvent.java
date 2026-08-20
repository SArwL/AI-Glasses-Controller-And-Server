package com.sdk.glassessdksample.events;

/**
 * EventBus 事件：蓝牙连接状态变化
 * 由 {@link com.sdk.glassessdksample.receivers.BluetoothReceiver}、
 * {@link com.sdk.glassessdksample.receivers.MyBluetoothReceiver} 在连接状态变化时发出，
 * UI 层（如 MainActivity）订阅后刷新蓝牙连接图标 / 状态显示。
 */
public class BluetoothEvent {

    // 是否处于已连接状态
    private final boolean connected;

    /**
     * @param connected true=已连接，false=未连接
     */
    public BluetoothEvent(boolean connected) {
        this.connected = connected;
    }

    /**
     * @return 当前连接状态（true=已连接）
     */
    public boolean isConnected() {
        return connected;
    }
}
