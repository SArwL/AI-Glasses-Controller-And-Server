"""生成AI眼镜BLE SDK示例项目开发日志 .docx"""
from docx import Document
from docx.shared import Inches, Pt, Cm, RGBColor
from docx.enum.text import WD_ALIGN_PARAGRAPH
from docx.enum.style import WD_STYLE_TYPE
import datetime

doc = Document()

# ===== 样式设置 =====
style = doc.styles['Normal']
style.font.name = '微软雅黑'
style.font.size = Pt(10.5)
style.paragraph_format.space_after = Pt(4)
style.paragraph_format.line_spacing = 1.35

for level in range(1, 4):
    h = doc.styles[f'Heading {level}']
    h.font.name = '微软雅黑'
    h.font.color.rgb = RGBColor(0x1A, 0x1A, 0x1A)

# ===== 封面 =====
doc.add_paragraph()
doc.add_paragraph()
title = doc.add_paragraph()
title.alignment = WD_ALIGN_PARAGRAPH.CENTER
run = title.add_run('AI眼镜 BLE SDK 示例项目\n开发日志')
run.font.size = Pt(26)
run.font.bold = True
run.font.color.rgb = RGBColor(0x1A, 0x56, 0xDB)

doc.add_paragraph()
subtitle = doc.add_paragraph()
subtitle.alignment = WD_ALIGN_PARAGRAPH.CENTER
run = subtitle.add_run('新视海AI眼镜 (CY 01_0799) · QC GLASSES SDK\n'
                       f'文档生成日期: {datetime.date.today()}')
run.font.size = Pt(12)
run.font.color.rgb = RGBColor(0x66, 0x66, 0x66)

doc.add_page_break()

# ===== 目录页 =====
doc.add_heading('目录', level=1)
toc_items = [
    '1. 项目概述',
    '2. 问题一：BLE连接后UI不刷新',
    '3. 问题二：glassesControl/WiFi回调不工作',
    '4. 问题三：WiFi同步完成后眼镜卡传输状态',
    '5. 问题四：首次连接无法识别设备名称文件夹',
    '6. 问题五：智能拍照缩略图无法解码',
    '7. 问题六：录音失败与音频文件损坏',
    '8. 问题七：AI对话PCM音频帧处理',
    '9. 问题八：BT经典蓝牙A2DP配对',
    '10. 问题九：连接重复Toast与电量不显示',
    '11. 功能实现：媒体存储架构',
    '12. 功能实现：照片图库与查看器',
    '13. 核心经验总结',
]
for item in toc_items:
    p = doc.add_paragraph(item)
    p.paragraph_format.space_after = Pt(2)

doc.add_page_break()

# ============== 辅助函数 ==============
def add_problem_section(number, title, problem, root_cause, solution, code_changes=None, lesson=''):
    doc.add_heading(f'{number}. {title}', level=1)

    doc.add_heading('问题描述', level=2)
    doc.add_paragraph(problem)

    doc.add_heading('根因分析', level=2)
    doc.add_paragraph(root_cause)

    doc.add_heading('解决方案', level=2)
    doc.add_paragraph(solution)

    if code_changes:
        doc.add_heading('关键代码变更', level=2)
        for change in code_changes:
            doc.add_paragraph(change, style='List Bullet')

    if lesson:
        doc.add_heading('经验教训', level=2)
        doc.add_paragraph(lesson)

    doc.add_page_break()

# ===== 1. 项目概述 =====
doc.add_heading('1. 项目概述', level=1)

doc.add_paragraph(
    '本项目为深圳QC无线科技GLASSES SDK的Android示例应用，用于与新视海AI眼镜(CY 01_0799, '
    'MAC: 79:6D:0B:7D:07:99)进行BLE通信、WiFi Direct文件传输、拍照/录音控制及AI对话音频采集。'
)

doc.add_heading('技术栈', level=2)
tech_items = [
    '开发语言: Java (Android)',
    'SDK: LIB_GLASSES_SDK-release_3.aar (com.glasses.sdk)',
    '芯片方案: 杰理(JieLi) BLE芯片, Nordic UART Service',
    '通信方式: BLE (控制指令) + WiFi Direct (文件传输/音频流)',
    '音频编解码: 杰理OPUS库 (libjl_opus.so)',
    '事件总线: EventBus (greenrobot)',
    '图片加载: Glide 4.16.0',
    'UI组件: ViewPager2, RecyclerView, GridLayoutManager',
]
for item in tech_items:
    doc.add_paragraph(item, style='List Bullet')

doc.add_heading('核心文件结构', level=2)
files = [
    'MainActivity.java — 主控界面：连接管理、拍照/录音、音频播放、AI对话处理',
    'DeviceScanActivity.java — BLE设备扫描与连接',
    'GalleryActivity.java — 照片图库（3列缩略图网格）',
    'PhotoViewerActivity.java — 照片查看器（ViewPager2翻页、删除、保存）',
    'GlassApp.java — Application：BLE初始化、设备目录管理、SharedPreferences',
    'BluetoothReceiver.java — 系统蓝牙广播（ACL/经典蓝牙配对）',
    'MyBluetoothReceiver.java — SDK本地广播（GATT状态）',
    'BluetoothEvent.java / BtPairEvent.java — EventBus事件类',
    'PcmUtils.java — PCM→WAV转换工具',
]
for f in files:
    doc.add_paragraph(f, style='List Bullet')

doc.add_page_break()

# ===== 2. BLE连接UI不刷新 =====
add_problem_section(
    '2', 'BLE连接后UI不刷新',

    '蓝牙连接成功后，MainActivity页面不自动刷新，仍显示"未连接"状态。'
    '需手动切换页面再返回才能看到"已连接"。',

    'BLE连接分为两层：ACL硬件层和GATT服务层。'
    'BluetoothReceiver监听系统ACTION_ACL_CONNECTED广播时仅ACL层就绪，'
    '此时BleOperateManager.isConnected()仍返回false。'
    'SDK通过LocalBroadcastManager广播真正的GATT连接状态时，'
    '原MyBluetoothReceiver只打印日志未通知UI。'
    '此外，BluetoothEvent从两个渠道各发一次（MyBluetoothReceiver + BluetoothReceiver），'
    '无去重导致重复处理。',

    '1. BluetoothReceiver和MyBluetoothReceiver均添加状态去重标志，仅在状态变化时通过EventBus通知UI\n'
    '2. MainActivity.onBluetoothEvent添加connectionHandled防重入标志\n'
    '3. 连接后延迟1.5秒执行initGlasses和updateConnectionStatus，确保GATT完全就绪\n'
    '4. DeviceScanActivity同样添加connectionHandled去重，消除重复"设备连接成功"Toast',

    ['BluetoothReceiver.java: 添加lastAclConnected状态去重',
     'MyBluetoothReceiver.java: 添加lastConnectedState + isConnected()主动检查',
     'MainActivity.java: 添加connectionHandled + 1.5s postDelayed',
     'DeviceScanActivity.java: 添加connectionHandled'],

    'BLE的双层连接架构(ACL→GATT)是根本原因。任何依赖"已连接"状态的逻辑都必须等GATT层就绪，'
    '仅靠ACL广播不足以判断。'
)

# ===== 3. glassesControl回调 =====
add_problem_section(
    '3', 'glassesControl/WiFi回调不工作',

    'glassesControl()发送拍照、录音等指令后无回调响应，WiFi同步功能完全无法使用。'
    '初步怀疑SDK与眼镜硬件协议不兼容。',

    '最终确认SDK与硬件在应用层协议上是兼容的，问题由两个原因叠加：\n'
    '① BLE写门控：SDK的execute()方法要求ready=true才执行写请求，但SDK内部不调用setReady(true)。'
    '需在MyBluetoothReceiver收到SERVICE_DISCOVERED时手动调用setReady(true), initEnable(), packageLength()解锁。\n'
    '② WiFi P2P接口冲突：手机连接着WiFi热点时，WiFi芯片不支持STA+P2P并发模式。'
    '同步前需断开手机WiFi热点连接（但保持WiFi开关打开）。',

    '1. 在MyBluetoothReceiver的SERVICE_DISCOVERED广播中调用：\n'
    '   - LargeDataHandler.getInstance().setReady(true)\n'
    '   - LargeDataHandler.getInstance().initEnable()\n'
    '   - LargeDataHandler.getInstance().packageLength()\n'
    '2. 同步前提示用户断开手机WiFi热点连接\n'
    '3. BLE event 0x0b（每3秒一次）是SDK内部心跳，不要误判为协议错误',

    ['MyBluetoothReceiver.java: SERVICE_DISCOVERED时调用三个初始化函数',
     'MainActivity.java: 同步前WiFi权限检查'],

    '不应该过早下"协议不兼容"结论。很多"不兼容"问题本质是初始化步骤缺失。'
    '此外SDK文档不完善（initEnable等函数未在文档中描述），需要结合AAR反编译和参考App分析来补全知识。'
)

# ===== 4. WiFi同步卡传输状态 =====
add_problem_section(
    '4', 'WiFi同步完成后眼镜持续卡在"传输中"状态',

    'WiFi同步所有文件下载完成后，眼镜持续报告workType=4(传输中)约25-60秒，'
    '期间无法进行拍照、录音等操作。',

    'APP在SDK回调中添加了大量"清理"代码：P2P removeGroup()、BLE P2P复位指令、'
    'OkHttp连接池evictAll()、30秒超时定时器、防重入门控等。'
    '这些外部干预破坏了SDK内部的正常退出时序。\n\n'
    '通过对比另一款使用相同SDK的参考App发现：参考App的fileDownloadComplete()回调仅发LocalBroadcast，'
    '不做任何清理操作。SDK的importAlbum()流程是自包含的，内部自动处理P2P连接建立和释放。',

    '彻底精简MainActivity.java的WiFi同步代码：\n'
    '1. 删除isImportingAlbum防重入标记\n'
    '2. 删除syncTimeoutRunnable 30秒超时定时器\n'
    '3. 删除forceExitTransmissionMode() 反射P2P清理（整段）\n'
    '4. 删除closeOkHttpConnections() 连接池清理（整段）\n'
    '5. startAlbumImport()精简为仅一行importAlbum()调用\n'
    '6. 所有WifiFilesDownloadListener回调仅做Toast+日志',

    ['startAlbumImport(): 仅调用GlassesControl.importAlbum()',
     'fileDownloadComplete(): 仅Toast+refreshAudioFileList()',
     'onGlassesFail(): 仅Log+Toast',
     '删除: isImportingAlbum, syncTimeoutRunnable, photoRetryCount等冗余变量',
     '删除: WifiP2pManager/reflect相关import'],

    '★ 核心教训：信任SDK。SDK的回调是通知，不是要求你做清理。'
    'SDK内部流程是自包含的，外部干预只会破坏正常退出时序。'
    '当不确定SDK正确用法时，找参考实现比反复试错更高效。'
)

# ===== 5. 首次连接设备文件夹 =====
add_problem_section(
    '5', '首次连接无法识别设备名称、媒体文件无法保存',

    '眼镜每次首次开机与APP连接时，APP未正确识别眼镜名称文件夹。'
    '媒体文件未放入以设备ID命名的文件夹（如CY 01_0799/）下，'
    '而是在APP根目录下直接创建了picture/video/audio文件夹并存入文件。'
    '第二次连接恢复正常。',

    '问题由两个原因叠加：\n\n'
    '① 时序问题：initWifiListener()在onCreate()中调用，此时设备尚未连接，'
    'currentDeviceName为null。initGlasses()被调用时将SDK下载目录设置为APP根目录。'
    '后续在onBluetoothEvent中虽然重新设置了设备名和initGlasses()，但错误的目录已创建。\n\n'
    '② 设备名获取失败：DeviceManager.getInstance().getDeviceName()在首次连接时返回null。'
    'SDK在首次GATT连接时尚未缓存设备名称，第二次才可用。',

    '综合修复三管齐下：\n\n'
    '① 移除onCreate()中的premature initGlasses()调用 — 只在设备名确认后才初始化\n'
    '② 三级回退获取设备名（resolveAndSetDeviceName）：SDK缓存 → 扫描预存 → SharedPreferences历史\n'
    '③ 设备名确认后立即调用ensureDeviceDirectories()主动创建完整目录结构，不依赖懒加载',

    ['GlassApp.java: 新增setPendingDeviceName(), consumePendingDeviceName(), getLastDeviceName(), ensureDeviceDirectories()',
     'GlassApp.java: getDeviceDir/getPhotoDir等返回null当设备名未知（拒绝降级到根目录）',
     'DeviceScanActivity.java: 连接前调用GlassApp.setPendingDeviceName(info.name)',
     'MainActivity.java: 新增resolveAndSetDeviceName()三级回退方法',
     'MainActivity.java: initWifiListener()不再调用initGlasses()',
     'MainActivity.java: onBluetoothEvent中initGlasses前调用ensureDeviceDirectories()双保险'],

    '懒加载不适合需要"设备名→目录"强绑定的场景。应主动在数据就绪的第一时间创建目录。'
    '多级回退（SDK→扫描→持久化）确保首次连接的鲁棒性。'
)

# ===== 6. 缩略图解码 =====
add_problem_section(
    '6', '智能拍照缩略图无法解码',

    '智能拍照{0x02,0x01,0x06}产生的缩略图数据以A4 5A...开头而非FF D8(JPEG SOI)，'
    '保存的.bin文件无法被图片查看器打开。初步怀疑数据被加密，尝试XOR解密。',

    '缩略图数据从未加密！SDK的getPictureThumbnails回调采用分包传输：'
    'success=false为中间包，success=true为最后一包。'
    '原代码只保存success=true的最后一包数据（100-695字节），'
    '丢弃了包含JPEG头部(FF D8)的前面所有包。\n\n'
    '之前通过4字节XOR密钥[5B,82,72,15]将A4 5A...转为FF D8 FF E0纯属巧合 — '
    '不同传输的"密钥"完全不同（验证了两个文件：5B 82 72 15 vs AA CE 37 DC），'
    '证明数据不是XOR加密而是压缩数据片段。',

    '用ByteArrayOutputStream累积所有分包数据，success=true时一次性保存完整JPEG：\n'
    '1. 新增thumbnailAccumulator (ByteArrayOutputStream)\n'
    '2. success=false时: accumulator.write(data)\n'
    '3. success=true时: accumulator.write(data) → toByteArray() → saveThumbnail(completeData) → close+清空',

    ['MainActivity.java: 新增ByteArrayOutputStream thumbnailAccumulator',
     'case 0x02回调: 改为累积模式，success=true时保存完整数据'],

    '误判"加密"导致多日错误方向。分包传输是SDK的底层机制，必须累积所有数据才能得到完整文件。'
    '数据格式分析应基于字节分布统计而非4字节模式匹配。'
)

# ===== 7. 录音问题 =====
add_problem_section(
    '7', '录音失败与音频文件损坏',

    '① 按下录音按钮后眼镜有提示音，但Toast显示"录音启动失败: -1"\n'
    '② 录音后传输到APP的音频文件无法播放',

    '问题①: -1是response==null时的默认值(int errCode = response!=null? response.getErrorCode() : -1)。'
    'glassesControl({0x02,0x01,0x08})的BLE直接响应可能为null但指令已送达（眼镜有提示音）。'
    '原代码用单一if-else检查所有失败情况（null/dataType不对/errorCode非零），一律显示错误。\n\n'
    '问题②: 眼镜录音原始格式是OPUS而非PCM。下载的文件是OPUS编码但以.pcm扩展名保存。'
    'MediaPlayer无法播放原始OPUS数据（需等SDK的recordingToPcm回调完成解码）。'
    '而且PCM文件没有WAV头，MediaPlayer也无法直接播放原始PCM。',

    '问题①修复: 分离dataType检查和errorCode检查。response为null时乐观更新UI，'
    '通过事件0x03/0x0c确认状态。重连时重新注册device listener + 发送{0x02,0x04}初始查询。\n'
    '问题②修复: 新增PcmUtils.java实现PCM→WAV转换（添加44字节WAV头）。'
    '播放.wav文件前检查是否需要转换。MediaPlayer添加setOnErrorListener处理格式不兼容。',

    ['MainActivity.java: 录音响应处理改为分离dataType/errorCode检查 + 乐观更新',
     'PcmUtils.java: 新增convertPcmToWav()方法（16kHz/单声道/16bit）',
     'MainActivity.java: playAudio()先检查.pcm→.wav转换',
     'MainActivity.java: onBluetoothEvent重连时重新注册listener + 初始查询'],

    'BLE指令的响应通道与拍照不同，response可能为null但指令已生效。'
    '音频格式链：OPUS(眼镜录制) → SDK解码 → PCM → WAV头 → MediaPlayer可播放。'
)

# ===== 8. AI对话PCM =====
add_problem_section(
    '8', 'AI对话模式PCM音频帧处理',

    '触发眼镜AI对话模式(workType=7)后，voiceFromGlasses(pcmData)回调产生大量~1.28KB的.pcm文件，'
    '每次对话产生数百个碎片文件。同时voiceFromGlassesStatus(2)回调延迟数十秒才到达，'
    '无法用于判断对话结束。页面刷新也被Toast弹窗阻塞。',

    '① 碎片文件：voiceFromGlasses是流式回调，每收到一个WiFi Direct音频帧(~1.28KB)触发一次。'
    '原savePcmData()每帧创建独立文件，导致大量碎片。\n'
    '② status(2)延迟：眼镜固件→BLE→SDK链路的数十秒延迟无法从APP端消除。\n'
    '③ Toast阻塞：voiceFromGlassesStatus和事件0x03的Toast在主线程排队（LENGTH_SHORT=2秒），'
    '阻塞了refreshAudioFileList()的执行。',

    '完全绕开SDK的status(2)回调，用文件系统监控自行判断对话结束：\n\n'
    '① 启动: status(1) → 创建audio/temp/目录\n'
    '② 传输: voiceFromGlasses每帧 → 原子序号命名(temp/0000.pcm, 0001.pcm...) → 写入temp\n'
    '③ 监控: 延迟300ms后每100ms轮询temp/文件数\n'
    '④ 结束判断: 300ms无新文件 → 后台线程按序号拼接所有分片 → audio/voice_xxx.pcm → 删除temp\n'
    '⑤ Toast优化: voiceFromGlassesStatus和0x03事件的Toast全部移除，仅在拼接完成时弹一次',

    ['MainActivity.java: 新增startPcmMonitor(), pcmPollLoop(), mergePcmChunks(), cleanupPcmTemp()',
     'MainActivity.java: 成员变量pcmTempDir, pcmChunkSeq(AtomicInteger), pcmLastFileCount等',
     'MainActivity.java: savePcmData()改为存入temp目录，用AtomicInteger防并发冲突',
     'MainActivity.java: voiceFromGlassesStatus和事件0x03的Toast移除',
     '参数: PCM_START_DELAY=300ms, PCM_POLL_INTERVAL=100ms, PCM_SILENCE_MS=300ms'],

    '当SDK回调延迟不可接受时，文件系统监控是可行的绕过方案。'
    'AtomicInteger确保多线程并发写入时序号不冲突。'
    'Toast虽小但会排队阻塞主线程，高频场景必须精简。'
)

# ===== 9. BT A2DP配对 =====
add_problem_section(
    '9', 'BT经典蓝牙A2DP配对',

    '眼镜音频播放需要A2DP经典蓝牙通道，与BLE数据通道是独立的配对过程。'
    '需要实现BLE连接后自动/手动配对。',

    '眼镜的经典蓝牙和BLE使用相同物理芯片（杰理），但配对流程独立。'
    '需要先打开BT经典蓝牙功能，扫描发现设备后匹配地址，'
    '使用SDK的createBondBluetoothJieLi()专用方法配对。',

    '实现双路径配对：\n'
    '① 自动配对: BLE连接成功 → openBT() → 延迟2s → classicBluetoothStartScan() → '
    'ACTION_FOUND → 地址匹配 → createBondBluetoothJieLi()\n'
    '② 手动配对: 用户点击按钮 → startBtPairing() → 同样流程\n'
    '③ 状态反馈: BtPairEvent通过EventBus传递配对状态，UI显示A2DP连接状态',

    ['BluetoothReceiver.java: ACTION_FOUND处理 → 地址匹配 → createBondBluetoothJieLi()',
     'BtPairEvent.java: 新增BT配对状态EventBus事件类',
     'MainActivity.java: startBtPairing(), onBtPairEvent(), checkA2dpConnected(), updateBtStatusDisplay()',
     'activity_main.xml: 新增tvBtStatus + btnBtPair',
     'GlassApp.java: IntentFilter补充ACTION_FOUND + ACTION_STATE_CHANGED'],

    '杰理芯片的经典蓝牙配对需使用SDK专用方法createBondBluetoothJieLi()，'
    '不能用Android标准配对API。地址匹配是连接正确设备的关键。'
)

# ===== 10. Toast + 电量 =====
add_problem_section(
    '10', '连接重复Toast与电量不显示',

    '① 连接时"设备连接成功！"Toast弹出两次\n'
    '② 连接后电量始终不显示\n'
    '③ BLUETOOTH_CONNECT权限Lint警告',

    '① BluetoothEvent(true)从两个独立渠道各发一次：MyBluetoothReceiver(GATT) + BluetoothReceiver(ACL)。'
    'DeviceScanActivity.onBluetoothEvent无去重。\n'
    '② 眼镜只在电量变化时主动推送（事件0x05 / addBatteryCallBack），连接后不会立即上报。\n'
    '③ checkA2dpConnected()的getProfileConnectionState() API 31+需要BLUETOOTH_CONNECT权限。',

    '① DeviceScanActivity添加connectionHandled标志（与MainActivity一致的防重入模式）\n'
    '② 新增updateBattery(battery, charging)统一方法；连接后主动调用syncBattery()；重连时重新注册addBatteryCallBack\n'
    '③ 添加checkSelfPermission(BLUETOOTH_CONNECT)检查 + SecurityException catch兜底',

    ['DeviceScanActivity.java: connectionHandled防重入',
     'MainActivity.java: updateBattery(battery, charging), 多处调用syncBattery()',
     'MainActivity.java: checkA2dpConnected()权限检查 + try-catch'],

    'BLE+经典蓝牙双重连接导致EventBus事件重复是常见陷阱。'
    'SDK的push模型（只在变化时通知）需要APP端主动pull初始化状态。'
)

# ===== 11. 媒体存储架构 =====
doc.add_heading('11. 功能实现：媒体存储架构', level=1)

doc.add_paragraph(
    '实现了按设备名称分文件夹的媒体存储架构，支持多副眼镜独立存储。'
)

doc.add_heading('目录结构', level=2)
doc.add_paragraph(
    '<externalFilesDir>/\n'
    '└── CY 01_0799/           ← DeviceManager.getDeviceName() 作为文件夹名\n'
    '    ├── picture/           ← .jpg/.jpeg/.png\n'
    '    ├── video/             ← .mp4\n'
    '    └── audio/             ← .pcm/.wav/.mp3/.aac'
)

doc.add_heading('核心设计', level=2)
design_points = [
    'SDK通过initGlasses(deviceRootPath)将文件下载到设备根目录',
    '在fileWasDownloadSuccessfully回调中按扩展名移动到picture/video/audio子目录',
    'GlassApp.java: 统一的getDeviceDir/getPhotoDir/getVideoDir/getAudioDir/getAlbumDir',
    'ensureDeviceDirectories(): 设备名确认后立即主动创建完整目录结构',
    '连接时setCurrentDevice(deviceName) → 持久化到SharedPreferences',
]
for point in design_points:
    doc.add_paragraph(point, style='List Bullet')

doc.add_page_break()

# ===== 12. 照片图库 =====
doc.add_heading('12. 功能实现：照片图库与查看器', level=1)

doc.add_paragraph('实现了完整的照片管理和浏览体验。')

doc.add_heading('图库 (GalleryActivity)', level=2)
gallery_features = [
    '3列正方形缩略图网格（屏幕宽/3，GridLayoutManager）',
    'Glide加载+缓存，按修改时间降序排列',
    '无照片时显示空状态提示',
    '点击缩略图 → 启动PhotoViewerActivity',
]
for f in gallery_features:
    doc.add_paragraph(f, style='List Bullet')

doc.add_heading('查看器 (PhotoViewerActivity)', level=2)
viewer_features = [
    'ViewPager2左右滑动翻页，底部位置指示器 "3 / 10"',
    '图片居中适配（ImageView FIT_CENTER + 黑色背景）',
    '左上角半透明返回按钮',
    '删除：删除文件 → 移除列表项 → 更新ViewPager2 → 全部删除后自动返回图库',
    '保存到相册：MediaStore API写入Pictures/GlassesPhotos/，兼容Android 10+',
]
for f in viewer_features:
    doc.add_paragraph(f, style='List Bullet')

doc.add_page_break()

# ===== 13. 核心经验 =====
doc.add_heading('13. 核心经验总结', level=1)

lessons = [
    ('信任SDK，回调只做UI更新',
     'SDK的回调是通知机制，不是要求APP做清理。WiFi同步问题的根源就是"过度干预"——在回调中添加了大量清理代码反而破坏了SDK的正常退出时序。参考App只用一行importAlbum()，回调里只发广播，这才是正确用法。'),

    ('第一次往往不行，需要重试/回退机制',
     '首次连接时SDK未缓存设备名称、首次BLE连接需要延迟等GATT就绪——很多"首次"场景需要额外的等待或重试逻辑。三级回退获取设备名、1.5s延迟执行initGlasses都是这个问题的体现。'),

    ('用参考App验证SDK用法',
     'SDK文档只有8页，很多API（如initEnable、packageLength）未在文档中描述。对比参考App是最高效的学习方式，比反复试错快得多。'),

    ('当SDK回调延迟不可接受时，自己监控文件系统',
     'AI对话PCM处理是最典型的例子：SDK的status(2)回调延迟数十秒，改用文件系统轮询后延迟降到300ms。不一定要依赖SDK的所有回调。'),

    ('Toast虽小但会阻塞主线程',
     '多个高频Toast排队会严重延迟UI刷新。高频场景（如AI对话、文件传输）应精简Toast，仅保留关键节点。'),

    ('分包传输必须累积',
     '缩略图解码问题的教训：不是所有数据包都是完整的文件。SDK底层采用分包传输时，success标志区分中间包和最后一包，APP必须自己累积。'),

    ('目录懒创建不适合关键路径',
     '媒体文件保存等关键路径不能依赖懒加载的mkdirs()。设备名确认后应立即主动创建目录结构（ensureDeviceDirectories）。'),
]

for title, detail in lessons:
    doc.add_heading(title, level=2)
    doc.add_paragraph(detail)

# ===== 保存 =====
output_path = r'D:\学校相关\研究生\实验室\AI眼镜\NewGlasseaSDKSample2\AI眼镜SDK开发日志.docx'
doc.save(output_path)
print(f'文档已保存: {output_path}')
