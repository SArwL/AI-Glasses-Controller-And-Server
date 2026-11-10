package com.sdk.glassessdksample.managers;

/**
 * 音频文件数据模型。
 *
 * 不可变数据类，描述音频列表中的单个文件：文件名、绝对路径、字节大小。
 * 由 AudioPlaybackManager.refreshAudioFileList() 创建，供 AudioFileAdapter 渲染展示。
 */
public class AudioFileItem {
    /** 文件名（含扩展名） */
    public final String name;
    /** 文件绝对路径，点击播放时直接传给 playAudio */
    public final String path;
    /** 文件大小（字节） */
    public final long size;

    /**
     * @param name 文件名（含扩展名）
     * @param path 文件绝对路径
     * @param size 文件大小（字节）
     */
    public AudioFileItem(String name, String path, long size) {
        this.name = name;
        this.path = path;
        this.size = size;
    }
}
