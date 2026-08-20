package com.sdk.glassessdksample.ui;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import androidx.core.content.ContextCompat;

/**
 * 蓝牙工具类
 * 提供蓝牙启用状态检查、系统版本判断等通用静态方法。
 * Created by hzy on 2016/1/25.
 */
public class BluetoothUtils {
    /**
     * 蓝牙功能是否已经启用
     *
     * @return
     */
    /**
     * 检查蓝牙是否已启用
     * 注意：调用此方法前需要确保已授予相应的蓝牙权限
     */
    @SuppressLint("MissingPermission")
    public static boolean isEnabledBluetooth(Context context) {
        try {
            // 检查是否有蓝牙权限
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Android 12+ 需要 BLUETOOTH_CONNECT 权限
                if (context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) 
                    != PackageManager.PERMISSION_GRANTED) {
                    return false;
                }
            }
            
            // 通过系统服务获取蓝牙管理器
            BluetoothManager bluetoothManager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
            if (bluetoothManager == null) {
                return false;
            }
            
            BluetoothAdapter adapter = bluetoothManager.getAdapter();
            // 不支持BLE
            if (!context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
                return false;
            }
            // 不支持蓝牙
            if (adapter == null) {
                return false;
            }
            // 蓝牙未打开
            return adapter.isEnabled();
        } catch (Exception e) {
            // 权限被拒或系统服务异常时，统一按"未启用"处理
            Log.e("BluetoothUtils", "检查蓝牙状态失败", e);
            return false;
        }
    }

    /**
     * 判断当前系统版本是否为 Android 5.0（LOLLIPOP）及以上
     *
     * @return true 表示 API level >= 21，可用于区分高低版本蓝牙 API 的用法
     */
    public static boolean hasLollipop() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP;
    }


}
