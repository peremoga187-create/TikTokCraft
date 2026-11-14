package com.ttcraft.droidmedia;
import org.lwjgl.openal.AL10;
import java.nio.ByteBuffer;

public final class OpenAlAudioOutput implements AutoCloseable {
    private final int source, format, sampleRate;
    public OpenAlAudioOutput(int sampleRate, int channels) {
        this.sampleRate = sampleRate;
        this.format = (channels == 1) ? AL10.AL_FORMAT_MONO16 : AL10.AL_FORMAT_STEREO16;
        this.source = AL10.alGenSources();
    }
    public void queuePcm(ByteBuffer pcmData, int bytesCount) {
        if (bytesCount <= 0) return;
        cleanupProcessed();
        if (AL10.alGetSourcei(source, AL10.AL_BUFFERS_QUEUED) > 12) return;
        ByteBuffer slice = pcmData.duplicate();
        slice.position(0).limit(bytesCount);
        int buf = AL10.alGenBuffers();
        AL10.alBufferData(buf, format, slice, sampleRate);
        AL10.alSourceQueueBuffers(source, buf);
        if (AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE) != AL10.AL_PLAYING) AL10.alSourcePlay(source);
    }
    private void cleanupProcessed() {
        int processed = AL10.alGetSourcei(source, AL10.AL_BUFFERS_PROCESSED);
        while (processed-- > 0) AL10.alDeleteBuffers(AL10.alSourceUnqueueBuffers(source));
    }
    @Override
    public void close() {
        AL10.alSourceStop(source); cleanupProcessed();
        int queued = AL10.alGetSourcei(source, AL10.AL_BUFFERS_QUEUED);
        while (queued-- > 0) AL10.alDeleteBuffers(AL10.alSourceUnqueueBuffers(source));
        AL10.alDeleteSources(source);
    }
}
