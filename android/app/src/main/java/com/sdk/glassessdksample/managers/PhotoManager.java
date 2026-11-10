package com.sdk.glassessdksample.managers;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.Toast;

import com.oudmon.ble.base.bluetooth.BleOperateManager;
import com.oudmon.ble.base.communication.LargeDataHandler;
import com.sdk.glassessdksample.GlassApp;
import com.sdk.glassessdksample.utils.FileUtils;

import java.io.ByteArrayOutputStream;
import java.io.File;

/**
 * 拍照控制、智能拍照缩略图接收与解码、照片预览。
 *
 * 功能职责：
 *  - 发送普通拍照指令(0x02,0x01,0x01)，根据眼镜回传的 workType 判断是否拍照成功
 *  - 接收智能拍照的缩略图分包数据，按序累积拼接为完整字节流后保存
 *  - 从原始字节中定位 JPEG 文件头(FF D8)并裁剪出图片数据，写入设备照片目录
 *  - 将保存的图片解码并加载到预览 ImageView 上，供外部读取最近照片路径
 *
 * 协作关系：
 *  - 依赖 BleOperateManager.getInstance() 判断 BLE 连接状态
 *  - 依赖 LargeDataHandler.getInstance().glassesControl 向眼镜下发指令并接收回调
 *  - 依赖 GlassApp.getInstance().getPhotoDir() 获取设备专属照片目录（picture 子目录）
 *  - 依赖 FileUtils.writeToFile 将字节写入文件
 *  - 缩略图分包回调由外部（BLE 数据回调）调用 receiveThumbnailChunk 驱动
 */
public class PhotoManager {

    private static final String TAG = "PhotoManager";

    private final Activity activity;
    private final Handler mainHandler;
    private final ImageView ivPhotoPreview;
    private final OnCaptureCompleteListener onCaptureCompleteListener;

    private String lastPhotoPath = null;
    private ByteArrayOutputStream thumbnailAccumulator = null;

    /** 缩略图保存完成回调（用于上传后端），可为 null */
    private OnThumbnailReadyListener onThumbnailReadyListener;

    /**
     * 缩略图就绪回调。
     * 在调用线程（SDK 回调线程）中触发，**非主线程**，实现方需自行处理线程切换。
     */
    public interface OnThumbnailReadyListener {
        /**
         * @param jpegFile 已裁剪并保存为 .jpg 的缩略图文件
         */
        void onThumbnailReady(File jpegFile);
    }

    /** 设置缩略图就绪回调 */
    public void setOnThumbnailReadyListener(OnThumbnailReadyListener listener) {
        this.onThumbnailReadyListener = listener;
    }

    public PhotoManager(Activity activity, Handler mainHandler,
                        ImageView ivPhotoPreview,
                        OnCaptureCompleteListener onCaptureCompleteListener) {
        this.activity = activity;
        this.mainHandler = mainHandler;
        this.ivPhotoPreview = ivPhotoPreview;
        this.onCaptureCompleteListener = onCaptureCompleteListener;
    }

    // ==================== 普通拍照 ====================

    /**
     * 发送拍照指令 (0x02, 0x01, 0x01)。
     * 通过 glassesControl 异步回调判断结果：workType=1/6 表示拍照成功，
     * 其余 workType 表示眼镜当前处于其它状态（录像中/传输中/录音中等），给出对应提示。
     */
    public void takePhoto() {
        if (!BleOperateManager.getInstance().isConnected()) {
            Toast.makeText(activity, "请先连接眼镜设备", Toast.LENGTH_SHORT).show();
            return;
        }

        LargeDataHandler.getInstance().glassesControl(
                new byte[]{0x02, 0x01, 0x01},
                (cmdType, response) -> {
                    // 仅当返回的是正常业务数据(dataType==1)且无错误(errorCode==0)时才处理
                    if (response != null && response.getDataType() == 1 && response.getErrorCode() == 0) {
                        int workType = response.getWorkTypeIng();
                        if (workType == 1 || workType == 6) {
                            Log.i(TAG, "拍照成功");
                            mainHandler.post(() -> {
                                Toast.makeText(activity, "拍照成功", Toast.LENGTH_SHORT).show();
                                if (onCaptureCompleteListener != null) {
                                    // 延迟 2 秒触发“拍照完成”回调，给眼镜端留出写入时间
                                    mainHandler.postDelayed(onCaptureCompleteListener::onCaptureComplete, 2000);
                                }
                            });
                        } else {
                            // 其它 workType 表示眼镜正忙，映射为对应状态提示文案
                            String msg;
                            switch (workType) {
                                case 2: msg = "眼镜正在录像中"; break;
                                case 4: msg = "眼镜正在传输中"; break;
                                case 8: msg = "眼镜正在录音中"; break;
                                default: msg = "眼镜状态: " + workType; break;
                            }
                            final String finalMsg = msg;
                            mainHandler.post(() -> Toast.makeText(activity, finalMsg, Toast.LENGTH_SHORT).show());
                        }
                    }
                }
        );
    }

    // ==================== 智能拍照 ====================

    /** 智能拍照的缩略图尺寸参数，SDK 文档标注取值范围 0~6，示例值为 2 */
    private static final byte THUMBNAIL_SIZE = 0x02;

    /**
     * 发送智能拍照指令 (0x02, 0x01, 0x06, size, size, 0x02)。
     *
     * <p>与 {@link #takePhoto()} 的区别：普通拍照指令 (0x02,0x01,0x01) **不会触发缩略图事件 0x02**，
     * 因此拿不到缩略图；智能拍照会触发事件 0x02，由 {@code MediaSyncManager} 的
     * deviceNotifyListener 捕获后调用 {@code getPictureThumbnails()} 拉取缩略图分包，
     * 最终经 {@link #receiveThumbnailChunk} 累积并由 {@link #saveThumbnail} 保存。</p>
     *
     * <p>因此本方法**不需要处理响应回调**——真正的结果通过上述事件链异步交付。</p>
     *
     * @see #setOnThumbnailReadyListener 缩略图保存完成后的回调
     */
    public void takeSmartPhoto() {
        if (!BleOperateManager.getInstance().isConnected()) {
            Toast.makeText(activity, "请先连接眼镜设备", Toast.LENGTH_SHORT).show();
            return;
        }

        Log.i(TAG, "发送智能拍照指令，缩略图尺寸=" + THUMBNAIL_SIZE);
        mainHandler.post(() ->
                Toast.makeText(activity, "智能拍照中，等待缩略图…", Toast.LENGTH_SHORT).show());

        LargeDataHandler.getInstance().glassesControl(
                new byte[]{0x02, 0x01, 0x06, THUMBNAIL_SIZE, THUMBNAIL_SIZE, 0x02},
                (cmdType, response) -> {
                    // 仅记录状态用于排查：即使响应为 null 或 workType 非预期，
                    // 缩略图仍可能通过事件 0x02 正常送达
                    if (response != null) {
                        Log.i(TAG, "智能拍照响应: dataType=" + response.getDataType()
                                + ", errCode=" + response.getErrorCode()
                                + ", workType=" + response.getWorkTypeIng());
                    } else {
                        Log.i(TAG, "智能拍照响应为空（不影响事件 0x02 的缩略图送达）");
                    }
                });
    }

    // ==================== 缩略图（智能拍照） ====================

    /**
     * 累积缩略图分包数据。
     * 智能拍照的缩略图可能被拆分成多包下传，这里按序写入 ByteArrayOutputStream 累积，
     * 直到收到 isLast 标记的最后一块，才把完整字节交给 saveThumbnail 解码保存。
     *
     * 背景：此前缩略图曾因分包接收不完整被误判为“加密”，实际是缺少累积拼接导致数据缺失；
     * 修复后采用本累积逻辑——先攒齐所有分包，最后一包到达后再统一处理，彻底解决“加密”问题。
     *
     * @param data      分包数据
     * @param isLast    是否为最后一包
     */
    public void receiveThumbnailChunk(byte[] data, boolean isLast) {
        if (data == null || data.length == 0) return;

        // 首次收到分包时创建累积缓冲区（按序追加，保持字节顺序）
        if (thumbnailAccumulator == null) {
            thumbnailAccumulator = new ByteArrayOutputStream();
        }
        try {
            // 将本包数据追加到缓冲区尾部
            thumbnailAccumulator.write(data, 0, data.length);
        } catch (Exception e) {
            Log.e(TAG, "缩略图数据累积失败", e);
        }

        // 收到最后一包且缓冲区内已有数据时，统一取出完整字节流并处理
        if (isLast && thumbnailAccumulator != null && thumbnailAccumulator.size() > 0) {
            byte[] completeData = thumbnailAccumulator.toByteArray();
            Log.i(TAG, "缩略图接收完成，总大小: " + completeData.length + " bytes");
            saveThumbnail(completeData);
            // 处理完毕释放累积缓冲区，等待下一次拍照重新累积
            try { thumbnailAccumulator.close(); } catch (java.io.IOException e) { /* ignore */ }
            thumbnailAccumulator = null;
        }
    }

    /**
     * 保存缩略图：查找JPEG文件头(FF D8)，提取JPEG数据保存到picture目录。
     * 若找不到 JPEG 头，则把原始字节以 .bin 形式保存，便于排查数据格式问题。
     * @param data 累积拼接后的完整缩略图原始字节
     */
    private void saveThumbnail(byte[] data) {
        // 打印前32字节用于诊断数据格式（确认是否存在额外包头/前缀）
        int previewLen = Math.min(32, data.length);
        StringBuilder hexBuilder = new StringBuilder();
        for (int i = 0; i < previewLen; i++) {
            hexBuilder.append(String.format("%02X ", data[i] & 0xFF));
        }
        Log.i(TAG, "缩略图原始数据前" + previewLen + "字节: " + hexBuilder.toString().trim()
                + "  总大小: " + data.length + " bytes");

        // 查找JPEG文件头：扫描 0xFF 0xD8 两个连续字节作为 JPEG 起始偏移
        int jpegStart = -1;
        for (int i = 0; i < data.length - 1; i++) {
            if ((data[i] & 0xFF) == 0xFF && (data[i + 1] & 0xFF) == 0xD8) {
                jpegStart = i;
                break;
            }
        }
        File photoDir = GlassApp.getInstance().getPhotoDir();
        if (photoDir == null) {
            Log.w(TAG, "设备名称未设置，无法保存缩略图");
            return;
        }

        if (jpegStart == -1) {
            // 未找到 JPEG 头：数据格式可能异常，将原始字节保存为 .bin 供离线分析
            Log.w(TAG, "未找到JPEG文件头(FF D8)，保存原始数据用于分析");
            String fileName = "thumb_raw_" + System.currentTimeMillis() + ".bin";
            String filePath = photoDir.getAbsolutePath() + "/" + fileName;
            FileUtils.writeToFile(data, filePath);
            return;
        }

        Log.i(TAG, "找到JPEG文件头，偏移=" + jpegStart);
        // 从 JPEG 头偏移处截取到末尾，即为完整的图片数据
        byte[] jpegData = new byte[data.length - jpegStart];
        System.arraycopy(data, jpegStart, jpegData, 0, jpegData.length);

        // 命名规则：thumb_时间戳.jpg（时间戳避免重名覆盖）
        String fileName = "thumb_" + System.currentTimeMillis() + ".jpg";
        String filePath = photoDir.getAbsolutePath() + "/" + fileName;
        FileUtils.writeToFile(jpegData, filePath);

        // 通知外部（如后端上传）；在回调线程中直接触发，避免大图操作影响主线程
        if (onThumbnailReadyListener != null) {
            File saved = new File(filePath);
            if (saved.exists() && saved.length() > 0) {
                try {
                    onThumbnailReadyListener.onThumbnailReady(saved);
                } catch (Exception e) {
                    Log.e(TAG, "缩略图就绪回调异常", e);
                }
            }
        }

        // 保存完成后切回主线程加载预览
        mainHandler.post(() -> loadPhotoPreview(filePath));
    }

    // ==================== 照片预览 ====================

    /**
     * 加载照片到预览 ImageView。
     * 使用 BitmapFactory 解码本地图片并直接设置到预览控件，失败时仅记录日志不抛异常。
     * @param filePath 图片文件绝对路径
     */
    public void loadPhotoPreview(String filePath) {
        try {
            Bitmap bitmap = BitmapFactory.decodeFile(filePath);
            if (bitmap != null) {
                lastPhotoPath = filePath; // 记录最近一次成功加载的照片路径
                ivPhotoPreview.setImageBitmap(bitmap);
                ivPhotoPreview.setVisibility(View.VISIBLE);
                Log.i(TAG, "照片加载成功: " + filePath);
            }
        } catch (Exception e) {
            Log.e(TAG, "加载照片失败", e);
        }
    }

    /**
     * 获取最近一次成功加载的照片路径，供外部（如保存、上传、二次确认）使用。
     * @return 照片绝对路径；若尚未加载过任何照片则返回 null
     */
    public String getLastPhotoPath() {
        return lastPhotoPath;
    }
}
