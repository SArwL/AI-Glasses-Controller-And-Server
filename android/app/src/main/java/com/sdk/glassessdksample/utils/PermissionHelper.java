package com.sdk.glassessdksample.utils;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

/**
 * 权限管理工具类
 * 管理蓝牙、WiFi、定位、存储、录音等权限
 *
 * 职责与协作说明：
 * - 按 Android 版本差异动态组装所需权限清单（Android 12+ 使用蓝牙新权限，
 *   Android 13+ 使用媒体权限与 NEARBY_WIFI_DEVICES，旧版本退化为定位 + 存储权限）。
 * - 提供"检查全部权限 / 一次性请求全部权限"的入口，供启动页或主界面在
 *   onRequestPermissionsResult 中回调后继续业务逻辑（如 BLE 扫描、录音、WiFi 同步）。
 */
public class PermissionHelper {

    // 一次性请求全部权限时的请求码，用于在 Activity#onRequestPermissionsResult 中识别回调
    public static final int REQUEST_CODE_ALL = 100;

    /**
     * 获取应用需要的所有权限列表
     */
    public static String[] getAllRequiredPermissions() {
        List<String> permissions = new ArrayList<>();

        // 蓝牙权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+：蓝牙扫描/连接/广播为三个独立运行时权限
            permissions.add(Manifest.permission.BLUETOOTH_SCAN);
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT);
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE);
        }
        // 定位权限（低版本蓝牙扫描需要）
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            // Android 11 及以下：BLE 扫描必须配合定位权限才能发现设备
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
            permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }

        // WiFi权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES);
        }
        permissions.add(Manifest.permission.ACCESS_WIFI_STATE);
        permissions.add(Manifest.permission.CHANGE_WIFI_STATE);
        permissions.add(Manifest.permission.ACCESS_NETWORK_STATE);
        permissions.add(Manifest.permission.CHANGE_NETWORK_STATE);

        // 存储权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.READ_MEDIA_IMAGES);
            permissions.add(Manifest.permission.READ_MEDIA_VIDEO);
            permissions.add(Manifest.permission.READ_MEDIA_AUDIO);
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE);
            permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }

        // 录音权限（AI 对话、语音识别需要）
        permissions.add(Manifest.permission.RECORD_AUDIO);

        // 网络权限（WiFi 同步、云端接口需要）
        permissions.add(Manifest.permission.INTERNET);

        return permissions.toArray(new String[0]);
    }

    /**
     * 检查所有必需权限是否已授予
     */
    public static boolean hasAllPermissions(Activity activity) {
        for (String perm : getAllRequiredPermissions()) {
            if (ContextCompat.checkSelfPermission(activity, perm)
                    != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    /**
     * 请求所有必需权限
     */
    public static void requestAllPermissions(Activity activity) {
        String[] permissions = getAllRequiredPermissions();
        ActivityCompat.requestPermissions(activity, permissions, REQUEST_CODE_ALL);
    }

    /**
     * 检查蓝牙权限是否已授予
     */
    public static boolean hasBluetoothPermission(Activity activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(activity, Manifest.permission.BLUETOOTH_SCAN)
                    == PackageManager.PERMISSION_GRANTED
                    && ContextCompat.checkSelfPermission(activity, Manifest.permission.BLUETOOTH_CONNECT)
                    == PackageManager.PERMISSION_GRANTED;
        } else {
            return ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED;
        }
    }

    /**
     * 检查WiFi权限是否已授予
     */
    public static boolean hasWifiPermission(Activity activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(activity, Manifest.permission.NEARBY_WIFI_DEVICES)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return true; // Android 12及以下不需要NEARBY_WIFI_DEVICES
    }
}
