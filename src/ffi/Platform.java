package ffi;

import java.util.List;
import java.util.Locale;

/// Which machine a native library is for.
///
/// One name, used by everything: the thing that cross-builds a library, the
/// thing that lays it out in `vendor/`, and the thing that picks one at run
/// time. A layout whose writer and reader disagree about a name is a library
/// nobody can find, so they share this.
///
/// `macos-aarch64`, `linux-x86_64`, `windows-x86_64` — the operating system,
/// then the instruction set, both spelled the one way.
public record Platform(String os, String arch) {

    public static final String MACOS = "macos";
    public static final String LINUX = "linux";
    public static final String WINDOWS = "windows";

    /// The platforms tuul ships a library for. A machine outside this list can
    /// still build from source; it just cannot be handed a binary.
    public static final List<Platform> SHIPPED = List.of(
            new Platform(MACOS, "aarch64"),
            new Platform(MACOS, "x86_64"),
            new Platform(LINUX, "x86_64"),
            new Platform(LINUX, "aarch64"),
            new Platform(WINDOWS, "x86_64"),
            new Platform(WINDOWS, "aarch64"));

    /// The machine this is running on, as this JVM understands it. A JVM built
    /// for one instruction set running on another — an x86_64 JDK on Apple
    /// silicon — reports the one it was built for, which is the one whose
    /// libraries it can actually load.
    public static Platform host() {
        var name = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        var machine = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        return new Platform(os(name), arch(machine));
    }

    /// Reads back what [#directory()] wrote.
    public static Platform of(String directory) {
        var parts = directory.split("-", 2);
        if (parts.length != 2) throw new IllegalArgumentException("not a platform: " + directory);
        return new Platform(parts[0], parts[1]);
    }

    /// The directory a library for this platform lives in.
    public String directory() {
        return os + "-" + arch;
    }

    /// What a library is called here. [java.lang.System#mapLibraryName] answers
    /// for the running machine only, and cross-building means naming a file for
    /// a machine this is not.
    public String library(String name) {
        return switch (os) {
            case MACOS -> "lib" + name + ".dylib";
            case WINDOWS -> name + ".dll";
            default -> "lib" + name + ".so";
        };
    }

    /// What zig calls this target. Linux is named with its C library because
    /// zig needs to be told which one, and glibc is what a JDK is built against.
    public String target() {
        return switch (os) {
            case MACOS -> arch + "-macos";
            case WINDOWS -> arch + "-windows-gnu";
            default -> arch + "-linux-gnu";
        };
    }

    public boolean shipped() {
        return SHIPPED.contains(this);
    }

    private static String os(String name) {
        if (name.contains("mac") || name.contains("darwin")) return MACOS;
        if (name.contains("win")) return WINDOWS;
        return LINUX;
    }

    private static String arch(String machine) {
        if (machine.equals("aarch64") || machine.equals("arm64")) return "aarch64";
        if (machine.equals("x86_64") || machine.equals("amd64")) return "x86_64";
        return machine;
    }
}
