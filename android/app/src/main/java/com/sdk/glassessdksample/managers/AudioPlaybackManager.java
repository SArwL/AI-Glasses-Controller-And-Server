package com.sdk.glassessdksample.managers;

import android.app.Activity;
import android.media.MediaPlayer;
import android.os.Handler;
import android.util.Log;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.sdk.glassessdksample.GlassApp;
import com.sdk.glassessdksample.utils.PcmUtils;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 音频播放与文件列表管理。
 *
 * 职责：
 * 1) 扫描 audio 目录下的音频文件（.pcm/.wav/.mp3/.aac/.opus）并展示到 RecyclerView；
 * 2) 点击列表项播放对应音频文件。
 *
 * 格式处理要点：MediaPlayer 不支持裸 PCM/OPUS，播放前需转换——
 * - PCM → WAV：调用 {@link PcmUtils#convertPcmToWav(File)} 转换后播放；
 * - OPUS：需 SDK 先解码为 PCM/WAV，若对应 WAV 已存在则直接播放，否则提示等待解码。
 *
 * 协作类：GlassApp（audio 目录）、PcmUtils（PCM→WAV 转换）、
 * AudioFileAdapter（列表适配器，点击回调 {@link #playAudio(String)}）。
 */
public class AudioPlaybackManager {

    private static final String TAG = "AudioPlaybackManager";

    /** 宿主 Activity，用于弹 Toast */
    private final Activity activity;
    /** 主线程 Handler，把播放完成/失败事件切回主线程 */
    private final Handler mainHandler;
    /** 音频文件列表 RecyclerView */
    private final RecyclerView rvAudioFiles;
    /** 音频文件数据缓存 */
    private final List<AudioFileItem> audioFiles = new ArrayList<>();
    /** 列表适配器（点击项 → 回调 playAudio） */
    private final AudioFileAdapter audioAdapter;
    /** 当前播放器实例，同一时刻只允许一路播放 */
    private MediaPlayer mediaPlayer;

    /**
     * 构造音频播放管理器。
     *
     * @param activity     宿主 Activity
     * @param mainHandler  主线程 Handler
     * @param rvAudioFiles 承载音频列表的 RecyclerView
     */
    public AudioPlaybackManager(Activity activity, Handler mainHandler, RecyclerView rvAudioFiles) {
        this.activity = activity;
        this.mainHandler = mainHandler;
        this.rvAudioFiles = rvAudioFiles;

        audioAdapter = new AudioFileAdapter(audioFiles, this::playAudio);
        rvAudioFiles.setLayoutManager(new LinearLayoutManager(activity));
        rvAudioFiles.setAdapter(audioAdapter);
    }

    /**
     * 刷新音频文件列表：扫描 audio 目录下的 .pcm/.wav/.mp3/.aac/.opus 文件。
     * 在 UI 线程调用，完成后调用 notifyDataSetChanged 通知列表更新。
     */
    public void refreshAudioFileList() {
        audioFiles.clear();
        File audioDir = GlassApp.getInstance().getAudioDir();
        if (audioDir != null && audioDir.exists()) {
            File[] files = audioDir.listFiles((dir, name) -> {
                String lower = name.toLowerCase();
                return lower.endsWith(".pcm") || lower.endsWith(".wav")
                        || lower.endsWith(".mp3") || lower.endsWith(".aac")
                        || lower.endsWith(".opus");
            });
            if (files != null) {
                for (File f : files) {
                    audioFiles.add(new AudioFileItem(f.getName(), f.getAbsolutePath(), f.length()));
                }
            }
        }
        audioAdapter.notifyDataSetChanged();
    }

    /**
     * 播放音频文件。PCM 文件会自动转换为 WAV 后再播放；OPUS 文件需依赖 SDK 先解码。
     *
     * 转换优先级：OPUS → 优先同名 .wav，其次同名 .pcm 实时转 WAV；PCM → 实时转 WAV。
     * 转换/加载成功后，旧播放器会被释放，只保留当前这一路播放。
     *
     * @param filePath 音频文件绝对路径
     */
    public void playAudio(String filePath) {
        try {
            String playPath = filePath;
            File audioFile = new File(filePath);
            if (!audioFile.exists()) {
                Toast.makeText(activity, "音频文件不存在", Toast.LENGTH_SHORT).show();
                return;
            }

            // MediaPlayer 无法直接播放裸 PCM/OPUS，先统一寻找可播放的 WAV 版本
            // OPUS 文件需要 SDK 先转换为 PCM → WAV 才能播放
            // 如果到这里还没有 WAV 版本，说明 recordingToPcm 回调未触发
            if (filePath.toLowerCase().endsWith(".opus")) {
                // 去掉 .opus 后缀（大小写不敏感），尝试找同名 .wav 或 .pcm
                String baseName = filePath.replaceAll("(?i)\\.opus$", "");
                File wavFile = new File(baseName + ".wav");
                if (wavFile.exists()) {
                    playPath = wavFile.getAbsolutePath();
                    Log.i(TAG, "OPUS已有对应WAV版本，直接播放: " + playPath);
                } else {
                    // 再尝试找 .pcm
                    File pcmFile = new File(baseName + ".pcm");
                    if (pcmFile.exists()) {
                        wavFile = PcmUtils.convertPcmToWav(pcmFile);
                        if (wavFile != null && wavFile.exists()) {
                            playPath = wavFile.getAbsolutePath();
                            Log.i(TAG, "找到对应PCM并转换为WAV: " + playPath);
                        } else {
                            Toast.makeText(activity,
                                    "OPUS解码中，请稍后再试", Toast.LENGTH_SHORT).show();
                            return;
                        }
                    } else {
                        Toast.makeText(activity,
                                "OPUS文件暂不支持直接播放，请等待SDK解码完成", Toast.LENGTH_SHORT).show();
                        return;
                    }
                }
            }

            // PCM 文件需转换为 WAV 后才能用 MediaPlayer 播放
            if (filePath.toLowerCase().endsWith(".pcm")) {
                File wavFile = PcmUtils.convertPcmToWav(audioFile);
                if (wavFile != null && wavFile.exists()) {
                    playPath = wavFile.getAbsolutePath();
                    Log.i(TAG, "PCM已转换为WAV用于播放: " + playPath);
                } else {
                    Toast.makeText(activity, "PCM转换失败，无法播放", Toast.LENGTH_SHORT).show();
                    return;
                }
            }

            // 释放上一个播放器，避免多路音频同时播放
            if (mediaPlayer != null) {
                mediaPlayer.release();
            }
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setDataSource(playPath);
            mediaPlayer.prepare();
            mediaPlayer.start();
            // 播放完成后释放资源并切回主线程提示
            mediaPlayer.setOnCompletionListener(mp -> {
                mp.release();
                mediaPlayer = null;
                mainHandler.post(() ->
                        Toast.makeText(activity, "播放完成", Toast.LENGTH_SHORT).show());
            });
            // 播放出错（如格式不支持）时释放资源并提示
            mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                Log.e(TAG, "MediaPlayer错误: what=" + what + ", extra=" + extra);
                mp.release();
                mediaPlayer = null;
                mainHandler.post(() ->
                        Toast.makeText(activity, "播放失败: 不支持的音频格式", Toast.LENGTH_SHORT).show());
                return true;
            });
            Toast.makeText(activity, "正在播放...", Toast.LENGTH_SHORT).show();
        } catch (IOException e) {
            Log.e(TAG, "音频播放失败", e);
            Toast.makeText(activity, "播放失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 释放 MediaPlayer 资源。
     */
    public void onDestroy() {
        if (mediaPlayer != null) {
            mediaPlayer.release();
            mediaPlayer = null;
        }
    }
}
