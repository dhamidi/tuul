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
> What runs today is `tuul new`, `tuul add`, `tuul install`, `tuul build`, `tuul run`,
> `tuul test`, `tuul docs`, `tuul bind` and `tuul self-test`, over seven
> libraries: `json` (streaming
> parser and serializer), `application` (the Elm Architecture runtime from
> [ARCHITECTURE.md](./ARCHITECTURE.md)), `fetch` (streaming HTTP with sessions
> and caller-selected concurrency), `symbols` (javac compiles a source
> tree in memory; the JDK's class file parser reads the symbols back out),
> `project` (scaffold, build, launch), `ffi` (shared libraries, downcall
> handles, and a C header reader) and `sqlite3` (the whole SQLite C API —
> 296 functions and 474 constants — generated from the amalgamation vendored
> in `native/sqlite3` by `tuul bind`, with a small typed layer over it).
>
> `tuul docs` answers for your code, for the selected jars in `vendor/`, and
> for the JDK itself, with doc comments and their `@param`/`@return`/`@throws` tags
> read from the matching source — a `-sources.jar` for a dependency,
> `lib/src.zip` for the JDK. `tuul run [entry] -- <args>` runs an entrypoint;
> running a file from `tasks/` is not implemented yet. `tuul new` scaffolds
> the library, the entrypoint, the native module and the FFI wrapper
> described below, but no vendored dependencies and no `tasks/`. `dev`,
> `console`, `generate`, `release`, `deploy` and `doctor` do not exist.
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

In this checkout, `mise run bootstrap` is only the bootstrap compiler needed
to start Tuul from source. `mise run build` and `mise run test` then dispatch
through Tuul's own CLI entrypoint. Successful library, entrypoint and test
compiles are fingerprinted under `build/.tuul`, so an unchanged build does not
start `javac` again.

The outbound HTTP library is [`fetch`](src/fetch/README.md). It provides
sessions, persistent connections, caller-selected concurrency, and streaming
request and response bodies.

## Why

- Only modern Java (JDK 27) is supported.
- The primary user of `tuul` is a developer *through an agent*. Every command
  is built to be predictable and scriptable first, pleasant for humans
  second.
- There is exactly one step between intent and action: `tuul add`, not
  "edit this XML file, then re-import the project."
- No external config language, and no dependency manifest to keep in sync.
  Deploy targets are declared in a plain Java file `tuul` runs directly — not
  TOML, not YAML, not a Groovy/Kotlin DSL. Tasks are files under `tasks/`.
  Build, release, and other task Java files contain their inputs. The files
  under `vendor/` are the complete dependency set. These two inputs contain
  all project and dependency state.
- The file tree *is* the documentation. A library holds the application;
  entrypoints are thin call-throughs. Open the tree and you can see what the
  app does before reading a line of code.
- Java modules follow runtime boundaries, not package boundaries. Tuul's
  runtime libraries form the module `tuul`. Tuul's CLI and build commands
  form `tuul.tasks`.
- A managed project separates application code, runtime entrypoints, tests,
  and development tasks. Each source set is an explicit Java module.

See [AGENTS.md](./AGENTS.md) for the full set of commandments.

## Library design: fetch

`fetch` is the foundation for HTTP work in the browser, `web.hyperspec`, and
`tuul add`. `Fetch` owns the connection pool and the execution strategy.
`Session` owns cookies and request defaults. `Form` preserves repeated fields.
`Body` can upload a large file, stream a Maven jar to a file, or publish
response bytes to an incremental parser. `Response.text()` uses the response
`Content-Type` charset.

Read the [fetch documentation](src/fetch/README.md) for the Java API and its
Diátaxis documents.

## Install

Tuul is managed like any other tool, through mise:

```sh
mise use -g tuul
```

This also pulls in JDK 27, since Tuul only targets the current JDK.

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
| `tuul add <group:artifact:version>...` | Resolve and install one Maven dependency graph into `vendor/` |
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
| `tuul docs <symbol>` | Query symbols, members, and package documents in the application, dependencies, and JDK |
| `tuul docs <symbol> --code` | Print the source of a symbol, member, or document |
| `tuul docs --search <text>` | Search the application, selected dependencies, and exported JDK types, grouped by package |
| `tuul deploy` | Release and ship the application |
| `tuul doctor` | Check the JDK, module graph, and `vendor/` |

Entrypoint and task names share one command namespace. `tuul run` refuses a
name that identifies both. Arguments after `--` go to the selected entrypoint
or task.

## Example: adding a dependency

```sh
$ tuul add com.fasterxml.jackson.core:jackson-databind:2.18.1
add.resolve com.fasterxml.jackson.core:jackson-databind:2.18.1
add.selected com.fasterxml.jackson.core:jackson-databind:2.18.1 runtime via com.fasterxml.jackson.core:jackson-databind:2.18.1
add.limits all 8 global, 4 per origin
add.done com.fasterxml.jackson.core:jackson-databind:2.18.1:sources vendor/com.fasterxml.jackson.core/jackson-databind/2.18.1/jackson-databind-2.18.1-sources.jar
add.complete 9 downloaded, 0 cached, 0 failed
```

Tuul resolves all command-line roots as one graph. It uses breadth-first
conflict mediation. The nearest version wins. Declaration order breaks a tie.
The roots, exclusions, and duplicate exceptions apply only to that invocation.
Put repeatable dependency setup in a Java task. The published JARs then become
the dependency set.

Tuul downloads to a temporary directory outside `vendor/`. It verifies
SHA-256, or SHA-1 when SHA-256 metadata is not present. It reports a warning
when a repository has no checksum metadata. It validates each JAR before
publication. A required-file failure leaves `vendor/` unchanged. Source and
javadoc JARs are optional and never enter a classpath.

Maven GET requests use at most four attempts. Tuul retries transport failures,
HTTP 408, 425, 429, 500, 502, 503, and 504. It honors `Retry-After`. It does not
retry HTTP 404. At most eight downloads run at once, with at most four for one
repository origin. Use `--repository URL` more than once to try repositories
in order.

When stdout is a terminal, `tuul add` replaces one bounded ANSI region. It
batches concurrent updates into at most ten frames per second and flushes each
complete frame once. One shared body bound covers tasks, outcomes, notices, and
the overflow line. The overflow line summarizes work that does not fit, so many
concurrent downloads do not flicker or overwhelm the terminal.
When stdout is not a terminal, it keeps the plain lifecycle, plan, and
diagnostic events so an agent can read them without terminal control codes.
Byte-level progress events are omitted from this stream.

```sh
$ tuul add --repository https://repo.example.test/maven2/ com.example:library:1.2.3
add.complete 3 downloaded, 0 cached, 0 failed
```

Commit the coordinate directories under `vendor/`. A fresh clone then builds
and runs without a network request. Every binary JAR under `vendor/` enters
the application and test classpaths. Source and javadoc JARs contribute only
to documentation.

Vendoring sources, not just compiled classes, is also what makes `tuul docs`
work on dependencies — see [Example: querying the code index](#example-querying-the-code-index).

### Dependency reference

A selected artifact uses `vendor/<group>/<artifact>/<version>/`. Its binary,
source, and javadoc JARs share that directory. Artifact type and classifier are
part of the selected identity. POM parents, properties, dependency management,
imported BOMs, optional dependencies, scopes, exclusions, classifiers,
`test-jar`, and relocation are supported. Active-by-default and file-activated
profiles are supported. Other profile activation, system scope, unresolved
dependency properties, and unknown artifact types stop resolution with an
explicit error.

`tuul add` scans runtime binaries for duplicate class names. It reports every
owning coordinate and stops before publication. Use `--exclude group:artifact`
to remove a dependency path. Use `--allow-duplicate package.Type` only when the
duplicate is intentional. Both choices apply only to the current invocation.

Use `tuul add --dry-run <coordinates>` to see the selected graph and files that
are missing from `vendor/`. An add preserves files from earlier invocations.
Delete a JAR to remove it from the project. Tuul reads every JAR below
`vendor/`, regardless of its nesting depth.

## Example: an agent driving the whole lifecycle

Because the main user of `tuul` is a developer working through an agent, the
CLI is designed so an agent can go from a blank request to a running,
deployed change using only `tuul`:

```sh
$ tuul new invoicing
$ cd invoicing
$ tuul add com.stripe:stripe-java:27.0.0
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
├── vendor/                      # complete dependency set; no manifest
│   └── com.fasterxml.jackson.core/
│       └── jackson-databind/
│           └── 2.18.1/
│               ├── jackson-databind-2.18.1.jar
│               └── jackson-databind-2.18.1-sources.jar
├── native/
│   └── hello.c                  # native module: hello world, callback via function pointer
├── src/
│   ├── module-info.java         # module invoicing
│   ├── resources/
│   │   └── application.properties # copied to the classpath root
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

**Dependencies.** There is no hand-written manifest for these. Commands such as
`tuul add com.fasterxml.jackson.core:jackson-databind:2.18.1` and
`tuul add org.xerial:sqlite-jdbc:3.46.0.0` resolve a graph and populate
coordinate directories. The JARs present under `vendor/` are the dependencies.

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

Put a classpath-root resource in `src/resources/`. For example,
`src/resources/application.properties` becomes
`build/classes/application.properties`. A non-Java file in another `src/`
package stays beside that package. An entrypoint resource stays relative to
its entrypoint output. Resource changes invalidate the related build output.

The manifest selects the CLI entrypoint when the project has one. Otherwise,
it selects the only entrypoint. A machine with JDK 27 runs the application
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

Ask for one member as `Type#member`. Add `--code` to print the source of
any name:

```sh
$ tuul docs invoicing.Invoice#compareTo
invoicing.Invoice#compareTo
  at src/invoicing/Invoice.java:7

  int compareTo(Invoice other)  :42
      Orders invoices by amount, then id.

$ tuul docs invoicing.Invoice#compareTo --code
    /// Orders invoices by amount, then id.
    @Override
    public int compareTo(Invoice other) {
        return amount.compareTo(other.amount);
    }
```

Ask for a package to see its types and its documents. Add `--documents` to
read every document of the package in one call:

```sh
$ tuul docs invoicing
package invoicing
  Money owed, and who owes it.

  invoicing/readme  Invoicing
  invoicing/tutorial  First invoice

  invoicing.Invoice
  invoicing.Ledger

$ tuul docs invoicing/tutorial
$ tuul docs invoicing --documents
```

Use search when you do not know the full symbol name:

```sh
$ tuul docs --search "event stream"
eventstream
  eventstream.Parser
      Reads a text/event-stream.
  eventstream.Event#data
      The data lines, joined.

$ tuul docs --search SpringBootApplication --json
{"query":"SpringBootApplication","every":true,"groups":[{"prefix":"org.springframework.boot.autoconfigure","matches":[{"symbol":"org.springframework.boot.autoconfigure.SpringBootApplication","kind":"annotation","doc":"...","origin":"org.springframework.boot:spring-boot-autoconfigure:3.5.5","source":"vendor/org.springframework.boot/spring-boot-autoconfigure/3.5.5/spring-boot-autoconfigure-3.5.5-sources.jar!/org/springframework/boot/autoconfigure/SpringBootApplication.java"}]}]}
```

Every word has to match. When nothing holds every word, the results that
hold some of them are shown instead and standard error says so.

Search covers project symbols, every binary JAR under `vendor/`, and exported
JDK names. The first search builds complete cached project and dependency
generations plus a lightweight JDK name generation. Dependency source JARs
supply type and public or protected member documentation. Exact symbol lookup
remains lazy and supplies full JDK source documentation.

Run `tuul docs` while the project is broken or mid-edit. A question about
`java.lang.String` does not wait for the project to compile. A question
about the project is answered from the last build that compiled, with
javac's messages after a `warning:` on standard error. Run
`tuul docs docs/reference` for the flags and the JSON fields.

## Contributing

Tuul is implemented with no dependencies beyond the JDK standard library,
favors minimal nesting and streaming interfaces over buffering, and uses
structured concurrency where concurrency is needed at all. See
[AGENTS.md](./AGENTS.md) before sending changes.
