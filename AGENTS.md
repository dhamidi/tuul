# Tuul

Tuul is to Java what Bun is to JavaScript:

- a unified toolchain,
- a break with the past: only modern JDK 27 is supported,
- a development tool to give easy access to agents to working with Java code.

## Commandments

- use modern Java,
- use minimal nesting,
- abdicate Java culture,
- no dependencies beyond the standard library,
- optimize for small, understandable, performant systems,
- the main user of tuul is a developer *through* an agent,
- there must be exactly one step between user intent and action taken by the system,
- always code against streaming interfaces (e.g. serialize/write to a writer, not a buffer),
- use structured concurrency,
- center all work on the JPMS module graph. Maven coordinates acquire
  artifacts. The resolved module graph decides how every application compiles,
  runs, tests, reloads, documents, analyzes, packages, and releases. Tuul never
  compiles or launches application code on the classpath or in the unnamed
  module. A source root without a module descriptor is invalid,
- finish the design: a change that leaves the workaround it made unnecessary standing beside it is not done,
- documentation is a deliverable, so that `tuul docs` is self-documenting, and must be written in ASD-STE100, following the diataxis framework.

## Goals

Tuul is the one-stop shop for managing Tuul-generated projects.

Yet, it is not necessary to use tool to run a project.

Tuul gives access to:

- adding and updating dependencies,
- analyzing source code and documentation, both of the application and dependencies used by the application,
- tuul makes it possible to develop, build, and deploy and application by only invoking `tuul` and having an agent write code.

## Testing and profiling

Keep the regular test suite fast and I/O free.

- Mark a suite with `Named.fast` when it uses only in-memory data, fake
  requests or downloads, `StringWriter`, and other local test doubles. Do not
  start an HTTP server, access the network, create project files, or depend on
  a user's cache in a fast suite.
- Mark a suite with `Named.integration` when it tests real filesystem,
  network, archive, process, or external-service behavior. Keep that behavior
  in a separate `*IntegrationTest` method or class. Run it with
  `mise run test-all`, or select it with `run --suite NAME`.
- Inject resolver, request, and download services when a unit test needs to
  exercise orchestration. The fake must report the same events and outcomes
  as the real service, and it must assert important limits such as maximum
  concurrency.
- Use `mise run test` for the fast suite and `mise run test-all` before
  handoff. A suite report includes checks and elapsed time; use
  `mise exec -- java ... run --suite NAME` for a focused measurement after
  the project is built.
- Profile cold startup and warm cached execution separately. For CPU and
  startup attribution, use a short Java Flight Recorder run. For blocking or
  filesystem/network attribution, use a bounded `strace -f -c` run. Do not
  leave timing probes, sleeps, or profiling output in committed tests.
