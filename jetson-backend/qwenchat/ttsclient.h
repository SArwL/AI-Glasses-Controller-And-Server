#ifndef TTSCLIENT_H
#define TTSCLIENT_H

#include <QObject>
#include <QPair>
#include <QProcess>
#include <QQueue>

// sherpa-onnx TTS 桥接客户端（默认引擎 Matcha-zh，速度优先）
// 经常驻进程 /mnt/ssd/jetson/tts/run_tts.py 通信（JSON-lines，与 run_asr.py 同风格）
// 单条流水线：忙时自动排队，完成顺序即入队顺序（上层可据此顺序播放）
class TtsClient : public QObject
{
    Q_OBJECT

public:
    explicit TtsClient(QObject *parent = nullptr);
    ~TtsClient() override;

    // 入队一段文本进行合成（生成 wav 文件，不负责播放）
    // engine: "matcha"（中文/默认）或 "kokoro"（英文段）
    void speak(const QString &text, const QString &engine = QStringLiteral("matcha"));
    // 提前拉起桥接进程（预载双引擎），可随时调用
    void warmUp();
    // 打断：清空排队请求；在途请求完成后静默丢弃（不发 synthesized）
    void cancelAll();
    bool isIdle() const; // 无排队且无在途请求

signals:
    // 合成完成：wavPath 为原采样率 wav（本地播放用）；
    // pcm16kPath 为 16k 单声道裸 PCM（UDP 回传用）
    void synthesized(const QString &wavPath, const QString &pcm16kPath, const QString &text,
                     const QString &engine, double seconds, double rtf);
    void failed(const QString &text, const QString &message);
    // 队列清空且无在途请求（本轮合成全部结束）
    void becameIdle();

private slots:
    void onStdoutReady();
    void onStderrReady();
    void onProcessError(QProcess::ProcessError error);
    void onProcessFinished(int exitCode, QProcess::ExitStatus exitStatus);

private:
    bool ensureRunning();
    void sendNext();

    QProcess m_proc;
    QByteArray m_buf;
    QQueue<QPair<QString, QString>> m_queue; // (文本, 引擎)，FIFO 保证完成顺序=入队顺序
    QString m_currentText;    // 正在合成的文本（空=空闲）
    QString m_currentEngine;  // 当前请求使用的引擎
    bool m_cancelCurrent = false; // 在途请求结果静默丢弃（打断）
    bool m_shuttingDown = false;
};

#endif // TTSCLIENT_H
