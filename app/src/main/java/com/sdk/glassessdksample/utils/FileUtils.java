package com.sdk.glassessdksample.utils;

import android.util.Log;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * 通用文件工具类，从 MainActivity 提取。
 * 提供字节数组落盘、十六进制格式化、目录清理等静态工具方法。
 * 构造方法私有化，禁止实例化（纯静态工具类）。
 */
public final class FileUtils {

    private static final String TAG = "FileUtils";

    private FileUtils() {}

    /**
     * 将字节数据写入文件（覆盖模式）。
     */
    public static void writeToFile(byte[] data, String filePath) {
        // 确保目标文件的父目录存在，不存在则递归创建
        File dir = new File(filePath).getParentFile();
        if (dir != null && !dir.exists()) {
            dir.mkdirs();
        }
        // false 表示覆盖写入；try-with-resources 自动关闭输出流
        try (BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(filePath, false))) {
            bos.write(data);
        } catch (IOException e) {
            Log.e(TAG, "文件写入失败: " + filePath, e);
        }
    }

    /**
     * 字节数组转十六进制字符串（调试用）。
     */
    public static String bytesToHex(byte[] bytes) {
        if (bytes == null) return "null";
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            // 每个字节格式化为两位大写十六进制；&0xFF 消除负数符号位的扩展
            sb.append(String.format("%02X ", b & 0xFF));
        }
        // 去掉末尾多余的空格
        return sb.toString().trim();
    }

    /**
     * 递归删除目录及其内容。
     */
    public static void cleanupDir(File dir) {
        if (dir == null || !dir.exists()) return;
        File[] files = dir.listFiles();
        if (files != null) {
            // 先删除目录下的所有子文件/子目录
            for (File f : files) {
                f.delete();
            }
        }
        // 再删除目录本身（注意：未做深层递归，嵌套子目录需自行扩展）
        dir.delete();
    }
}
