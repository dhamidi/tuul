package modules;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.module.ModuleReference;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/// A class loader for one named module backed by an in-memory snapshot.
///
/// A loader owns the classes in its module. It delegates a class in another
/// readable module's package to that module's loader. It refuses packages
/// from the parent's unnamed module. This keeps binary names from becoming a
/// global class map and lets the VM associate every class with a named module.
public final class MemoryModuleLoader extends ClassLoader {

    private final String moduleName;
    private final Map<String, byte[]> entries;
    private final Map<String, MemoryModuleLoader> packageOwners;
    private volatile Module module;
    private volatile Map<String, Module> parentPackageOwners = Map.of();

    private MemoryModuleLoader(MemoryModule module, ClassLoader parent,
            Map<String, MemoryModuleLoader> packageOwners) {
        super(parent);
        moduleName = module.name();
        entries = module.entries();
        this.packageOwners = packageOwners;
    }

    /// Creates one loader per supplied named module.
    public static Map<String, MemoryModuleLoader> create(Collection<MemoryModule> modules,
            ClassLoader parent) {
        Objects.requireNonNull(modules, "modules");
        Objects.requireNonNull(parent, "parent");
        var loaders = new HashMap<String, MemoryModuleLoader>();
        for (var module : modules) {
            if (loaders.containsKey(module.name())) {
                throw new IllegalArgumentException("duplicate module: " + module.name());
            }
            loaders.put(module.name(), null);
        }
        var owners = new HashMap<String, MemoryModuleLoader>();
        for (var module : modules) {
            var loader = new MemoryModuleLoader(module, parent, owners);
            loaders.put(module.name(), loader);
            for (var packageName : module.descriptor().packages()) {
                if (owners.putIfAbsent(packageName, loader) != null) {
                    throw new IllegalArgumentException("split package: " + packageName);
                }
            }
        }
        return Map.copyOf(loaders);
    }

    /// Associates these loaders with the modules created in a fresh layer.
    /// This is called before application code is loaded, so delegation can
    /// enforce the resolved JPMS read edges instead of exposing every module.
    public static void configure(Map<String, MemoryModuleLoader> loaders,
            ModuleLayer layer) {
        Objects.requireNonNull(loaders, "loaders");
        Objects.requireNonNull(layer, "layer");
        var modules = new HashMap<String, Module>();
        for (var entry : loaders.entrySet()) {
            var module = layer.findModule(entry.getKey()).orElseThrow(() ->
                    new IllegalArgumentException("layer has no module: " + entry.getKey()));
            modules.put(entry.getKey(), module);
        }
        var parentPackages = new HashMap<String, Module>();
        for (var parent : layer.parents()) collectPackages(parent, parentPackages);
        for (var entry : loaders.entrySet()) {
            entry.getValue().module = modules.get(entry.getKey());
            entry.getValue().parentPackageOwners = Map.copyOf(parentPackages);
        }
    }

    /// Reads one external named module into memory for use by a candidate layer.
    public static MemoryModule read(ModuleReference reference) throws IOException {
        Objects.requireNonNull(reference, "reference");
        var entries = new HashMap<String, byte[]>();
        try (var reader = reference.open()) {
            try (var names = reader.list()) {
                names.forEach(name -> {
                    try {
                        var bytes = reader.read(name).orElseThrow(
                                () -> new IllegalStateException("module entry disappeared: " + name));
                        var copy = new byte[bytes.remaining()];
                        bytes.get(copy);
                        entries.put(name, copy);
                    } catch (IOException failure) {
                        throw new ModuleReadFailure(failure);
                    }
                });
            }
        } catch (ModuleReadFailure failure) {
            throw failure.failure;
        }
        return new MemoryModule(reference.descriptor(), entries);
    }

    /// The module represented by this loader.
    public String moduleName() { return moduleName; }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            var loaded = findLoadedClass(name);
            if (loaded != null) {
                if (resolve) resolveClass(loaded);
                return loaded;
            }
            var owner = packageOwners.get(packageName(name));
            if (owner != null && owner != this) {
                var requester = module;
                var target = owner.module;
                if (requester == null || target == null || !requester.canRead(target)) {
                    throw new ClassNotFoundException(name);
                }
                return owner.loadClass(name, resolve);
            }
            try {
                var type = findClass(name);
                if (resolve) resolveClass(type);
                return type;
            } catch (ClassNotFoundException missing) {
                var requester = module;
                var parentOwner = parentPackageOwners.get(packageName(name));
                if (requester == null || parentOwner == null || !requester.canRead(parentOwner)) throw missing;
                return super.loadClass(name, resolve);
            }
        }
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        var bytes = entries.get(classEntry(name));
        if (bytes == null) throw new ClassNotFoundException(name);
        return defineClass(name, bytes, 0, bytes.length);
    }

    @Override
    protected Class<?> findClass(String requestedModule, String name) {
        if (!moduleName.equals(requestedModule)) return null;
        try {
            return findClass(name);
        } catch (ClassNotFoundException missing) {
            return null;
        }
    }

    @Override
    public URL getResource(String name) {
        var own = resource(name);
        return own != null ? own : super.getResource(name);
    }

    @Override
    public InputStream getResourceAsStream(String name) {
        var bytes = entry(name);
        return bytes == null ? super.getResourceAsStream(name) : new ByteArrayInputStream(bytes);
    }

    @Override
    protected URL findResource(String name) {
        return resource(name);
    }

    @Override
    protected URL findResource(String requestedModule, String name) {
        return moduleName.equals(requestedModule) ? resource(name) : null;
    }

    private URL resource(String name) {
        var bytes = entry(name);
        if (name == null || name.startsWith("/") || bytes == null) return null;
        try {
            var handler = new URLStreamHandler() {
                @Override protected URLConnection openConnection(URL url) {
                    return new URLConnection(url) {
                        @Override public void connect() {}
                        @Override public InputStream getInputStream() {
                            return new ByteArrayInputStream(bytes.clone());
                        }
                    };
                }
            };
            var uri = new java.net.URI("memory", null, "/" + moduleName + "/" + name, null);
            return URL.of(uri, handler);
        } catch (java.net.URISyntaxException | IOException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private byte[] entry(String name) {
        if (name == null) return null;
        var bytes = entries.get(name.startsWith("/") ? name.substring(1) : name);
        return bytes == null ? null : bytes.clone();
    }

    private String classEntry(String binaryName) {
        return binaryName.replace('.', '/') + ".class";
    }

    private static String packageName(String binaryName) {
        var separator = binaryName.lastIndexOf('.');
        return separator < 0 ? "" : binaryName.substring(0, separator);
    }

    private static void collectPackages(ModuleLayer layer, Map<String, Module> packages) {
        for (var module : layer.modules()) {
            for (var packageName : module.getPackages()) packages.putIfAbsent(packageName, module);
        }
        for (var parent : layer.parents()) collectPackages(parent, packages);
    }

    private static final class ModuleReadFailure extends RuntimeException {
        private final IOException failure;
        private ModuleReadFailure(IOException failure) { this.failure = failure; }
    }
}
