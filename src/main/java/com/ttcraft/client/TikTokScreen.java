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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class TikTokScreen extends Screen {
    private static final Logger LOG = LoggerFactory.getLogger("TikTokCraft");
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
        int topY = 8;
        int searchW = Math.max(70, Math.min(180, this.width - 230));

        searchBox = new TextFieldWidget(this.textRenderer, 8, topY, searchW, 20, Text.literal("Поиск, @ник или ссылка"));
        searchBox.setMaxLength(256);
        addDrawableChild(searchBox);

        addDrawableChild(ButtonWidget.builder(Text.literal("Найти"), b -> loadFeed(searchBox.getText()))
                .dimensions(12 + searchW, topY, 42, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Лента"), b -> {
            searchBox.setText("");
            loadFeed("");
        }).dimensions(58 + searchW, topY, 42, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Сайт/Войти"), b ->
                ConfirmLinkScreen.open(this, "https://www.tiktok.com/")
        ).dimensions(104 + searchW, topY, 72, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("X"), b -> close())
                .dimensions(this.width - 26, topY, 20, 20).build());

        int phoneH = (int) (this.height * 0.78);
        int phoneW = (int) (phoneH * 9.0 / 16.0);
        int ctrlX = Math.min(this.width - 50, (this.width + phoneW) / 2 + 8);
        int midY = this.height / 2;

        addDrawableChild(ButtonWidget.builder(Text.literal("▲"), b -> switchVideo(-1)).dimensions(ctrlX, midY - 46, 44, 20).build());
        pauseBtn = addDrawableChild(ButtonWidget.builder(Text.literal("⏸"), b -> togglePause()).dimensions(ctrlX, midY - 22, 44, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("▼"), b -> switchVideo(1)).dimensions(ctrlX, midY + 2, 44, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("↻"), b -> restartVideo()).dimensions(ctrlX, midY + 26, 44, 20).build());

        if (feed.isEmpty()) loadFeed("");
    }

    private void loadFeed(String query) {
        closePlayer();
        feed = new ArrayList<>();
        currentIndex = 0;
        statusText = query.trim().isEmpty() ? "Загрузка рекомендаций..." : "Загрузка: " + query + "...";

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
                LOG.info("Opened video {}x{} from {}", w, h, path);
                if (w > 0 && h > 0) {
                    frameBuffer = MemoryUtil.memAlloc(w * h * 4).order(ByteOrder.nativeOrder());
                    pcmBuffer = MemoryUtil.memAlloc(65536).order(ByteOrder.nativeOrder());
                    videoTexture = new MinecraftVideoTexture();
                    audioOut = new OpenAlAudioOutput(DroidMediaNative.nativeGetAudioSampleRate(playerHandle), DroidMediaNative.nativeGetAudioChannels(playerHandle));
                    startTimeMs = System.currentTimeMillis(); lastFramePtsMs = -1; paused = false;
                } else { closePlayer(); statusText = "Ошибка размера: " + w + "x" + h; }
            } else {
                LOG.error("nativeCreate returned 0 for {}", path);
                statusText = "MediaCodec не открыл mp4";
            }
        } catch (Throwable t) {
            LOG.error("Failed opening video " + path, t);
            closePlayer();
            statusText = t.getClass().getSimpleName() + ": " + t.getMessage();
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        int phoneH = (int) (this.height * 0.78), phoneW = (int) (phoneH * 9.0 / 16.0);
        int x = (this.width - phoneW) / 2, y = 34 + (this.height - 38 - phoneH) / 2;
        context.fill(x - 3, y - 3, x + phoneW + 3, y + phoneH + 3, 0xFF141414);

        TikTokVideo cur = feed.isEmpty() ? null : feed.get(currentIndex);
        if (cur != null) {
            if (cur.localPath != null && !cur.localPath.equals(openedPath) && playerHandle == 0) tryOpenVideo(cur.localPath);
            else if (cur.failed && playerHandle == 0) statusText = "Скачивание: " + (cur.errorMsg != null ? cur.errorMsg : "ошибка");
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
            context.drawCenteredTextWithShadow(this.textRenderer, Text.literal(statusText), this.width / 2, y + phoneH / 2, 0xFFFF5555);
        }

        if (cur != null) {
            int infoY = y + phoneH - 34;
            context.fill(x, infoY, x + phoneW, y + phoneH, 0x88000000);
            context.drawTextWithShadow(this.textRenderer, Text.literal(cur.author + "  [" + cur.likes + "]"), x + 5, infoY + 4, 0xFF55FFFF);
            String desc = cur.title == null ? "" : cur.title.replace("\n", " ");
            if (desc.length() > 28) desc = desc.substring(0, 27) + "...";
            context.drawTextWithShadow(this.textRenderer, Text.literal(desc), x + 5, infoY + 16, 0xFFFFFFFF);
            context.drawTextWithShadow(this.textRenderer, Text.literal((currentIndex + 1) + "/" + feed.size()), x + 5, y + 5, 0xAAFFFFFF);
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
