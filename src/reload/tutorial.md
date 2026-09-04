# Tutorial: embed the reload coordinator

Use one coordinator to replace a complete set of definitions without changing
work that already acquired the prior generation.

For exact behavior, read [reference.md](reference.md).

## Define a capability

Create the capability key in host code. Every generation uses that same key:

```java
var message = Capability.<String>create();
```

The host owns the key. Candidate code supplies its value.

## Start the coordinator

Connect an in-process source when another component submits revisions:

```java
var source = new MemoryRevisionSource();
var reload = new Reload().source(source);
```

`source` produces revisions. `reload` validates, activates, and retires their
generations.

## Submit the first generation

Submit a program that attaches the first value:

```java
source.submit(Revision.of("one",
        () -> Generation.empty().with(message, "hello")));
```

Acquire a lease before reading a generation:

```java
try (var lease = reload.lease().orElseThrow()) {
    System.out.println(lease.generation().capability(message).orElseThrow());
}
```

The program prints `hello`. Closing the lease permits that generation to
retire after a replacement activates.

## Replace the generation

Submit another complete program:

```java
source.submit(Revision.of("two",
        () -> Generation.empty().with(message, "hello again")));
```

A new lease reads `hello again`. A lease acquired before the replacement keeps
reading `hello` until it closes.

## Reject a candidate

Submit a program that throws:

```java
source.submit(Revision.of("broken", () -> {
    throw new IllegalStateException("not ready");
}));
```

The coordinator records a problem for `broken`. The next lease still acquires
revision `two`.

## Stop the coordinator

Close the coordinator when the host stops:

```java
reload.close();
```

Close stops its sources and refuses new leases. A lease already acquired keeps
its generation until that lease closes.

Use the [web.reload tutorial](../web/reload/tutorial.md) when the leased
capability is an HTTP handler.

## Run a generation-owned JDK tool

Create this external named module in one candidate source root:

```java
// candidate/module-info.java
module example.tools {
    provides java.util.spi.ToolProvider with example.tools.EchoTool;
}
```

Add the provider class to the same source root:

```java
// candidate/example/tools/EchoTool.java
package example.tools;

import java.io.PrintWriter;
import java.util.spi.ToolProvider;

public final class EchoTool implements ToolProvider {
    @Override public String name() { return "echo"; }

    @Override public java.util.Optional<String> description() {
        return java.util.Optional.of("writes each argument");
    }

    @Override public int run(PrintWriter out, PrintWriter err, String... arguments) {
        for (var argument : arguments) out.println(argument);
        return 0;
    }
}
```

Wire the host factory to that root module. The host owns the source list and
the compiler. The candidate root owns `module-info.java` and `EchoTool.java`:

```java
// host/module-info.java
module example.host {
    requires tuul;
}
```

```java
// host/Host.java
package example.host;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.List;
import java.util.spi.ToolProvider;
import reload.JdkServiceFactory;
import reload.JdkToolCatalog;
import reload.Reload;
import reload.Revision;
import reload.RevisionCompiler;

public final class Host {
    public static void main(String[] args) throws Exception {
        var root = Path.of("candidate");
        var descriptor = root.resolve("module-info.java");
        var provider = root.resolve("example/tools/EchoTool.java");
        var source = new Revision.SourceModule("example.tools", root, descriptor,
                List.of(descriptor, provider), List.of());
        var factory = new JdkServiceFactory(ToolProvider.class);
        var compiler = new RevisionCompiler(List.of(), factory);
        var revision = compiler.compile(Revision.from("example.tools", List.of(source), List.of()));

        try (var reload = new Reload()) {
            reload.submit(revision);
            var catalog = new JdkToolCatalog(reload);
            catalog.list().forEach(tool -> System.out.println(
                    tool.name() + ": " + tool.description()));
            var code = catalog.run("echo", new PrintWriter(System.out, true),
                    new PrintWriter(System.err, true), "hello");
            System.out.println("exit=" + code);
        }
    }
}
```

`module tuul` owns the `uses java.util.spi.ToolProvider` declaration. The
candidate root owns the `provides` declaration. `RevisionCompiler` compiles
`module-info.java`, and `CandidateContext` reads the resulting module
descriptor before `JdkServiceFactory` loads the provider.

Construct `JdkToolCatalog` once in the stable host. `list()` returns immutable
names and descriptions. `run()` acquires one lease, runs the selected provider,
and returns its exit code before closing that lease. The catalog does not return
the provider. A provider resource closes when its generation retires.

## Run a JDK compiler plugin inside one lease

Create another external named module with a standard javac plugin service:

```java
// candidate/module-info.java
module example.plugin {
    requires jdk.compiler;
    provides com.sun.source.util.Plugin with example.plugin.AuditPlugin;
}
```

Add its provider class:

```java
// candidate/example/plugin/AuditPlugin.java
package example.plugin;

import com.sun.source.util.JavacTask;
import com.sun.source.util.Plugin;
import com.sun.source.util.TaskEvent;
import com.sun.source.util.TaskListener;

public final class AuditPlugin implements Plugin {
    @Override public String getName() { return "audit"; }

    @Override public void init(JavacTask task, String... arguments) {
        task.addTaskListener(new TaskListener() {
            @Override public void started(TaskEvent event) {}
            @Override public void finished(TaskEvent event) {}
        });
    }
}
```

Use `new JdkServiceFactory(Plugin.class)` for the `example.plugin` root. Keep
the host-owned compiler task and the plugin invocation inside one lease:

```java
// Add these requirements to the host module descriptor.
module example.host {
    requires tuul;
    requires java.compiler;
    requires jdk.compiler;
}
```

```java
// host/PluginHost.java
package example.host;

import com.sun.source.util.JavacTask;
import java.util.List;
import javax.tools.JavaFileObject;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import reload.Reload;

public final class PluginHost {
    private PluginHost() {}

    public static boolean compileWithPlugin(Reload reload,
            Iterable<? extends JavaFileObject> sources) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        try (var lease = reload.lease().orElseThrow();
                var files = compiler.getStandardFileManager(null, null, null)) {
            var task = (JavacTask) compiler.getTask(
                    null, files, null, List.of(), null, sources);
            var plugin = lease.generation().service(com.sun.source.util.Plugin.class)
                    .orElseThrow();
            plugin.init(task);
            return Boolean.TRUE.equals(task.call());
        }
    }
}
```

The host creates the task and calls the plugin. The plugin does not affect the
candidate compilation that loaded it. Do not return the plugin, task, task
listener, or another candidate object from this method after the lease closes.
