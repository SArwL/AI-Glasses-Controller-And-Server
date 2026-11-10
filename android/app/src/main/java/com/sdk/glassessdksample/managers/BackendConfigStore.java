package com.sdk.glassessdksample.managers;

import android.content.Context;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 后端连接配置的本地持久化（JSON 文件）。
 *
 * 文件位置：&lt;externalFilesDir&gt;/ai_backend_config.json
 * 文件格式：BackendConfig 的 JSON 数组，下标 0 为最近一次使用的配置：
 *
 * <pre>
 * [
 *   { "host": "192.168.0.194", "port": 9527, "lastUsedAt": 1791894000000 },
 *   { "host": "192.168.0.100", "port": 9527, "lastUsedAt": 1791800000000 }
 * ]
 * </pre>
 *
 * 用途：首次进入 AI 连接页需用户手输 IP；之后优先用历史记录里最新的一条自动重连，
 * 失败再回退到手动输入。
 *
 * 线程约定：所有方法均为同步文件 I/O，调用方需自行确保不在主线程执行
 * （配置页在子线程调用，见 {@code AiConnectionActivity}）。
 */
public final class BackendConfigStore {

    private static final String TAG = "BackendConfigStore";

    /** 配置文件名 */
    private static final String FILE_NAME = "ai_backend_config.json";
    /** 历史记录上限，超出后丢弃最旧的条目 */
    private static final int MAX_HISTORY = 10;
    /**
     * 默认 UDP 端口。
     *
     * 选 1050 的原因：大于 1024，属于非特权端口，Jetson 上的服务端**无需 root/sudo**
     * 即可绑定；同时避开了 1024 以下的系统保留区间。
     *
     * 已知背景（不影响使用）：1050 是 IANA 注册端口（服务名 cma / CORBA Management Agent），
     * 且历史上被 MiniCommand 木马使用过，少数安全扫描器会将其标记为可疑端口。
     *
     * 该值只是界面初始值，用户可在连接页随时修改。
     */
    public static final int DEFAULT_PORT = 1050;

    /** JSON 解析/序列化（开启缩进，便于人工查看该配置文件） */
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type LIST_TYPE = new TypeToken<List<BackendConfig>>() {}.getType();

    private BackendConfigStore() {
    }

    /** 配置文件对象（位于应用外部私有目录，与其他媒体文件同级） */
    public static File getConfigFile(Context context) {
        File root = context.getExternalFilesDir("");
        if (root == null) {
            root = context.getFilesDir();
        }
        return new File(root, FILE_NAME);
    }

    /**
     * 读取全部历史配置（按下标顺序，0 为最近使用）。
     * 文件不存在或解析失败时返回空列表，绝不抛异常。
     */
    public static synchronized List<BackendConfig> loadAll(Context context) {
        File file = getConfigFile(context);
        if (!file.exists()) {
            Log.i(TAG, "配置文件不存在，返回空历史: " + file.getAbsolutePath());
            return new ArrayList<>();
        }
        try (FileInputStream fis = new FileInputStream(file);
             InputStreamReader reader = new InputStreamReader(fis, StandardCharsets.UTF_8)) {
            List<BackendConfig> list = GSON.fromJson(reader, LIST_TYPE);
            if (list == null) return new ArrayList<>();
            // 过滤掉字段缺失的脏数据（例如手工编辑损坏的条目）
            List<BackendConfig> valid = new ArrayList<>();
            for (BackendConfig c : list) {
                if (c != null && c.getHost() != null && !c.getHost().isEmpty() && c.getPort() > 0) {
                    valid.add(c);
                }
            }
            Log.i(TAG, "已加载 " + valid.size() + " 条历史配置");
            return valid;
        } catch (Exception e) {
            Log.e(TAG, "配置文件解析失败，按空历史处理", e);
            return new ArrayList<>();
        }
    }

    /**
     * 读取最近一次使用的配置；无历史时返回 null。
     */
    public static synchronized BackendConfig loadLatest(Context context) {
        List<BackendConfig> all = loadAll(context);
        return all.isEmpty() ? null : all.get(0);
    }

    /**
     * 记录一条成功使用的配置：写入历史、置顶、更新使用时间，并持久化。
     *
     * @return 持久化后的完整历史列表（下标 0 为本次记录）
     */
    public static synchronized List<BackendConfig> save(Context context, String host, int port) {
        List<BackendConfig> all = loadAll(context);

        // 去重：同一 host:port 的旧记录先移除，稍后以最新时间重新置顶
        String key = host + ":" + port;
        all.removeIf(c -> key.equals(c.key()));

        all.add(0, new BackendConfig(host, port, System.currentTimeMillis()));

        // 截断到上限
        if (all.size() > MAX_HISTORY) {
            all = new ArrayList<>(all.subList(0, MAX_HISTORY));
        }

        persist(context, all);
        return all;
    }

    /** 清空全部历史配置（调试用）。 */
    public static synchronized void clear(Context context) {
        persist(context, Collections.emptyList());
        Log.i(TAG, "历史配置已清空");
    }

    /** 将列表写回文件（覆盖写）。 */
    private static void persist(Context context, List<BackendConfig> list) {
        File file = getConfigFile(context);
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        try (FileOutputStream fos = new FileOutputStream(file, false);
             OutputStreamWriter writer = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
            GSON.toJson(list, LIST_TYPE, writer);
            writer.flush();
            Log.i(TAG, "配置已保存: " + file.getAbsolutePath() + "（" + list.size() + " 条）");
        } catch (IOException e) {
            Log.e(TAG, "配置写入失败: " + file.getAbsolutePath(), e);
        }
    }
}
