#ifndef UDPRECEIVER_H
#define UDPRECEIVER_H

#include <QHostAddress>
#include <QJsonObject>
#include <QMap>
#include <QObject>
#include <QTimer>
#include <QUdpSocket>

// Jetson 端 UDP 接收服务（协议依据 jetson-udp-receiver-design.md；
// 分片布局以 App 侧 AiUploader.buildChunk() 为准，双方已对齐）
//
// 报文分两类，共用前 6 字节（魔数 GLSY / 版本 0x01 / 类型），但偏移 6~15 语义完全不同：
//
//   偏移   握手包（type=0x01/0x02）   业务分片包（type=0x10/0x20/…）
//   0..3   魔数 "GLSY"                魔数 "GLSY"
//   4      版本 0x01                  版本 0x01
//   5      类型 0x01/0x02             类型 0x10/0x11/0x20/…
//   6..7   保留 = 0                   载荷长度 N（大端 uint16）
//   8..9   时间戳高 2 字节            seq（大端 uint16，从 0 开始）
//   10..11 时间戳（中 2 字节）        total（大端 uint16，总片数）
//   12..15 时间戳低 4 字节            保留 = 0
//
//   ⚠️ 业务包没有时间戳！偏移 8~15 是 seq/total/保留，绝不能在业务包上按握手包
//   语义读时间戳（会读到 seq/total/0 拼出的垃圾值）。区分报文看偏移 5 的类型字节，
//   不要用长度区分。
//
// VOICE_PCM(0x10) 分片与重组（与 App 侧 AiUploader 行为对齐）：
//   - 载荷从偏移 16 开始，数据报总长 = 16 + N；每片 ≤16384 字节（末片可短）
//   - 重组键 = 对端地址:端口（+ 消息类型；本实现仅对 0x10 做重组）
//   - 收到 seq == 0 → 视为一条新语音开始：该键已有未完成缓冲则丢弃旧缓冲
//     （协议没有消息 ID，只能丢旧留新，否则会拼出两条录音杂交的脏数据）
//   - 收齐 [0, total) 全部 seq → 按 seq 升序拼接为 voice_<接收时刻ms>.pcm 落盘
//     → 整体送入 ASR 一次（不再逐片送入）
//   - 协议无 ACK/重传：缺片永远等不齐；30 秒未收到新分片 → 丢弃该段并上报
//     voiceIncomplete（丢包只能整段作废并记日志）
// 其余约定（握手/回包取来源地址/非法包直接丢弃）见 jetson-udp-receiver-design.md §3。
class UdpReceiver : public QObject
{
    Q_OBJECT

public:
    explicit UdpReceiver(QObject *parent = nullptr);

    bool start(quint16 port = 1050);
    quint16 port() const { return m_port; }

    // —— 音频回传（0x32，协议与上行语音一致：16k PCM16LE，载荷 16384/片）——
    // 把一段 16k 裸 PCM 文件分片发给最近会话对端；
    // 返回：>0 已发出分片数；-1 无有效对端或文件不可读
    int sendReplyAudio(const QString &pcm16kPath);
    // 0x33：通知对端立即停止播放并清空回传音频队列
    void sendReplyStop();

signals:
    void listening(quint16 port);
    void bindFailed(const QString &reason);
    void probeHandled(const QString &fromIp, quint16 fromPort);
    void heartbeatReceived(const QString &fromIp, quint16 fromPort);
    // 一段完整语音重组完毕（filePath 为按 seq 拼接后的完整 PCM）
    void voiceAssembled(const QString &filePath, int fragments, const QString &fromIp, quint16 fromPort);
    // 分片收不齐（丢包超时 / 新一段开始 / 会话切换），片段已丢弃
    void voiceIncomplete(int received, int total, const QString &fromIp, quint16 fromPort);
    // 收到新一段用户语音（seq==0）：上层据此打断正在进行的音频回传
    void newVoiceTransfer(const QString &fromIp, quint16 fromPort);
    // 收到手机端打断请求（0x40 ABORT_REQ）
    void abortRequested(const QString &fromIp, quint16 fromPort);
    void photoSaved(const QString &filePath, const QString &fromIp, quint16 fromPort);
    void metaReceived(const QJsonObject &meta, const QString &fromIp, quint16 fromPort);
    void unknownPacket(quint8 type, const QString &fromIp, quint16 fromPort);

private slots:
    void onReadyRead();
    void onAssembleTimeout();

private:
    // ts 仅对握手包有效；业务包偏移 8~15 不是时间戳（见文件头注释）
    bool parseHeader(const QByteArray &packet, quint8 *type, quint64 *ts) const;
    void handleProbe(const QByteArray &packet, const QHostAddress &from, quint16 fromPort);
    void handleHeartbeat(const QByteArray &packet, const QHostAddress &from, quint16 fromPort);
    void handleVoicePcm(const QByteArray &packet, const QHostAddress &from, quint16 fromPort);
    void handleVoiceMeta(const QByteArray &payload, const QHostAddress &from, quint16 fromPort);
    void handlePhoto(const QByteArray &payload, const QHostAddress &from, quint16 fromPort);
    QString sessionDir(const QHostAddress &from, quint16 fromPort);
    void finishVoiceTransfer(const QString &dir); // 收齐：按 seq 拼接、落盘、上报
    void dropVoiceTransfer(const QString &why);   // 丢弃未完成段（各中断场景复用）

    // 进行中的一段语音（同一时刻只允许一段；键=对端地址:端口 + 0x10）
    struct VoiceTransfer {
        bool active = false;
        quint16 total = 0;
        QString fromIp;
        quint16 fromPort = 0;
        QMap<quint16, QByteArray> fragments; // seq -> 载荷（QMap 按键升序 = seq 序）
    };

    QUdpSocket m_socket;
    quint16 m_port = 0;
    int m_photoSeq = 0;
    QString m_currentSessionKey; // "IP:端口"，端口变化即视为新会话
    // 最近一次对端（来自握手或数据包），音频回传的发往地址；10 分钟无往来视为失效
    QHostAddress m_replyPeerAddr;
    quint16 m_replyPeerPort = 0;
    qint64 m_replyPeerTs = 0;
    VoiceTransfer m_transfer;
    QTimer m_assembleTimer; // 单次触发；每收到一片重开
};

#endif // UDPRECEIVER_H
