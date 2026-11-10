package com.sdk.glassessdksample.utils;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

/**
 * PCM 音频工具类
 * 负责 PCM → WAV 格式转换（添加 WAV 文件头），使 MediaPlayer 可播放
 *
 * 说明：
 * - 眼镜录音 / AI 对话的原始音频数据通常为 PCM（16kHz / 单声道 / 16bit），
 *   加上 44 字节 WAV 头后即可被 MediaPlayer 等标准播放器识别播放。
 * - 转换结果与 PCM 同名、后缀改为 .wav；若 WAV 已存在且不旧于 PCM 则直接复用，避免重复转换。
 * - 纯静态工具类，由录音保存、AI 对话音频处理等模块调用。
 */
public class PcmUtils {

    private static final String TAG = "PcmUtils";

    /**
     * 将 PCM 文件转换为 WAV 格式（用于播放）
     * @param pcmFile 原始 PCM 文件
     * @param sampleRate 采样率（眼镜录音通常为 16000 或 8000）
     * @param channels 声道数（1=单声道）
     * @param bitsPerSample 位深（16）
     * @return 转换后的 WAV 文件，失败返回 null
     */
    public static File convertPcmToWav(
            File pcmFile,
            int sampleRate,
            short channels,
            short bitsPerSample) {

        if (pcmFile == null || !pcmFile.exists()) {
            Log.e(TAG, "PCM文件不存在: " + (pcmFile != null ? pcmFile.getAbsolutePath() : "null"));
            return null;
        }

        String pcmPath = pcmFile.getAbsolutePath();
        String wavPath;
        if (pcmPath.toLowerCase().endsWith(".pcm")) {
            wavPath = pcmPath.substring(0, pcmPath.length() - 4) + ".wav";
        } else {
            wavPath = pcmPath + ".wav";
        }
        File wavFile = new File(wavPath);

        // 如果已转换且比 PCM 文件新，直接复用
        if (wavFile.exists() && wavFile.lastModified() >= pcmFile.lastModified()) {
            Log.i(TAG, "WAV文件已存在，跳过转换: " + wavPath);
            return wavFile;
        }

        FileInputStream fis = null;
        try {
            fis = new FileInputStream(pcmFile);
            byte[] pcmData = new byte[(int) pcmFile.length()];
            int read = fis.read(pcmData);
            if (read != pcmData.length) {
                Log.w(TAG, "PCM读取长度不匹配: read=" + read + ", expected=" + pcmData.length);
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(baos);

            // ===== RIFF 文件头 =====
            writeString(dos, "RIFF");
            // 整个文件长度 = 头部 36 字节 + 音频数据长度
            writeIntLittleEndian(dos, 36 + pcmData.length);
            writeString(dos, "WAVE");

            // ===== fmt 子块：描述音频格式 =====
            writeString(dos, "fmt ");
            writeIntLittleEndian(dos, 16);           // subchunk1 size (PCM = 16)
            writeShortLittleEndian(dos, (short) 1);  // audio format (1 = PCM)
            writeShortLittleEndian(dos, channels);   // 声道数（1=单声道，2=双声道）
            writeIntLittleEndian(dos, sampleRate);   // 采样率（Hz），如 16000
            // 每秒字节数 = 采样率 × 声道数 × 位深 / 8
            writeIntLittleEndian(dos, sampleRate * channels * bitsPerSample / 8); // byte rate
            // 单个采样帧字节数 = 声道数 × 位深 / 8
            writeShortLittleEndian(dos, (short) (channels * bitsPerSample / 8));  // block align
            writeShortLittleEndian(dos, bitsPerSample); // 位深（16=16bit）

            // ===== data 子块：原始 PCM 采样数据 =====
            writeString(dos, "data");
            writeIntLittleEndian(dos, pcmData.length); // 数据区字节数
            dos.write(pcmData);                        // 写入全部 PCM 采样数据

            dos.flush();
            byte[] wavData = baos.toByteArray();

            java.io.FileOutputStream fos = new java.io.FileOutputStream(wavFile);
            fos.write(wavData);
            fos.close();

            Log.i(TAG, "PCM转WAV成功: " + wavFile.getAbsolutePath()
                    + " (sampleRate=" + sampleRate + ", channels=" + channels
                    + ", bits=" + bitsPerSample + ", size=" + wavData.length + " bytes)");
            return wavFile;

        } catch (IOException e) {
            Log.e(TAG, "PCM转WAV失败", e);
            // 删除可能的不完整文件
            if (wavFile.exists()) wavFile.delete();
            return null;
        } finally {
            if (fis != null) {
                try { fis.close(); } catch (IOException ignored) {}
            }
        }
    }

    /**
     * 默认参数转换（16kHz, 单声道, 16bit）— 眼镜录音常用参数
     */
    public static File convertPcmToWav(File pcmFile) {
        return convertPcmToWav(pcmFile, 16000, (short) 1, (short) 16);
    }

    // ========== 小端写入辅助方法 ==========

    /**
     * 按 ASCII 逐字节写入字符串（WAV 头中的标识均为 ASCII 字符）
     *
     * @param dos 数据输出流
     * @param str 要写入的 ASCII 字符串
     */
    private static void writeString(DataOutputStream dos, String str) throws IOException {
        for (int i = 0; i < str.length(); i++) {
            dos.writeByte(str.charAt(i) & 0xFF);
        }
    }

    /**
     * 按小端序写入 4 字节 int（低字节在前，WAV 规范要求）
     *
     * @param dos   数据输出流
     * @param value 要写入的 int 值
     */
    private static void writeIntLittleEndian(DataOutputStream dos, int value) throws IOException {
        dos.writeByte(value & 0xFF);
        dos.writeByte((value >> 8) & 0xFF);
        dos.writeByte((value >> 16) & 0xFF);
        dos.writeByte((value >> 24) & 0xFF);
    }

    /**
     * 按小端序写入 2 字节 short
     *
     * @param dos   数据输出流
     * @param value 要写入的 short 值
     */
    private static void writeShortLittleEndian(DataOutputStream dos, short value) throws IOException {
        dos.writeByte(value & 0xFF);
        dos.writeByte((value >> 8) & 0xFF);
    }

    // ==================== 重采样 ====================

    /**
     * 16bit 单声道 PCM 重采样（线性插值）。
     *
     * <p>用途：后端回传的回复音频是 16kHz，而 Android 设备不一定支持 16kHz 输出采样率。
     * 此时需要先升采样到设备支持的采样率（通常 48kHz）再交给 AudioTrack 播放，
     * 否则会出现播放速度不对/创建失败。线性插值对语音质量足够，且不引入额外依赖。</p>
     *
     * @param pcm16le 原始 PCM（小端 16bit 单声道）
     * @param srcRate 源采样率
     * @param dstRate 目标采样率
     * @return 重采样后的 PCM；入参为空或采样率非法时原样返回，避免调用方多做判断
     */
    public static byte[] resamplePcm16(byte[] pcm16le, int srcRate, int dstRate) {
        if (pcm16le == null || pcm16le.length < 2) return pcm16le;
        if (srcRate == dstRate || srcRate <= 0 || dstRate <= 0) return pcm16le;

        int srcSamples = pcm16le.length / 2;
        int dstSamples = (int) ((long) srcSamples * dstRate / srcRate);
        if (dstSamples <= 0) return pcm16le;
        byte[] out = new byte[dstSamples * 2];

        for (int i = 0; i < dstSamples; i++) {
            // 目标第 i 个采样点对应源时间轴上的位置，取下取整点与下一点做线性插值
            double srcPos = (double) i * srcRate / dstRate;
            int i0 = (int) srcPos;
            int i1 = Math.min(i0 + 1, srcSamples - 1);
            double frac = srcPos - i0;

            int s0 = readLe16(pcm16le, i0);
            int s1 = readLe16(pcm16le, i1);
            int v = (int) Math.round(s0 + (s1 - s0) * frac);

            // 裁剪到 16bit 范围，避免插值溢出产生爆音
            if (v > Short.MAX_VALUE) v = Short.MAX_VALUE;
            else if (v < Short.MIN_VALUE) v = Short.MIN_VALUE;

            out[i * 2] = (byte) (v & 0xFF);
            out[i * 2 + 1] = (byte) ((v >> 8) & 0xFF);
        }
        return out;
    }

    /** 读取小端 16bit 采样值（按采样点下标寻址） */
    private static short readLe16(byte[] b, int sampleIndex) {
        int lo = b[sampleIndex * 2] & 0xFF;
        int hi = b[sampleIndex * 2 + 1];
        return (short) ((hi << 8) | lo);
    }
}
