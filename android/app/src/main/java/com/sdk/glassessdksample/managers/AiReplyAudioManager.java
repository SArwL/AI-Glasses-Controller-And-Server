package com.sdk.glassessdksample.managers;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.sdk.glassessdksample.utils.PcmUtils;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AI 语音回复的接收与播放（Jetson → 手机，消息类型 {@code 0x32} / {@code 0x33}）。
 *
 * <h3>职责</h3>
 * <ol>
 *   <li><b>重组</b>：按 {@code seq} 缓存 {@code 0x32} 分片，收齐 {@code [0, total)} 后拼成一段完整 PCM；</li>
 *   <li><b>排队</b>：完整的一段追加到播放队列末尾，按到达顺序（= 对话自然顺序）连续播放；</li>
 *   <li><b>播放</b>：单线程从队列取段，写入 {@link AudioTrack} 连续播出；</li>
 *   <li><b>打断</b>：{@link #interruptByUser} 立即停播、清空队列与未完成缓冲，并向后端发 {@code 0x40}；
 *       收到 {@code 0x33} 则按后端要求停播（不回发 {@code 0x40}，避免互相打断形成风暴）。</li>
 * </ol>
 *
 * <h3>为什么不用 MediaPlayer</h3>
 * 回复音频是"流式到达的裸 PCM"，没有文件头也没有完整文件；MediaPlayer 只能播文件，
 * 因此这里必须用 {@link AudioTrack} 直接写 PCM。这与 {@link AudioPlaybackManager}
 * （播本地已落盘的录音/照片音频）职责不同，两者互不影响。
 *
 * <h3>采样率</h3>
 * 后端统一给 16kHz。设备不一定支持 16kHz 输出，因此先按 16kHz 建轨，
 * 建不了则降级到 48kHz 并用 {@link PcmUtils#resamplePcm16} 重采样后播放。
 *
 * <h3>线程约定</h3>
 * 收包回调（{@link #onDataReceived}）在主线程；重组缓冲只由主线程读写，无需加锁；
 * 播放队列由主线程入队、播放线程出队，使用线程安全队列。
 *
 * @see AiUdpManager#TYPE_REPLY_AUDIO
 * @see AiUdpManager#TYPE_REPLY_AUDIO_STOP
 * @see AiUdpManager#TYPE_ABORT_REQ
 */
public class AiReplyAudioManager implements AiUdpManager.Listener {

    private static final String TAG = "AiReplyAudioManager";

    /** 后端回传音频格式：16kHz / 16bit / 单声道 / 小端 */
    public static final int REPLY_SAMPLE_RATE = 16000;
    /** 采样点字节数（16bit 单声道） */
    private static final int BYTES_PER_SAMPLE = 2;
    /** 16kHz 不被设备支持时的兜底输出采样率，配合重采样使用 */
    private static final int FALLBACK_OUTPUT_RATE = 48000;

    /** 一段音频超过该时长仍未收齐 → 视为丢包，丢弃该段（否则会永久等待、卡住后续段） */
    private static final long SEGMENT_TIMEOUT_MS = 2000;
    /** 播放线程轮询间隔 */
    private static final long PLAYER_POLL_MS = 50;
    /** 单次写入 AudioTrack 的字节数：越小则打断响应越快，代价是系统调用更频繁 */
    private static final int WRITE_CHUNK_BYTES = 1024;
    /** 队列空且静默该时长后暂停 AudioTrack：此时缓冲区必然已放完，不会截断尾音 */
    private static final long IDLE_PAUSE_MS = 1000;

    private static volatile AiReplyAudioManager instance;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // ==================== 接收侧状态（仅主线程访问） ====================

    /** 当前段的分片：seq → 载荷。收齐后清空 */
    private final Map<Integer, byte[]> segmentChunks = new HashMap<>();
    /** 当前段声明的总片数；-1 表示当前没有正在接收的段 */
    private int segmentTotal = -1;
    /** 当前段已收到的载荷字节数（仅用于日志） */
    private int segmentBytes = 0;
    /**
     * 打断后置 true：丢弃后续分片，直到下一段的 seq==0 到来。
     * 目的是不把被打断那一轮的残余分片当成新内容排队。
     */
    private boolean droppingUntilNewSegment = false;

    // ==================== 播放侧状态 ====================

    /** 待播放的完整音频段，FIFO；主线程入队、播放线程出队 */
    private final ConcurrentLinkedQueue<byte[]> playQueue = new ConcurrentLinkedQueue<>();
    /** 是否处于"已 start 播放"状态（AudioTrack 需在 pause 后重新 play） */
    private final AtomicBoolean playing = new AtomicBoolean(false);
    /** 打断请求：由打断方置位，播放线程在写分块之间检查并执行 pause+flush */
    private final AtomicBoolean interruptRequested = new AtomicBoolean(false);

    private AudioTrack audioTrack;
    /** 当前 AudioTrack 的实际输出采样率（可能因降级而与 REPLY_SAMPLE_RATE 不同） */
    private int outputSampleRate = REPLY_SAMPLE_RATE;
    /** 最近一次向 AudioTrack 写入的时间，用于空闲后暂停 */
    private volatile long lastWriteAt = 0;
    private volatile boolean running = true;
    private Thread playerThread;

    /**
     * 一段音频的接收超时任务：超时仍未收齐则丢弃该段。
     * 每次收到分片都会取消并重新投递，因此计的是"距最后一个分片"的时间。
     */
    private final Runnable segmentTimeoutTask = () -> {
        if (segmentTotal > 0 && segmentChunks.size() < segmentTotal) {
            Log.w(TAG, "一段回复音频 " + SEGMENT_TIMEOUT_MS + "ms 未收齐（"
                    + segmentChunks.size() + "/" + segmentTotal + " 片），丢弃该段");
            resetSegment();
        }
    };

    private AiReplyAudioManager() {
        // 自注册为 UDP 监听器：回传音频的到达与当前显示哪个页面无关
        AiUdpManager.getInstance().addListener(this);
        playerThread = new Thread(this::playerLoop, "ai-reply-play");
        playerThread.start();
        Log.i(TAG, "回传音频管理器就绪（等待 0x32）");
    }

    /** 获取单例；首次调用会注册 UDP 监听并启动播放线程 */
    public static AiReplyAudioManager getInstance() {
        if (instance == null) {
            synchronized (AiReplyAudioManager.class) {
                if (instance == null) {
                    instance = new AiReplyAudioManager();
                }
            }
        }
        return instance;
    }

    /**
     * 释放播放资源并注销监听器（宿主销毁时调用）。
     * 释放后再次调用 {@link #getInstance()} 会重新创建，因此可安全地随页面反复建立/释放；
     * 未创建过时调用是空操作。
     */
    public static void release() {
        synchronized (AiReplyAudioManager.class) {
            if (instance != null) {
                instance.shutdown();
                instance = null;
            }
        }
    }

    private void shutdown() {
        running = false;
        if (playerThread != null) {
            playerThread.interrupt();
            playerThread = null;
        }
        AiUdpManager.getInstance().removeListener(this);
        mainHandler.removeCallbacks(segmentTimeoutTask);
        playQueue.clear();
        resetSegment();
        releaseAudioTrack();
        Log.i(TAG, "回传音频管理器已释放");
    }

    /** 当前是否正在播报（播报中或队列里还有待播段） */
    public boolean isSpeaking() {
        return playing.get() || !playQueue.isEmpty();
    }

    // ==================== 打断 ====================

    /**
     * 用户主动打断：立即停止播报、清空播放队列与未完成缓冲，并向后端发送 {@code 0x40}。
     *
     * <p>典型触发：播报途中用户按下眼镜的 AI 对话按键（眼镜麦克风启动 = 用户又开始说话）。</p>
     *
     * <p><b>只在确实有内容在播或在途时才发 0x40</b>：否则会把"后端还没播的一轮回复"
     * 误判为需要中止，导致用户什么都没听到。</p>
     *
     * <p>可在任意线程调用：内部会切到主线程执行，因为重组缓冲只由主线程读写。</p>
     *
     * @param trigger 触发来源，仅用于日志
     */
    public void interruptByUser(String trigger) {
        postInterrupt(trigger, true);
    }

    /**
     * 打断（若当前没有播报内容则为空操作）。可安全地在任何"用户又说话了"的信号上调用。
     *
     * <p>与 {@link #interruptByUser} 的唯一区别：<b>本方法不会创建单例</b>。
     * 用于 SDK 设备事件回调（眼镜 0x03 麦克风启动 / 0x0c 暂停事件）这类不在主界面上下文里的
     * 调用点——未初始化时直接忽略，避免凭空建出播放线程与 AudioTrack。</p>
     *
     * <p>由于 {@link #interruptInternal} 自带"无内容在播则不发 0x40"的门槛，
     * 在"用户正常说完话"这类场景下调用它是零副作用的。</p>
     *
     * @param trigger 触发来源，仅用于日志
     */
    public static void interruptIfSpeaking(String trigger) {
        AiReplyAudioManager m = instance;
        if (m != null) {
            m.interruptByUser(trigger);
        }
    }

    /** 收到后端 {@code 0x33}：按要求停播，且不回发 0x40（否则会互相打断形成死循环） */
    private void stopByBackend() {
        postInterrupt("后端 0x33 终止", false);
    }

    /** 统一把打断动作派发到主线程，避免调用方线程不确定导致的并发问题 */
    private void postInterrupt(String trigger, boolean sendAbort) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            interruptInternal(trigger, sendAbort);
        } else {
            mainHandler.post(() -> interruptInternal(trigger, sendAbort));
        }
    }

    /**
     * 停止播报并清空所有缓冲。
     *
     * @param sendAbort 是否向后端发送 0x40 打断请求
     */
    private void interruptInternal(String trigger, boolean sendAbort) {
        boolean audioPlaying = playing.get();
        int queued = playQueue.size();
        boolean hasIncoming = segmentTotal > 0 || !segmentChunks.isEmpty();
        boolean anything = audioPlaying || queued > 0 || hasIncoming;

        // 清缓冲必须在置位 interruptRequested 之前完成，保证播放线程不会取到残余段
        playQueue.clear();
        resetSegment();
        droppingUntilNewSegment = true;
        mainHandler.removeCallbacks(segmentTimeoutTask);
        interruptRequested.set(true);

        if (!anything) {
            Log.i(TAG, "打断（" + trigger + "）：当前无播报内容，不发 0x40");
            return;
        }

        Log.i(TAG, "打断（" + trigger + "）：停止播报，清空队列 " + queued
                + " 段" + (hasIncoming ? " + 未完成缓冲" : "")
                + (sendAbort ? "，发送 0x40" : "，不回发 0x40"));

        if (sendAbort) {
            // 注意：主线程调用时 send() 只表示"已排队到后台线程发送"，真正结果由
            // AiUdpManager 的 sendInternal 记录（未连接时会打"发送被丢弃"）
            boolean submitted = AiUdpManager.getInstance()
                    .sendControlMessage(AiUdpManager.TYPE_ABORT_REQ);
            Log.i(TAG, submitted ? "0x40 打断请求已提交发送" : "0x40 提交失败（UDP 未连接）");
        }
    }

    // ==================== 收包（主线程） ====================

    @Override
    public void onDataReceived(byte[] data, String fromHost, int fromPort) {
        if (data == null || data.length < AiUdpManager.BIZ_HEADER_LEN) return;
        // 收包循环对所有报文一视同仁，因此这里必须自己校验魔数与版本
        if (!hasValidHeader(data)) return;

        int type = data[5] & 0xFF;
        if (type == AiUdpManager.TYPE_REPLY_AUDIO) {
            handleAudioFragment(data);
        } else if (type == AiUdpManager.TYPE_REPLY_AUDIO_STOP) {
            stopByBackend();
        }
        // 其他类型（0x30 文字回复、0x31 拍照指令等）本期不在本类处理
    }

    /** 校验业务报文头：魔数 + 版本 */
    private boolean hasValidHeader(byte[] p) {
        byte[] magic = AiUdpManager.MAGIC;
        for (int i = 0; i < magic.length; i++) {
            if (p[i] != magic[i]) return false;
        }
        return p[4] == AiUdpManager.PROTOCOL_VERSION;
    }

    /**
     * 处理一个 {@code 0x32} 分片。
     *
     * <p>每片自带 {@code payloadLen / seq / total}，字段布局与上行分片包一致。
     * 由于协议里<b>没有段 ID</b>，段边界只能靠 {@code seq==0} 识别。</p>
     */
    private void handleAudioFragment(byte[] p) {
        final int payloadLen = ((p[6] & 0xFF) << 8) | (p[7] & 0xFF);
        final int seq = ((p[8] & 0xFF) << 8) | (p[9] & 0xFF);
        final int total = ((p[10] & 0xFF) << 8) | (p[11] & 0xFF);

        // 合法性：载荷长度必须与数据报实际长度吻合，seq 必须在 [0, total) 内
        if (payloadLen <= 0 || total <= 0 || seq >= total
                || p.length != AiUdpManager.BIZ_HEADER_LEN + payloadLen) {
            Log.w(TAG, "0x32 分片不合法，丢弃（datagramLen=" + p.length
                    + ", payloadLen=" + payloadLen + ", seq=" + seq + ", total=" + total + "）");
            return;
        }

        if (seq == 0) {
            // 新一段开始：上一段若没收齐则明确丢弃（协议里没段 ID，seq=0 是唯一的段边界信号）
            if (segmentTotal > 0 && segmentChunks.size() < segmentTotal) {
                Log.w(TAG, "新段到达，丢弃上一段未收齐的 " + segmentChunks.size()
                        + "/" + segmentTotal + " 片");
            }
            resetSegment();
            droppingUntilNewSegment = false;
            segmentTotal = total;
        }

        // 打断后到下一段 seq=0 之间的残余分片一律丢弃
        if (droppingUntilNewSegment) return;

        // 首片就丢了的情况：没有 seq=0 就无法确定段边界，只能忽略这些中间片
        if (segmentTotal < 0) {
            Log.w(TAG, "未收到段首片（seq=0）却先收到 seq=" + seq + "，忽略该片");
            return;
        }

        // 段中途出现不同的 total，说明两段在交织：丢弃该片，避免把两段拼成一段
        if (total != segmentTotal) {
            Log.w(TAG, "分片的 total 与当前段不一致（片内 " + total
                    + " vs 段内 " + segmentTotal + "），丢弃 seq=" + seq);
            return;
        }

        if (segmentChunks.containsKey(seq)) return;   // 重复片（UDP 重传/回环），忽略

        byte[] payload = Arrays.copyOfRange(p, AiUdpManager.BIZ_HEADER_LEN, p.length);
        segmentChunks.put(seq, payload);
        segmentBytes += payloadLen;

        if (segmentChunks.size() >= segmentTotal) {
            onSegmentComplete();
        } else {
            // 每次收到分片都重排超时：计的是"距最后一个分片"的静默时间
            mainHandler.removeCallbacks(segmentTimeoutTask);
            mainHandler.postDelayed(segmentTimeoutTask, SEGMENT_TIMEOUT_MS);
        }
    }

    /** 一段收齐：按 seq 顺序拼接后入播放队列 */
    private void onSegmentComplete() {
        final int total = segmentTotal;
        final int bytes = segmentBytes;

        byte[] merged = new byte[bytes];
        int offset = 0;
        for (int i = 0; i < total; i++) {
            byte[] chunk = segmentChunks.get(i);
            if (chunk == null) {
                // 理论上进不来（收齐才调用），留个保险避免拼出错位数据
                Log.w(TAG, "段内缺少 seq=" + i + "，放弃该段");
                resetSegment();
                return;
            }
            System.arraycopy(chunk, 0, merged, offset, chunk.length);
            offset += chunk.length;
        }

        resetSegment();
        mainHandler.removeCallbacks(segmentTimeoutTask);

        playQueue.add(merged);
        Log.i(TAG, "一段回复音频入队：" + total + " 片 / " + bytes + " 字节（约 "
                + (bytes / (REPLY_SAMPLE_RATE * BYTES_PER_SAMPLE / 1000)) + " ms），队列 "
                + playQueue.size() + " 段");
    }

    /** 清空当前段的接收缓冲（不影响播放队列） */
    private void resetSegment() {
        segmentChunks.clear();
        segmentTotal = -1;
        segmentBytes = 0;
    }

    // ==================== 播放（播放线程） ====================

    private void playerLoop() {
        long idleSince = 0;
        while (running) {
            // 打断优先：先执行 pause+flush，再回到取队列状态
            if (interruptRequested.getAndSet(false)) {
                doStopPlayback();
                idleSince = 0;
                continue;
            }

            byte[] segment = playQueue.poll();
            if (segment == null) {
                long now = System.currentTimeMillis();
                if (idleSince == 0) {
                    idleSince = now;
                } else if (playing.get() && now - idleSince >= IDLE_PAUSE_MS
                        && now - lastWriteAt >= IDLE_PAUSE_MS) {
                    // 队列已空且缓冲区早已放完，暂停以避免无声占用音频通道
                    doStopPlayback();
                    idleSince = 0;
                }
                sleepQuietly(PLAYER_POLL_MS);
                continue;
            }

            idleSince = 0;
            playSegment(segment);
        }
        Log.i(TAG, "播放线程已退出");
    }

    /** 播放一段完整 PCM；被打断时立刻放弃剩余部分 */
    private void playSegment(byte[] pcm) {
        if (!ensureAudioTrack()) return;

        // 打断后 AudioTrack 处于 pause 状态，播新段前必须重新 play
        if (!playing.get()) {
            audioTrack.play();
            playing.set(true);
        }

        byte[] data = pcm;
        if (outputSampleRate != REPLY_SAMPLE_RATE) {
            // 降级到 48kHz 时先重采样，否则会变速
            data = PcmUtils.resamplePcm16(pcm, REPLY_SAMPLE_RATE, outputSampleRate);
        }

        int offset = 0;
        while (offset < data.length) {
            if (!running || interruptRequested.get()) {
                // 立即返回，由 playerLoop 统一处理 pause+flush
                return;
            }
            int len = Math.min(WRITE_CHUNK_BYTES, data.length - offset);
            int written;
            try {
                written = audioTrack.write(data, offset, len);
            } catch (Exception e) {
                Log.e(TAG, "写入 AudioTrack 失败", e);
                releaseAudioTrack();
                return;
            }
            if (written <= 0) {
                Log.w(TAG, "AudioTrack 写入返回 " + written + "，放弃本段"
                        + "（错误码：" + audioTrack.getPlayState() + "）");
                return;
            }
            offset += written;
            lastWriteAt = System.currentTimeMillis();
        }
    }

    /** 暂停并丢弃缓冲区中未播放的数据（在本线程调用，避免跨线程操作 AudioTrack） */
    private void doStopPlayback() {
        if (audioTrack == null) {
            playing.set(false);
            return;
        }
        try {
            if (playing.get()) {
                audioTrack.pause();
            }
            audioTrack.flush();
        } catch (Exception e) {
            Log.w(TAG, "停止播放异常", e);
        }
        playing.set(false);
    }

    /**
     * 确保 AudioTrack 可用：优先按 16kHz 建轨，设备不支持时降级到 48kHz（配合重采样）。
     *
     * @return true 表示可以写入
     */
    private boolean ensureAudioTrack() {
        if (audioTrack != null) return true;

        // 优先尝试 16kHz：省掉重采样，音质最好
        int minBuf = AudioTrack.getMinBufferSize(REPLY_SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minBuf > 0) {
            AudioTrack track = buildTrack(REPLY_SAMPLE_RATE, minBuf);
            if (track != null) {
                audioTrack = track;
                outputSampleRate = REPLY_SAMPLE_RATE;
                Log.i(TAG, "AudioTrack 就绪：" + REPLY_SAMPLE_RATE + "Hz");
                return true;
            }
        } else {
            Log.w(TAG, "设备不支持 " + REPLY_SAMPLE_RATE + "Hz 输出，降级到 "
                    + FALLBACK_OUTPUT_RATE + "Hz（将重采样）");
        }

        int fallbackMin = AudioTrack.getMinBufferSize(FALLBACK_OUTPUT_RATE,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (fallbackMin > 0) {
            AudioTrack track = buildTrack(FALLBACK_OUTPUT_RATE, fallbackMin);
            if (track != null) {
                audioTrack = track;
                outputSampleRate = FALLBACK_OUTPUT_RATE;
                Log.i(TAG, "AudioTrack 就绪：" + FALLBACK_OUTPUT_RATE + "Hz（重采样播放）");
                return true;
            }
        }

        Log.e(TAG, "无法创建 AudioTrack，回复音频将无法播放");
        return false;
    }

    /** 按给定采样率建轨；失败返回 null */
    private AudioTrack buildTrack(int sampleRate, int minBufBytes) {
        try {
            // 缓冲区取 minBuf 的 2 倍并留 8KB 下限，降低播放线程被调度延迟导致爆音的概率
            int bufferSize = Math.max(minBufBytes * 2, 8192);
            return new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setBufferSizeInBytes(bufferSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
        } catch (Exception e) {
            Log.w(TAG, "创建 AudioTrack 失败（" + sampleRate + "Hz）", e);
            return null;
        }
    }

    private void releaseAudioTrack() {
        if (audioTrack == null) return;
        try {
            audioTrack.pause();
            audioTrack.flush();
            audioTrack.release();
        } catch (Exception e) {
            Log.w(TAG, "释放 AudioTrack 异常", e);
        }
        audioTrack = null;
        playing.set(false);
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ==================== AiUdpManager.Listener 其余回调 ====================

    @Override
    public void onConnected(String host, int port, boolean verified) {
        // 回传音频不关心连接建立本身
    }

    @Override
    public void onConnectFailed(String host, int port, String reason) {
        // 连接失败不会带来回传音频
    }

    @Override
    public void onDisconnected(String reason) {
        // 链路断了，队列里剩下的段已经播不完，直接停掉并清空
        postInterrupt("链路断开", false);
    }
}
