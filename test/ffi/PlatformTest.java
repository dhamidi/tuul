package ffi;

import harness.Check;
import java.util.List;

public final class PlatformTest {

    private PlatformTest() {}

    public static void run() {
        names();
        host();
        targets();
    }

    /// The name is written by whatever lays a library out and read by whatever
    /// loads one. If those two ever disagree the library is invisible, so they
    /// share this and it is tested.
    private static void names() {
        var macos = new Platform(Platform.MACOS, "aarch64");
        Check.equal("a platform is its operating system and its instruction set", "macos-aarch64", macos.directory());
        Check.equal("and reads back the way it was written", macos, Platform.of(macos.directory()));
        Check.equal("libraries are named the way the platform names them",
                List.of("libsqlite3.dylib", "libsqlite3.so", "sqlite3.dll"),
                List.of(macos.library("sqlite3"),
                        new Platform(Platform.LINUX, "x86_64").library("sqlite3"),
                        new Platform(Platform.WINDOWS, "x86_64").library("sqlite3")));
        Check.throwing("something that is not a platform is refused", () -> Platform.of("macos"));
    }

    private static void host() {
        var here = Platform.host();
        Check.that("this machine is one tuul ships for: " + here.directory(), here.shipped());
        Check.equal("and its libraries are named the way this JVM names them",
                System.mapLibraryName("sqlite3"),
                here.library("sqlite3"));
        Check.equal("every shipped platform is distinct",
                Platform.SHIPPED.size(),
                (int) Platform.SHIPPED.stream().map(Platform::directory).distinct().count());
    }

    /// What zig is told to build. Linux carries its C library because zig has
    /// to be told which one, and a JDK is built against glibc.
    private static void targets() {
        Check.equal("targets are zig's spelling, not ours",
                List.of("aarch64-macos", "x86_64-linux-gnu", "aarch64-windows-gnu"),
                List.of(new Platform(Platform.MACOS, "aarch64").target(),
                        new Platform(Platform.LINUX, "x86_64").target(),
                        new Platform(Platform.WINDOWS, "aarch64").target()));
    }
}
