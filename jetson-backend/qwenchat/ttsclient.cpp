#include "ttsclient.h"

#include <QDebug>
#include <QFile>
#include <QJsonDocument>
#include <QJsonObject>

TtsClient::TtsClient(QObject *parent)
    : QObject(parent)
{
    m_proc.setProcessChannelMode(QProcess::SeparateChannels);
    connect(&m_proc, &QProcess::readyReadStandardOutput, this, &TtsClient::onStdoutReady);
    connect(&m_proc, &QProcess::readyReadStandardError, this, &TtsClient::onStderrReady);
    connect(&m_proc, &QProcess::errorOccurred, this, &TtsClient::onProcessError);
    connect(&m_proc,
            static_cast<void (QProcess::*)(int, QProcess::ExitStatus)>(&QProcess::finished),
            this, &TtsClient::onProcessFinished);
}

TtsClient::~TtsClient()
{
    // 优雅关闭 TTS 桥接进程（同 AsrClient 的关闭流程）
    m_shuttingDown = true;
    if (m_proc.state() == QProcess::Running) {
        m_proc.closeWriteChannel();
        if (!m_proc.waitForFinished(3000)) {
            m_proc.kill();
            m_proc.waitForFinished(3000);
        }
    }
}

void TtsClient::speak(const QString &text, const QString &engine)
{
    const bool idle = m_currentText.isEmpty();
    m_queue.enqueue(qMakePair(text, engine));
    if (idle)
        sendNext();
}

void TtsClient::warmUp()
{
    if (m_shuttingDown || m_proc.state() == QProcess::Running)
        return;
    ensureRunning(); // 桥接启动后自行预载双引擎，不等第一次请求
}

void TtsClient::cancelAll()
{
    m_queue.clear();
    if (!m_currentText.isEmpty())
        m_cancelCurrent = true; // 在途请求完成时静默丢弃
}

bool TtsClient::isIdle() const
{
    return m_currentText.isEmpty() && m_queue.isEmpty();
}

void TtsClient::sendNext()
{
    if (m_shuttingDown)
        return;
    if (!m_currentText.isEmpty() || m_queue.isEmpty())
        return;
    if (!ensureRunning())
        return; // 启动失败：失败信号已发出，队列留待下次调用
    const QPair<QString, QString> item = m_queue.dequeue();
    m_currentText = item.first;
    m_currentEngine = item.second;
    const QJsonObject obj{{QStringLiteral("text"), m_currentText},
                          {QStringLiteral("engine"), m_currentEngine}};
    m_proc.write(QJsonDocument(obj).toJson(QJsonDocument::Compact) + '\n');
}

void TtsClient::onStdoutReady()
{
    if (m_shuttingDown)
        return;
    m_buf += m_proc.readAllStandardOutput();
    int idx;
    while ((idx = m_buf.indexOf('\n')) >= 0) {
        const QByteArray line = m_buf.left(idx).trimmed();
        m_buf.remove(0, idx + 1);
        if (line.isEmpty())
            continue;
        const QJsonObject obj = QJsonDocument::fromJson(line).object();
        const QString type = obj.value(QStringLiteral("type")).toString();
        const QString text = m_currentText;
        if (type == QLatin1String("done")) {
            if (m_cancelCurrent) {
                // 打断丢弃的在途结果：桥接已把文件写入磁盘，这里负责删除，避免残留
                QFile::remove(obj.value(QStringLiteral("wav")).toString());
                QFile::remove(obj.value(QStringLiteral("pcm16k")).toString());
            } else {
                emit synthesized(obj.value(QStringLiteral("wav")).toString(),
                                 obj.value(QStringLiteral("pcm16k")).toString(),
                                 text, m_currentEngine,
                                 obj.value(QStringLiteral("seconds")).toDouble(),
                                 obj.value(QStringLiteral("rtf")).toDouble());
            }
            m_cancelCurrent = false;
            m_currentText.clear();
            m_currentEngine.clear();
            sendNext();
            if (isIdle())
                emit becameIdle();
        } else if (type == QLatin1String("error")) {
            if (!m_cancelCurrent)
                emit failed(text, obj.value(QStringLiteral("message")).toString());
            m_cancelCurrent = false;
            m_currentText.clear();
            m_currentEngine.clear();
            sendNext();
            if (isIdle())
                emit becameIdle();
        }
    }
}

void TtsClient::onStderrReady()
{
    // 及时排空 stderr（模型告警等），否则管道写满会阻塞桥接进程
    if (!m_shuttingDown)
        qDebug().noquote() << QString::fromUtf8(m_proc.readAllStandardError()).trimmed();
}

void TtsClient::onProcessError(QProcess::ProcessError error)
{
    if (m_shuttingDown)
        return; // 析构关闭期间不向上层发信号（上层窗口可能已开始析构）
    Q_UNUSED(error);
    if (!m_currentText.isEmpty()) {
        emit failed(m_currentText, QStringLiteral("TTS 桥接进程中断"));
        m_currentText.clear();
        m_currentEngine.clear();
        sendNext(); // 下次会自动重启进程
        if (isIdle())
            emit becameIdle();
    }
}

void TtsClient::onProcessFinished(int exitCode, QProcess::ExitStatus exitStatus)
{
    if (m_shuttingDown)
        return; // 同上：析构期间 kill 触发的 finished 不再转发
    Q_UNUSED(exitCode);
    Q_UNUSED(exitStatus);
    if (!m_currentText.isEmpty()) {
        emit failed(m_currentText, QStringLiteral("TTS 桥接进程已退出"));
        m_currentText.clear();
        m_currentEngine.clear();
        sendNext();
        if (isIdle())
            emit becameIdle();
    }
}

bool TtsClient::ensureRunning()
{
    if (m_proc.state() == QProcess::Running)
        return true;
    m_proc.start(QString::fromUtf8(TTS_PYTHON_PATH),
                 {QString::fromUtf8(TTS_SCRIPT_PATH)});
    return m_proc.waitForStarted(5000);
}
