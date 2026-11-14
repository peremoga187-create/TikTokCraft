package com.ttcraft.client;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;

public final class MinecraftVideoTexture implements AutoCloseable {
    private static final Identifier TEXTURE_ID = Identifier.of("tiktokcraft", "video_frame");
    private NativeImageBackedTexture texture;
    private int width = 0, height = 0;

    public void updateFrame(ByteBuffer argbBytes, int w, int h) {
        if (w <= 0 || h <= 0) return;
        if (texture == null || width != w || height != h) {
            if (texture != null) texture.close();
            width = w; height = h;
            texture = new NativeImageBackedTexture(() -> "tiktokcraft_video", w, h, false);
            MinecraftClient.getInstance().getTextureManager().registerTexture(TEXTURE_ID, texture);
        }
        NativeImage img = texture.getImage();
        if (img == null) return;
        IntBuffer ints = argbBytes.asIntBuffer();
        for (int y = 0; y < h; y++) {
            int rowOffset = y * w;
            for (int x = 0; x < w; x++) img.setColorArgb(x, y, ints.get(rowOffset + x));
        }
        texture.upload();
    }
    public Identifier getTextureId() { return texture != null ? TEXTURE_ID : null; }
    @Override
    public void close() {
        if (texture != null) {
            MinecraftClient.getInstance().getTextureManager().destroyTexture(TEXTURE_ID);
            texture.close(); texture = null;
        }
    }
}
