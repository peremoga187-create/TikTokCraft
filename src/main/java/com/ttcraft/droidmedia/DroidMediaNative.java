package com.ttcraft.droidmedia;
import java.nio.ByteBuffer;

public final class DroidMediaNative {
    public static native long nativeCreate(String filePath);
    public static native void nativeDestroy(long handle);
    public static native int nativeGetWidth(long handle);
    public static native int nativeGetHeight(long handle);
    public static native long nativeGetDurationMs(long handle);
    public static native int nativeGetAudioSampleRate(long handle);
    public static native int nativeGetAudioChannels(long handle);
    public static native long nativeReadVideoFrame(long handle, ByteBuffer argbOutBuffer);
    public static native int nativeReadAudioPcm(long handle, ByteBuffer pcmOutBuffer);
    public static native void nativeSeekToStart(long handle);
}
