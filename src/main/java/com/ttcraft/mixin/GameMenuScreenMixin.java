package com.ttcraft.mixin;

import com.ttcraft.client.TikTokScreen;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameMenuScreen.class)
public abstract class GameMenuScreenMixin extends Screen {
    protected GameMenuScreenMixin(Text title) { super(title); }

    @Inject(method = "init", at = @At("RETURN"))
    private void addTikTokPauseButton(CallbackInfo ci) {
        int bx = this.width / 2 + 106;
        int by = 0;
        for (var el : this.children()) {
            if (el instanceof ClickableWidget w && w.getY() >= by) {
                by = w.getY();
                bx = Math.min(this.width - 58, w.getX() + w.getWidth() + 4);
            }
        }
        if (by == 0) by = this.height / 4 + 120;
        this.addDrawableChild(ButtonWidget.builder(Text.literal("TikTok"), b -> {
            if (this.client != null) this.client.setScreen(new TikTokScreen(this));
        }).dimensions(bx, by, 54, 20).build());
    }
}
