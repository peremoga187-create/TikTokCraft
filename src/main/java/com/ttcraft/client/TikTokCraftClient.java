package com.ttcraft.client;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

public class TikTokCraftClient implements ClientModInitializer {
    private static KeyMapping openPhoneKey;
    @Override
    public void onInitializeClient() {
        KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("tiktokcraft", "main"));
        openPhoneKey = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.tiktokcraft.open", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_T, category));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openPhoneKey.consumeClick()) {
                String testVideo = FabricLoader.getInstance().getGameDir().resolve("tiktok_test.mp4").toAbsolutePath().toString();
                client.setScreen(new TikTokScreen(testVideo));
            }
        });
    }
}
