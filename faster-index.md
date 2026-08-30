# Faster documentation indexing

## Decision

Separate indexing from browsing.

`tuul docs` is the synchronous agent interface. It makes sure that the
index is current, then reads it and exits.

`tuul browse` is the human interface. It starts the web server quickly and
serves the current `index.db` as a read-only catalog. An indexing coordinator
can update the database in the background.

The web application does not compile Java, discover source files, or write the
index during an HTTP request.

## Developer workflow

An agent normally works in this order:

1. Read documentation with `tuul docs`.
2. Change source files.
3. Read the affected documentation again.

The first `tuul docs` call after a source change can wait for indexing. Later
calls reuse the completed database:

```text
agent changes source
    |
    +-- tuul docs A  -> checks freshness, indexes once, answers
    +-- tuul docs B  -> reuses index.db
    +-- tuul docs C  -> reuses index.db
```

There is no need for a persistent indexing service for this workflow. The
persistent work is `index.db`.

## Human workflow

`tuul browse` starts the HTTP server without waiting for a complete rebuild.
It opens the most recent usable index and serves it immediately.

If the index is missing or stale, the browse process starts the same indexing
coordinator in the background. The browser shows an indexing state until a
complete index is available. When indexing finishes, the browser refreshes.

```text
tuul browse
    |
    +-- start HTTP server
    +-- read current index.db
    +-- start one background refresh when needed
    +-- refresh pages after publication
```

If an agent has already run `tuul docs`, browsing is normally immediate because
the index is already warm.

## Components

### Index coordinator

The coordinator owns all indexing state and all writes to `index.db`.

It handles these operations:

- check the source fingerprint;
- decide whether the current index is usable;
- acquire the cross-process build lock;
- recheck freshness after acquiring the lock;
- compile and parse the project when required;
- write a complete replacement in one transaction;
- notify the browser after a successful publication.

An actor or a small application is suitable for this role. It must allow only
one indexing job for a given project fingerprint. Repeated `ensure current`
messages must join the existing job instead of starting another one.

### Catalog

The catalog is read-only. It answers symbol, package, document, and search
queries from `index.db`.

The catalog must not contain lazy compilation behavior. A request that reads a
catalog either gets data from the current committed index or gets an explicit
indexing response.

### Browser

The browser owns HTTP routes, HTML, JSON negotiation, and refresh events. It
uses the catalog and does not know how Java sources become index rows.

## One database

Keep one `index.db` as the first implementation. SQLite WAL allows readers to
continue reading the last committed state while a writer works.

The writer must publish a replacement transactionally:

1. Build and parse outside the active index tables.
2. Start one write transaction.
3. Replace the active rows.
4. Mark the new fingerprint complete.
5. Commit.

Readers must continue to see the old complete data until the commit. The
current behavior, which deletes stale rows before compilation starts, must not
be used by the coordinator.

Generation files can be added later if database replacement becomes a
limitation. They are not required for the initial design.

## Avoiding duplicate work

### Within one process

The coordinator keeps one in-flight job per fingerprint. Concurrent browser
requests and concurrent callers in one process wait for or reuse that job.

The catalog uses immutable read state or synchronized access. Lazy mutable
fields such as the current class map and root list must not be initialized by
multiple HTTP threads.

### Between processes

Use a lock file or a SQLite lease around the indexing job.

The sequence is:

1. Calculate the requested fingerprint.
2. Check whether `index.db` already contains it.
3. Acquire the build lock if it does not.
4. Check again after acquiring the lock.
5. Build only if the index is still missing.
6. Publish the completed transaction.

The second check is required. A SQLite busy timeout only makes writers wait;
it does not stop two processes from both compiling before they write.

## Reuse existing build work

The documentation index should reuse the project library classes produced by
`tuul build` when their compile fingerprint matches.

If no usable build output exists, the coordinator compiles into a keyed cache.
The build system and the documentation index must use the same inputs for this
fingerprint:

- Java source files;
- vendor binary JARs;
- JDK version;
- compiler options.

This prevents `tuul build` and `tuul docs` from compiling the same project
separately.

## Invalidation

Do not use one stamp for every kind of documentation.

Track at least these inputs separately:

- Java source and compiler inputs for type data;
- Markdown files for package documents;
- vendor binary JARs for dependency symbols;
- vendor source JARs for dependency documentation;
- JDK version for platform symbols.

A Markdown edit must not trigger `javac`. A vendor source JAR edit must not
trigger project compilation. A vendor binary edit must invalidate project
compilation because it can change name resolution.

The source inventory can still be shared. One scan should produce the file
metadata needed by all fingerprints instead of walking the source tree once
for each stage.

## Browser startup contract

The browser startup path must not call a method that can compile the project.
In particular, listing roots must read a stored package summary or return an
indexing state. It must not fall through to `names()` and then to project
indexing.

The first page may show:

- the last complete project and dependency roots;
- cached JDK roots;
- an indexing indicator when no current project summary exists.

When the coordinator publishes a new index, it invalidates the browser's root
cache and sends one refresh event.

## Query efficiency

The catalog should provide a package-document operation that returns the
selected document and its sibling list together. This avoids separate
`documents()` and `document()` reads for one document page.

The same rule applies to other compound pages: one catalog operation should
return the facts needed to render one response. The browser should not resolve
the same package or document again while rendering links.

## Result

This design gives each user the behavior they need:

- an agent gets a current answer from `tuul docs`;
- repeated agent queries reuse persistent work;
- a human gets an HTTP server without waiting for a cold index;
- browser requests never start compilation;
- concurrent callers perform one build per source fingerprint;
- the build system and docs index share compiled output;
- the database remains useful until a replacement is complete.
