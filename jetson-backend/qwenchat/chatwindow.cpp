#include "chatwindow.h"
#include "asrclient.h"
#include "ttsclient.h"
#include "udpreceiver.h"

#include <QApplication>
#include <QCheckBox>
#include <QCoreApplication>
#include <QDateTime>
#include <QFile>
#include <QHBoxLayout>
#include <QHostAddress>
#include <QJsonDocument>
#include <QJsonObject>
#include <QLabel>
#include <QLineEdit>
#include <QPair>
#include <QProcess>
#include <QPushButton>
#include <QResizeEvent>
#include <QScrollArea>
#include <QScrollBar>
#include <QStatusBar>
#include <QTimer>
#include <QUdpSocket>
#include <QVBoxLayout>

namespace {
// 微信风格配色
const QString kUserBubbleQss = QStringLiteral(
    "background-color:#95EC69; color:#1a1a1a;"
    "border-radius:10px; padding:8px 12px;");
const QString kAssistantBubbleQss = QStringLiteral(
    "background-color:#ffffff; color:#1a1a1a;"
    "border-radius:10px; padding:8px 12px; border:1px solid #e0e0e0;");
const QString kChatBgQss = QStringLiteral(
    "QScrollArea{background-color:#ededed; border:none;}"
    "QScrollArea > QWidget > QWidget{background-color:#ededed;}");
const QString kThinkingPlaceholder = QStringLiteral("正在思考…");

// 写入 u16 大端（业务分片包头部字段用，与 App 侧 AiUploader.buildChunk() 对齐）
void appendBeU16(QByteArray &out, quint16 v)
{
    out.append(char((v >> 8) & 0xFF));
    out.append(char(v & 0xFF));
}

// 把一句话按语言切成 [(段文本, 引擎)]：拉丁字母段→kokoro（读英文），
// 中文/数字段→matcha（桥接的 FST 会把数字读成中文）；保持原顺序，纯符号段忽略
QVector<QPair<QString, QString>> splitByLanguage(const QString &text)
{
    QVector<QPair<QString, QString>> out;
    QString seg;
    int mode = 0; // 0=未定 1=拉丁 2=中文
    auto flush = [&out, &seg, &mode]() {
        const QString s = seg.trimmed();
        seg.clear();
        mode = 0;
        if (s.isEmpty())
            return;
        bool hasLetter = false, hasReadable = false;
        for (const QChar &c : s) {
            const ushort u = c.unicode();
            if (c.isLetter() && u < 128)
                hasLetter = true;
            if ((u >= 0x4E00 && u <= 0x9FFF) || c.isDigit())
                hasReadable = true;
        }
        if (!hasLetter && !hasReadable)
            return; // 纯符号段
        out.append(qMakePair(s, hasLetter ? QStringLiteral("kokoro") : QStringLiteral("matcha")));
    };
    for (const QChar &c : text) {
        const ushort u = c.unicode();
        if (c.isLetter() && u < 128) {
            if (mode == 2)
                flush();
            mode = 1;
        } else if (u >= 0x4E00 && u <= 0x9FFF) {
            if (mode == 1)
                flush();
            mode = 2;
        }
        seg += c; // 数字/标点/空白跟随当前段
    }
    flush();
    return out;
}
}

ChatWindow::ChatWindow(QWidget *parent)
    : QMainWindow(parent)
{
    // --selftest：offscreen 自动化验证模式（提前确定，供 bind 失败等早期路径判断）
    m_selftest = QCoreApplication::arguments().contains(QStringLiteral("--selftest"));

    setWindowTitle(QStringLiteral("Qwen Chat — AI 眼镜助手"));
    resize(700, 800);

    auto *central = new QWidget(this);
    auto *layout = new QVBoxLayout(central);

    // 消息区：滚动区域 + 垂直布局动态挂气泡
    m_scroll = new QScrollArea(central);
    m_scroll->setWidgetResizable(true);
    m_scroll->setStyleSheet(kChatBgQss);
    m_msgContainer = new QWidget;
    m_msgLayout = new QVBoxLayout(m_msgContainer);
    m_msgLayout->setSpacing(10);
    m_msgLayout->setContentsMargins(12, 12, 12, 12);
    m_msgLayout->addStretch(1); // 底部弹簧，把气泡顶到上方
    m_scroll->setWidget(m_msgContainer);
    layout->addWidget(m_scroll, 1);

    auto *bottom = new QHBoxLayout;
    m_inputEdit = new QLineEdit(central);
    m_inputEdit->setPlaceholderText(QStringLiteral("输入消息"));
    m_thinkCheck = new QCheckBox(QStringLiteral("深度思考"), central);
    m_thinkCheck->setToolTip(QStringLiteral(
        "勾选：模型先思考再回答（推理更强，需等待数十秒）；\n"
        "不勾选：快速模式，即时回复"));
    m_speakCheck = new QCheckBox(QStringLiteral("语音播报"), central);
    m_speakCheck->setChecked(true);
    m_speakCheck->setToolTip(QStringLiteral(
        "勾选：LLM 回复按句自动合成语音（Matcha）并在本机播放"));
    m_sendBtn = new QPushButton(QStringLiteral("发送"), central);
    m_clearBtn = new QPushButton(QStringLiteral("清空对话"), central);
    bottom->addWidget(m_inputEdit, 1);
    bottom->addWidget(m_thinkCheck);
    bottom->addWidget(m_speakCheck);
    bottom->addWidget(m_sendBtn);
    bottom->addWidget(m_clearBtn);
    layout->addLayout(bottom);

    setCentralWidget(central);
    statusBar()->showMessage(QStringLiteral("就绪：llama-server 127.0.0.1:8080"));

    connect(m_sendBtn, &QPushButton::clicked, this, &ChatWindow::sendMessage);
    connect(m_inputEdit, &QLineEdit::returnPressed, this, &ChatWindow::sendMessage);
    connect(m_clearBtn, &QPushButton::clicked, this, &ChatWindow::clearChat);

    // Python 桥接进程：本程序经 stdin/stdout 与之通信（JSON-lines 协议）
    m_proc = new QProcess(this);
    m_proc->setProcessChannelMode(QProcess::SeparateChannels);
    connect(m_proc, &QProcess::readyReadStandardOutput, this, &ChatWindow::onStdoutReady);
    connect(m_proc, &QProcess::errorOccurred, this, &ChatWindow::onProcessError);
    connect(m_proc,
            static_cast<void (QProcess::*)(int, QProcess::ExitStatus)>(&QProcess::finished),
            this, &ChatWindow::onProcessFinished);

    // UDP 接收服务（协议见 jetson-udp-receiver-design.md）
    m_udp = new UdpReceiver(this);
    connect(m_udp, &UdpReceiver::listening, this, [this](quint16 port) {
        addSystemMessage(QStringLiteral("UDP 服务已启动，监听 0.0.0.0:%1").arg(port));
    });
    connect(m_udp, &UdpReceiver::bindFailed, this, [this](const QString &reason) {
        showError(QStringLiteral("UDP 端口绑定失败: %1").arg(reason));
        if (m_selftest) {
            // 端口被占时必须中止自测：否则探活/分片会被占用端口的实例接走，产生假结果。
            // 注意：这里仍在构造函数里、事件循环未启动，直接 qApp->exit() 无效，需排队到循环启动后执行
            qInfo().noquote() << "SELFTEST_FAIL UDP 端口绑定失败:" << reason;
            QTimer::singleShot(0, qApp, [] { qApp->exit(6); });
        }
    });
    connect(m_udp, &UdpReceiver::probeHandled, this,
            [this](const QString &ip, quint16 port) {
                addSystemMessage(QStringLiteral("收到握手探测并已应答（%1:%2）").arg(ip).arg(port));
            });
    connect(m_udp, &UdpReceiver::voiceAssembled, this,
            [this](const QString &path, int fragments, const QString &ip, quint16 port) {
                addSystemMessage(QStringLiteral("收到完整语音 %1（%2 片已拼接，来自 %3:%4），正在识别…")
                                     .arg(path.section(QLatin1Char('/'), -1))
                                     .arg(fragments)
                                     .arg(ip)
                                     .arg(port));
                m_asr->recognize(path); // 完整 PCM → ASR，结果以用户气泡显示
            });
    connect(m_udp, &UdpReceiver::voiceIncomplete, this,
            [this](int received, int total, const QString &ip, quint16 port) {
                showError(QStringLiteral("语音不完整（收到 %1/%2 片，来自 %3:%4），已丢弃")
                              .arg(received)
                              .arg(total)
                              .arg(ip)
                              .arg(port));
            });
    connect(m_udp, &UdpReceiver::photoSaved, this,
            [this](const QString &path, const QString &ip, quint16 port) {
                addSystemMessage(QStringLiteral("收到照片 %1（JPEG，来自 %2:%3）")
                                     .arg(path.section(QLatin1Char('/'), -1), ip).arg(port));
            });
    connect(m_udp, &UdpReceiver::metaReceived, this,
            [this](const QJsonObject &, const QString &ip, quint16 port) {
                addSystemMessage(QStringLiteral("收到语音元信息 meta.json（JSON，来自 %1:%2）")
                                     .arg(ip).arg(port));
            });
    connect(m_udp, &UdpReceiver::unknownPacket, this,
            [this](quint8 type, const QString &ip, quint16 port) {
                addSystemMessage(QStringLiteral("收到未知类型报文 0x%1（来自 %2:%3）")
                                     .arg(type, 2, 16, QLatin1Char('0')).arg(ip).arg(port));
            });
    // 打断联动：用户开始说话（新一段语音首片）或手机端显式请求（0x40）
    connect(m_udp, &UdpReceiver::newVoiceTransfer, this,
            [this](const QString &, quint16) { abortReplyAudio(QStringLiteral("用户开始说话")); });
    connect(m_udp, &UdpReceiver::abortRequested, this,
            [this](const QString &, quint16) { abortReplyAudio(QStringLiteral("手机端请求打断")); });
    // 监听端口：默认 1050，可用环境变量 QWEN_UDP_PORT 覆盖（便于多实例/自动化测试）
    const quint16 udpPort = qEnvironmentVariableIsSet("QWEN_UDP_PORT")
        ? quint16(qgetenv("QWEN_UDP_PORT").toUShort())
        : quint16(1050);
    m_udp->start(udpPort);

    // ASR 桥接：识别结果以用户气泡显示（暂不送 LLM）
    m_asr = new AsrClient(this);
    connect(m_asr, &AsrClient::recognized, this,
            [this](const QString &, const QString &text) {
                // 语音链路：识别文本先以用户气泡显示，再送入 LLM（模型忙则排队）
                const QString trimmed = text.trimmed();
                if (trimmed.isEmpty()) {
                    addSystemMessage(QStringLiteral("语音识别结果为空，未发送给模型"));
                    if (m_selftest && m_voiceTestPending) {
                        m_voiceTestPending = false;
                        qInfo().noquote() << "ASRSELFTEST_FAIL 空识别结果";
                        qApp->exit(5);
                    }
                    return;
                }
                addMessageRow(true, trimmed);
                if (m_busy) {
                    m_voiceQueue.enqueue(trimmed);
                    addSystemMessage(QStringLiteral("模型回复中，语音已排队（待处理 %1 条）")
                                         .arg(m_voiceQueue.size()));
                } else {
                    requestLlmReply(trimmed); // 识别文本 → LLM
                }
            });
    connect(m_asr, &AsrClient::failed, this,
            [this](const QString &file, const QString &message) {
                showError(QStringLiteral("语音识别失败（%1）: %2")
                              .arg(file.section(QLatin1Char('/'), -1), message));
                if (m_selftest && m_voiceTestPending) {
                    m_voiceTestPending = false;
                    qApp->exit(5);
                }
            });

    // TTS 桥接：LLM 回复按句合成（Matcha）并顺序本地播放；selftest 只合成不播放
    m_tts = new TtsClient(this);
    connect(m_tts, &TtsClient::synthesized, this,
            [this](const QString &wav, const QString &pcm16k, const QString &text,
                   const QString &engine, double seconds, double rtf) {
                if (wav.isEmpty()) {
                    // 整段无可读内容：静默跳过，不算失败
                    qInfo().noquote() << QStringLiteral("TTS 空合成[%1]（已跳过）：%2…")
                                             .arg(engine, text.left(18));
                    return;
                }
                qInfo().noquote() << QStringLiteral("TTS 合成[%1]：%2…（音频 %3s，RTF %4）")
                                         .arg(engine, text.left(16))
                                         .arg(seconds, 0, 'f', 2)
                                         .arg(rtf, 0, 'f', 3);
                if (m_selftest) {
                    // 自动化测试只合成：立即删除产物，避免在 out/ 残留
                    QFile::remove(wav);
                    if (!pcm16k.isEmpty())
                        QFile::remove(pcm16k);
                    return;
                }
                m_replyAudioFiles << wav;
                if (!pcm16k.isEmpty())
                    m_replyAudioFiles << pcm16k;
                // 优先 UDP 回传手机；无有效对端则回退本地播放（避免两端同时出声）
                const int frags = pcm16k.isEmpty() ? -1 : m_udp->sendReplyAudio(pcm16k);
                if (frags > 0) {
                    m_replyAudioActive = true;
                    m_peerAudioSent = true; // 手机端可能仍在播放 → 打断时需发 0x33
                    maybeFinishReplyAudio();
                } else {
                    m_playQueue.enqueue(wav);
                    playNextWav();
                }
            });
    connect(m_tts, &TtsClient::becameIdle, this, [this] {
        maybeFinishReplyAudio();
        if (m_selftest && m_voiceTestExit) {
            m_voiceTestExit = false;
            qApp->exit(0); // TTS 收尾完毕，全链路验证结束
        }
    });
    connect(m_tts, &TtsClient::failed, this,
            [this](const QString &text, const QString &message) {
                addSystemMessage(QStringLiteral("语音播报失败（%1…）: %2")
                                     .arg(text.left(12), message));
            });
    m_aplayProc = new QProcess(this);
    connect(m_aplayProc,
            static_cast<void (QProcess::*)(int, QProcess::ExitStatus)>(&QProcess::finished),
            this, [this] { playNextWav(); maybeFinishReplyAudio(); });
    connect(m_aplayProc, &QProcess::errorOccurred, this, [this](QProcess::ProcessError) {
        if (!m_aplayWarned) {
            m_aplayWarned = true;
            addSystemMessage(QStringLiteral("本地播放失败（aplay 不可用？），后续只合成不播放"));
        }
        playNextWav();
    });
    m_tts->warmUp(); // 提前拉起桥接并预载双引擎（与后续 LLM 生成并行）

    runSelftestIfRequested();

    // --speak "文本"：直接走一遍播报链路（切句→分段→合成→播放），供命令行验证/试听，例如：
    //   QWEN_UDP_PORT=1052 QT_QPA_PLATFORM=offscreen ./QwenChat --speak "这个 RTF 是 0.09"
    const QStringList argList = QCoreApplication::arguments();
    const int speakIdx = argList.indexOf(QStringLiteral("--speak"));
    if (speakIdx >= 0 && speakIdx + 1 < argList.size()) {
        const QString text = argList.at(speakIdx + 1);
        QTimer::singleShot(1000, this, [this, text] {
            qInfo().noquote() << QStringLiteral("SPEAKTEST：%1").arg(text);
            feedSpeech(text);
            flushSpeech();
            m_replyDraining = true; // 与正常回复一致：全部送出/播完后清理音频文件
            maybeFinishReplyAudio();
        });
    }
}

ChatWindow::~ChatWindow()
{
    // 停掉本地播放（若有），先断开 finished 回调避免析构期间又启动下一句
    if (m_aplayProc) {
        m_aplayProc->disconnect(this);
        if (m_aplayProc->state() == QProcess::Running) {
            m_aplayProc->kill();
            m_aplayProc->waitForFinished(2000);
        }
    }

    // 优雅关闭桥接进程：先发 EOF 让 python 退出，超时才强制 kill，
    // 避免 "QProcess: Destroyed while process is still running" 崩溃
    if (m_proc->state() == QProcess::Running) {
        m_proc->closeWriteChannel();
        if (!m_proc->waitForFinished(3000)) {
            m_proc->kill();
            m_proc->waitForFinished(3000);
        }
    }
}

void ChatWindow::resizeEvent(QResizeEvent *event)
{
    QMainWindow::resizeEvent(event);
    updateBubbleMaxWidths();
}

void ChatWindow::sendMessage()
{
    if (m_busy)
        return;

    const QString text = m_inputEdit->text().trimmed();
    if (text.isEmpty())
        return;

    if (!ensureProcessRunning())
        return;

    abortReplyAudio(QStringLiteral("键盘输入")); // 新一轮输入 → 打断上一轮音频
    m_inputEdit->clear();
    addMessageRow(true, text); // 用户消息：右侧绿泡
    requestLlmReply(text);
}

// 向 LLM 发起一次请求（不含用户气泡；手动输入与语音识别共用）
void ChatWindow::requestLlmReply(const QString &text)
{
    m_speechSuppressed = false; // 新一轮回复恢复正常播报/回传
    m_peerAudioSent = false;    // 新一轮开始（上一轮若有回传音频，打断流程已通知手机停播）
    if (!ensureProcessRunning())
        return;

    addMessageRow(false, kThinkingPlaceholder); // 助手气泡：先显示"正在思考…"
    setBusy(true);
    statusBar()->showMessage(QStringLiteral("Qwen 思考中…"));

    const QJsonObject obj{{QStringLiteral("prompt"), text},
                          {QStringLiteral("think"), m_thinkCheck->isChecked()}};
    m_proc->write(QJsonDocument(obj).toJson(QJsonDocument::Compact) + '\n');
}

// 模型空闲时取出排队中的语音识别文本继续送 LLM
void ChatWindow::pumpVoiceQueue()
{
    if (m_busy || m_voiceQueue.isEmpty())
        return;
    requestLlmReply(m_voiceQueue.dequeue());
}

// —— 语音播报：LLM 回复流式 token → 按标点切句 → TTS（Matcha）→ aplay 顺序播放 ——
void ChatWindow::feedSpeech(const QString &token)
{
    if (m_speechSuppressed || !m_speakCheck->isChecked())
        return;
    m_speechBuf += token;
    const QString terminators = QStringLiteral("。！？!?；;\n");
    QVector<QString> sentences;
    int start = 0;
    for (int i = 0; i < m_speechBuf.size(); ++i) {
        const QChar ch = m_speechBuf.at(i);
        bool cut = terminators.contains(ch);
        if (!cut && i - start >= 80 && QStringLiteral("，,").contains(ch))
            cut = true; // 超长句在逗号处断，别让单句合成等待过久
        if (cut) {
            const QString s = m_speechBuf.mid(start, i - start + 1).trimmed();
            if (!s.isEmpty())
                sentences.append(s);
            start = i + 1;
        }
    }
    m_speechBuf = m_speechBuf.mid(start);
    for (const QString &s : sentences)
        speakSentence(s);
}

void ChatWindow::flushSpeech()
{
    const QString rest = m_speechBuf;
    m_speechBuf.clear();
    if (m_speakCheck->isChecked() && !rest.trimmed().isEmpty())
        speakSentence(rest);
}

void ChatWindow::speakSentence(const QString &text)
{
    QString clean = text;
    const QString drop = QStringLiteral("*#`>[]");
    for (const QChar c : drop)
        clean.remove(c); // 去掉 markdown 装饰符，避免读出怪符号
    clean = clean.trimmed();
    if (clean.isEmpty())
        return;
    // 句内按语言切段：中文/数字段 → Matcha（快），字母段 → Kokoro（能读英文）；
    // 依次入队，TtsClient（FIFO）与播放队列保证按原顺序合成/播出
    const auto segments = splitByLanguage(clean);
    for (const auto &seg : segments) {
        qInfo().noquote() << QStringLiteral("TTS 排队[%1]：%2…").arg(seg.second, seg.first.left(16));
        m_tts->speak(seg.first, seg.second);
    }
}

void ChatWindow::playNextWav()
{
    if (m_selftest || m_playQueue.isEmpty())
        return;
    if (m_aplayProc->state() == QProcess::Running)
        return; // 上一句还在播；它结束的 finished 会再进来取下一句
    m_aplayProc->start(QStringLiteral("aplay"), {QStringLiteral("-q"), m_playQueue.dequeue()});
}

// —— 音频回传的打断与清理 ——
void ChatWindow::abortReplyAudio(const QString &why)
{
    const bool hadAudio = m_replyAudioActive || m_peerAudioSent
        || !m_replyAudioFiles.isEmpty() || !m_playQueue.isEmpty() || !m_speechBuf.isEmpty();
    m_speechSuppressed = true; // 本轮剩余句子不再播报/回传
    m_tts->cancelAll();
    m_speechBuf.clear();
    m_playQueue.clear();
    if (m_aplayProc && m_aplayProc->state() == QProcess::Running)
        m_aplayProc->kill();
    // 本轮曾向手机回过音频（可能仍在播放）→ 通知其立即停播并清队列
    if (m_peerAudioSent)
        m_udp->sendReplyStop();
    m_peerAudioSent = false;
    cleanupReplyAudioFiles();
    m_replyAudioActive = false;
    m_replyDraining = false;
    if (hadAudio) {
        qInfo().noquote() << QStringLiteral("语音回传打断（%1）").arg(why);
        addSystemMessage(QStringLiteral("已打断语音回传：%1").arg(why));
    }
}

void ChatWindow::maybeFinishReplyAudio()
{
    if (!m_replyDraining || !m_tts->isIdle())
        return;
    if (!m_replyAudioActive) {
        // 本地播放模式：等队列播完再清理（避免删掉还在播/待播的文件）
        if (!m_playQueue.isEmpty()
            || (m_aplayProc && m_aplayProc->state() == QProcess::Running))
            return;
    }
    cleanupReplyAudioFiles();
    m_replyAudioActive = false;
    m_replyDraining = false;
}

void ChatWindow::cleanupReplyAudioFiles()
{
    if (m_replyAudioFiles.isEmpty())
        return;
    for (const QString &p : m_replyAudioFiles)
        QFile::remove(p);
    qInfo().noquote() << QStringLiteral("已清理本轮音频文件 %1 个").arg(m_replyAudioFiles.size());
    m_replyAudioFiles.clear();
}

void ChatWindow::clearChat()
{
    m_proc->write("{\"clear\":true}\n");
    // 删除 stretch 之前的全部气泡行
    while (m_msgLayout->count() > 1) {
        QLayoutItem *item = m_msgLayout->takeAt(0);
        if (QWidget *w = item->widget())
            delete w;
        else if (QLayout *l = item->layout())
            while (QLayoutItem *child = l->takeAt(0))
                delete child->widget();
        delete item;
    }
    m_bubbles.clear();
    m_currentBubble = nullptr;
    m_gotToken = false;
    m_voiceQueue.clear();
    abortReplyAudio(QStringLiteral("清空对话"));
    statusBar()->showMessage(QStringLiteral("就绪：llama-server 127.0.0.1:8080"));
}

void ChatWindow::onStdoutReady()
{
    m_stdoutBuf += m_proc->readAllStandardOutput();
    int idx;
    while ((idx = m_stdoutBuf.indexOf('\n')) >= 0) {
        const QByteArray line = m_stdoutBuf.left(idx).trimmed();
        m_stdoutBuf.remove(0, idx + 1);
        if (line.isEmpty())
            continue;

        const QJsonObject obj = QJsonDocument::fromJson(line).object();
        const QString type = obj.value(QStringLiteral("type")).toString();

        if (type == QLatin1String("token")) {
            m_gotToken = true;
            const QString tok = obj.value(QStringLiteral("text")).toString();
            appendTokenToCurrentBubble(tok);
            feedSpeech(tok); // LLM 回复按句喂给 TTS（语音播报）
            statusBar()->showMessage(QStringLiteral("回复中…"));
        } else if (type == QLatin1String("done")) {
            setBusy(false);
            m_gotToken = false;
            m_currentBubble = nullptr;
            statusBar()->showMessage(QStringLiteral("就绪：llama-server 127.0.0.1:8080"));
            flushSpeech(); // 回复结束：把最后一句交给 TTS
            m_replyDraining = true; // 音频全部送出/播完后统一清理
            maybeFinishReplyAudio();
            if (m_selftest && m_voiceTestPending) {
                // 语音阶段：识别文本已送 LLM 且收到完整回复 → 全链路通过。
                // 等 TTS 在途合成全部处理完（产物已删）再退出，避免残留文件
                m_voiceTestPending = false;
                qInfo().noquote() << "ASRSELFTEST_OK（语音→ASR→LLM 全链路完成）";
                if (m_tts->isIdle())
                    qApp->exit(0);
                else
                    m_voiceTestExit = true; // becameIdle 时退出
                return;
            }
            if (m_selftest)
                QTimer::singleShot(500, this, &ChatWindow::runUdpProbeTest);
            pumpVoiceQueue(); // 有空闲则继续处理排队的语音
        } else if (type == QLatin1String("error")) {
            showError(obj.value(QStringLiteral("message")).toString());
            setBusy(false);
            m_gotToken = false;
            m_currentBubble = nullptr;
            m_speechBuf.clear(); // 出错丢弃未播报的残句
            statusBar()->showMessage(QStringLiteral("模型调用失败"));
            if (m_selftest)
                qApp->exit(1);
            pumpVoiceQueue(); // 上一条失败不影响排队中的后续语音
        }
    }
}

void ChatWindow::onProcessError(QProcess::ProcessError error)
{
    Q_UNUSED(error);
    if (m_busy) {
        showError(QStringLiteral("Python 桥接进程中断，请重新发送（将自动重启）"));
        setBusy(false);
        m_gotToken = false;
        m_currentBubble = nullptr;
    }
    statusBar()->showMessage(QStringLiteral("桥接进程中断"));
    pumpVoiceQueue();
}

void ChatWindow::onProcessFinished(int exitCode, QProcess::ExitStatus exitStatus)
{
    Q_UNUSED(exitCode);
    Q_UNUSED(exitStatus);
    if (m_busy) {
        showError(QStringLiteral("Python 桥接进程已退出，请重新发送（将自动重启）"));
        setBusy(false);
        m_gotToken = false;
        m_currentBubble = nullptr;
    }
    pumpVoiceQueue();
}

// 追加一行气泡消息：isUser=true 时头像在右、绿泡；否则头像在左、白泡
void ChatWindow::addMessageRow(bool isUser, const QString &text)
{
    auto *row = new QHBoxLayout;

    // 圆形头像
    auto *avatar = new QLabel(isUser ? QStringLiteral("我") : QStringLiteral("Q"),
                              m_msgContainer);
    avatar->setFixedSize(40, 40);
    avatar->setAlignment(Qt::AlignCenter);
    avatar->setStyleSheet(isUser
        ? QStringLiteral("background-color:#95EC69; color:#1a1a1a;"
                         "border-radius:20px; font-weight:bold;")
        : QStringLiteral("background-color:#4c7df0; color:#ffffff;"
                         "border-radius:20px; font-weight:bold;"));

    // 气泡
    auto *bubble = new QLabel(text, m_msgContainer);
    bubble->setWordWrap(true);
    bubble->setTextInteractionFlags(Qt::TextSelectableByMouse);
    bubble->setStyleSheet(isUser ? kUserBubbleQss : kAssistantBubbleQss);

    if (isUser) {
        row->addStretch(1);
        row->addWidget(bubble, 0, Qt::AlignTop);
        row->addWidget(avatar, 0, Qt::AlignTop);
    } else {
        row->addWidget(avatar, 0, Qt::AlignTop);
        row->addWidget(bubble, 0, Qt::AlignTop);
        row->addStretch(1);
    }

    // 插到末尾 stretch 之前
    m_msgLayout->insertLayout(m_msgLayout->count() - 1, row);
    m_bubbles.append(bubble);
    if (!isUser)
        m_currentBubble = bubble;

    updateBubbleMaxWidths();
    QTimer::singleShot(0, this, [this] { scrollToBottom(); });
}

// 居中灰色系统消息（非对话内容，如 UDP 事件）
void ChatWindow::addSystemMessage(const QString &text)
{
    auto *lbl = new QLabel(text, m_msgContainer);
    lbl->setWordWrap(true);
    lbl->setStyleSheet(QStringLiteral("color:#8a8a8a;"));
    auto *row = new QHBoxLayout;
    row->addStretch(1);
    row->addWidget(lbl, 0, Qt::AlignTop);
    row->addStretch(1);
    m_msgLayout->insertLayout(m_msgLayout->count() - 1, row);
    QTimer::singleShot(0, this, [this] { scrollToBottom(); });
}

void ChatWindow::appendTokenToCurrentBubble(const QString &text)
{
    if (!m_currentBubble)
        return;
    if (m_currentBubble->text() == kThinkingPlaceholder)
        m_currentBubble->clear(); // 清掉"正在思考…"占位
    m_currentBubble->setText(m_currentBubble->text() + text);
    QTimer::singleShot(0, this, [this] { scrollToBottom(); });
}

void ChatWindow::showError(const QString &msg)
{
    auto *lbl = new QLabel(msg, m_msgContainer);
    lbl->setWordWrap(true);
    lbl->setStyleSheet(QStringLiteral("color:#c62828;"));
    auto *row = new QHBoxLayout;
    row->addStretch(1);
    row->addWidget(lbl, 0, Qt::AlignTop);
    row->addStretch(1);
    m_msgLayout->insertLayout(m_msgLayout->count() - 1, row);
    QTimer::singleShot(0, this, [this] { scrollToBottom(); });
}

void ChatWindow::setBusy(bool busy)
{
    m_busy = busy;
    m_sendBtn->setEnabled(!busy);
    m_clearBtn->setEnabled(!busy);
    m_inputEdit->setEnabled(!busy);
    if (!busy)
        m_inputEdit->setFocus();
}

bool ChatWindow::ensureProcessRunning()
{
    if (m_proc->state() == QProcess::Running)
        return true;

    m_proc->start(QStringLiteral("python3"),
                  {QString::fromUtf8(PYTHON_CLIENT_PATH)});
    if (!m_proc->waitForStarted(5000)) {
        showError(QStringLiteral("无法启动 Python 桥接进程: %1")
                      .arg(QString::fromUtf8(PYTHON_CLIENT_PATH)));
        return false;
    }
    return true;
}

void ChatWindow::scrollToBottom()
{
    m_scroll->verticalScrollBar()->setValue(m_scroll->verticalScrollBar()->maximum());
}

void ChatWindow::updateBubbleMaxWidths()
{
    const int w = m_scroll->viewport()->width() * 3 / 5;
    for (QLabel *b : m_bubbles)
        b->setMaximumWidth(w);
}

void ChatWindow::runSelftestIfRequested()
{
    if (!m_selftest)
        return;

    // offscreen 自动化验证：
    // 阶段1 自动发送一条消息，成功后进入 UDP 握手自测（runUdpProbeTest），超时退出(2)
    QTimer::singleShot(2000, this, [this] {
        m_inputEdit->setText(QStringLiteral("用一句话介绍你自己"));
        sendMessage();
    });
    QTimer::singleShot(150000, qApp, [] { qApp->exit(2); });
}

// ---- selftest 阶段 2：模拟 App 握手探测（行为与 AiUdpManager.doHandshake() 一致）----
// 报文：16 字节 PROBE（魔数+版本+类型+时间戳）→ 期待 ACK：type==2、时间戳回填
// 超时 800ms、重试 3 次（设计文档 §3.2 / §8）
void ChatWindow::runUdpProbeTest()
{
    if (!m_testSock) {
        m_testSock = new QUdpSocket(this);
        connect(m_testSock, &QUdpSocket::readyRead, this, &ChatWindow::onProbeAckReady);
    }
    m_probeRetry = 0;
    sendProbe();
}

void ChatWindow::sendProbe()
{
    ++m_probeRetry;
    m_probeTs = quint64(QDateTime::currentMSecsSinceEpoch());
    QByteArray probe;
    probe.append("GLSY");
    probe.append(char(0x01)); // 版本
    probe.append(char(0x01)); // 类型 PROBE
    probe.append(char(0x00));
    probe.append(char(0x00));
    for (int i = 7; i >= 0; --i)
        probe.append(char((m_probeTs >> (8 * i)) & 0xFF));
    m_testSock->writeDatagram(probe, QHostAddress::LocalHost, m_udp->port());
    if (!m_probeTimer) {
        m_probeTimer = new QTimer(this);
        m_probeTimer->setSingleShot(true);
        connect(m_probeTimer, &QTimer::timeout, this, &ChatWindow::onProbeTimeout);
    }
    m_probeTimer->start(800);
}

void ChatWindow::onProbeAckReady()
{
    while (m_testSock->hasPendingDatagrams()) {
        QByteArray ack;
        ack.resize(int(m_testSock->pendingDatagramSize()));
        m_testSock->readDatagram(ack.data(), ack.size());
        if (ack.size() >= 16 && quint8(ack[0]) == 0x47 && quint8(ack[1]) == 0x4C
            && quint8(ack[2]) == 0x53 && quint8(ack[3]) == 0x59 && quint8(ack[5]) == 0x02) {
            quint64 ts = 0;
            for (int i = 0; i < 8; ++i)
                ts = (ts << 8) | quint8(ack[8 + i]);
            m_probeDone = true;
            if (m_probeTimer)
                m_probeTimer->stop(); // 取消残留的超时重试
            qInfo().noquote() << "UDPSELFTEST_OK retry=" << m_probeRetry
                              << "ts_echoed=" << (ts == m_probeTs);
            sendVoicePcmTest(); // 进入阶段 3：发真实 PCM 验证 ASR 识别链路
            return;
        }
    }
}

void ChatWindow::onProbeTimeout()
{
    if (m_probeDone)
        return;
    if (m_probeRetry >= 3) {
        qInfo().noquote() << "UDPSELFTEST_FAIL";
        qApp->exit(3);
        return;
    }
    sendProbe();
}

// 阶段 3：以 VOICE_PCM(0x10) 报文发送真实中文语音 PCM，验证 UDP → 分片重组 → ASR → LLM → 气泡全链路
// 分片格式与 App 侧 AiUploader.buildChunk() 完全对齐：载荷从偏移 16 开始，
// 头内偏移 6~7 载荷长 N(u16)、8~9 seq(u16)、10~11 total(u16)、12~15 保留，全大端；
// 每片 16384 字节（末片可短）
void ChatWindow::sendVoicePcmTest()
{
    QFile pcm(QStringLiteral("/mnt/ssd/jetson/asr/test/zh.pcm"));
    if (!pcm.open(QIODevice::ReadOnly)) {
        qInfo().noquote() << "ASRSELFTEST_FAIL 找不到测试 PCM";
        qApp->exit(5);
        return;
    }
    const QByteArray payload = pcm.readAll();
    pcm.close();

    const int kChunk = 16384; // 与 App 侧一致（固定 16384/片）
    const quint16 total = quint16((payload.size() + kChunk - 1) / kChunk);
    for (quint16 seq = 0; seq < total; ++seq) {
        const QByteArray chunk = payload.mid(int(seq) * kChunk, kChunk);
        QByteArray packet;
        packet.append("GLSY");
        packet.append(char(0x01));
        packet.append(char(0x10)); // VOICE_PCM
        appendBeU16(packet, quint16(chunk.size())); // 6~7 载荷长度 N
        appendBeU16(packet, seq);                   // 8~9 seq（从 0 开始）
        appendBeU16(packet, total);                 // 10~11 total（总片数）
        packet.append(char(0x00));                  // 12~15 保留
        packet.append(char(0x00));
        packet.append(char(0x00));
        packet.append(char(0x00));
        packet.append(chunk); // 载荷从偏移 16 开始
        m_testSock->writeDatagram(packet, QHostAddress::LocalHost, m_udp->port());
    }
    qInfo().noquote() << "ASRSELFTEST 已发送" << total << "个分片（16B 头含 N/seq/total，16384/片）";

    m_voiceTestPending = true;
    // 超时含 ASR + LLM 往返，给足余量
    QTimer::singleShot(30000, qApp, [] {
        qInfo().noquote() << "ASRSELFTEST_FAIL 超时";
        qApp->exit(4);
    });
}
