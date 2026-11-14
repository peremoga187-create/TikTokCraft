package com.ttcraft.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;
import java.util.List;

public class TikTokCraftClient implements ClientModInitializer {
    private static KeyBinding openPhoneKey;

    @Override
    public void onInitializeClient() {
        KeyBinding.Category category = KeyBinding.Category.create(Identifier.of("tiktokcraft", "main"));
        openPhoneKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.tiktokcraft.open", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_T, category));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openPhoneKey.wasPressed()) {
                client.setScreen(new TikTokScreen(client.currentScreen));
            }
        });

        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            List<ClickableWidget> buttons = Screens.getButtons(screen);

            if (screen instanceof TitleScreen) {
                ClickableWidget rightBottom = null;
                for (ClickableWidget w : buttons) {
                    if (w.getY() >= scaledHeight / 4 + 100) {
                        if (rightBottom == null || (w.getX() + w.getWidth()) > (rightBottom.getX() + rightBottom.getWidth())) {
                            rightBottom = w;
                        }
                    }
                }
                int bx = rightBottom != null ? Math.min(scaledWidth - 48, rightBottom.getX() + rightBottom.getWidth() + 4) : scaledWidth / 2 + 128;
                int by = rightBottom != null ? rightBottom.getY() : scaledHeight / 4 + 132;
                buttons.add(ButtonWidget.builder(Text.literal("TikTok"), b -> client.setScreen(new TikTokScreen(screen)))
                        .dimensions(bx, by, 46, 20).build());
            } else if (screen instanceof GameMenuScreen) {
                ClickableWidget bottomBtn = null;
                for (ClickableWidget w : buttons) {
                    if (bottomBtn == null || w.getY() > bottomBtn.getY()) bottomBtn = w;
                }
                int bx = bottomBtn != null ? Math.min(scaledWidth - 48, bottomBtn.getX() + bottomBtn.getWidth() + 4) : scaledWidth / 2 + 106;
                int by = bottomBtn != null ? bottomBtn.getY() : scaledHeight / 4 + 120;
                buttons.add(ButtonWidget.builder(Text.literal("TikTok"), b -> client.setScreen(new TikTokScreen(screen)))
                        .dimensions(bx, by, 46, 20).build());
            }
        });
    }
}
