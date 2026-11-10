#include "asrclient.h"

#include <QJsonDocument>
#include <QJsonObject>

namespace {
const int kDefaultSampleRate = 16000; // 眼镜端 PCM 固定 16kHz（设计文档 §5）
}

AsrClient::AsrClient(QObject *parent)
    : QObject(parent)
{
    m_proc.setProcessChannelMode(QProcess::SeparateChannels);
    connect(&m_proc, &QProcess::readyReadStandardOutput, this, &AsrClient::onStdoutReady);
    connect(&m_proc, &QProcess::errorOccurred, this, &AsrClient::onProcessError);
    connect(&m_proc,
            static_cast<void (QProcess::*)(int, QProcess::ExitStatus)>(&QProcess::finished),
            this, &AsrClient::onProcessFinished);
}

AsrClient::~AsrClient()
{
    // 优雅关闭 ASR 桥接进程（同 ChatWindow 处理 LLM 桥接的方式）。
    // 先置关闭标志：waitForFinished 期间进程残留输出会触发 onStdoutReady，
    // 若不拦截会继续 emit recognized → 上层往已析构中的窗口加 widget → 崩溃
    m_shuttingDown = true;
    if (m_proc.state() == QProcess::Running) {
        m_proc.closeWriteChannel();
        if (!m_proc.waitForFinished(3000)) {
            m_proc.kill();
            m_proc.waitForFinished(3000);
        }
    }
}

void AsrClient::recognize(const QString &filePath)
{
    const bool idle = m_currentFile.isEmpty();
    m_queue.enqueue(filePath);
    if (idle)
        sendNext();
}

void AsrClient::sendNext()
{
    if (m_shuttingDown)
        return;
    if (!m_currentFile.isEmpty() || m_queue.isEmpty())
        return;
    if (!ensureRunning())
        return; // 启动失败：失败信号已发出，队列留待下次调用
    m_currentFile = m_queue.dequeue();
    const QJsonObject obj{{QStringLiteral("path"), m_currentFile},
                          {QStringLiteral("sample_rate"), kDefaultSampleRate}};
    m_proc.write(QJsonDocument(obj).toJson(QJsonDocument::Compact) + '\n');
}

void AsrClient::onStdoutReady()
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
        const QString file = m_currentFile;
        if (type == QLatin1String("done")) {
            emit recognized(file, obj.value(QStringLiteral("text")).toString());
            m_currentFile.clear();
            sendNext();
        } else if (type == QLatin1String("error")) {
            emit failed(file, obj.value(QStringLiteral("message")).toString());
            m_currentFile.clear();
            sendNext();
        }
    }
}

void AsrClient::onProcessError(QProcess::ProcessError error)
{
    if (m_shuttingDown)
        return; // 析构关闭期间不向上层发信号（上层窗口可能已开始析构）
    Q_UNUSED(error);
    if (!m_currentFile.isEmpty()) {
        emit failed(m_currentFile, QStringLiteral("ASR 桥接进程中断"));
        m_currentFile.clear();
        sendNext(); // 下次会自动重启进程
    }
}

void AsrClient::onProcessFinished(int exitCode, QProcess::ExitStatus exitStatus)
{
    if (m_shuttingDown)
        return; // 同上：析构期间 kill 触发的 finished 不再转发
    Q_UNUSED(exitCode);
    Q_UNUSED(exitStatus);
    if (!m_currentFile.isEmpty()) {
        emit failed(m_currentFile, QStringLiteral("ASR 桥接进程已退出"));
        m_currentFile.clear();
        sendNext();
    }
}

bool AsrClient::ensureRunning()
{
    if (m_proc.state() == QProcess::Running)
        return true;
    m_proc.start(QString::fromUtf8(ASR_PYTHON_PATH),
                 {QString::fromUtf8(ASR_SCRIPT_PATH)});
    return m_proc.waitForStarted(5000);
}
