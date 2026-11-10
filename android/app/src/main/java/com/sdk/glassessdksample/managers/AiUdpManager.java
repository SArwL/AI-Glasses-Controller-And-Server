package com.sdk.glassessdksample.managers;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AI 后端的 UDP 通信管理（连接、握手校验、收发）。
 *
 * <h3>为什么需要握手</h3>
 * UDP 是无连接协议：{@code DatagramSocket.connect()} 对本机不存在、甚至可达性未知的地址
 * 也会立刻成功返回。因此**"IP 是否可用"无法通过建立连接来判断**，只能靠往目标发一个探测包、
 * 并在超时内收到对方回包来确认。所以本类的连接流程是：
 *
 * <pre>
 *   ① 校验 IP 字面量格式
 *   ② 创建 socket，发送 PROBE 探测包（最多重试 N 次，每次等待 timeout）
 *   ③ 收到 PROBE_ACK → 连接成功
 *      超时未收到     → 连接失败（原因：无响应）
 * </pre>
 *
 * <h3>握手报文格式（16 字节定长，便于对端解析）</h3>
 * <pre>
 *   偏移  长度  含义
 *   0     4    魔数 "GLSY" (0x47 0x4C 0x53 0x59)
 *   4     1    协议版本（当前 1）
 *   5     1    消息类型：1=PROBE  2=PROBE_ACK
 *   6     2    保留（0）
 *   8     8    发送端时间戳（epoch millis，大端）
 * </pre>
 * 对端收到 PROBE 后应原样回一个 type=PROBE_ACK 的 16 字节报文即可完成校验。
 * 若对端暂未实现回包，可用 {@link #connect(String, int, boolean)} 的
 * {@code skipHandshake=true} 强制进入"未校验"状态，仅用于联调。
 *
 * <h3>线程约定</h3>
 * {@link #connect} 内部切到工作线程执行握手，结果通过 {@link Listener} 回调；
 * 接收循环常驻独立线程；{@link #send} 可在任意线程调用。
 *
 * @see BackendConfigStore 历史地址的持久化
 */
public class AiUdpManager {

    private static final String TAG = "AiUdpManager";

    // ==================== 协议常量 ====================

    /** 魔数 "GLSY"（所有报文的前 4 字节） */
    public static final byte[] MAGIC = {0x47, 0x4C, 0x53, 0x59};
    /** 协议版本 */
    public static final byte PROTOCOL_VERSION = 1;

    /** 握手报文固定长度 */
    private static final int HANDSHAKE_LEN = 16;
    /**
     * 业务报文头长度（与握手包同为 16 字节，但偏移 6~15 的语义不同：
     * 业务包为 载荷长度 / seq / total / 保留，详见 {@link AiUploader} 的类注释）。
     */
    public static final int BIZ_HEADER_LEN = 16;

    // ---- 握手类型 ----
    /** 握手探测（手机 → 后端） */
    public static final byte TYPE_PROBE = 0x01;
    /** 握手应答（后端 → 手机） */
    public static final byte TYPE_PROBE_ACK = 0x02;

    // ---- 业务消息类型（与 Jetson 端设计文档 §4.1 对齐）----
    /** 语音 PCM 数据（手机 → 后端），载荷为裸 PCM，需按 seq 重组 */
    public static final byte[] TYPE_VOICE_PCM = {0x10};
    /** 语音元信息（手机 → 后端），载荷为 JSON */
    public static final byte[] TYPE_VOICE_META = {0x11};
    /** 照片 JPEG（手机 → 后端），载荷为 JPEG 字节 */
    public static final byte[] TYPE_PHOTO_JPEG = {0x20};
    /** 文字回复（后端 → 手机），载荷为 UTF-8 文本 */
    public static final byte TYPE_REPLY_TEXT = 0x30;
    /** 拍照指令（后端 → 手机），载荷为 JSON */
    public static final byte TYPE_CMD_TAKE_PHOTO = 0x31;
    /**
     * 语音回复音频分片（后端 → 手机）。载荷为 16kHz/16bit/单声道/小端裸 PCM，
     * 分片格式与上行 {@link #TYPE_VOICE_PCM} 完全一致；一段音频 = 一个独立分片会话。
     * 由 {@link AiReplyAudioManager} 重组并播放。
     */
    public static final byte TYPE_REPLY_AUDIO = 0x32;
    /** 语音回复终止（后端 → 手机），无载荷：立即停止播放并清空回传队列 */
    public static final byte TYPE_REPLY_AUDIO_STOP = 0x33;
    /** 打断请求（手机 → 后端），无载荷：请求后端停止本轮回复的合成与回传 */
    public static final byte TYPE_ABORT_REQ = 0x40;
    /** 心跳（双向），无载荷 */
    public static final byte TYPE_HEARTBEAT = 0x7F;
    /** 单次握手等待超时 */
    private static final int HANDSHAKE_TIMEOUT_MS = 800;
    /** 握手重试次数（含首次），总耗时约 800ms × 3 ≈ 2.4 秒 */
    private static final int HANDSHAKE_RETRY = 3;
    /** 接收缓冲区大小：单条 UDP 报文上限（PCM 分片与图片 base64 都走这里，留足余量） */
    private static final int RECV_BUFFER_SIZE = 64 * 1024;

    /** 单例实例 */
    private static volatile AiUdpManager instance;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // ==================== 运行时状态 ====================
    private DatagramSocket socket;
    private InetAddress targetAddress;
    private SocketAddress targetSocketAddress;
    private int targetPort;
    private String targetHost;

    /** 是否已连接（握手通过或强制连接后为 true） */
    private volatile boolean connected = false;
    /** 连接是否经过握手校验（false 表示强制连接，对端可能并未真正就绪） */
    private volatile boolean verified = false;
    /** 握手/接收线程是否应继续运行 */
    private final AtomicBoolean running = new AtomicBoolean(false);
    /** 串行化 connect/disconnect，避免并发操作同一个 socket */
    private final Object lock = new Object();

    /**
     * 监听器列表。支持多监听器并存的原因：
     * 连接页需要一个监听器把连接过程写进日志，而回传音频（{@link AiReplyAudioManager}）
     * 需要长期驻留地收 0x32/0x33 业务报文——两者生命周期不同，不能互相顶掉。
     */
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    // ==================== 回调接口 ====================

    /**
     * 连接与数据回调。所有回调都在主线程触发，可直接更新 UI。
     *
     * <p>可以注册多个监听器，每个监听器都会收到同一份回调；
     * 注册方必须在销毁时 {@link #removeListener}，否则会因单例持有而泄漏。</p>
     */
    public interface Listener {
        /** 连接成功。verified=false 表示未经握手校验（强制连接） */
        void onConnected(String host, int port, boolean verified);

        /** 连接失败，reason 为可直接展示的中文原因 */
        void onConnectFailed(String host, int port, String reason);

        /** 连接已断开（主动断开或链路异常） */
        void onDisconnected(String reason);

        /** 收到后端发来的一条完整 UDP 报文 */
        void onDataReceived(byte[] data, String fromHost, int fromPort);
    }

    private AiUdpManager() {
    }

    /** 获取单例 */
    public static AiUdpManager getInstance() {
        if (instance == null) {
            synchronized (AiUdpManager.class) {
                if (instance == null) {
                    instance = new AiUdpManager();
                }
            }
        }
        return instance;
    }

    /**
     * 注册监听器。重复注册同一实例会被忽略（幂等）。
     */
    public void addListener(Listener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    /**
     * 注销监听器。注册方销毁时必须调用，避免单例长期持有 Activity 引用。
     */
    public void removeListener(Listener listener) {
        if (listener != null) {
            listeners.remove(listener);
        }
    }

    /** 当前是否已连接 */
    public boolean isConnected() {
        return connected;
    }

    /** 当前连接是否经过握手校验 */
    public boolean isVerified() {
        return verified;
    }

    /** 当前连接的目标地址（未连接时返回 null） */
    public String getTargetHost() {
        return connected ? targetHost : null;
    }

    /** 当前连接的目标端口（未连接时返回 -1） */
    public int getTargetPort() {
        return connected ? targetPort : -1;
    }

    // ==================== 连接 ====================

    /**
     * 连接后端：校验地址格式 → 握手探测 → 建立接收循环。
     * 耗时操作，内部自动切到工作线程，结果通过 {@link Listener} 回调。
     *
     * @param host IP 字面量地址
     * @param port UDP 端口
     */
    public void connect(String host, int port) {
        connect(host, port, false);
    }

    /**
     * 连接后端。
     *
     * @param skipHandshake true 则跳过握手校验直接进入连接状态（对端未实现回包时的联调开关），
     *                      此时 {@link Listener#onConnected} 的 verified 参数为 false
     */
    public void connect(final String host, final int port, final boolean skipHandshake) {
        synchronized (lock) {
            // 先清理既有连接，保证幂等
            closeSocketLocked("重新连接");
        }

        new Thread(() -> {
            String formatError = validateAddress(host, port);
            if (formatError != null) {
                notifyConnectFailed(host, port, formatError);
                return;
            }

            DatagramSocket newSocket = null;
            try {
                InetAddress addr = InetAddress.getByName(host);

                // 绑定到任意本地端口（0 = 由系统分配），并开启广播能力以便将来需要
                newSocket = new DatagramSocket();
                newSocket.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
                newSocket.setBroadcast(true);

                SocketAddress target = new InetSocketAddress(addr, port);

                boolean handshakeOk = skipHandshake || doHandshake(newSocket, target);

                if (!handshakeOk) {
                    newSocket.close();
                    notifyConnectFailed(host, port,
                            "无响应（已重试 " + HANDSHAKE_RETRY + " 次，每次超时 " + HANDSHAKE_TIMEOUT_MS + "ms）");
                    return;
                }

                synchronized (lock) {
                    socket = newSocket;
                    targetAddress = addr;
                    targetSocketAddress = target;
                    targetHost = host;
                    targetPort = port;
                    connected = true;
                    verified = !skipHandshake;
                }

                Log.i(TAG, "UDP 已连接 " + host + ":" + port
                        + "（校验=" + (!skipHandshake) + "）");
                notifyConnected(host, port, !skipHandshake);

                // 握手成功后恢复阻塞超时，进入接收循环
                startReceiveLoop(newSocket);

            } catch (Exception e) {
                Log.e(TAG, "UDP 连接异常: " + host + ":" + port, e);
                if (newSocket != null && !newSocket.isClosed()) {
                    newSocket.close();
                }
                notifyConnectFailed(host, port, describeException(e));
            }
        }, "ai-udp-connect").start();
    }

    /**
     * 执行握手探测：发送 PROBE，等待 PROBE_ACK。
     *
     * @return true 表示在重试次数内收到合法回包
     */
    private boolean doHandshake(DatagramSocket sock, SocketAddress target) {
        byte[] probe = buildHandshakePacket(TYPE_PROBE);
        byte[] buf = new byte[HANDSHAKE_LEN];

        for (int attempt = 1; attempt <= HANDSHAKE_RETRY; attempt++) {
            try {
                sock.send(new DatagramPacket(probe, probe.length, target));
                Log.d(TAG, "握手探测第 " + attempt + "/" + HANDSHAKE_RETRY + " 次");

                DatagramPacket resp = new DatagramPacket(buf, buf.length);
                sock.receive(resp);

                if (isValidHandshakeAck(resp.getData(), resp.getLength())) {
                    Log.i(TAG, "握手成功，收到 PROBE_ACK（第 " + attempt + " 次探测）");
                    return true;
                }
                Log.w(TAG, "收到非预期报文，忽略（长度=" + resp.getLength() + "）");
            } catch (java.net.SocketTimeoutException e) {
                Log.d(TAG, "握手第 " + attempt + " 次超时");
            } catch (IOException e) {
                Log.e(TAG, "握手 IO 异常", e);
                return false;
            }
        }
        return false;
    }

    /** 构造 16 字节握手报文 */
    private byte[] buildHandshakePacket(byte type) {
        byte[] p = new byte[HANDSHAKE_LEN];
        System.arraycopy(MAGIC, 0, p, 0, 4);
        p[4] = PROTOCOL_VERSION;
        p[5] = type;
        // p[6..7] 保留为 0
        long ts = System.currentTimeMillis();
        for (int i = 0; i < 8; i++) {
            p[8 + i] = (byte) ((ts >>> (56 - 8 * i)) & 0xFF);
        }
        return p;
    }

    /** 校验对端回包是否为合法 PROBE_ACK */
    private boolean isValidHandshakeAck(byte[] data, int len) {
        if (data == null || len < 6) return false;
        if (data[0] != MAGIC[0] || data[1] != MAGIC[1]
                || data[2] != MAGIC[2] || data[3] != MAGIC[3]) {
            return false;
        }
        return data[5] == TYPE_PROBE_ACK;
    }

    // ==================== 接收 ====================

    /** 启动常驻接收循环：任何到达的报文都回调给 Listener */
    private void startReceiveLoop(final DatagramSocket sock) {
        running.set(true);

        new Thread(() -> {
            byte[] buf = new byte[RECV_BUFFER_SIZE];
            while (running.get() && !sock.isClosed()) {
                try {
                    DatagramPacket packet = new DatagramPacket(buf, buf.length);
                    // 阻塞读取；断开时 close() 会让它抛 SocketException 从而退出循环
                    sock.receive(packet);

                    int len = packet.getLength();
                    final byte[] payload = Arrays.copyOf(packet.getData(), len);
                    final String fromHost = packet.getAddress() != null
                            ? packet.getAddress().getHostAddress() : "unknown";
                    final int fromPort = packet.getPort();

                    Log.d(TAG, "收到 UDP 报文 " + len + " 字节，来自 "
                            + fromHost + ":" + fromPort);

                    mainHandler.post(() -> {
                        for (Listener l : listeners) {
                            l.onDataReceived(payload, fromHost, fromPort);
                        }
                    });

                } catch (java.net.SocketTimeoutException e) {
                    // 理论上接收循环里不该有超时；忽略并继续
                } catch (IOException e) {
                    if (running.get() && !sock.isClosed()) {
                        Log.e(TAG, "接收循环异常，链路视为断开", e);
                        handleUnexpectedDisconnect(describeException(e));
                    }
                    break;
                }
            }
            Log.i(TAG, "接收循环已退出");
        }, "ai-udp-recv").start();
    }

    // ==================== 发送 ====================

    /**
     * 发送一条 UDP 报文到已连接的后端。
     *
     * <p><b>可在任意线程调用。</b>UDP send 是阻塞式网络 I/O，Android 的 StrictMode 会把
     * 主线程上的网络调用直接判为 {@link android.os.NetworkOnMainThreadException} 并让 App 崩溃。
     * 而设备事件回调（眼镜按键等）恰恰是在主线程分发的，所以这里必须在主线程调用时
     * 把真正的发送改派到后台线程——否则调用方会因为一个纯粹的线程问题而闪退。</p>
     *
     * @return true 表示已交由系统发送（UDP 无送达保证）；主线程调用时表示已排队待发
     */
    public boolean send(byte[] data) {
        if (data == null || data.length == 0) return false;

        if (Looper.myLooper() == Looper.getMainLooper()) {
            // 主线程不能做网络 I/O：改派到后台线程，接口语义保持不变
            Log.d(TAG, "send() 由主线程调用，改派到后台线程（" + data.length + " 字节）");
            new Thread(() -> sendInternal(data), "ai-udp-send").start();
            return true;
        }
        return sendInternal(data);
    }

    /**
     * 真正的发送动作，<b>只能在非主线程执行</b>（{@link #send} 负责保证这一点）。
     */
    private boolean sendInternal(byte[] data) {
        synchronized (lock) {
            if (!connected || socket == null || socket.isClosed()) {
                Log.w(TAG, "未连接，发送被丢弃（" + data.length + " 字节）");
                return false;
            }
            try {
                socket.send(new DatagramPacket(data, data.length, targetSocketAddress));
                Log.d(TAG, "已发送 " + data.length + " 字节");
                return true;
            } catch (IOException e) {
                Log.e(TAG, "发送失败", e);
                handleUnexpectedDisconnect(describeException(e));
                return false;
            }
        }
    }

    /** 发送 UTF-8 文本（控制指令等场景的便捷方法） */
    public boolean sendText(String text) {
        if (text == null) return false;
        return send(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * 发送一个"无载荷"的业务控制报文：16 字节头 + 载荷长度 0。
     *
     * <p>用于 {@link #TYPE_ABORT_REQ}（打断请求）、{@link #TYPE_REPLY_AUDIO_STOP}
     * 这类只表达意图、不带数据的消息。字段布局与上行分片包完全一致，
     * 只是 6..15 全部为 0（载荷长度 0 / seq 0 / total 0 / 保留 0）。</p>
     *
     * @return true 表示已交由系统发送（UDP 无送达保证）
     */
    public boolean sendControlMessage(byte msgType) {
        byte[] p = new byte[BIZ_HEADER_LEN];
        System.arraycopy(MAGIC, 0, p, 0, MAGIC.length);
        p[4] = PROTOCOL_VERSION;
        p[5] = msgType;
        // 6..15 保持 0：无载荷、seq/total 无意义
        return send(p);
    }

    // ==================== 断开 ====================

    /** 主动断开连接 */
    public void disconnect() {
        synchronized (lock) {
            closeSocketLocked("主动断开");
        }
        notifyDisconnected("主动断开");
    }

    /**
     * 关闭 socket 并复位状态（调用方需持有 {@link #lock}）。
     *
     * @param reason 仅用于日志
     */
    private void closeSocketLocked(String reason) {
        running.set(false);
        boolean wasConnected = connected;
        if (socket != null) {
            try {
                socket.close();   // 会唤醒阻塞中的 receive()，使其抛异常后退出循环
            } catch (Exception ignored) {
            }
            socket = null;
        }
        targetSocketAddress = null;
        targetAddress = null;
        targetHost = null;
        targetPort = -1;
        connected = false;
        verified = false;
        if (wasConnected) {
            Log.i(TAG, "连接已关闭：" + reason);
        }
    }

    /** 链路异常导致的断开：复位状态并通知 UI */
    private void handleUnexpectedDisconnect(String reason) {
        boolean wasConnected;
        synchronized (lock) {
            wasConnected = connected;
            closeSocketLocked(reason);
        }
        if (wasConnected) {
            notifyDisconnected(reason);
        }
    }

    // ==================== 地址校验 ====================

    /**
     * 校验地址与端口是否合法（仅做本地格式校验，不涉及网络探测）。
     *
     * @return null 表示合法；否则返回可直接展示的错误原因
     */
    public static String validateAddress(String host, int port) {
        if (host == null || host.trim().isEmpty()) {
            return "IP 地址不能为空";
        }
        String h = host.trim();
        // 逐段校验 IPv4 字面量：4 段、每段 0~255、无前导零
        String[] parts = h.split("\\.", -1);
        if (parts.length != 4) {
            return "IP 格式不正确（应形如 192.168.0.194）";
        }
        for (String p : parts) {
            if (p.isEmpty() || p.length() > 3) {
                return "IP 格式不正确（应形如 192.168.0.194）";
            }
            for (int i = 0; i < p.length(); i++) {
                if (p.charAt(i) < '0' || p.charAt(i) > '9') {
                    return "IP 格式不正确（应形如 192.168.0.194）";
                }
            }
            int v = Integer.parseInt(p);
            if (v < 0 || v > 255) {
                return "IP 每段应在 0~255 之间";
            }
        }
        if (port < 1 || port > 65535) {
            return "端口应在 1~65535 之间";
        }
        return null;
    }

    /** 把底层异常翻译成用户能看懂的中文原因 */
    private static String describeException(Exception e) {
        String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        if (e instanceof java.net.UnknownHostException) {
            return "地址无法解析：" + msg;
        }
        if (e instanceof java.net.SocketException) {
            return "网络不可用：" + msg;
        }
        return msg;
    }

    // ==================== 主线程回调分发 ====================

    private void notifyConnected(final String host, final int port, final boolean verifiedFlag) {
        mainHandler.post(() -> {
            for (Listener l : listeners) {
                l.onConnected(host, port, verifiedFlag);
            }
        });
    }

    private void notifyConnectFailed(final String host, final int port, final String reason) {
        mainHandler.post(() -> {
            for (Listener l : listeners) {
                l.onConnectFailed(host, port, reason);
            }
        });
    }

    private void notifyDisconnected(final String reason) {
        mainHandler.post(() -> {
            for (Listener l : listeners) {
                l.onDisconnected(reason);
            }
        });
    }
}
