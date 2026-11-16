package com.ttcraft.client;

import com.ttcraft.droidmedia.*;
import com.ttcraft.tiktok.*;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ConfirmLinkScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class TikTokScreen extends Screen {
    private final Screen parent;
    private List<TikTokVideo> feed = new ArrayList<>();
    private int currentIndex = 0;
    private TextFieldWidget searchBox;
    private ButtonWidget pauseBtn;

    private long playerHandle = 0;
    private String openedPath = null;
    private MinecraftVideoTexture videoTexture;
    private OpenAlAudioOutput audioOut;
    private ByteBuffer frameBuffer, pcmBuffer;

    private long startTimeMs = -1, lastFramePtsMs = -1, pausedAtMs = -1;
    private boolean paused = false, screenClosed = false;
    private String statusText = "Загрузка ленты TikTok...";

    public TikTokScreen(Screen parent) {
        super(Text.literal("TikTokCraft"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int topY = 6;
        int searchW = Math.max(60, this.width - 216);

        searchBox = new TextFieldWidget(this.textRenderer, 6, topY, searchW, 20, Text.literal("Поиск, @ник или темы"));
        searchBox.setMaxLength(256);
        searchBox.setText(TikTokFeedService.loadSavedTopics());
        addDrawableChild(searchBox);

        addDrawableChild(ButtonWidget.builder(Text.literal("Найти"), b -> loadFeed(searchBox.getText()))
                .dimensions(this.width - 206, topY, 42, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("★Темы"), b -> {
            TikTokFeedService.saveTopics(searchBox.getText());
            loadFeed("");
        }).dimensions(this.width - 162, topY, 48, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Лента"), b -> loadFeed(""))
                .dimensions(this.width - 112, topY, 42, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Сайт"), b ->
                ConfirmLinkScreen.open(this, "https://www.tiktok.com/")
        ).dimensions(this.width - 68, topY, 42, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("X"), b -> close())
                .dimensions(this.width - 24, topY, 18, 20).build());

        int phoneH = Math.max(100, this.height - 38);
        int phoneW = (int) (phoneH * 9.0 / 16.0);
        int ctrlX = Math.min(this.width - 44, (this.width + phoneW) / 2 + 6);
        int midY = 30 + phoneH / 2;

        addDrawableChild(ButtonWidget.builder(Text.literal("▲"), b -> switchVideo(-1)).dimensions(ctrlX, midY - 46, 38, 20).build());
        pauseBtn = addDrawableChild(ButtonWidget.builder(Text.literal("⏸"), b -> togglePause()).dimensions(ctrlX, midY - 22, 38, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("▼"), b -> switchVideo(1)).dimensions(ctrlX, midY + 2, 38, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("↻"), b -> restartVideo()).dimensions(ctrlX, midY + 26, 38, 20).build());

        if (feed.isEmpty()) loadFeed("");
    }

    private void loadFeed(String query) {
        closePlayer();
        feed = new ArrayList<>();
        currentIndex = 0;
        statusText = query.trim().isEmpty() ? "Загрузка ленты..." : "Поиск: " + query + "...";

        TikTokFeedService.fetchFeedAsync(query, list -> {
            if (screenClosed || this.client == null) return;
            this.client.execute(() -> {
                if (screenClosed) return;
                this.feed = list;
                this.currentIndex = 0;
                prepareAroundCurrent();
            });
        }, err -> {
            if (screenClosed || this.client == null) return;
            this.client.execute(() -> {
                Path localTest = FabricLoader.getInstance().getGameDir().resolve("tiktok_test.mp4");
                if (Files.exists(localTest)) {
                    TikTokVideo v = new TikTokVideo("local", "@local", "Локальный tiktok_test.mp4", "", 0);
                    v.localPath = localTest.toAbsolutePath().toString();
                    this.feed = List.of(v);
                    this.currentIndex = 0;
                } else { statusText = err; }
            });
        });
    }

    private void prepareAroundCurrent() {
        if (feed.isEmpty()) return;
        TikTokFeedService.ensureDownloadedAsync(feed.get(currentIndex));
        if (currentIndex + 1 < feed.size()) TikTokFeedService.ensureDownloadedAsync(feed.get(currentIndex + 1));
    }

    private void switchVideo(int delta) {
        if (feed.isEmpty()) return;
        currentIndex = (currentIndex + delta + feed.size()) % feed.size();
        paused = false;
        if (pauseBtn != null) pauseBtn.setMessage(Text.literal("⏸"));
        closePlayer();
        prepareAroundCurrent();
    }

    private void togglePause() {
        if (playerHandle == 0) return;
        if (!paused) {
            paused = true; pausedAtMs = System.currentTimeMillis();
            if (pauseBtn != null) pauseBtn.setMessage(Text.literal("▶"));
        } else {
            paused = false;
            if (pausedAtMs > 0) startTimeMs += (System.currentTimeMillis() - pausedAtMs);
            if (pauseBtn != null) pauseBtn.setMessage(Text.literal("⏸"));
        }
    }

    private void restartVideo() {
        if (playerHandle == 0) return;
        DroidMediaNative.nativeSeekToStart(playerHandle);
        startTimeMs = System.currentTimeMillis(); lastFramePtsMs = -1; paused = false;
        if (pauseBtn != null) pauseBtn.setMessage(Text.literal("⏸"));
    }

    private void tryOpenVideo(String path) {
        closePlayer();
        openedPath = path;
        try {
            NativeLoader.ensureLoaded();
            playerHandle = DroidMediaNative.nativeCreate(path);
            if (playerHandle != 0) {
                int w = DroidMediaNative.nativeGetWidth(playerHandle);
                int h = DroidMediaNative.nativeGetHeight(playerHandle);
                if (w > 0 && h > 0) {
                    frameBuffer = MemoryUtil.memAlloc(w * h * 4).order(ByteOrder.nativeOrder());
                    pcmBuffer = MemoryUtil.memAlloc(65536).order(ByteOrder.nativeOrder());
                    videoTexture = new MinecraftVideoTexture();
                    audioOut = new OpenAlAudioOutput(DroidMediaNative.nativeGetAudioSampleRate(playerHandle), DroidMediaNative.nativeGetAudioChannels(playerHandle));
                    startTimeMs = System.currentTimeMillis(); lastFramePtsMs = -1; paused = false;
                } else { closePlayer(); statusText = "Ошибка размера видео"; }
            } else { statusText = "Кодек не открыл видео (жми ▼)"; }
        } catch (Throwable t) {
            closePlayer(); statusText = "Ошибка: " + t.getClass().getSimpleName();
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        int phoneH = Math.max(100, this.height - 38), phoneW = (int) (phoneH * 9.0 / 16.0);
        int x = (this.width - phoneW) / 2, y = 30;
        context.fill(x - 3, y - 3, x + phoneW + 3, y + phoneH + 3, 0xFF141414);

        TikTokVideo cur = feed.isEmpty() ? null : feed.get(currentIndex);
        if (cur != null) {
            if (cur.localPath != null && !cur.localPath.equals(openedPath) && playerHandle == 0) tryOpenVideo(cur.localPath);
            else if (cur.failed && playerHandle == 0) statusText = "Не удалось скачать (жми ▼)";
            else if (cur.downloading && playerHandle == 0) statusText = "Скачивание видео...";
        }

        if (playerHandle != 0 && frameBuffer != null) {
            if (!paused) {
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
            }
            Identifier texId = videoTexture != null ? videoTexture.getTextureId() : null;
            if (texId != null) context.drawTexture(RenderPipelines.GUI_TEXTURED, texId, x, y, 0.0f, 0.0f, phoneW, phoneH, phoneW, phoneH);
        } else {
            context.drawCenteredTextWithShadow(this.textRenderer, Text.literal(statusText), x + phoneW / 2, y + phoneH / 2, 0xFFFFFFFF);
        }

        if (cur != null) {
            int infoY = y + phoneH - 30;
            context.fill(x, infoY, x + phoneW, y + phoneH, 0x88000000);
            context.drawTextWithShadow(this.textRenderer, Text.literal(cur.author + " [" + cur.likes + "]"), x + 4, infoY + 3, 0xFF55FFFF);
            String desc = cur.title == null ? "" : cur.title.replace("\n", " ");
            if (desc.length() > 24) desc = desc.substring(0, 23) + "...";
            context.drawTextWithShadow(this.textRenderer, Text.literal(desc), x + 4, infoY + 15, 0xFFFFFFFF);
            context.drawTextWithShadow(this.textRenderer, Text.literal((currentIndex + 1) + "/" + feed.size()), x + 4, y + 4, 0xAAFFFFFF);
        }
    }

    private void closePlayer() {
        if (playerHandle != 0) { DroidMediaNative.nativeDestroy(playerHandle); playerHandle = 0; }
        if (videoTexture != null) { videoTexture.close(); videoTexture = null; }
        if (audioOut != null) { audioOut.close(); audioOut = null; }
        if (frameBuffer != null) { MemoryUtil.memFree(frameBuffer); frameBuffer = null; }
        if (pcmBuffer != null) { MemoryUtil.memFree(pcmBuffer); pcmBuffer = null; }
        openedPath = null;
    }

    @Override public void close() { if (this.client != null) this.client.setScreen(parent); }

    @Override
    public void removed() {
        screenClosed = true;
        closePlayer();
        super.removed();
    }
}
