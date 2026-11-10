package com.sdk.glassessdksample.managers;

import android.util.Log;

import com.sdk.glassessdksample.utils.FileUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

/**
 * AI 数据上传器：把本地文件按分片协议经 UDP 发送到后端。
 *
 * <h3>为什么必须分片</h3>
 * UDP 单个数据报上限 65507 字节，而实测语音数据量为 32 字节/毫秒：
 * <pre>
 *   3 秒语音  ≈ 96 KB   （约 6 片）
 *   10 秒语音 ≈ 320 KB  （约 20 片）
 * </pre>
 * 因此超出单片容量的文件必须切成多片发送，由后端按 seq 重组。
 *
 * <h3>分片报文格式（16 字节头 + 载荷）</h3>
 * <pre>
 *   偏移  长度  含义
 *   0     4    魔数 "GLSY" (0x47 0x4C 0x53 0x59)
 *   4     1    协议版本 = 1
 *   5     1    消息类型（见 {@link AiUdpManager} 的 TYPE_* 常量）
 *   6     2    载荷长度（大端）
 *   8     2    分片序号 seq，从 0 开始（大端）
 *   10    2    本消息总片数 total（大端）
 *   12    4    保留 = 0
 *   16    N    载荷（N = 载荷长度）
 * </pre>
 *
 * <h3>本期不做重传</h3>
 * 后端暂未实现 ACK，因此这里只上报"已发出"进度，不保证送达。若后续需要可靠传输，
 * 在此类中增加 ACK 等待与重传逻辑即可，报文格式无需改动（seq/total 已预留）。
 *
 * <h3>一次性数据的上传清理</h3>
 * AI 对话的 PCM 只是"送给后端做语音识别的过路数据"，本地不需要长期保存，
 * 因此这类文件用 {@code uploadAsync(..., deleteAfterUpload=true)} 上传：
 * 无论成功、断线还是异常，流程一结束就删除本地文件。
 * 普通媒体（普通录音 / 照片 / 录像）经 WiFi 同步链路进入长期存储，不走这里的清理逻辑。
 */
public class AiUploader {

    private static final String TAG = "AiUploader";

    /** 单片的载荷字节数。取值权衡：太大则丢一片代价高，太小则包数多、UDP 开销占比高 */
    public static final int CHUNK_PAYLOAD_SIZE = 16 * 1024;
    /** 分片头长度 */
    private static final int CHUNK_HEADER_LEN = 16;
    /** 分片间隔（毫秒）：让出时间片，避免瞬时打满 send buffer 导致丢包 */
    private static final long CHUNK_SEND_INTERVAL_MS = 2;

    private AiUploader() {
    }

    /**
     * 上传进度回调。所有回调都在**上传线程**触发（非主线程），
     * 调用方若需更新 UI 请自行切换到主线程。
     */
    public interface ProgressListener {
        /**
         * 上传完成（所有分片已发出）。注意：UDP 无送达确认，"完成"仅表示本端发送完毕。
         *
         * @param sentChunks 实际发出的分片数
         */
        void onUploadFinished(String fileName, int sentChunks, int totalBytes);

        /**
         * 上传失败（未连接、发送失败、中途断线、文件读取异常等）。
         *
         * @param kind 失败类别，供调用方决定如何向用户表述（见 {@link FailureKind}）
         */
        void onUploadFailed(String fileName, String reason, FailureKind kind);
    }

    /**
     * 上传失败的类别。
     *
     * <p>存在的意义是避免"张冠李戴"的提示：如果把本地文件错误也报成"服务器连接失败"，
     * 用户会去反复重连后端，而真正的问题在本地。因此调用方应按类别分别组织文案。</p>
     */
    public enum FailureKind {
        /** 传输侧失败：UDP 未连接、发送失败、中途断线 —— 属于"后端/网络不通" */
        TRANSPORT,
        /** 本地侧失败：文件不存在或为空、读取异常、线程被中断 —— 与后端无关 */
        LOCAL
    }

    /**
     * 异步上传一个文件（上传后保留本地文件）。方法立即返回，实际上传在独立线程中进行。
     *
     * @param file     待上传的本地文件
     * @param msgType  消息类型（{@link AiUdpManager#TYPE_VOICE_PCM} 等）
     * @param listener 进度回调，可为 null
     */
    public static void uploadAsync(final File file, final byte[] msgType,
                                   final ProgressListener listener) {
        uploadAsync(file, msgType, listener, false);
    }

    /**
     * 异步上传一个文件，可选择上传后删除本地文件。
     *
     * <p>用于"一次性数据"：这类文件只是送给后端的过路数据（如 AI 对话的 PCM），
     * 本地不需要长期保存。{@code deleteAfterUpload=true} 时，<b>无论上传成功、中途断线还是
     * 读取异常，上传流程一结束就立即删除本地文件</b>——因为后端没收到时，留着也无从补传。</p>
     *
     * <p>删除在 "ai-upload" 线程内执行（非主线程），且在 {@link ProgressListener} 回调<b>之后</b>，
     * 因此回调里仍可正常读取文件名与大小。与普通媒体（录音/照片/录像）无关，
     * 那些文件走 WiFi 同步链路并长期保留。</p>
     *
     * @param file              待上传的本地文件
     * @param msgType           消息类型（{@link AiUdpManager#TYPE_VOICE_PCM} 等）
     * @param listener          进度回调，可为 null
     * @param deleteAfterUpload true 表示一次性数据，流程结束后删除本地文件
     */
    public static void uploadAsync(final File file, final byte[] msgType,
                                   final ProgressListener listener,
                                   final boolean deleteAfterUpload) {
        if (file == null || !file.exists() || file.length() == 0) {
            Log.w(TAG, "文件不存在或为空，跳过上传: " + (file == null ? "null" : file.getPath()));
            if (listener != null) {
                listener.onUploadFailed(file == null ? "null" : file.getName(),
                        "文件不存在或为空", FailureKind.LOCAL);
            }
            // 空文件同样没有保留价值（例如拼接结果为空）
            if (deleteAfterUpload) {
                deleteTransientFile(file);
            }
            return;
        }

        // 未连接时不发起上传，避免无谓的线程与 I/O
        if (!AiUdpManager.getInstance().isConnected()) {
            Log.w(TAG, "UDP 未连接，跳过上传: " + file.getName());
            if (listener != null) {
                listener.onUploadFailed(file.getName(), "UDP 未连接", FailureKind.TRANSPORT);
            }
            if (deleteAfterUpload) {
                deleteTransientFile(file);
            }
            return;
        }

        new Thread(() -> {
            try {
                doUpload(file, msgType, listener);
            } finally {
                // 一次性数据：成功/中断/异常任何结局都在此清理，避免残留在本地
                if (deleteAfterUpload) {
                    deleteTransientFile(file);
                }
            }
        }, "ai-upload").start();
    }

    /**
     * 删除一次性数据文件。删除失败只记日志，不上抛——
     * 清理失败不应改变"上传结果"这一回调语义。
     *
     * @param file 待删除文件，可为 null（静默忽略）
     */
    private static void deleteTransientFile(File file) {
        if (file == null || !file.exists()) return;
        long size = file.length();
        if (FileUtils.deleteFile(file)) {
            Log.i(TAG, "已清理本地一次性文件: " + file.getName() + "（" + size + " 字节）");
        } else {
            Log.w(TAG, "本地一次性文件清理失败: " + file.getAbsolutePath());
        }
    }

    /** 实际上传逻辑：读文件 → 切分 → 逐片发送 */
    private static void doUpload(File file, byte[] msgType, ProgressListener listener) {
        final String fileName = file.getName();
        final long fileSize = file.length();
        final int totalChunks = (int) ((fileSize + CHUNK_PAYLOAD_SIZE - 1) / CHUNK_PAYLOAD_SIZE);

        Log.i(TAG, "开始上传 " + fileName + "，大小 " + fileSize
                + " 字节，共 " + totalChunks + " 片");

        FileInputStream fis = null;
        try {
            fis = new FileInputStream(file);
            byte[] payload = new byte[CHUNK_PAYLOAD_SIZE];
            byte[] packet = new byte[CHUNK_HEADER_LEN + CHUNK_PAYLOAD_SIZE];

            int seq = 0;
            int read;
            long sentBytes = 0;

            while ((read = fis.read(payload)) > 0) {
                // 每次发送前检查连接：中途断线就没必要继续读了
                if (!AiUdpManager.getInstance().isConnected()) {
                    Log.w(TAG, "上传中断：UDP 已断开（已发送 " + seq + "/" + totalChunks + " 片）");
                    if (listener != null) {
                        listener.onUploadFailed(fileName, "上传中断，连接已断开", FailureKind.TRANSPORT);
                    }
                    return;
                }

                byte[] data = buildChunk(msgType, payload, read, seq, totalChunks);
                if (!AiUdpManager.getInstance().send(data)) {
                    Log.w(TAG, "第 " + seq + " 片发送失败");
                    if (listener != null) {
                        listener.onUploadFailed(fileName, "第 " + seq + " 片发送失败",
                                FailureKind.TRANSPORT);
                    }
                    return;
                }

                sentBytes += read;
                seq++;

                // 适度让出时间片，避免短时间灌入过多数据
                if (CHUNK_SEND_INTERVAL_MS > 0) {
                    try {
                        Thread.sleep(CHUNK_SEND_INTERVAL_MS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        if (listener != null) {
                            listener.onUploadFailed(fileName, "上传被中断", FailureKind.LOCAL);
                        }
                        return;
                    }
                }
            }

            Log.i(TAG, "上传完成 " + fileName + "：" + seq + " 片 / "
                    + sentBytes + " 字节");
            if (listener != null) {
                listener.onUploadFinished(fileName, seq, (int) sentBytes);
            }

        } catch (IOException e) {
            Log.e(TAG, "上传失败 " + fileName, e);
            if (listener != null) {
                listener.onUploadFailed(fileName, "读取文件失败: " + e.getMessage(),
                        FailureKind.LOCAL);
            }
        } finally {
            if (fis != null) {
                try {
                    fis.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    /**
     * 构造单个分片报文。
     *
     * @param msgType     消息类型（1 字节，取 {@code msgType[0]}）
     * @param payload     载荷缓冲区（可能大于实际数据长度）
     * @param payloadLen  本次实际要发送的载荷字节数
     * @param seq         分片序号，从 0 开始
     * @param total       总片数
     */
    private static byte[] buildChunk(byte[] msgType, byte[] payload,
                                     int payloadLen, int seq, int total) {
        byte[] p = new byte[CHUNK_HEADER_LEN + payloadLen];

        // 0..3 魔数
        p[0] = AiUdpManager.MAGIC[0];
        p[1] = AiUdpManager.MAGIC[1];
        p[2] = AiUdpManager.MAGIC[2];
        p[3] = AiUdpManager.MAGIC[3];
        // 4 版本
        p[4] = AiUdpManager.PROTOCOL_VERSION;
        // 5 消息类型
        p[5] = msgType[0];
        // 6..7 载荷长度（大端）
        p[6] = (byte) ((payloadLen >> 8) & 0xFF);
        p[7] = (byte) (payloadLen & 0xFF);
        // 8..9 分片序号（大端）
        p[8] = (byte) ((seq >> 8) & 0xFF);
        p[9] = (byte) (seq & 0xFF);
        // 10..11 总片数（大端）
        p[10] = (byte) ((total >> 8) & 0xFF);
        p[11] = (byte) (total & 0xFF);
        // 12..15 保留
        p[12] = 0;
        p[13] = 0;
        p[14] = 0;
        p[15] = 0;

        // 载荷
        System.arraycopy(payload, 0, p, CHUNK_HEADER_LEN, payloadLen);
        return p;
    }
}
