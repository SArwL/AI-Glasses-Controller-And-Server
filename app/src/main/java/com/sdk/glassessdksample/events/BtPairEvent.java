package com.sdk.glassessdksample.events;

/**
 * BT 经典蓝牙配对事件
 * 通过 EventBus 传递 BT 配对 / 连接状态变化到 MainActivity，
 * 用于驱动 A2DP 配对流程的 UI 反馈（配对中、成功、失败、ACL 连接等）。
 * 事件来源：{@link com.sdk.glassessdksample.receivers.BluetoothReceiver}。
 */
public class BtPairEvent {

    public enum State {
        /** 正在配对中 */
        PAIRING,
        /** 配对成功 */
        PAIRED,
        /** 配对失败 */
        FAILED,
        /** 取消配对 */
        UNPAIRED,
        /** ACL连接成功 */
        ACL_CONNECTED,
        /** ACL连接断开 */
        ACL_DISCONNECTED,
        /** 系统蓝牙已关闭 */
        BT_OFF,
        /** 系统蓝牙已开启 */
        BT_ON
    }

    // 配对 / 连接状态
    public final State state;
    // 设备名称（可能为 null）
    public final String deviceName;
    // 设备蓝牙 MAC 地址
    public final String deviceAddress;

    /**
     * @param state         配对 / 连接状态
     * @param deviceName    设备名称
     * @param deviceAddress 设备蓝牙地址
     */
    public BtPairEvent(State state, String deviceName, String deviceAddress) {
        this.state = state;
        this.deviceName = deviceName;
        this.deviceAddress = deviceAddress;
    }

    /**
     * 判断当前是否处于"音频可播放"状态
     * 即 ACL 链路已连接或已完成配对，可认为经典蓝牙 A2DP 音频通路已就绪
     *
     * @return true 表示音频通路已连接
     */
    public boolean isAudioConnected() {
        return state == State.ACL_CONNECTED || state == State.PAIRED;
    }
}
