# Tuul

Tuul is to Java what Bun is to JavaScript:

- a unified toolchain,
- a break with the past: only modern JDK 24 is supported,
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
- finish the design: a change that leaves the workaround it made unnecessary standing beside it is not done,
- documentation is a deliverable, so that `tuul docs` is self-documenting, and must be written in ASD-STE100, following the diataxis framework.

## Goals

Tuul is the one-stop shop for managing Tuul-generated projects.

Yet, it is not necessary to use tool to run a project.

Tuul gives access to:

- adding and updating dependencies,
- analyzing source code and documentation, both of the application and dependencies used by the application,
- tuul makes it possible to develop, build, and deploy and application by only invoking `tuul` and having an agent write code.



