package com.ttcraft.client;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ConfirmLinkScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

public class TikTokScreen extends Screen {
    private final Screen parent;

    public TikTokScreen(Screen parent) {
        super(Text.literal("TikTok Web"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int cy = this.height / 2;

        addDrawableChild(ButtonWidget.builder(Text.literal("Открыть TikTok (Вход / Лента)"), b ->
                ConfirmLinkScreen.open(this, "https://www.tiktok.com/")
        ).dimensions(cx - 120, cy - 24, 240, 22).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("Рекомендации (Explore)"), b ->
                ConfirmLinkScreen.open(this, "https://www.tiktok.com/explore")
        ).dimensions(cx - 120, cy + 4, 240, 20).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("Назад"), b -> close())
                .dimensions(cx - 120, cy + 32, 240, 20).build());
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("TikTokCraft Web"), this.width / 2, this.height / 2 - 52, 0xFF55FFFF);
        context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("Для горизонтального вида включи 'Версия для ПК' в браузере"), this.width / 2, this.height / 2 - 38, 0xFFAAAAAA);
    }

    @Override
    public void close() {
        if (this.client != null) this.client.setScreen(parent);
    }
}
