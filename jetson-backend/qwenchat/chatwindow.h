#ifndef CHATWINDOW_H
#define CHATWINDOW_H

#include <QMainWindow>
#include <QProcess>
#include <QQueue>
#include <QStringList>
#include <QVector>

QT_BEGIN_NAMESPACE
class QLineEdit;
class QPushButton;
class QLabel;
class QScrollArea;
class QVBoxLayout;
class QCheckBox;
class QUdpSocket;
class QTimer;
QT_END_NAMESPACE
class UdpReceiver;
class AsrClient;
class TtsClient;

// 与本地 Qwen 大模型对话的主窗口（微信风格气泡界面）
class ChatWindow : public QMainWindow
{
    Q_OBJECT

public:
    explicit ChatWindow(QWidget *parent = nullptr);
    ~ChatWindow() override;

protected:
    void resizeEvent(QResizeEvent *event) override;

private slots:
    void sendMessage();
    void clearChat();
    void onStdoutReady();
    void onProcessError(QProcess::ProcessError error);
    void onProcessFinished(int exitCode, QProcess::ExitStatus exitStatus);

private:
    void requestLlmReply(const QString &text); // 送 LLM 一条请求（手动输入与语音识别共用）
    void pumpVoiceQueue();                     // 模型空闲时发送排队中的语音文本
    void feedSpeech(const QString &token);   // 流式 token 按标点切句喂给 TTS（Matcha）
    void flushSpeech();                      // 回复结束：播报剩余未成句的文本
    void speakSentence(const QString &text); // 清理后送 TTS 合成
    void playNextWav();                      // 顺序本地播放（aplay）合成结果
    void abortReplyAudio(const QString &why); // 打断：停发/停播并清理本轮音频文件
    void maybeFinishReplyAudio();             // 本轮音频发送/播放完毕 → 清理文件
    void cleanupReplyAudioFiles();            // 删除本轮产生的 wav/pcm16k
    void addMessageRow(bool isUser, const QString &text);
    void addSystemMessage(const QString &text);
    void appendTokenToCurrentBubble(const QString &text);
    void showError(const QString &msg);
    void setBusy(bool busy);
    bool ensureProcessRunning();
    void scrollToBottom();
    void updateBubbleMaxWidths();
    void runSelftestIfRequested();
    void runUdpProbeTest();      // selftest 阶段 2：模拟 App 握手探测（文档 §8）
    void sendProbe();
    void onProbeAckReady();
    void onProbeTimeout();
    void sendVoicePcmTest();     // selftest 阶段 3：发真实 PCM 验证 ASR 链路

    QScrollArea *m_scroll;
    QWidget *m_msgContainer;
    QVBoxLayout *m_msgLayout;
    QLineEdit *m_inputEdit;
    QCheckBox *m_thinkCheck;   // 勾选=开启思考链（慢但推理强）；不勾选=快速模式
    QCheckBox *m_speakCheck;   // 勾选=LLM 回复自动语音播报（Matcha TTS）
    QPushButton *m_sendBtn;
    QPushButton *m_clearBtn;
    QProcess *m_proc;
    QByteArray m_stdoutBuf;
    QQueue<QString> m_voiceQueue; // 模型忙时排队的语音识别文本（空闲后依次送 LLM）
    QQueue<QString> m_playQueue;  // 待播放的合成 wav（完成顺序=句子顺序）
    QString m_speechBuf;          // 回复流式 token 的播报缓冲（按标点切句）
    QStringList m_replyAudioFiles;    // 本轮回复产生的音频文件（结束后统一清理）
    bool m_replyAudioActive = false;  // 本轮走 UDP 回传（否则本地播放）
    bool m_peerAudioSent = false;     // 本轮已向手机回过音频：打断时需发 0x33 让其停播
    bool m_replyDraining = false;     // LLM 已回复完，等待音频全部送出/播完
    bool m_speechSuppressed = false;  // 被打断后，本轮剩余句子不再播报/回传
    bool m_aplayWarned = false;   // 播放失败只提示一次

    UdpReceiver *m_udp;
    AsrClient *m_asr;
    TtsClient *m_tts = nullptr;
    QProcess *m_aplayProc = nullptr;   // 本地播放进程（aplay），一次播一句
    QUdpSocket *m_testSock = nullptr;  // selftest 用的探测 socket
    QTimer *m_probeTimer = nullptr;    // 800ms 超时定时器
    int m_probeRetry = 0;
    quint64 m_probeTs = 0;
    bool m_probeDone = false;          // 握手已成功，忽略残留超时
    bool m_voiceTestPending = false;   // selftest 阶段 3 等待 ASR→LLM 全链路完成
    bool m_voiceTestExit = false;      // selftest：全链路通过后等 TTS 收尾再退出

    QLabel *m_currentBubble = nullptr;  // 流式输出中的当前气泡
    QVector<QLabel *> m_bubbles;        // 所有气泡（用于自适应宽度）

    bool m_busy = false;
    bool m_gotToken = false;   // 当前回复是否已收到正文
    bool m_selftest = false;
};

#endif // CHATWINDOW_H
