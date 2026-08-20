package com.sdk.glassessdksample.managers;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import com.sdk.glassessdksample.R;

import java.util.List;

/**
 * 音频文件列表的 RecyclerView 适配器。
 *
 * 职责：把 {@link AudioFileItem} 列表渲染到 RecyclerView，每一行显示文件名、
 * 文件大小和一个"播放"按钮；点击播放按钮时回调
 * {@link OnPlayClickListener#onPlay(String)}。
 *
 * 协作类：AudioPlaybackManager（创建本适配器并注入点击监听）、AudioFileItem（数据模型）。
 */
public class AudioFileAdapter extends RecyclerView.Adapter<AudioFileAdapter.ViewHolder> {

    /** 列表项"播放"按钮点击监听。 */
    public interface OnPlayClickListener {
        void onPlay(String filePath);
    }

    /** 数据源列表 */
    private final List<AudioFileItem> items;
    /** 播放点击回调 */
    private final OnPlayClickListener listener;

    /**
     * @param items    音频文件数据列表（与外部共享引用，外部改动会直接反映到列表）
     * @param listener 点击"播放"时的回调
     */
    public AudioFileAdapter(List<AudioFileItem> items, OnPlayClickListener listener) {
        this.items = items;
        this.listener = listener;
    }

    @Override
    public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        // 加载 item_audio 布局并包装成 ViewHolder
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_audio, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(ViewHolder holder, int position) {
        AudioFileItem item = items.get(position);
        holder.tvFileName.setText(item.name);              // 文件名
        holder.tvFileSize.setText(formatSize(item.size));  // 格式化后的文件大小
        holder.btnPlay.setOnClickListener(v -> {
            // 点击播放按钮，把文件路径回调给监听者（AudioPlaybackManager）
            if (listener != null) {
                listener.onPlay(item.path);
            }
        });
    }

    /** 列表项总数，由 RecyclerView 在布局/复用过程中调用。 */
    @Override
    public int getItemCount() {
        return items.size();
    }

    /** 将字节数格式化为人类可读大小：B / KB / MB。 */
    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }

    /** 列表项视图持有者：缓存 item_audio 布局中的控件引用。 */
    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvFileName;   // 文件名
        TextView tvFileSize;   // 文件大小
        Button btnPlay;        // 播放按钮

        ViewHolder(View itemView) {
            super(itemView);
            tvFileName = itemView.findViewById(R.id.tvFileName);
            tvFileSize = itemView.findViewById(R.id.tvFileSize);
            btnPlay = itemView.findViewById(R.id.btnPlay);
        }
    }
}
