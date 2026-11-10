#include "udpreceiver.h"

#include <QDate>
#include <QDateTime>
#include <QDebug>
#include <QDir>
#include <QFile>
#include <QJsonDocument>

namespace {
const quint8 kMagic0 = 0x47, kMagic1 = 0x4C, kMagic2 = 0x53, kMagic3 = 0x59; // "GLSY"
const quint8 kVersion = 0x01;
const quint8 kTypeProbe = 0x01;
const quint8 kTypeProbeAck = 0x02;
const quint8 kTypeVoicePcm = 0x10;
const quint8 kTypeVoiceMeta = 0x11;
const quint8 kTypePhotoJpeg = 0x20;
const quint8 kTypeReplyAudio = 0x32; // Jetson → 手机：音频回传分片
const quint8 kTypeReplyStop = 0x33;  // Jetson → 手机：停止播放并清空回传队列
const quint8 kTypeAbortReq = 0x40;   // 手机 → Jetson：请求打断
const quint8 kTypeHeartbeat = 0x7F;
const int kReplyFragPayload = 16384;         // 回传分片载荷大小（与上行一致）
const qint64 kPeerFreshMs = 10 * 60 * 1000;  // 对端 10 分钟无往来视为失效
const int kHeaderLen = 16;
const int kAssembleTimeoutMs = 30000; // 无 ACK/重传：30 秒未收到新分片即放弃该段

// 业务分片包头部字段偏移（全部大端 u16；与握手包的偏移 6~15 语义不同，勿混用）
const int kOffPayloadLen = 6; // 载荷长度 N
const int kOffSeq = 8;        // 分片序号 seq（0 基）
const int kOffTotal = 10;     // 分片总数 total

quint16 beU16(const QByteArray &b, int off)
{
    return quint16((quint16(quint8(b[off])) << 8) | quint16(quint8(b[off + 1])));
}

void appendBeU16(QByteArray &out, quint16 v)
{
    out.append(char((v >> 8) & 0xFF));
    out.append(char(v & 0xFF));
}
}

UdpReceiver::UdpReceiver(QObject *parent)
    : QObject(parent)
{
    connect(&m_socket, &QUdpSocket::readyRead, this, &UdpReceiver::onReadyRead);
    m_assembleTimer.setSingleShot(true);
    connect(&m_assembleTimer, &QTimer::timeout, this, &UdpReceiver::onAssembleTimeout);
}

bool UdpReceiver::start(quint16 port)
{
    m_port = port;
    // 监听所有网卡，不要绑定到单一网卡（文档 §2.1）
    if (!m_socket.bind(QHostAddress::AnyIPv4, port)) {
        emit bindFailed(m_socket.errorString());
        return false;
    }
    emit listening(port);
    return true;
}

void UdpReceiver::onReadyRead()
{
    while (m_socket.hasPendingDatagrams()) {
        QByteArray packet;
        QHostAddress from;
        quint16 fromPort = 0;
        packet.resize(int(m_socket.pendingDatagramSize()));
        m_socket.readDatagram(packet.data(), packet.size(), &from, &fromPort);

        quint8 type = 0;
        quint64 ts = 0; // 握手包时间戳；业务包勿使用（见 parseHeader 注释）
        if (!parseHeader(packet, &type, &ts))
            continue; // 非法包：直接丢弃，不回包（文档 §3.3-5）

        switch (type) {
        case kTypeProbe:
            handleProbe(packet, from, fromPort); // 应答路径必须零耗时操作（文档 §3.2）
            break;
        case kTypeHeartbeat:
            handleHeartbeat(packet, from, fromPort);
            break;
        case kTypeVoicePcm:
            handleVoicePcm(packet, from, fromPort);
            break;
        case kTypeVoiceMeta:
            handleVoiceMeta(packet.mid(kHeaderLen), from, fromPort);
            break;
        case kTypePhotoJpeg:
            handlePhoto(packet.mid(kHeaderLen), from, fromPort);
            break;
        case kTypeAbortReq:
            emit abortRequested(from.toString(), fromPort); // 手机请求打断回传
            break;
        default:
            // 其余类型（0x30/0x31 等）本期只打日志（文档 §4.1）
            emit unknownPacket(type, from.toString(), fromPort);
            break;
        }
    }
}

bool UdpReceiver::parseHeader(const QByteArray &packet, quint8 *type, quint64 *ts) const
{
    // ⚠️ ts 只在握手包（0x01/0x02）里有意义；业务包偏移 8~15 是 seq/total/保留，
    //    按本函数读出来的是垃圾值，业务处理路径不要用 ts。
    if (packet.size() < kHeaderLen)
        return false;
    if (quint8(packet[0]) != kMagic0 || quint8(packet[1]) != kMagic1
        || quint8(packet[2]) != kMagic2 || quint8(packet[3]) != kMagic3)
        return false;
    if (quint8(packet[4]) != kVersion)
        return false;
    *type = quint8(packet[5]);
    quint64 v = 0;
    for (int i = 0; i < 8; ++i)
        v = (v << 8) | quint8(packet[8 + i]);
    *ts = v;
    return true;
}

void UdpReceiver::handleProbe(const QByteArray &packet, const QHostAddress &from, quint16 fromPort)
{
    // 只改类型字节，时间戳原样回填；恰好 16 字节；回包到来源地址与端口（文档 §3.3）
    QByteArray ack = packet.left(kHeaderLen);
    ack[5] = char(kTypeProbeAck);
    m_socket.writeDatagram(ack, from, fromPort);
    // 记录对端（握手来源地址:端口即 App 的收包地址，音频回传发往此处）
    m_replyPeerAddr = from;
    m_replyPeerPort = fromPort;
    m_replyPeerTs = QDateTime::currentMSecsSinceEpoch();
    emit probeHandled(from.toString(), fromPort);
}

void UdpReceiver::handleHeartbeat(const QByteArray &packet, const QHostAddress &from, quint16 fromPort)
{
    // 收到即回同类型空包（文档 §4.1）
    m_socket.writeDatagram(packet.left(kHeaderLen), from, fromPort);
    emit heartbeatReceived(from.toString(), fromPort);
}

void UdpReceiver::handleVoicePcm(const QByteArray &packet, const QHostAddress &from, quint16 fromPort)
{
    // 业务分片包：[16B 头][载荷]，头内偏移 6~7 载荷长 N、8~9 seq、10~11 total（大端 u16）；
    // ⚠️ 偏移 8~15 不是时间戳（那是握手包语义）
    const quint16 payloadLen = beU16(packet, kOffPayloadLen);
    const quint16 seq = beU16(packet, kOffSeq);
    const quint16 total = beU16(packet, kOffTotal);
    const QByteArray payload = packet.mid(kHeaderLen);
    if (total == 0 || seq >= total || int(payloadLen) != payload.size()) {
        qWarning().noquote()
            << QStringLiteral("VOICE_PCM 分片字段非法，已丢弃：seq=%1 total=%2 载荷长=%3 实际=%4")
                   .arg(seq)
                   .arg(total)
                   .arg(payloadLen)
                   .arg(payload.size());
        return;
    }

    const QString dir = sessionDir(from, fromPort); // 会话切换时会丢弃旧的未完成段

    if (m_transfer.active && seq == 0) {
        // App 侧规则：seq==0 表示一条新消息开始。该键已有未完成缓冲 → 丢弃旧缓冲
        // （协议无消息 ID，丢旧留新；不这么做会把两条录音拼成脏数据）
        dropVoiceTransfer(QStringLiteral("收到新一段的首片"));
    }
    if (!m_transfer.active) {
        m_transfer.active = true;
        m_transfer.total = total;
        m_transfer.fromIp = from.toString();
        m_transfer.fromPort = fromPort;
        qInfo().noquote() << QStringLiteral("语音分片开始：共 %1 片，来自 %2:%3")
                                 .arg(total)
                                 .arg(m_transfer.fromIp)
                                 .arg(fromPort);
        emit newVoiceTransfer(from.toString(), fromPort); // 通知上层：用户开始说话（可打断回传）
    }
    if (total != m_transfer.total) {
        qWarning().noquote()
            << QStringLiteral("分片 total 与当前传输不一致，丢弃该片：seq=%1 total=%2")
                   .arg(seq)
                   .arg(total);
        return;
    }

    m_transfer.fragments.insert(seq, payload); // 重复 seq 覆盖（同一分片内容应一致）
    m_assembleTimer.start(kAssembleTimeoutMs); // 每收到一片即重新计时

    if (int(m_transfer.fragments.size()) == int(m_transfer.total))
        finishVoiceTransfer(dir); // 收齐 → 拼接落盘 → 整体送 ASR（由上层连接处理）
}

void UdpReceiver::finishVoiceTransfer(const QString &dir)
{
    m_assembleTimer.stop();
    const QString ip = m_transfer.fromIp;
    const quint16 port = m_transfer.fromPort;
    const int fragmentCount = int(m_transfer.total);

    QByteArray full;
    int totalBytes = 0;
    for (auto it = m_transfer.fragments.cbegin(); it != m_transfer.fragments.cend(); ++it)
        totalBytes += it.value().size();
    full.reserve(totalBytes);
    // QMap 按键（seq）升序遍历 → 天然按序拼接
    for (auto it = m_transfer.fragments.cbegin(); it != m_transfer.fragments.cend(); ++it)
        full.append(it.value());

    // 业务包没有时间戳，文件名用本地接收时刻（毫秒）保证唯一
    const QString path = dir
        + QStringLiteral("/voice_%1.pcm").arg(QDateTime::currentMSecsSinceEpoch());
    QFile f(path);
    const bool ok = f.open(QIODevice::WriteOnly);
    if (ok) {
        f.write(full);
        f.close();
    }
    m_transfer = VoiceTransfer();

    if (ok) {
        qInfo().noquote() << QStringLiteral("语音重组完成：%1（%2 片，%3 字节）")
                                 .arg(path)
                                 .arg(fragmentCount)
                                 .arg(full.size());
        emit voiceAssembled(path, fragmentCount, ip, port);
    } else {
        qWarning().noquote() << QStringLiteral("语音落盘失败：%1").arg(path);
    }
}

void UdpReceiver::dropVoiceTransfer(const QString &why)
{
    if (!m_transfer.active)
        return;
    m_assembleTimer.stop();
    const int received = m_transfer.fragments.size();
    const int total = int(m_transfer.total);
    const QString ip = m_transfer.fromIp;
    const quint16 port = m_transfer.fromPort;
    m_transfer = VoiceTransfer();
    qWarning().noquote() << QStringLiteral("语音重组中断（%1）：收到 %2/%3 片，来自 %4:%5，已丢弃")
                                .arg(why)
                                .arg(received)
                                .arg(total)
                                .arg(ip)
                                .arg(port);
    emit voiceIncomplete(received, total, ip, port);
}

void UdpReceiver::onAssembleTimeout()
{
    dropVoiceTransfer(QStringLiteral("超时未收齐"));
}

int UdpReceiver::sendReplyAudio(const QString &pcm16kPath)
{
    // 分片协议与上行 0x10 完全一致：[16B 头][载荷]，头内偏移 6~7 N、8~9 seq、10~11 total（大端 u16）
    if (m_replyPeerTs <= 0
        || QDateTime::currentMSecsSinceEpoch() - m_replyPeerTs > kPeerFreshMs)
        return -1; // 无有效对端：上层回退本地播放
    QFile f(pcm16kPath);
    if (!f.open(QIODevice::ReadOnly))
        return -1;
    const QByteArray pcm = f.readAll();
    f.close();
    const int total = (pcm.size() + kReplyFragPayload - 1) / kReplyFragPayload;
    if (total <= 0 || total > 65535)
        return 0;
    for (int seq = 0; seq < total; ++seq) {
        const QByteArray chunk = pcm.mid(seq * kReplyFragPayload, kReplyFragPayload);
        QByteArray pkt;
        pkt.append("GLSY");
        pkt.append(char(kVersion));
        pkt.append(char(kTypeReplyAudio));
        appendBeU16(pkt, quint16(chunk.size())); // 6~7 载荷长度 N
        appendBeU16(pkt, quint16(seq));          // 8~9 seq（每段从 0 开始）
        appendBeU16(pkt, quint16(total));        // 10~11 total（该段总片数）
        pkt.append(char(0x00));                  // 12~15 保留
        pkt.append(char(0x00));
        pkt.append(char(0x00));
        pkt.append(char(0x00));
        pkt.append(chunk);
        m_socket.writeDatagram(pkt, m_replyPeerAddr, m_replyPeerPort);
    }
    qInfo().noquote() << QStringLiteral("音频回传：%1 片（%2 字节）发往 %3:%4")
                             .arg(total)
                             .arg(pcm.size())
                             .arg(m_replyPeerAddr.toString())
                             .arg(m_replyPeerPort);
    return total;
}

void UdpReceiver::sendReplyStop()
{
    if (m_replyPeerTs <= 0
        || QDateTime::currentMSecsSinceEpoch() - m_replyPeerTs > kPeerFreshMs)
        return;
    QByteArray pkt;
    pkt.append("GLSY");
    pkt.append(char(kVersion));
    pkt.append(char(kTypeReplyStop));
    pkt.append(QByteArray(kHeaderLen - 6, '\0')); // 余下补零，共 16 字节
    m_socket.writeDatagram(pkt, m_replyPeerAddr, m_replyPeerPort);
    qInfo().noquote() << QStringLiteral("已发送回传停止指令（0x33）到 %1:%2")
                             .arg(m_replyPeerAddr.toString())
                             .arg(m_replyPeerPort);
}

void UdpReceiver::handleVoiceMeta(const QByteArray &payload, const QHostAddress &from, quint16 fromPort)
{
    const QString dir = sessionDir(from, fromPort);
    const QJsonObject meta = QJsonDocument::fromJson(payload).object();
    QFile f(dir + QStringLiteral("/meta.json"));
    if (f.open(QIODevice::WriteOnly)) {
        f.write(QJsonDocument(meta).toJson(QJsonDocument::Indented));
        f.close();
    }
    emit metaReceived(meta, from.toString(), fromPort);
}

void UdpReceiver::handlePhoto(const QByteArray &payload, const QHostAddress &from, quint16 fromPort)
{
    // 注：0x20 若也按分片发送（AiUploader 同一套分片头），此处仍是逐包落盘，
    // 多片照片会写成多个文件；需要重组时再按 0x10 的方式扩展。
    const QString dir = sessionDir(from, fromPort);
    const QString path = dir + QStringLiteral("/photo_%1.jpg").arg(++m_photoSeq, 3, 10, QLatin1Char('0'));
    QFile f(path);
    if (f.open(QIODevice::WriteOnly)) {
        f.write(payload);
        f.close();
    }
    emit photoSaved(path, from.toString(), fromPort);
}

QString UdpReceiver::sessionDir(const QHostAddress &from, quint16 fromPort)
{
    // 会话键用「对端地址:端口」——手机重连时源端口会变，可自然区分连接（文档 §5）
    const QString key = from.toString() + QLatin1Char(':') + QString::number(fromPort);
    if (key != m_currentSessionKey) {
        dropVoiceTransfer(QStringLiteral("会话切换")); // 换设备/重连：旧会话的未完成段作废
        m_currentSessionKey = key;
        m_photoSeq = 0;
    }
    m_replyPeerAddr = from;
    m_replyPeerPort = fromPort;
    m_replyPeerTs = QDateTime::currentMSecsSinceEpoch();
    const QString dir = QStringLiteral(RECEIVED_DIR) + QLatin1Char('/')
        + QDate::currentDate().toString(Qt::ISODate) + QLatin1Char('_')
        + from.toString() + QLatin1Char('_') + QString::number(fromPort);
    QDir().mkpath(dir);
    return dir;
}
