package com.ttcraft.droidmedia;
import net.fabricmc.loader.api.FabricLoader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;

public final class NativeLoader {
    private static volatile boolean loaded = false;
    public static synchronized void ensureLoaded() {
        if (loaded) return;
        String resPath = PlatformDetector.getNativeResourcePath();
        try (InputStream in = NativeLoader.class.getResourceAsStream(resPath)) {
            if (in == null) throw new IllegalStateException("Нативка не найдена: " + resPath);
            Path dir = FabricLoader.getInstance().getGameDir().resolve("droidmedia-natives");
            Files.createDirectories(dir);
            Path out = dir.resolve("libdroidmedia.so");
            Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
            System.load(out.toAbsolutePath().toString());
            loaded = true;
        } catch (IOException e) { throw new RuntimeException("Ошибка загрузки libdroidmedia.so", e); }
    }
}
