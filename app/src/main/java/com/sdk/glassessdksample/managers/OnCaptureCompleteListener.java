package com.sdk.glassessdksample.managers;

/**
 * 拍照/录音完成后的回调接口。
 * 用于打破 PhotoManager/RecordingManager 与 MediaSyncManager 之间的循环依赖。
 *
 * 由 MediaSyncManager 持有并注册回调，拍照/录音完成并保存文件后触发
 * onCaptureComplete()，用于通知 UI/其他模块刷新文件列表。
 */
public interface OnCaptureCompleteListener {
    /** 拍照或录音完成后的回调，用于刷新文件列表等。 */
    void onCaptureComplete();
}
