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
> What runs today is `tuul new`, `tuul build`, `tuul run`, `tuul test`,
> `tuul docs` and `tuul self-test`, over six libraries: `json` (streaming
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
> `tuul add`, `dev`, `console`, `generate`, `deploy` and `doctor` do not
> exist.
>
> `tuul self-test` is the end-to-end proof: it scaffolds a project in a
> temporary directory, drives the real command line against it, and keeps the
> directory when an assertion fails. Until `tuul` installs itself:
> `mise run build`, `mise run test`, `mise run self-test`,
> `mise run tuul -- docs <symbol>`.

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

`tuul new` doesn't scaffold a blank slate — it scaffolds a small working
reference: a couple of libraries, three entrypoints (CLI, GUI, web server),
a vendored dependency or two, a task or two, and a native FFI module with a
hello-world callback. Everything below in [Project structure](#project-structure)
is what you get by default; extend it, don't start from nothing. `tuul dev`
builds it, runs it, and re-runs it on save — no separate watch tool, no IDE
required.

## Commands

| Command                | Does what                                                        |
|-------------------------|-------------------------------------------------------------------|
| `tuul new <name>`      | Scaffold a new Tuul-managed project                              |
| `tuul add <name>`      | Fetch a dependency straight into `vendor/` — no manifest to edit  |
| `tuul remove <name>`   | Delete a dependency from `vendor/`                                |
| `tuul dev`             | Run the app, rebuilding and restarting on change                 |
| `tuul build`           | Produce a release build                                          |
| `tuul test`            | Run the test suite                                                |
| `tuul run <task>`      | Run a task from `tasks/`                                          |
| `tuul console`         | Open a REPL with the app's classpath loaded                      |
| `tuul generate <what>` | Generate boilerplate (a library, an entrypoint, a task, a native module, a test, ...) |
| `tuul docs <symbol>`   | Query the code index: supertypes, interfaces, methods, fields, javadoc — for your code and every vendored dependency |
| `tuul deploy`          | Build and ship the app                                           |
| `tuul doctor`          | Verify the toolchain is sane (JDK version, `vendor/` consistency) |

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
# then a one-line call from src/cli/main.java (the entrypoint)
$ tuul test
$ tuul build
$ tuul deploy
```

No build-file XML to hand-edit, no separate wrapper script, no IDE project
files to keep in sync — the agent only ever needs to know `tuul`.

## Project structure

This is what `tuul new invoicing` actually generates — not a single library
and a single entrypoint, but a full reference covering everything a real
project eventually needs: several libraries, several entrypoints, several
vendored dependencies, one task per file, and a native module.

```
invoicing/
├── project.java                 # deploy target — plain Java, run by tuul
├── vendor/                      # every dependency: binary + sources — this *is* the manifest
│   ├── jackson-databind/
│   ├── sqlite-jdbc/
│   └── test/
│       └── junit-jupiter/       # test-only dependencies live here instead
├── tasks/                       # one file per task — drop a file in, tuul finds it
│   ├── Format.java
│   ├── Native.java              # builds native/hello.c with `zig cc`
│   └── Deploy.java
├── native/
│   └── hello.c                  # native module: hello world, callback via function pointer
├── src/
│   ├── invoicing/                # library: core domain logic
│   │   ├── Invoice.java
│   │   ├── Invoices.java
│   │   └── Ledger.java
│   ├── reporting/                 # library: built on invoicing
│   │   ├── Report.java
│   │   └── Renderer.java
│   ├── greet/                     # library: FFI wrapper around native/hello.c
│   │   └── Greeter.java            # java.lang.foreign — no JNI, no javah, no generated glue
│   ├── cli/
│   │   └── main.java                # thin entrypoint: command line
│   ├── gui/
│   │   └── main.java                # thin entrypoint: desktop window
│   └── web/
│       └── main.java                # thin entrypoint: HTTP server
└── test/
    ├── invoicing/
    │   └── InvoicesTest.java
    ├── reporting/
    │   └── RendererTest.java
    └── greet/
        └── GreeterTest.java
```

**Libraries.** `invoicing` and `reporting` are ordinary application code —
`reporting` depends on `invoicing`, nothing depends on `reporting`. `greet`
is the FFI wrapper described below. None of them know an entrypoint exists.

**Entrypoints.** `cli`, `gui`, and `web` are three ways into the same
application, and each is a handful of lines: parse input, call the library,
render output. Compare an entrypoint to its library — if the entrypoint has
grown business logic of its own, that logic moved to the wrong place.

**Dependencies.** There's no manifest for these — `tuul add jackson-databind`
and `tuul add sqlite-jdbc` populate the two runtime entries directly;
`tuul add --test junit-jupiter` populates the test-only one under
`vendor/test/`. Each directory holds sources and binary, committed. Nothing
declares a dependency separately from fetching it, so nothing can drift out
of sync with what's actually there.

**Tasks.** Each file under `tasks/` is one task, discovered by its filename
— no registry, no entry to add to `project.java`:

```java
// tasks/Deploy.java
void run(Project project) {
    project.build();
    project.upload("prod");
}
```

`tuul run deploy` runs it. Delete the file and the task is gone; that's the
whole lifecycle.

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
