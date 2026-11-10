#include "chatwindow.h"

#include <QApplication>
#include <QDebug>

int main(int argc, char *argv[])
{
    QApplication app(argc, argv);

    ChatWindow w;
    w.show();

    int code = app.exec();

    // --selftest 模式：offscreen 下自动发一条消息并退出，用于自动化验证
    if (QCoreApplication::arguments().contains(QStringLiteral("--selftest")))
        qInfo().noquote() << (code == 0 ? "SELFTEST_OK" : "SELFTEST_FAIL")
                          << "code=" << code;

    return code;
}
