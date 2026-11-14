package com.ttcraft.mixin;

import com.ttcraft.client.TikTokScreen;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameMenuScreen.class)
public abstract class GameMenuScreenMixin extends Screen {
    protected GameMenuScreenMixin(Text title) { super(title); }

    @Inject(method = "initWidgets", at = @At("RETURN"))
    private void addTikTokPauseButton(CallbackInfo ci) {
        // Кнопка в меню паузы рядом с "Сохранить и выйти"
        this.addDrawableChild(ButtonWidget.builder(Text.literal("♪ TikTok"), b -> {
            if (this.client != null) this.client.setScreen(new TikTokScreen(this));
        }).dimensions(this.width / 2 + 106, this.height / 4 + 120 + 24, 60, 20).build());
    }
}
