package com.sdk.glassessdksample.managers;

/**
 * AI 后端服务的一份连接配置（历史记录条目）。
 *
 * 以 JSON 数组的形式持久化在本地，由 {@link BackendConfigStore} 读写。
 * 数组下标 0 为"最近一次成功使用的配置"，App 启动时可优先取用。
 */
public class BackendConfig {

    /** 后端主机地址（IPv4 字面量，如 192.168.0.194） */
    private String host;
    /** 后端 UDP 端口 */
    private int port;
    /** 最近一次使用时间（epoch millis），用于历史排序 */
    private long lastUsedAt;

    /** Gson 反序列化需要无参构造 */
    public BackendConfig() {
    }

    public BackendConfig(String host, int port, long lastUsedAt) {
        this.host = host;
        this.port = port;
        this.lastUsedAt = lastUsedAt;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public long getLastUsedAt() {
        return lastUsedAt;
    }

    public void setLastUsedAt(long lastUsedAt) {
        this.lastUsedAt = lastUsedAt;
    }

    /** 用于界面展示与去重比较：host:port */
    public String key() {
        return host + ":" + port;
    }
}
