package com.ttcraft.mixin;

import com.ttcraft.client.TikTokScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TitleScreen.class)
public abstract class TitleScreenMixin extends Screen {
    protected TitleScreenMixin(Text title) { super(title); }

    @Inject(method = "init", at = @At("RETURN"))
    private void addTikTokButton(CallbackInfo ci) {
        // Кнопка в Главном меню рядом с кнопками доступности/языка
        this.addDrawableChild(ButtonWidget.builder(Text.literal("♪ TikTok"), b -> {
            if (this.client != null) this.client.setScreen(new TikTokScreen(this));
        }).dimensions(this.width / 2 + 104, this.height / 4 + 132, 60, 20).build());
    }
}
