package com.ttcraft.droidmedia;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.InputStream;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

public final class NativeLoader {
    private static final Logger LOG = LoggerFactory.getLogger("DroidMedia");
    private static volatile boolean loaded = false;

    public static synchronized void ensureLoaded() {
        if (loaded) return;
        String resPath = PlatformDetector.getNativeResourcePath();
        byte[] libBytes;
        try (InputStream in = NativeLoader.class.getResourceAsStream(resPath)) {
            if (in == null) throw new IllegalStateException("Нет нативки в JAR: " + resPath);
            libBytes = in.readAllBytes();
        } catch (Exception e) {
            throw new RuntimeException("Ошибка чтения " + resPath, e);
        }

        List<Path> dirs = new ArrayList<>();
        String tmpEnv = System.getenv("TMPDIR");
        if (tmpEnv != null && !tmpEnv.isEmpty()) dirs.add(Path.of(tmpEnv));
        String tmpProp = System.getProperty("java.io.tmpdir");
        if (tmpProp != null && !tmpProp.isEmpty()) dirs.add(Path.of(tmpProp));
        dirs.add(FabricLoader.getInstance().getGameDir());

        Throwable lastErr = null;
        for (Path base : dirs) {
            try {
                Path dir = base.resolve("droidmedia-natives");
                Files.createDirectories(dir);
                Path out = dir.resolve("libdroidmedia.so");
                Files.write(out, libBytes);
                out.toFile().setExecutable(true, false);
                System.load(out.toAbsolutePath().toString());
                LOG.info("Loaded libdroidmedia.so from {}", out);
                loaded = true;
                return;
            } catch (Throwable t) {
                LOG.warn("Failed loading from {}: {}", base, t.toString());
                lastErr = t;
            }
        }
        throw new RuntimeException("Не удалось загрузить libdroidmedia.so", lastErr);
    }
}
