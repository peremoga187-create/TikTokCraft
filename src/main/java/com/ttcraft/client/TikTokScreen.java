package com.ttcraft.client;
import com.ttcraft.droidmedia.*;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.system.MemoryUtil;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class TikTokScreen extends Screen {
    private final String videoPath;
    private long playerHandle = 0;
    private MinecraftVideoTexture videoTexture;
    private OpenAlAudioOutput audioOut;
    private ByteBuffer frameBuffer, pcmBuffer;
    private long startTimeMs = -1, lastFramePtsMs = -1;

    public TikTokScreen(String videoPath) {
        super(Text.literal("TikTokCraft"));
        this.videoPath = videoPath;
    }

    @Override
    protected void init() {
        addDrawableChild(ButtonWidget.builder(Text.literal("След. видео ▼"), btn -> {
            if (playerHandle != 0) {
                DroidMediaNative.nativeSeekToStart(playerHandle);
                startTimeMs = System.currentTimeMillis(); lastFramePtsMs = -1;
            }
        }).dimensions(this.width - 110, this.height / 2 - 10, 100, 20).build());
        try {
            NativeLoader.ensureLoaded();
            playerHandle = DroidMediaNative.nativeCreate(videoPath);
            if (playerHandle != 0) {
                int w = DroidMediaNative.nativeGetWidth(playerHandle);
                int h = DroidMediaNative.nativeGetHeight(playerHandle);
                frameBuffer = MemoryUtil.memAlloc(w * h * 4).order(ByteOrder.nativeOrder());
                pcmBuffer = MemoryUtil.memAlloc(65536).order(ByteOrder.nativeOrder());
                videoTexture = new MinecraftVideoTexture();
                audioOut = new OpenAlAudioOutput(DroidMediaNative.nativeGetAudioSampleRate(playerHandle), DroidMediaNative.nativeGetAudioChannels(playerHandle));
                startTimeMs = System.currentTimeMillis();
            }
        } catch (Throwable t) { t.printStackTrace(); }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        int phoneH = (int) (this.height * 0.85), phoneW = (int) (phoneH * 9.0 / 16.0);
        int x = (this.width - phoneW) / 2, y = (this.height - phoneH) / 2;
        context.fill(x - 4, y - 4, x + phoneW + 4, y + phoneH + 4, 0xFF181818);
        if (playerHandle != 0 && frameBuffer != null) {
            int pcmBytes = DroidMediaNative.nativeReadAudioPcm(playerHandle, pcmBuffer);
            if (pcmBytes > 0 && audioOut != null) audioOut.queuePcm(pcmBuffer, pcmBytes);
            long elapsed = System.currentTimeMillis() - startTimeMs;
            if (lastFramePtsMs < elapsed) {
                long pts = DroidMediaNative.nativeReadVideoFrame(playerHandle, frameBuffer);
                if (pts >= 0) {
                    lastFramePtsMs = pts;
                    videoTexture.updateFrame(frameBuffer, DroidMediaNative.nativeGetWidth(playerHandle), DroidMediaNative.nativeGetHeight(playerHandle));
                } else if (pts == -2) {
                    DroidMediaNative.nativeSeekToStart(playerHandle);
                    startTimeMs = System.currentTimeMillis(); lastFramePtsMs = -1;
                }
            }
            Identifier texId = videoTexture.getTextureId();
            if (texId != null) context.drawTexture(RenderPipelines.GUI_TEXTURED, texId, x, y, 0.0f, 0.0f, phoneW, phoneH, phoneW, phoneH);
        } else {
            context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("Положи тестовое видео в .minecraft/tiktok_test.mp4"), this.width / 2, this.height / 2, 0xFFFFFFFF);
        }
    }

    @Override
    public void removed() {
        if (playerHandle != 0) { DroidMediaNative.nativeDestroy(playerHandle); playerHandle = 0; }
        if (videoTexture != null) { videoTexture.close(); videoTexture = null; }
        if (audioOut != null) { audioOut.close(); audioOut = null; }
        if (frameBuffer != null) { MemoryUtil.memFree(frameBuffer); frameBuffer = null; }
        if (pcmBuffer != null) { MemoryUtil.memFree(pcmBuffer); pcmBuffer = null; }
        super.removed();
    }
}
