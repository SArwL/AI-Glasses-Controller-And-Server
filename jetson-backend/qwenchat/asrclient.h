#ifndef ASRCLIENT_H
#define ASRCLIENT_H

#include <QObject>
#include <QProcess>
#include <QQueue>

// SenseVoice ASR 桥接客户端
// 经常驻进程 /mnt/ssd/jetson/asr/run_asr.py 通信（JSON-lines，与 qwen_client.py 同风格）
class AsrClient : public QObject
{
    Q_OBJECT

public:
    explicit AsrClient(QObject *parent = nullptr);
    ~AsrClient() override;

    // 入队一个音频文件（.pcm 16kHz / .wav）；单条流水线，忙时自动排队
    void recognize(const QString &filePath);

signals:
    void recognized(const QString &filePath, const QString &text);
    void failed(const QString &filePath, const QString &message);

private slots:
    void onStdoutReady();
    void onProcessError(QProcess::ProcessError error);
    void onProcessFinished(int exitCode, QProcess::ExitStatus exitStatus);

private:
    bool ensureRunning();
    void sendNext();

    QProcess m_proc;
    QByteArray m_buf;
    QQueue<QString> m_queue;
    QString m_currentFile;    // 正在识别的文件（空=空闲）
    bool m_shuttingDown = false;
};

#endif // ASRCLIENT_H
