package com.sdk.glassessdksample.managers;

import android.app.Activity;
import android.os.Handler;
import android.util.Log;
import android.widget.Button;
import android.widget.Toast;

import com.oudmon.ble.base.bluetooth.BleOperateManager;
import com.oudmon.ble.base.communication.LargeDataHandler;

/**
 * 眼镜端录音控制（开始/停止）。
 * 完全遵循SDK文档和参考App的简洁模式：仅检查 dataType/errorCode，不做冗余的状态推测。
 *
 * 职责：通过 BLE 指令控制眼镜端录音的启动与停止。
 * 指令格式：glassesControl(byte[]{cmdType, arg, workType})，
 * - 开始录音：{0x02, 0x01, 0x08}
 * - 停止录音：{0x02, 0x01, 0x0c}
 *
 * 关键约定：眼镜同时只能处于一种工作模式，若当前已是其他模式（录像/传输/OTA/AI对话），
 * 开始录音会被拒绝，此时通过响应中的 workTypeIng 提示用户当前所处模式。
 *
 * 协作类：BleOperateManager（连接状态判断）、LargeDataHandler（发送指令）、
 * MediaSyncManager（录音完成后同步文件，通过回调联动）。
 */
public class RecordingManager {

    private static final String TAG = "RecordingManager";

    /** 宿主 Activity，用于弹 Toast */
    private final Activity activity;
    /** 主线程 Handler，把 BLE 响应回调切回主线程更新 UI */
    private final Handler mainHandler;
    /** 录音按钮，用于切换"开始录音/停止录音"文案（可为 null） */
    private final Button btnRecord;

    /** 当前是否正在录音 */
    private boolean isRecording = false;

    /**
     * 构造录音管理器。
     *
     * @param activity    宿主 Activity
     * @param mainHandler 主线程 Handler
     * @param btnRecord   录音按钮（可为 null，null 时不做按钮文案切换）
     */
    public RecordingManager(Activity activity, Handler mainHandler,
                            Button btnRecord) {
        this.activity = activity;
        this.mainHandler = mainHandler;
        this.btnRecord = btnRecord;
    }

    // ==================== 录音控制 ====================

    /**
     * 按钮点击切换录音状态。
     */
    public void toggleRecording() {
        if (isRecording) {
            stopRecording();
        } else {
            startRecording();
        }
    }

    /**
     * 发送开始录音指令 (0x02, 0x01, 0x08)。
     * 严格遵循SDK文档：dataType==1 且 errorCode==0 且 workType==8 才表示录音成功。
     */
    public void startRecording() {
        // 未连接设备时直接提示，避免指令无效下发
        if (!BleOperateManager.getInstance().isConnected()) {
            Toast.makeText(activity, "请先连接眼镜设备", Toast.LENGTH_SHORT).show();
            return;
        }

        Log.i(TAG, "发送开始录音指令");
        // 发送指令 {0x02,0x01,0x08}，响应通过回调异步返回
        LargeDataHandler.getInstance().glassesControl(
                new byte[]{0x02, 0x01, 0x08},
                (cmdType, response) -> {
                    // BLE 响应回调不在主线程，先切回主线程再更新 UI
                    mainHandler.post(() -> {
                        // dataType==1 表示指令响应帧
                        if (response != null && response.getDataType() == 1) {
                            // errorCode==0 表示指令执行成功
                            if (response.getErrorCode() == 0) {
                                // workType==8 确认已进入录音模式
                                if (response.getWorkTypeIng() == 8) {
                                    isRecording = true;
                                    if (btnRecord != null) btnRecord.setText("停止录音");
                                    Toast.makeText(activity, "眼镜正在录音", Toast.LENGTH_SHORT).show();
                                    Log.i(TAG, "眼镜正在录音");
                                } else {
                                    // 参照参考App的 handleWorkTypeStatus
                                    String statusText;
                                    switch (response.getWorkTypeIng()) {
                                        case 2: statusText = "眼镜正在录像"; break;
                                        case 4: statusText = "眼镜正在传输模式"; break;
                                        case 5: statusText = "眼镜正在OTA升级模式"; break;
                                        case 7: statusText = "眼镜正在AI对话模式"; break;
                                        default: statusText = "眼镜状态: " + response.getWorkTypeIng(); break;
                                    }
                                    Toast.makeText(activity, statusText, Toast.LENGTH_SHORT).show();
                                }
                            } else {
                                Toast.makeText(activity,
                                        "录音启动失败: " + response.getErrorCode(), Toast.LENGTH_SHORT).show();
                                Log.e(TAG, "录音启动失败: errorCode=" + response.getErrorCode());
                                isRecording = false;
                            }
                        }
                    });
                }
        );
    }

    /**
     * 发送停止录音指令 (0x02, 0x01, 0x0c)。
     * 严格遵循SDK文档：仅 errorCode==0 时才确认停止。
     */
    public void stopRecording() {
        Log.i(TAG, "发送停止录音指令");
        // 发送指令 {0x02,0x01,0x0c}，响应通过回调异步返回
        LargeDataHandler.getInstance().glassesControl(
                new byte[]{0x02, 0x01, 0x0c},
                (cmdType, response) -> {
                    mainHandler.post(() -> {
                        // dataType==1 且 errorCode==0 才确认已停止
                        if (response != null && response.getDataType() == 1) {
                            if (response.getErrorCode() == 0) {
                                isRecording = false;
                                if (btnRecord != null) btnRecord.setText("开始录音");
                                Log.i(TAG, "录音已停止");
                                Toast.makeText(activity, "录音已停止", Toast.LENGTH_SHORT).show();
                            } else {
                                Log.e(TAG, "停止录音失败: errorCode=" + response.getErrorCode());
                            }
                        }
                    });
                }
        );
    }

    // ==================== 状态查询 ====================

    /** 查询当前是否正在录音。 */
    public boolean isRecording() {
        return isRecording;
    }
}
