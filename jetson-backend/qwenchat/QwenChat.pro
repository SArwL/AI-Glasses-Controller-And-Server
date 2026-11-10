QT += core gui widgets network

TARGET = QwenChat
TEMPLATE = app

CONFIG += c++11
DEFINES += QT_DEPRECATED_WARNINGS

# Python 桥接脚本的绝对路径（本地部署，写死路径即可；部署到其他机器时改这里）
DEFINES += PYTHON_CLIENT_PATH=\\\"/mnt/ssd/jetson/qt_workspace/QwenChat/python/qwen_client.py\\\"

# UDP 接收器落盘目录（设计文档 §5 的 received/）
DEFINES += RECEIVED_DIR=\\\"/mnt/ssd/jetson/qt_workspace/QwenChat/received\\\"

# SenseVoice ASR 桥接（/mnt/ssd/jetson/asr 下已部署）
DEFINES += ASR_PYTHON_PATH=\\\"/mnt/ssd/jetson/asr/venv/bin/python\\\"
DEFINES += ASR_SCRIPT_PATH=\\\"/mnt/ssd/jetson/asr/run_asr.py\\\"

# sherpa-onnx TTS 桥接（/mnt/ssd/jetson/tts 下已部署，默认引擎 Matcha-zh）
DEFINES += TTS_PYTHON_PATH=\\\"/mnt/ssd/jetson/asr/venv/bin/python\\\"
DEFINES += TTS_SCRIPT_PATH=\\\"/mnt/ssd/jetson/tts/run_tts.py\\\"

SOURCES += \
    main.cpp \
    chatwindow.cpp \
    udpreceiver.cpp \
    asrclient.cpp \
    ttsclient.cpp

HEADERS += \
    chatwindow.h \
    udpreceiver.h \
    asrclient.h \
    ttsclient.h
