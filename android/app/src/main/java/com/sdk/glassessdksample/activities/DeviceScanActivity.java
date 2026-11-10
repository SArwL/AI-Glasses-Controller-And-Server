package com.sdk.glassessdksample.activities;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.le.ScanResult;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.oudmon.ble.base.bluetooth.BleOperateManager;
import com.oudmon.ble.base.scan.BleScannerHelper;
import com.oudmon.ble.base.scan.ScanRecord;
import com.oudmon.ble.base.scan.ScanWrapperCallback;
import com.sdk.glassessdksample.GlassApp;
import com.sdk.glassessdksample.R;
import com.sdk.glassessdksample.events.BluetoothEvent;
import com.sdk.glassessdksample.ui.BluetoothUtils;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 设备扫描与连接 Activity
 * 功能：扫描附近的AI眼镜设备，点击连接
 *
 * 职责：
 * - 调用 SDK 的 BleScannerHelper 扫描附近的 BLE 蓝牙设备，实时展示在列表中
 *   （按 RSSI 信号强度降序排列，最多保留30个）。
 * - 用户点击列表项后，通过 BleOperateManager.connectDirectly() 直接发起 GATT 连接，
 *   并在连接成功（收到 BluetoothEvent）后自动返回主界面。
 *
 * 关键流程：
 * onCreate → 注册EventBus → initViews() 绑定列表 → startScanning()（15秒超时自动停止）。
 * 列表点击 → GlassApp.setPendingDeviceName() 预存设备名 → connectDirectly() → 连接成功 → finish()。
 *
 * 协作关系：
 * - BleScannerHelper / BleOperateManager（SDK）：负责BLE扫描与发起连接；
 * - GlassApp.setPendingDeviceName()：连接前预存设备名，解决首次连接时 SDK 拿不到
 *   DeviceManager.getDeviceName() 返回 null 的问题；
 * - EventBus 的 BluetoothEvent：连接成功时通知本界面自动返回；
 * - DeviceAdapter / DeviceInfo：列表展示与设备数据模型。
 */
public class DeviceScanActivity extends AppCompatActivity {

    private static final String TAG = "DeviceScanActivity";
    /** 扫描超时时间：15秒后自动停止扫描并提示结果 */
    private static final long SCAN_TIMEOUT_MS = 15 * 1000L;

    private RecyclerView deviceRecyclerView;
    private DeviceAdapter adapter;
    /** 已扫描到的设备列表（按MAC地址去重，信号强度降序排列） */
    private final List<DeviceInfo> deviceList = new ArrayList<>();
    /** 连接成功处理标记：用于去重（GATT连接与ACL广播可能各触发一次连接事件） */
    private boolean connectionHandled = false;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    /** 扫描超时任务：停止扫描并提示结果（无设备 / 找到N个设备） */
    private final Runnable scanTimeoutRunnable = () -> {
        Log.i(TAG, "扫描超时，停止扫描");
        BleScannerHelper.getInstance().stopScan(this);
        if (deviceList.isEmpty()) {
            Toast.makeText(this, "未扫描到设备", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "扫描完成，共找到 " + deviceList.size() + " 个设备", Toast.LENGTH_SHORT).show();
        }
    };

    /** 创建扫描界面：注册EventBus、初始化列表UI、立即开始扫描 */
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_device_scan);
        EventBus.getDefault().register(this);

        initViews();
        startScanning();
    }

    /** 销毁：反注册EventBus、移除超时任务、停止扫描，避免资源泄漏 */
    @Override
    protected void onDestroy() {
        super.onDestroy();
        EventBus.getDefault().unregister(this);
        mainHandler.removeCallbacks(scanTimeoutRunnable);
        BleScannerHelper.getInstance().stopScan(this);
    }

    /** 初始化列表、返回按钮、重新扫描按钮，并注册列表项点击 → 连接设备 */
    private void initViews() {
        deviceRecyclerView = findViewById(R.id.deviceRecyclerView);
        deviceRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new DeviceAdapter(deviceList);
        deviceRecyclerView.setAdapter(adapter);

        // 返回按钮
        View btnBack = findViewById(R.id.btnBack);
        if (btnBack != null) {
            btnBack.setOnClickListener(v -> finish());
        }

        // 重新扫描按钮
        Button btnRescan = findViewById(R.id.btnRescan);
        if (btnRescan != null) {
            btnRescan.setOnClickListener(v -> startScanning());
        }

        // 列表项点击 → 连接设备
        adapter.setOnItemClickListener((view, position) -> {
            // 点击后立即停止扫描，避免扫描干扰连接建立
            mainHandler.removeCallbacks(scanTimeoutRunnable);
            BleScannerHelper.getInstance().stopScan(this);

            DeviceInfo info = deviceList.get(position);
            Log.i(TAG, "连接设备: " + info.name + " (" + info.address + ")");
            Toast.makeText(this, "正在连接 " + info.name + "...", Toast.LENGTH_SHORT).show();

            // ★ 关键：在发起连接前预存设备名，防止首次连接时
            // DeviceManager.getDeviceName() 返回 null 导致无法创建目标文件夹
            GlassApp.getInstance().setPendingDeviceName(info.name);

            // 发起GATT连接
            BleOperateManager.getInstance().connectDirectly(info.address);
        });
    }

    /** 开始扫描：检查蓝牙开关 → 清空旧列表 → 注册扫描回调 → 设置15秒超时 */
    private void startScanning() {
        // 检查蓝牙是否开启
        if (!BluetoothUtils.isEnabledBluetooth(this)) {
            Intent intent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(intent, 300);
            return;
        }

        // 清空上一次的扫描结果
        deviceList.clear();
        adapter.notifyDataSetChanged();
        // 重置SDK扫描回调，避免沿用上一次扫描的旧回调
        BleScannerHelper.getInstance().reSetCallback();

        Log.i(TAG, "开始扫描蓝牙设备");
        // 发起BLE扫描，扫描结果通过 BleScanCallback 实时回调
        BleScannerHelper.getInstance().scanDevice(this, null, new BleScanCallback());

        // 重新设置15秒超时任务（先移除上次未触发的，避免重复触发）
        mainHandler.removeCallbacks(scanTimeoutRunnable);
        mainHandler.postDelayed(scanTimeoutRunnable, SCAN_TIMEOUT_MS);

        Toast.makeText(this, "正在扫描设备...", Toast.LENGTH_SHORT).show();
    }

    /** 蓝牙开启请求的回调：开启成功则开始扫描，否则提示需要蓝牙 */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 300 && resultCode == RESULT_OK) {
            startScanning();
        } else {
            Toast.makeText(this, "需要启用蓝牙", Toast.LENGTH_LONG).show();
        }
    }

    /** 订阅蓝牙连接事件（主线程）：连接成功则自动返回主界面，用 connectionHandled 去重 */
    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onBluetoothEvent(BluetoothEvent event) {
        if (event.isConnected()) {
            if (connectionHandled) return;  // 去重：GATT+ACL 可能各发一次
            connectionHandled = true;
            Log.i(TAG, "蓝牙连接成功，返回主界面");
            Toast.makeText(this, "设备连接成功！", Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    // ==================== BLE扫描回调 ====================

    /** SDK扫描回调实现：实时接收扫描到的BLE设备并刷新列表 */
    private class BleScanCallback implements ScanWrapperCallback {

        /** 经典BLE扫描回调：有设备名且非空时加入列表，按RSSI降序排序 */
        @Override
        public void onLeScan(BluetoothDevice device, int rssi, byte[] scanRecord) {
            if (device != null && device.getName() != null && !device.getName().isEmpty()) {
                String name = device.getName();
                String address = device.getAddress();

                DeviceInfo info = new DeviceInfo(name, address, rssi, device);
                if (!deviceList.contains(info)) {  // 按MAC地址去重（equals基于address）
                    deviceList.add(info);
                    // 按信号强度降序排列，信号强的设备排前面
                    Collections.sort(deviceList, (a, b) -> Integer.compare(b.rssi, a.rssi));
                    runOnUiThread(() -> adapter.notifyDataSetChanged());

                    // 设备过多时提前停止扫描，避免列表过长并节省电量
                    if (deviceList.size() > 30) {
                        BleScannerHelper.getInstance().stopScan(DeviceScanActivity.this);
                    }
                }
            }
        }

        /** 扫描失败回调：打印错误码并Toast提示 */
        @Override
        public void onScanFailed(int errorCode) {
            Log.e(TAG, "扫描失败: errorCode=" + errorCode);
            runOnUiThread(() -> Toast.makeText(DeviceScanActivity.this,
                    "扫描失败: " + errorCode, Toast.LENGTH_SHORT).show());
        }

        @Override
        public void onParsedData(BluetoothDevice device, ScanRecord scanRecord) {
        }

        @Override
        public void onBatchScanResults(List<ScanResult> results) {
        }

        @Override
        public void onStart() {
            Log.i(TAG, "扫描开始");
        }

        @Override
        public void onStop() {
            Log.i(TAG, "扫描停止");
        }
    }

    // ==================== 设备数据模型 ====================

    /** 扫描结果的设备数据模型：名称、MAC地址、信号强度、底层设备对象（以address作为唯一标识） */
    public static class DeviceInfo {
        public final String name;
        public final String address;
        public final int rssi;
        public final BluetoothDevice device;

        public DeviceInfo(String name, String address, int rssi, BluetoothDevice device) {
            this.name = name != null ? name : "未知设备";
            this.address = address != null ? address : "";
            this.rssi = rssi;
            this.device = device;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            DeviceInfo that = (DeviceInfo) o;
            return address.equals(that.address);
        }

        @Override
        public int hashCode() {
            return address.hashCode();
        }
    }

    // ==================== 设备列表适配器 ====================

    /** RecyclerView适配器：绑定设备名称/地址/信号强度，并转发列表项点击事件 */
    public static class DeviceAdapter extends RecyclerView.Adapter<DeviceAdapter.ViewHolder> {

        private final List<DeviceInfo> devices;
        private OnItemClickListener listener;

        /** 列表项点击监听接口 */
        public interface OnItemClickListener {
            void onItemClick(View view, int position);
        }

        public DeviceAdapter(List<DeviceInfo> devices) {
            this.devices = devices;
        }

        public void setOnItemClickListener(OnItemClickListener listener) {
            this.listener = listener;
        }

        /** 创建列表项视图（加载 item_device 布局） */
        @Override
        public ViewHolder onCreateViewHolder(android.view.ViewGroup parent, int viewType) {
            View view = android.view.LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_device, parent, false);
            return new ViewHolder(view);
        }

        /** 绑定数据：显示设备名/地址/信号强度，并设置整行点击事件 */
        @Override
        public void onBindViewHolder(ViewHolder holder, int position) {
            DeviceInfo device = devices.get(position);
            holder.tvName.setText(device.name);
            holder.tvAddress.setText(device.address);
            holder.tvRssi.setText(device.rssi + " dBm");
            holder.itemView.setOnClickListener(v -> {
                if (listener != null) listener.onItemClick(v, holder.getAdapterPosition());
            });
        }

        @Override
        public int getItemCount() {
            return devices.size();
        }

        /** 列表项控件持有者：设备名、地址、信号强度 */
        static class ViewHolder extends RecyclerView.ViewHolder {
            android.widget.TextView tvName, tvAddress, tvRssi;

            ViewHolder(View itemView) {
                super(itemView);
                tvName = itemView.findViewById(R.id.tvDeviceName);
                tvAddress = itemView.findViewById(R.id.tvDeviceAddress);
                tvRssi = itemView.findViewById(R.id.tvRssi);
            }
        }
    }
}
