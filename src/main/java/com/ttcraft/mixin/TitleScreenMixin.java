package com.ttcraft.mixin;

import com.ttcraft.client.TikTokScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
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
        int bx = this.width / 2 + 128;
        int by = this.height / 4 + 132;
        for (var el : this.children()) {
            if (el instanceof ClickableWidget w && w.getY() >= this.height / 4 + 100) {
                int right = w.getX() + w.getWidth() + 4;
                if (right > bx) { bx = right; by = w.getY(); }
            }
        }
        bx = Math.min(this.width - 58, bx);
        this.addDrawableChild(ButtonWidget.builder(Text.literal("TikTok"), b -> {
            if (this.client != null) this.client.setScreen(new TikTokScreen(this));
        }).dimensions(bx, by, 54, 20).build());
    }
}
