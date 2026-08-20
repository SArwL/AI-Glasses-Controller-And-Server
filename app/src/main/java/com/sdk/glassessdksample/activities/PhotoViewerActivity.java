package com.sdk.glassessdksample.activities;

import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.bumptech.glide.Glide;
import com.sdk.glassessdksample.R;

import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.util.ArrayList;

/**
 * 照片查看器 — 左右滑动翻页、删除、保存到系统相册。
 *
 * 功能职责：
 *  - 通过 ViewPager2 全屏左右滑动查看照片（ViewPager2 底层复用 RecyclerView）
 *  - 支持删除当前照片（直接删除本地文件，并同步刷新列表与页码指示器）
 *  - 支持将当前照片保存到系统相册（MediaStore，兼容 Android 10+ 的 RELATIVE_PATH/IS_PENDING 事务式写入）
 *  - 删除或返回时通过 setResult(RESULT_OK) 通知图库页重新加载
 *
 * 协作关系：
 *  - 由 GalleryActivity 通过 Intent 传入照片路径列表(photo_paths)和起始位置(position)启动
 *  - 内部使用内部类 PhotoPagerAdapter（RecyclerView.Adapter）作为 ViewPager2 的适配器
 *  - 使用 Glide 加载本地文件，图片解码与内存缓存由 Glide 统一管理
 */
public class PhotoViewerActivity extends AppCompatActivity {

    private static final String TAG = "PhotoViewerActivity";

    private ViewPager2 vpPhotoViewer;
    private Button btnBack;
    private Button btnDelete;
    private Button btnSave;
    private TextView tvPosition;

    private ArrayList<String> photoPaths;
    private int currentPosition;
    private PhotoPagerAdapter adapter;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_photo_viewer);

        // 读取 Intent 数据：照片路径列表 + 初始展示位置
        photoPaths = getIntent().getStringArrayListExtra("photo_paths");
        currentPosition = getIntent().getIntExtra("position", 0);

        // 数据为空时直接结束页面，避免后续空列表访问崩溃
        if (photoPaths == null || photoPaths.isEmpty()) {
            Toast.makeText(this, "无照片数据", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        initViews();
    }

    /**
     * 初始化视图：绑定控件、创建 ViewPager2 适配器、注册页面切换与按钮监听。
     */
    private void initViews() {
        vpPhotoViewer = findViewById(R.id.vpPhotoViewer);
        btnBack = findViewById(R.id.btnBack);
        btnDelete = findViewById(R.id.btnDelete);
        btnSave = findViewById(R.id.btnSave);
        tvPosition = findViewById(R.id.tvPosition);

        // 返回按钮：携带 RESULT_OK 结束，通知图库页重新加载（查看过程中可能发生过删除）
        btnBack.setOnClickListener(v -> {
            setResult(RESULT_OK);
            finish();
        });

        adapter = new PhotoPagerAdapter();
        vpPhotoViewer.setAdapter(adapter);

        // 设置初始页：false 表示直接定位到传入位置、无滑动动画
        vpPhotoViewer.setCurrentItem(currentPosition, false);
        updatePositionIndicator(currentPosition);

        // 页面切换监听：左右滑动翻页时同步更新当前索引与页码指示器
        vpPhotoViewer.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                currentPosition = position;
                updatePositionIndicator(position);
            }
        });

        // 删除按钮
        btnDelete.setOnClickListener(v -> deleteCurrentPhoto());

        // 保存按钮
        btnSave.setOnClickListener(v -> saveCurrentPhoto());
    }

    /**
     * 刷新页码指示器，例如 "2 / 10"（position 从 0 开始，显示时 +1）。
     * @param position 当前页码（从 0 开始）
     */
    private void updatePositionIndicator(int position) {
        tvPosition.setText((position + 1) + " / " + photoPaths.size());
    }

    // ==================== 删除 ====================

    /**
     * 删除当前页照片：删除磁盘文件 → 从内存列表移除 → 刷新分页与页码指示器。
     * 注意：删除的是应用本地目录中的图片文件，属于真实文件删除，不可恢复。
     */
    private void deleteCurrentPhoto() {
        if (photoPaths.isEmpty()) return;

        String path = photoPaths.get(currentPosition);
        File file = new File(path);

        // 先删除磁盘上的实际文件
        if (file.exists()) {
            boolean deleted = file.delete();
            if (!deleted) {
                Toast.makeText(this, "删除失败", Toast.LENGTH_SHORT).show();
                return;
            }
        }

        Log.i(TAG, "已删除: " + path);
        // 从内存列表移除被删除项
        photoPaths.remove(currentPosition);

        // 删除的是最后一张照片：直接结束查看器，并通知图库页刷新
        if (photoPaths.isEmpty()) {
            Toast.makeText(this, "已删除全部照片", Toast.LENGTH_SHORT).show();
            setResult(RESULT_OK);
            finish();
            return;
        }

        // 更新 ViewPager2：若删除的是末尾项则索引回退一位，再刷新适配器
        if (currentPosition >= photoPaths.size()) {
            currentPosition = photoPaths.size() - 1;
        }
        adapter.notifyDataSetChanged();
        vpPhotoViewer.setCurrentItem(currentPosition, false);
        updatePositionIndicator(currentPosition);
        Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show();
    }

    // ==================== 保存到系统相册 ====================

    /**
     * 将当前照片保存到系统相册（MediaStore）。
     * Android 10+ 使用 RELATIVE_PATH + IS_PENDING 的事务式写入，写完再清除标记；
     * 更低版本仅通过内容解析器插入记录，不指定路径（由系统决定存储位置）。
     */
    private void saveCurrentPhoto() {
        if (photoPaths.isEmpty()) return;

        String path = photoPaths.get(currentPosition);
        File sourceFile = new File(path);
        if (!sourceFile.exists()) {
            Toast.makeText(this, "文件不存在", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            String fileName = sourceFile.getName();
            // 相册保存目录：Pictures/GlassesPhotos（Android 10+ 通过 RELATIVE_PATH 生效）
            String relativePath = Environment.DIRECTORY_PICTURES + "/GlassesPhotos";

            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            values.put(MediaStore.Images.Media.DATE_ADDED, System.currentTimeMillis() / 1000);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // 高版本系统：声明保存子路径，并用 IS_PENDING=1 标记“写入中”，避免相册提前展示半截文件
                values.put(MediaStore.Images.Media.RELATIVE_PATH, relativePath);
                values.put(MediaStore.Images.Media.IS_PENDING, 1);
            }

            // 向 MediaStore 插入一条待写入的媒体记录，返回其 Uri
            Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) {
                Toast.makeText(this, "保存失败：无法创建媒体记录", Toast.LENGTH_SHORT).show();
                return;
            }

            // 以流的方式将源文件内容分块拷贝到媒体库 Uri 中（8KB 缓冲）
            try (FileInputStream fis = new FileInputStream(sourceFile);
                 OutputStream os = getContentResolver().openOutputStream(uri)) {
                if (os == null) {
                    Toast.makeText(this, "保存失败：无法写入", Toast.LENGTH_SHORT).show();
                    return;
                }
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = fis.read(buffer)) != -1) {
                    os.write(buffer, 0, bytesRead);
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // 写入完成：清除 IS_PENDING 标记，相册此时才正式显示该图片
                values.clear();
                values.put(MediaStore.Images.Media.IS_PENDING, 0);
                getContentResolver().update(uri, values, null, null);
            }

            Log.i(TAG, "已保存到相册: " + uri);
            Toast.makeText(this, "已保存到相册", Toast.LENGTH_SHORT).show();

        } catch (Exception e) {
            Log.e(TAG, "保存到相册失败", e);
            Toast.makeText(this, "保存失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onBackPressed() {
        // 系统返回键同样携带 RESULT_OK，保证图库页能感知返回并刷新
        setResult(RESULT_OK);
        super.onBackPressed();
    }

    // ==================== ViewPager2 Adapter ====================

    /**
     * ViewPager2 分页适配器：每个条目直接使用一个全屏 ImageView，
     * 通过 Glide 按本地路径加载图片，FIT_CENTER 等比缩放完整显示。
     */
    private class PhotoPagerAdapter extends RecyclerView.Adapter<PhotoPagerAdapter.ViewHolder> {

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            // 手动创建全屏 ImageView 作为一页；黑底便于观察带黑边的缩放效果
            ImageView imageView = new ImageView(parent.getContext());
            imageView.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
            imageView.setBackgroundColor(0xFF000000);
            return new ViewHolder(imageView);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            // Glide 按本地文件路径加载图片；fitCenter 保证完整显示并保持原始宽高比
            Glide.with(PhotoViewerActivity.this)
                    .load(new File(photoPaths.get(position)))
                    .fitCenter()
                    .into(holder.imageView);
        }

        @Override
        public int getItemCount() {
            return photoPaths.size();
        }

        class ViewHolder extends RecyclerView.ViewHolder {
            ImageView imageView;

            ViewHolder(View itemView) {
                super(itemView);
                this.imageView = (ImageView) itemView;
            }
        }
    }
}
