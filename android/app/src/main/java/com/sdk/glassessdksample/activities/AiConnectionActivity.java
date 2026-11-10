package com.sdk.glassessdksample.activities;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.sdk.glassessdksample.R;
import com.sdk.glassessdksample.managers.AiUdpManager;
import com.sdk.glassessdksample.managers.BackendConfig;
import com.sdk.glassessdksample.managers.BackendConfigStore;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * AI 交互后端的连接页面。
 *
 * <h3>交互流程</h3>
 * <pre>
 * 进入页面
 *   ├─ 本地存在历史 IP → 自动尝试用最近一条连接
 *   │     ├─ 成功 → 保存配置 → 提示成功（可返回主界面）
 *   │     └─ 失败 → 清空输入框，提示用户手动输入
 *   └─ 无历史 IP     → 提示用户手动输入
 *
 * 用户点「连接」→ 校验格式 → UDP 握手探测 → 成功则写入历史（置顶）
 * </pre>
 *
 * 连接成功后 UDP socket 由 {@link AiUdpManager} 单例持有，离开本页不会断开，
 * 主界面通过 {@code AiUdpManager.getInstance().isConnected()} 读取状态。
 *
 * @see AiUdpManager 握手协议与收发实现
 * @see BackendConfigStore 历史地址的 JSON 持久化
 */
public class AiConnectionActivity extends AppCompatActivity {

    private static final String TAG = "AiConnectionActivity";

    /** 日志最多保留的行数，超出后截断头部避免 TextView 无限增长 */
    private static final int MAX_LOG_LINES = 120;

    private EditText etHost;
    private EditText etPort;
    private Button btnConnect;
    private Button btnDisconnect;
    private CheckBox cbSkipHandshake;
    private TextView tvStatus;
    private TextView tvLog;
    private LinearLayout llHistory;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
    private final StringBuilder logBuffer = new StringBuilder();

    /** 本次是否正处于"自动尝试历史地址"阶段——用于决定失败后的文案 */
    private boolean autoConnectingFromHistory = false;

    private final AiUdpManager.Listener udpListener = new AiUdpManager.Listener() {
        @Override
        public void onConnected(String host, int port, boolean verified) {
            autoConnectingFromHistory = false;
            setStatus(verified ? "● 已连接" : "● 已连接（未校验）",
                    verified ? "#2E7D32" : "#F57C00");
            btnConnect.setEnabled(true);
            btnDisconnect.setEnabled(true);
            cbSkipHandshake.setEnabled(false);

            appendLog("连接成功: " + host + ":" + port
                    + (verified ? "（握手校验通过）" : "（跳过握手校验）"));

            // 仅在真正连上时才写入历史，避免把连不通的地址也存进去
            new Thread(() -> {
                List<BackendConfig> all = BackendConfigStore.save(
                        getApplicationContext(), host, port);
                mainHandler.post(() -> renderHistory(all));
            }, "ai-cfg-save").start();

            Toast.makeText(AiConnectionActivity.this,
                    "后端已连接: " + host + ":" + port, Toast.LENGTH_SHORT).show();
        }

        @Override
        public void onConnectFailed(String host, int port, String reason) {
            appendLog("连接失败: " + host + ":" + port + " — " + reason);
            setStatus("● 连接失败", "#D32F2F");
            btnConnect.setEnabled(true);
            cbSkipHandshake.setEnabled(true);

            if (autoConnectingFromHistory) {
                // 历史地址失效：清空输入框，交回用户手动输入
                autoConnectingFromHistory = false;
                etHost.setText("");
                etHost.requestFocus();
                appendLog("历史地址不可用，请手动输入新的 IP 地址");
                Toast.makeText(AiConnectionActivity.this,
                        "历史地址不可用，请手动输入 IP", Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(AiConnectionActivity.this,
                        "连接失败: " + reason, Toast.LENGTH_LONG).show();
            }
        }

        @Override
        public void onDisconnected(String reason) {
            setStatus("● 未连接", "#888888");
            btnConnect.setEnabled(true);
            btnDisconnect.setEnabled(false);
            cbSkipHandshake.setEnabled(true);
            appendLog("连接已断开: " + reason);
        }

        @Override
        public void onDataReceived(byte[] data, String fromHost, int fromPort) {
            appendLog("收到数据 " + data.length + " 字节 (来自 " + fromHost + ":" + fromPort + ")");
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ai_connection);

        initViews();

        AiUdpManager.getInstance().addListener(udpListener);

        // 若已有连接（例如从主界面重复进入），直接反映现状
        if (AiUdpManager.getInstance().isConnected()) {
            String host = AiUdpManager.getInstance().getTargetHost();
            int port = AiUdpManager.getInstance().getTargetPort();
            etHost.setText(host);
            etPort.setText(String.valueOf(port));
            setStatus(AiUdpManager.getInstance().isVerified() ? "● 已连接" : "● 已连接（未校验）",
                    AiUdpManager.getInstance().isVerified() ? "#2E7D32" : "#F57C00");
            btnDisconnect.setEnabled(true);
            appendLog("当前已连接: " + host + ":" + port);
            loadHistoryAsync();
            return;
        }

        loadHistoryAsync();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 只注销本页自己的监听器（防止单例持有 Activity 造成泄漏）；
        // 连接本身以及其它监听器（如回传音频）由各自持有者管理，不能在这里清空
        AiUdpManager.getInstance().removeListener(udpListener);
    }

    // ==================== 初始化 ====================

    private void initViews() {
        etHost = findViewById(R.id.etHost);
        etPort = findViewById(R.id.etPort);
        btnConnect = findViewById(R.id.btnConnect);
        btnDisconnect = findViewById(R.id.btnDisconnect);
        cbSkipHandshake = findViewById(R.id.cbSkipHandshake);
        tvStatus = findViewById(R.id.tvStatus);
        tvLog = findViewById(R.id.tvLog);
        llHistory = findViewById(R.id.llHistory);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        btnConnect.setOnClickListener(v -> connectFromInput());
        btnDisconnect.setOnClickListener(v -> AiUdpManager.getInstance().disconnect());

        etPort.setText(String.valueOf(BackendConfigStore.DEFAULT_PORT));
    }

    /**
     * 后台读取历史配置：渲染列表，并在存在历史地址时自动尝试连接。
     */
    private void loadHistoryAsync() {
        new Thread(() -> {
            List<BackendConfig> all = BackendConfigStore.loadAll(getApplicationContext());
            mainHandler.post(() -> {
                renderHistory(all);
                if (all.isEmpty()) {
                    appendLog("无历史地址，请手动输入后端 IP");
                    return;
                }
                BackendConfig latest = all.get(0);
                etHost.setText(latest.getHost());
                etPort.setText(String.valueOf(latest.getPort()));
                appendLog("发现历史地址 " + latest.key() + "，自动尝试连接…");
                tryConnect(latest.getHost(), latest.getPort(), true);
            });
        }, "ai-cfg-load").start();
    }

    // ==================== 连接 ====================

    /** 用户点「连接」按钮：校验输入后发起连接 */
    private void connectFromInput() {
        String host = etHost.getText().toString().trim();
        String portText = etPort.getText().toString().trim();
        // 端口留空时用默认端口，而不是解析失败
        int port = TextUtils.isEmpty(portText)
                ? BackendConfigStore.DEFAULT_PORT
                : parsePort(portText);
        if (port < 0) {
            Toast.makeText(this, "端口应为 1~65535 之间的整数", Toast.LENGTH_SHORT).show();
            return;
        }
        tryConnect(host, port, false);
    }

    /**
     * 发起连接。
     *
     * @param fromHistory true 表示这是"自动尝试历史地址"，失败后走清空回退流程
     */
    private void tryConnect(String host, int port, boolean fromHistory) {
        String formatError = AiUdpManager.validateAddress(host, port);
        if (formatError != null) {
            appendLog("地址校验失败: " + formatError);
            Toast.makeText(this, formatError, Toast.LENGTH_SHORT).show();
            if (!fromHistory) {
                etHost.requestFocus();
            }
            return;
        }

        autoConnectingFromHistory = fromHistory;
        setStatus("● 连接中…", "#F57C00");
        btnConnect.setEnabled(false);
        btnDisconnect.setEnabled(false);
        cbSkipHandshake.setEnabled(false);

        boolean skip = cbSkipHandshake.isChecked();
        appendLog("正在连接 " + host + ":" + port
                + (skip ? "（跳过握手校验）" : "，握手探测中…"));

        AiUdpManager.getInstance().connect(host, port, skip);
    }

    /** 解析端口文本，非法返回 -1（空串由调用方处理为默认端口） */
    private int parsePort(String text) {
        try {
            int p = Integer.parseInt(text);
            return (p >= 1 && p <= 65535) ? p : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // ==================== 界面渲染 ====================

    /** 渲染历史地址列表，点击任一条目即填入输入框 */
    private void renderHistory(List<BackendConfig> all) {
        llHistory.removeAllViews();
        if (all == null || all.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("暂无历史记录");
            empty.setTextSize(13f);
            empty.setTextColor(0xFF999999);
            empty.setPadding(8, 8, 8, 8);
            llHistory.addView(empty);
            return;
        }
        for (BackendConfig c : all) {
            TextView item = new TextView(this);
            item.setText("• " + c.key());
            item.setTextSize(14f);
            item.setTextColor(ContextCompat.getColor(this, android.R.color.holo_blue_dark));
            item.setPadding(8, 10, 8, 10);
            item.setGravity(Gravity.START);
            item.setOnClickListener(v -> {
                etHost.setText(c.getHost());
                etPort.setText(String.valueOf(c.getPort()));
                appendLog("已选择历史地址 " + c.key());
            });
            llHistory.addView(item);
        }
    }

    /** 更新顶部状态文本与颜色 */
    private void setStatus(String text, String colorHex) {
        tvStatus.setText(text);
        try {
            tvStatus.setTextColor(android.graphics.Color.parseColor(colorHex));
        } catch (IllegalArgumentException ignored) {
            tvStatus.setTextColor(0xFF888888);
        }
    }

    /** 追加一行带时间戳的连接日志 */
    private void appendLog(String message) {
        String line = timeFormat.format(new Date()) + "  " + message + "\n";
        logBuffer.append(line);

        // 超出上限时丢弃最早的若干行
        int lineCount = 0;
        for (int i = 0; i < logBuffer.length(); i++) {
            if (logBuffer.charAt(i) == '\n') lineCount++;
        }
        if (lineCount > MAX_LOG_LINES) {
            int drop = lineCount - MAX_LOG_LINES;
            int idx = 0;
            for (int i = 0; i < drop; i++) {
                int nl = logBuffer.indexOf("\n", idx);
                if (nl < 0) break;
                idx = nl + 1;
            }
            logBuffer.delete(0, idx);
        }

        tvLog.setText(logBuffer.toString());
        Log.i(TAG, message);
    }
}
