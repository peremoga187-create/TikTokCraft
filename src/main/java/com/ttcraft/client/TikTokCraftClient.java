package com.ttcraft.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

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
    }
}
