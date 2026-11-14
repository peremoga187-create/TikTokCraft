package com.ttcraft.client;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ConfirmLinkScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Util;

public class TikTokScreen extends Screen {
    private final Screen parent;

    public TikTokScreen(Screen parent) {
        super(Text.literal("TikTok Web"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int centerY = this.height / 2;

        // Кнопка открытия полной веб-версии TikTok (горизонтальной, для ПК)
        addDrawableChild(ButtonWidget.builder(Text.literal("🌐 Открыть TikTok (Web / Вход в аккаунт)"), b -> {
            openTikTokWeb("https://www.tiktok.com/");
        }).dimensions(centerX - 130, centerY - 20, 260, 24).build());

        // Кнопка открытия рекомендаций
        addDrawableChild(ButtonWidget.builder(Text.literal("🔥 Рекомендации (Trending)"), b -> {
            openTikTokWeb("https://www.tiktok.com/explore");
        }).dimensions(centerX - 130, centerY + 10, 260, 20).build());

        // Кнопка возврата в игру
        addDrawableChild(ButtonWidget.builder(Text.literal("Назад в игру"), b -> close())
                .dimensions(centerX - 130, centerY + 40, 260, 20).build());
    }

    private void openTikTokWeb(String url) {
        if (this.client != null) {
            this.client.setScreen(new ConfirmLinkScreen(confirmed -> {
                if (confirmed) {
                    Util.getOperatingSystem().open(url);
                }
                this.client.setScreen(this);
            }, url, true));
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("§l♪ TikTokCraft Web"), this.width / 2, this.height / 2 - 50, 0xFFFFFFFF);
        context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("§7Горизонтальный режим, авторизация и просмотр"), this.width / 2, this.height / 2 - 36, 0xAAAAAAAA);
    }

    @Override
    public void close() {
        if (this.client != null) this.client.setScreen(parent);
    }
}
