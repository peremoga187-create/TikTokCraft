package com.ttcraft.droidmedia;
import java.io.File;
import java.util.Locale;

public final class PlatformDetector {
    public enum OS { ANDROID, LINUX, WINDOWS, MACOS, UNKNOWN }
    public enum Arch { ARM64, ARM32, X86_64, UNKNOWN }

    public static OS detectOS() {
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String osVer = System.getProperty("os.version", "").toLowerCase(Locale.ROOT);
        if (osVer.contains("android") || System.getenv("POJAV_NATIVEDIR") != null
                || System.getenv("FCL_NATIVEDIR") != null || System.getenv("ANDROID_ROOT") != null
                || new File("/system/build.prop").exists()) return OS.ANDROID;
        if (osName.contains("win")) return OS.WINDOWS;
        if (osName.contains("mac") || osName.contains("darwin")) return OS.MACOS;
        if (osName.contains("nux") || osName.contains("nix")) return OS.LINUX;
        return OS.UNKNOWN;
    }

    public static Arch detectArch() {
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (arch.contains("aarch64") || arch.contains("arm64")) return Arch.ARM64;
        if (arch.contains("64") || arch.contains("amd64") || arch.contains("x86_64")) return Arch.X86_64;
        if (arch.contains("arm")) return Arch.ARM32;
        return Arch.UNKNOWN;
    }

    public static String getNativeResourcePath() {
        OS os = detectOS(); Arch arch = detectArch();
        if (os == OS.ANDROID) {
            return switch (arch) {
                case ARM64 -> "/natives/android-arm64/libdroidmedia.so";
                case X86_64 -> "/natives/android-x86_64/libdroidmedia.so";
                default -> throw new UnsupportedOperationException("Unsupported Android arch: " + arch);
            };
        }
        throw new UnsupportedOperationException("Пока собрано только под Android (" + os + "/" + arch + ")");
    }
}
