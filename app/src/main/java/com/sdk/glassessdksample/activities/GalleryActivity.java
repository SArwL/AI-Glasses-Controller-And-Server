package com.sdk.glassessdksample.activities;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.sdk.glassessdksample.GlassApp;
import com.sdk.glassessdksample.R;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * 照片图库 Activity
 * 以3列网格展示眼镜传输的所有照片，点击可放大查看
 *
 * 功能职责：
 *  - 扫描 GlassApp 提供的当前设备照片目录，过滤出 jpg/jpeg/png 图片
 *  - 以 3 列网格（GridLayoutManager）展示缩略图，图片加载与缓存由 Glide 负责
 *  - 点击缩略图跳转 PhotoViewerActivity 全屏查看
 *  - 从查看器返回（RESULT_OK）后重新加载列表，保证删除操作后 UI 与磁盘一致
 *
 * 协作关系：
 *  - 通过 GlassApp.getInstance().getPhotoDir() 获取设备专属照片目录（picture 子目录）
 *  - 内部数据模型 PhotoItem 保存图片名称/路径/大小
 *  - 内部静态类 PhotoAdapter 负责 RecyclerView 网格渲染与点击回调分发
 */
public class GalleryActivity extends AppCompatActivity {

    private static final String TAG = "GalleryActivity";

    private RecyclerView rvPhotoGrid;
    private TextView tvEmptyHint;
    private TextView tvPhotoCount;
    private PhotoAdapter adapter;
    private final List<PhotoItem> photoList = new ArrayList<>();

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_gallery);

        initViews();
        loadPhotos(); // 进入页面即加载当前设备的照片列表
    }

    /**
     * 初始化视图：绑定返回/控件、配置 3 列网格布局、注册缩略图点击监听。
     */
    private void initViews() {
        // 返回按钮
        View btnBack = findViewById(R.id.btnBack);
        if (btnBack != null) {
            btnBack.setOnClickListener(v -> finish());
        }

        tvEmptyHint = findViewById(R.id.tvEmptyHint);
        tvPhotoCount = findViewById(R.id.tvPhotoCount);

        // RecyclerView：3列网格（每项正方形，宽度 = 屏幕宽/3）
        rvPhotoGrid = findViewById(R.id.rvPhotoGrid);
        rvPhotoGrid.setLayoutManager(new GridLayoutManager(this, 3));
        adapter = new PhotoAdapter(photoList);
        rvPhotoGrid.setAdapter(adapter);

        // 点击缩略图 → 全屏查看器
        adapter.setOnItemClickListener((position, itemView) -> {
            launchViewer(position);
        });
    }

    /**
     * 扫描当前设备照片目录，加载所有图片文件。
     * 流程：清空列表 → 校验目录 → 过滤图片扩展名 → 按修改时间降序 → 填充列表 → 刷新网格。
     */
    private void loadPhotos() {
        photoList.clear();

        // 获取设备专属照片目录（picture 子目录）；目录不存在视为空图库
        File photoDir = GlassApp.getInstance().getPhotoDir();
        if (photoDir == null || !photoDir.exists()) {
            showEmptyState();
            return;
        }

        // 只保留 jpg/jpeg/png 三种图片格式（扩展名不区分大小写）
        File[] files = photoDir.listFiles((dir, name) -> {
            String lower = name.toLowerCase();
            return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png");
        });

        if (files == null || files.length == 0) {
            showEmptyState();
            return;
        }

        // 按修改时间降序排列（最新的在前）
        Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));

        // 封装为 PhotoItem（名称/路径/大小）加入列表
        for (File f : files) {
            photoList.add(new PhotoItem(f.getName(), f.getAbsolutePath(), f.length()));
        }

        // 更新计数、隐藏空提示、显示网格并刷新适配器
        tvPhotoCount.setText(photoList.size() + " 张");
        tvEmptyHint.setVisibility(View.GONE);
        rvPhotoGrid.setVisibility(View.VISIBLE);
        adapter.notifyDataSetChanged();

        Log.i(TAG, "加载了 " + photoList.size() + " 张照片");
    }

    /**
     * 无照片时的空状态：显示空提示文案、隐藏网格、照片计数归零。
     */
    private void showEmptyState() {
        tvEmptyHint.setVisibility(View.VISIBLE);
        rvPhotoGrid.setVisibility(View.GONE);
        tvPhotoCount.setText("0 张");
    }

    /**
     * 启动照片查看器。
     * 将当前列表全部路径和点击位置通过 Intent 传入 PhotoViewerActivity；
     * requestCode=100 用于接收查看器的返回结果以触发列表刷新。
     * @param position 点击的照片在当前列表中的位置
     */
    private void launchViewer(int position) {
        // 将当前设备全部照片路径传递给查看器（支持左右滑动浏览全部）
        ArrayList<String> paths = new ArrayList<>();
        for (PhotoItem item : photoList) {
            paths.add(item.path);
        }
        Intent intent = new Intent(this, PhotoViewerActivity.class);
        intent.putStringArrayListExtra("photo_paths", paths);
        intent.putExtra("position", position);
        startActivityForResult(intent, 100);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 100 && resultCode == RESULT_OK) {
            // 查看器中可能有删除操作，重新加载照片列表
            loadPhotos();
        }
    }

    // ==================== 数据模型 ====================

    /**
     * 照片条目数据模型：保存图片的文件名、绝对路径、文件大小（字节）。
     * 全部字段 final，创建后不可变，用于列表展示与路径传递。
     */
    public static class PhotoItem {
        public final String name;
        public final String path;
        public final long size;

        public PhotoItem(String name, String path, long size) {
            this.name = name;
            this.path = path;
            this.size = size;
        }
    }

    // ==================== 照片网格适配器 ====================

    /**
     * 照片网格适配器：为每个条目创建正方形缩略图（边长 = 屏幕宽 / 3），
     * 通过 Glide centerCrop 加载并居中裁剪填满方块，点击回调交由外部处理。
     */
    public static class PhotoAdapter extends RecyclerView.Adapter<PhotoAdapter.ViewHolder> {

        private final List<PhotoItem> items;
        private OnItemClickListener listener;

        /** 条目点击回调接口：返回点击位置与所在 item 视图 */
        public interface OnItemClickListener {
            void onItemClick(int position, View itemView);
        }

        public PhotoAdapter(List<PhotoItem> items) {
            this.items = items;
        }

        public void setOnItemClickListener(OnItemClickListener listener) {
            this.listener = listener;
        }

        @Override
        public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view = android.view.LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_photo, parent, false);
            // 设置正方形尺寸：屏幕宽度 / 3（三列等宽，每项为屏幕 1/3 的正方形）
            int screenWidth = parent.getContext().getResources().getDisplayMetrics().widthPixels;
            int itemSize = screenWidth / 3;
            ViewGroup.LayoutParams params = view.getLayoutParams();
            params.width = itemSize;
            params.height = itemSize;
            view.setLayoutParams(params);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(ViewHolder holder, int position) {
            PhotoItem item = items.get(position);
            // Glide 按本地文件路径加载缩略图，centerCrop 居中裁剪以填满整个方块
            Glide.with(holder.itemView.getContext())
                    .load(new File(item.path))
                    .centerCrop()
                    .into(holder.ivThumbnail);

            // 每次绑定重新设置点击事件（保证监听与当前绑定位置一致）
            holder.itemView.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onItemClick(holder.getAdapterPosition(), v);
                }
            });
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        static class ViewHolder extends RecyclerView.ViewHolder {
            ImageView ivThumbnail;

            ViewHolder(View itemView) {
                super(itemView);
                ivThumbnail = itemView.findViewById(R.id.ivThumbnail);
            }
        }
    }
}
