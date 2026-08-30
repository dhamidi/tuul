# Tuul

Tuul is to Java what [Bun](https://bun.sh) is to JavaScript: a single, fast
binary that replaces the pile of separate tools (build system, dependency
manager, test runner, formatter, docs) a Java project usually needs.

It borrows:

- **from Bun** — one binary, one command surface, no config ceremony to add a
  dependency or run a script.
- **from Rails** — convention over configuration, generators, a console to
  poke at your app.
- **from mise** — CLI ergonomics: a simple, predictable task-running surface
  (`tuul run <task>`), not its config format.

> **Status:** parts of this README are still the target, not a changelog.
> What runs today is `tuul new`, `tuul install`, `tuul build`, `tuul run`,
> `tuul test`, `tuul docs`, `tuul bind` and `tuul self-test`, over six
> libraries: `json` (streaming
> parser and serializer), `application` (the Elm Architecture runtime from
> [ARCHITECTURE.md](./ARCHITECTURE.md)), `symbols` (javac compiles a source
> tree in memory; the JDK's class file parser reads the symbols back out),
> `project` (scaffold, build, launch), `ffi` (shared libraries, downcall
> handles, and a C header reader) and `sqlite3` (the whole SQLite C API —
> 296 functions and 474 constants — generated from the amalgamation vendored
> in `native/sqlite3` by `tuul bind`, with a small typed layer over it).
>
> `tuul docs` answers for your code, for the jars in `vendor/`, and for the
> JDK itself, with doc comments and their `@param`/`@return`/`@throws` tags
> read from the matching source — a `-sources.jar` for a dependency,
> `lib/src.zip` for the JDK. `tuul run [entry] -- <args>` runs an entrypoint;
> running a file from `tasks/` is not implemented yet. `tuul new` scaffolds
> the library, the entrypoint, the native module and the FFI wrapper
> described below, but no vendored dependencies and no `tasks/`, and
> `tuul add`, `dev`, `console`, `generate`, `release`, `deploy` and `doctor` do not
> exist.
>
> Today, `tuul build` compiles classes and native code into `build/`. It does
> not assemble the runnable jar described below. `tuul release` does not exist
> yet.
>
> Today, Tuul compiles its libraries as the explicit module `tuul`. It compiles
> its CLI separately and runs that CLI from the classpath. The `tuul.tasks`
> split described below is the target.
>
> `tuul install` vendors tuul into a project the way anything else gets
> there — a jar, a sources jar, and a compiled SQLite for each of the six
> platforms tuul ships — so an application can be written on `application`,
> `argparse`, `json` and `sqlite3` without a C compiler anywhere near the
> machine. `tuul install --source` vendors the amalgamation instead, for a
> platform with no prebuilt library.
>
> `tuul self-test` is the end-to-end proof: it scaffolds a project in a
> temporary directory, installs tuul into it, drives the real command line
> against it — with nothing on the PATH, so nothing can quietly compile — and
> keeps the directory when an assertion fails. Until `tuul` ships as a binary:
> `mise run build`, `mise run test`, `mise run self-test`,
> `mise run natives`, `mise run tuul -- docs <symbol>`.

## Why

- Only modern Java (JDK 24) is supported — no legacy toolchain juggling.
- The primary user of `tuul` is a developer *through an agent*. Every command
  is built to be predictable and scriptable first, pleasant for humans
  second.
- There is exactly one step between intent and action: `tuul add`, not
  "edit this XML file, then re-import the project."
- No external config language, and no dependency manifest to keep in sync.
  Deploy targets are declared in a plain Java file `tuul` runs directly — not
  TOML, not YAML, not a Groovy/Kotlin DSL. Tasks are files under `tasks/`.
  Dependencies are just what's checked into `vendor/` — the directory itself
  is the declaration, nothing else records it.
- The file tree *is* the documentation. A library holds the application;
  entrypoints are thin call-throughs. Open the tree and you can see what the
  app does before reading a line of code.
- Java modules follow runtime boundaries, not package boundaries. Tuul's
  runtime libraries form the module `tuul`. Tuul's CLI and build commands
  form `tuul.tasks`.
- A managed project separates application code, runtime entrypoints, tests,
  and development tasks. Each source set is an explicit Java module.

See [AGENTS.md](./AGENTS.md) for the full set of commandments.

## Install

Tuul is managed like any other tool, through mise:

```sh
mise use -g tuul
```

This also pulls in JDK 24, since Tuul only targets the current JDK.

## Quick start

```sh
tuul new hello-world
cd hello-world
tuul dev
```

The target `tuul new` command does not scaffold a blank project. It creates a
working reference with libraries, entrypoints, tasks, tests, and native code.
The target tree appears in [Project structure](#project-structure). Extend
that reference instead of starting from nothing. `tuul dev` rebuilds and
restarts it when a source file changes.

## Commands

| Command | Does what |
|---|---|
| `tuul new <name>` | Scaffold a new Tuul-managed project |
| `tuul add <name>` | Fetch a dependency into `vendor/` without a manifest edit |
| `tuul remove <name>` | Delete a dependency from `vendor/` |
| `tuul dev` | Run the application and restart it after a change |
| `tuul build` | Produce one runnable jar with all runtime and native dependencies |
| `tuul release` | Produce a `jlink` image for the host platform |
| `tuul release --target <platform>` | Produce a `jlink` image for one platform |
| `tuul release --all` | Produce a `jlink` image for each supported platform |
| `tuul test` | Run the test module |
| `tuul run <name>` | Run a named entrypoint or development task |
| `tuul console` | Open a REPL with the application's module path |
| `tuul generate <what>` | Generate a library, entrypoint, task, native module, or test |
| `tuul docs <symbol>` | Query symbols in the application, dependencies, and JDK |
| `tuul deploy` | Release and ship the application |
| `tuul doctor` | Check the JDK, module graph, and `vendor/` |

Entrypoint and task names share one command namespace. `tuul run` refuses a
name that identifies both. Arguments after `--` go to the selected entrypoint
or task.

## Example: adding a dependency

```sh
$ tuul add jackson-databind
resolved jackson-databind@2.18.1 (+3 dependencies)
vendored into vendor/jackson-databind, vendor/jackson-core, vendor/jackson-annotations
```

You name the library, not its coordinates — Tuul resolves `jackson-databind`
against Maven Central and drops the binary and its sources for it *and*
every transitive dependency straight into `vendor/`, one directory per
artifact. There's no manifest entry to write and no lockfile to generate:
`vendor/` is the dependency list. `ls vendor/` tells you exactly what the
project depends on, and at exactly which version, because that's literally
what's on disk — nothing to keep in sync, because there's nothing else that
records it.

```sh
$ tuul add --test junit-jupiter
vendored into vendor/test/junit-jupiter
```

Test-only dependencies live under `vendor/test/` instead of `vendor/` —
scope is a directory, not a flag recorded somewhere else. Everything under
`vendor/` gets committed, so a fresh clone builds and runs without touching
the network.

Vendoring sources, not just compiled classes, is also what makes `tuul docs`
work on dependencies — see [Example: querying the code index](#example-querying-the-code-index).

## Example: an agent driving the whole lifecycle

Because the main user of `tuul` is a developer working through an agent, the
CLI is designed so an agent can go from a blank request to a running,
deployed change using only `tuul`:

```sh
$ tuul new invoicing
$ cd invoicing
$ tuul add stripe-java
$ tuul generate library billing
# agent writes src/billing/*.java (the library),
# then a small call from entrypoints/invoicing/cli/Main.java
$ tuul test
$ tuul build
$ java -jar build/invoicing.jar
$ tuul release --target linux-x86_64
$ tuul deploy
```

No build-file XML to hand-edit, no separate wrapper script, no IDE project
files to keep in sync — the agent only ever needs to know `tuul`.

## Project structure

This is the target output of `tuul new invoicing`. Each source root has one
module descriptor. Packages remain directories inside those source roots.

```
invoicing/
├── project.java                 # deploy target — plain Java, run by tuul
├── vendor/                      # every dependency: binary + sources — this *is* the manifest
│   ├── jackson-databind/
│   ├── sqlite-jdbc/
│   └── test/
│       └── junit-jupiter/       # test-only dependencies live here instead
├── native/
│   └── hello.c                  # native module: hello world, callback via function pointer
├── src/
│   ├── module-info.java         # module invoicing
│   ├── invoicing/                # library: core domain logic
│   │   ├── Invoice.java
│   │   ├── Invoices.java
│   │   └── Ledger.java
│   ├── reporting/                 # library: built on invoicing
│   │   ├── Report.java
│   │   └── Renderer.java
│   └── greet/                   # library: FFI wrapper around native/hello.c
│       └── Greeter.java          # java.lang.foreign — no JNI or generated glue
├── entrypoints/
│   ├── module-info.java         # module invoicing.entrypoints
│   └── invoicing/
│       ├── cli/
│       │   └── Main.java        # command-line entrypoint
│       ├── gui/
│       │   └── Main.java        # desktop entrypoint
│       └── web/
│           └── Main.java        # HTTP entrypoint
├── tasks/
│   ├── module-info.java         # module invoicing.tasks
│   └── invoicing/tasks/
│       ├── Format.java
│       ├── Native.java          # builds native/hello.c with zig cc
│       └── Deploy.java
└── test/
    ├── module-info.java         # module invoicing.test
    └── invoicing/
        ├── InvoicesTest.java
        ├── RendererTest.java
        └── GreeterTest.java
```

**Application module.** The module `invoicing` contains the application
packages. The `reporting` package can depend on the `invoicing` package. No
application package depends on an entrypoint or development task.

**Entrypoint module.** The module `invoicing.entrypoints` contains the CLI,
GUI, and web entrypoints. It requires `invoicing` and `tuul`. Each entrypoint
parses input, calls the application, and renders output. It contains no
application rules.

**Task module.** The module `invoicing.tasks` contains development tasks. It
requires `invoicing` and `tuul.tasks`. A release does not include this module.

**Test module.** The module `invoicing.test` contains the tests and test-only
dependencies. A release does not include this module.

**Tuul modules.** `tuul install` vendors one jar. That jar is the explicit
module `tuul`. Packages such as `application`, `json`, and `web` belong to it.
A project declares `requires tuul`. It does not require each package.

The module `tuul.tasks` requires `tuul`. It contains Tuul's CLI and the
`project`, `symbols`, `docs`, `browser`, and `selftest` packages. These
packages implement `Build`, `Run`, `Test`, `Install`, documentation, and
self-test commands.

`tuul install` does not vendor `tuul.tasks`. Applications do not need that
module at run time. This boundary keeps compiler and build tools out of a
release image.

`tuul run` compiles a project task module against the tool's `tuul.tasks`
module. It does not copy `tuul.tasks` into the project or its release.

Tuul compiles and tests each source root as an explicit module. It uses the
module path for the application, entrypoints, tasks, tests, and dependencies.
The regular runnable jar is the deliberate exception described below.

**Dependencies.** There's no manifest for these — `tuul add jackson-databind`
and `tuul add sqlite-jdbc` populate the two runtime entries directly;
`tuul add --test junit-jupiter` populates the test-only one under
`vendor/test/`. Each directory holds sources and binary, committed. Nothing
declares a dependency separately from fetching it, so nothing can drift out
of sync with what's actually there.

**Tasks.** Each Java file under `tasks/` is one task. Tuul discovers the task
from its class name. No registry or `project.java` entry is necessary.

```java
// tasks/invoicing/tasks/Deploy.java
package invoicing.tasks;

import tuul.tasks.Project;

public final class Deploy {
    public static void run(Project project) {
        project.build();
        project.upload("prod");
    }
}
```

`tuul run deploy` runs the task. Delete the file to remove the task.

**Native module.** `native/hello.c` is the default hello-world: a C function
that takes a function pointer and calls it back.

```c
// native/hello.c
typedef void (*greeting)(const char *name);

void greet(const char *name, greeting on_greeting) {
    on_greeting(name);
}
```

`src/greet/Greeter.java` is the Java side — a downcall into `greet()` that
passes a Java method as the callback:

```java
// src/greet/Greeter.java (shape only — the java.lang.foreign calls are elided)
package greet;

public class Greeter {
    public static void greet(String name, Consumer<String> onGreeting) {
        // Linker.nativeLinker() downcall into native/hello.c's greet(),
        // passing onGreeting as an upcall stub. No JNI, no javah, no
        // generated glue — this is java.lang.foreign end to end.
    }
}
```

The C side calling back into the Java side through a real function pointer,
with no JNI in between, is the whole point of the default: it's a template
for wiring in an actual native library, not a toy that gets deleted.

## Build and release artifacts

### Runnable jar

`tuul build` writes one platform-neutral artifact:

```text
build/invoicing.jar
```

The jar contains:

- the application and entrypoint classes,
- all runtime dependency classes,
- all runtime resources,
- the native libraries for each supported platform.

The manifest selects the CLI entrypoint when the project has one. Otherwise,
it selects the only entrypoint. A machine with JDK 24 runs the application
without Tuul:

```sh
java -jar build/invoicing.jar
```

Tuul compiles the explicit module graph before it assembles the jar. It then
combines the runtime modules into one jar. `java -jar` runs that jar in the
unnamed module. The manifest enables native access for `ALL-UNNAMED`.

The jar contains each supported native variant. At startup, the runtime
selects the host variant. It extracts that variant to a content-addressed
cache and loads it from there.

The jar does not contain test modules, project task modules, or `tuul.tasks`.
It needs a JDK, but it does not need a Tuul installation or a C compiler.

### Release images

`tuul release` writes a `jlink` application image under the host platform.
The platform name contains the operating system and instruction set.

```text
build/release/
├── macos-aarch64/
├── macos-x86_64/
├── linux-aarch64/
├── linux-x86_64/
├── windows-aarch64/
└── windows-x86_64/
```

One target image contains launchers for each runtime entrypoint. The CLI
launcher uses the application name.

```text
build/release/linux-x86_64/
├── bin/
│   ├── invoicing
│   ├── invoicing-gui
│   └── invoicing-web
├── conf/
└── lib/
```

The image contains the application module, the entrypoint module, `tuul`,
runtime dependencies, and the necessary JDK modules. It contains only the
native libraries for its target. It excludes tests, development tasks, and
`tuul.tasks`.

The generated launchers preserve the named module graph. They enable native
access for `tuul`. They also enable it for an application module that uses the
Foreign Function and Memory API directly.

The target machine runs a launcher without Java or Tuul installed:

```sh
build/release/linux-x86_64/bin/invoicing
```

`tuul release` builds the host image. `tuul release --target <platform>` builds
one selected image. `tuul release --all` builds all six supported images.

`jlink` can create an image for a platform other than its host. Tuul supplies
the target JDK's `jmods` on the module path. The target JDK must match the JDK
version that runs `jlink`. See the official guide to
[cross-platform `jlink` images](https://dev.java/learn/jlink/#generating-images-across-operating-systems).

Cross-linking does not compile native code. Each native dependency must supply
a binary for the selected platform. Tuul already uses this rule when it
vendors SQLite.

`jlink` accepts explicit modules only. `tuul doctor` reports each automatic
module before a release. `tuul release` stops and names any automatic module
that remains. A target environment or emulator must test each foreign image.

## Example: querying the code index

`tuul docs` isn't a site you generate and read in a browser — it's a query
you run from a shell, or that an agent runs mid-task, against every symbol
in your code and every vendored dependency:

```sh
$ tuul docs invoicing.Invoice
class invoicing.Invoice
  implements Comparable<Invoice>, Serializable

  Invoice(String id, BigDecimal amount)
      An invoice for a fixed amount, identified by id.

  String id()
  BigDecimal amount()
  int compareTo(Invoice other)
      Orders invoices by amount, then id.
```

```sh
$ tuul docs invoicing.Invoice --implements
Comparable<Invoice>
Serializable

$ tuul docs invoicing.Invoice --methods
Invoice(String id, BigDecimal amount)
String id()
BigDecimal amount()
int compareTo(Invoice other)

$ tuul docs invoicing.Invoice --json
{"class":"invoicing.Invoice","implements":["java.lang.Comparable<invoicing.Invoice>","java.io.Serializable"],"methods":["..."]}
```

`--json` is there for the agent case: no scraping rendered HTML, no parsing
prose, a structured answer to "what does this implement" or "what are its
methods." And because `vendor/` holds sources, not just compiled classes,
the same queries answer questions about dependencies too:

```sh
$ tuul docs com.fasterxml.jackson.databind.ObjectMapper --implements
Versioned
Serializable
TreeCodec
```

## Contributing

Tuul is implemented with no dependencies beyond the JDK standard library,
favors minimal nesting and streaming interfaces over buffering, and uses
structured concurrency where concurrency is needed at all. See
[AGENTS.md](./AGENTS.md) before sending changes.
