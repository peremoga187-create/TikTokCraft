package com.ttcraft.client;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;

public final class MinecraftVideoTexture implements AutoCloseable {
    private static final Identifier TEXTURE_ID = Identifier.fromNamespaceAndPath("tiktokcraft", "video_frame");
    private DynamicTexture texture;
    private int width = 0, height = 0;

    public void updateFrame(ByteBuffer argbBytes, int w, int h) {
        if (w <= 0 || h <= 0) return;
        if (texture == null || width != w || height != h) {
            if (texture != null) texture.close();
            width = w; height = h;
            texture = new DynamicTexture(() -> "tiktokcraft_video", new NativeImage(w, h, false));
            Minecraft.getInstance().getTextureManager().register(TEXTURE_ID, texture);
        }
        NativeImage img = texture.getPixels();
        if (img == null) return;
        IntBuffer ints = argbBytes.asIntBuffer();
        for (int y = 0; y < h; y++) {
            int rowOffset = y * w;
            for (int x = 0; x < w; x++) img.setPixel(x, y, ints.get(rowOffset + x));
        }
        texture.upload();
    }
    public Identifier getTextureId() { return texture != null ? TEXTURE_ID : null; }
    @Override
    public void close() {
        if (texture != null) {
            Minecraft.getInstance().getTextureManager().release(TEXTURE_ID);
            texture.close(); texture = null;
        }
    }
}
