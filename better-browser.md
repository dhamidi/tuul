# Package documents in the browser

Decision: keep the Diataxis markdown files. Do not convert them into Java.
Attach each file to its package. Serve it through the same index, search, and
markdown renderer the browser already uses.

`package-info.java` stays the package overview. That file is Java's `doc.go`.
Markdown files stay the tutorials, how-tos, references, and guides.

A package may hold **many** files of each kind. The path names the document.
The indexer does not parse markdown to learn what a file is.

## What godoc does

Godoc has one pipeline. It reads comments from source. It does not invent a
second documentation site for API docs.

A package page is the hub:

- the package comment (often in `doc.go`)
- the list of exported types and functions
- examples that live in `_test.go` as `Example*` functions

Godoc does **not** put Effective Go, A Tour of Go, or the language spec into
`doc.go`. Those documents answer different questions. They live on go.dev as
separate pages.

`pkg.go.dev` adds one more attachment: it renders `README.md` at the **module**
root. It still does not treat a random markdown file in a package as a type.

The metadata is the path. `doc.go` is package docs because of its comment
position, not because a parser classified the prose. This design copies that
rule: **kind, slug, and order come from the filename.**

Java already has the godoc split:

| Godoc | Tuul today |
|---|---|
| package comment / `doc.go` | `package-info.java` |
| exported API comments | `///` comments on types and members |
| `Example*` tests | not present, and not this design |
| go.dev narrative docs | markdown next to the package |
| module README on pkgsite | package `README.md` for GitHub |

The missing piece is the narrative docs. They sit next to the code. The
browser does not show them.

## Do not merge markdown into Java files

A doc-only Java file would copy Go's `doc.go`. Tuul already has that file:
`package-info.java`. `tcl/package-info.java` is 19 lines. That is the right
length for "what is this package?"

`tcl/reference.md` is more than 1,200 lines. That is not a package comment.
It is a document. Splitting it into `reference-commands.md` and
`reference-values.md` must stay as easy as adding a file.

Putting narrative docs in Java fails in four ways.

1. **The comment tax.** Every line needs `/// `. Diffs get worse. An agent
   that writes STE100 markdown must then wrap every line.
2. **Fake types.** Extra documents would need dummy types (`tcl.Tutorial`).
   Search would return a class with no members. The index would lie.
3. **The wrong question on the package page.** `tuul docs tcl` would dump a
   novel. Godoc prints a short overview and a list of names.
4. **Two steps to add a document.** The intent is "write a how-to." The
   action is "add `howto-repl.md`." A Java stand-in adds a type, a comment
   prefix, and a class file.

An include tag in `package-info.java` is the same mistake. The filename
already names the document.

## Proposed model

A document is not a symbol. It belongs to a package.

```
package tcl
  overview     package-info.java
  types        Tcl, Repl, …
  documents    many files, grouped by kind
```

Three kinds of text stay distinct:

- **Overview.** A few paragraphs. Lives in `package-info.java`. Answers "what
  is this package?"
- **API.** Type and member comments. Live in the Java files. Answer "what does
  this name do?"
- **Document.** One Diataxis page. Lives in one markdown file in that package
  directory. Answers one question of one kind.

The browser already renders markdown from comments. A document is the same
markdown, without a `///` prefix, with a kind, a slug, and a title.

## Source convention

Identity is the filename. The indexer lists the package directory. It does
not walk a markdown AST. It does not read YAML. It does not consult
`README.md`.

### Filename

A document is a markdown file in the package directory whose name matches:

```
<kind>.md
<kind>-<slug>.md
<kind>-<nn>-<slug>.md
```

`<kind>` is one of:

| kind | prefixes | Question |
|---|---|---|
| tutorial | `tutorial` | Learn by a first program. |
| howto | `howto` | Complete a task. |
| reference | `reference` | Find a fact, a table, or a rule. |
| guide | `guide`, `explanation` | Know why the design is this way. |

`explanation` is a prefix for `guide`, so `explanation.md` in the tree today
keeps working. New files use `guide`. Do not add a fifth kind.

`<slug>` is the rest of the name without `.md`. It may contain hyphens.
`reference-expr-functions.md` is kind `reference`, slug `expr-functions`.

`<nn>` is one or more digits. It sets reading order. It is not part of the
slug. `tutorial-01-first-script.md` is slug `first-script`. Pad to two digits
(`01`, `02`) so lexicographic order matches numeric order.

`<kind>.md` has an empty slug. It is the intro for that kind. Today's
`tutorial.md`, `howto.md`, `reference.md`, and `explanation.md` are already
in this form.

Examples in `src/tcl/`:

| File | kind | slug | URL |
|---|---|---|---|
| `tutorial.md` | tutorial | (empty) | `tcl/tutorial` |
| `tutorial-01-first-script.md` | tutorial | `first-script` | `tcl/tutorial/first-script` |
| `tutorial-02-nested-eval.md` | tutorial | `nested-eval` | `tcl/tutorial/nested-eval` |
| `howto-repl.md` | howto | `repl` | `tcl/howto/repl` |
| `reference.md` | reference | (empty) | `tcl/reference` |
| `reference-commands.md` | reference | `commands` | `tcl/reference/commands` |
| `explanation.md` | guide | (empty) | `tcl/guide` |
| `guide-why-no-io.md` | guide | `why-no-io` | `tcl/guide/why-no-io` |

One file, one document, one question. A long `howto.md` with many `##`
sections is still one document. The indexer does not split on headings. Split
by adding a file.

### What the filename is not allowed to be

Do not use a directory named `tutorial/`, `howto/`, `reference/`, or
`guide/` inside the package. `src/tcl/tutorial/` looks like Java package
`tcl.tutorial`. An agent and javac will treat it as a subpackage.

Javadoc puts extra files in `doc-files/` because a hyphen cannot appear in a
package name. Prefixes on files give the same safety without a second tree.
Stay with files in the package directory.

Do not index other markdown:

- `README.md` is a map for a Git checkout. The package page replaces it in
  the browser.
- `ORIGIN.md` records where a vendored asset came from.
- `AGENTS.md` and `CLAUDE.md` instruct tools.
- `notes.md`, `TODO.md`, and any name that does not start with a kind
  prefix.

Names are lowercase. `Tutorial.md` is not a document.

Each package owns only its own directory. `src/web/ui/howto-form.md` belongs
to `web.ui`, not to `web`.

### Title, without a parse

The title is line 1.

- If line 1 matches `# ` plus text, the title is that text, stripped.
- If it does not, the title is the slug with hyphens turned into spaces. An
  empty slug falls back to the kind word (`Tutorial`, `How-to`,
  `Reference`, `Guide`).

This is the godoc rule in another shape. The package comment is the comment
before `package`. The document title is the first line of the file. Do not
scan later lines for a heading. Do not accept Setext underlines. Authors who
want a title put `# Title` on line 1. The files in this tree already do.

Reading line 1 is a line read. It is not `Markdown.parse`.

### Order, without a parse

Sort by filename. The optional `nn` prefix is how an author sets reading
order when A–Z is the wrong order.

Empty slug comes first in its kind, then the rest in filename order. The
intro file is the intro.

### Collisions

The key is `(package, kind, slug)`. Two files that normalize to the same key
are an error at index time. `guide.md` and `explanation.md` both have kind
`guide` and an empty slug, so a package must not hold both.

Fail the document set for that package with a message that names both paths.
Do not pick a winner.

### What you do not write

No front matter. No `README.md` table the indexer must parse. No
`@document` tag in `package-info.java`. No sidecar `.title` file.

Adding a how-to is one step: create `howto-repl.md` in the package
directory, first line `# Run an evaluator as a REPL`.

## Index

Discover documents while the project origin is indexed. For each package
directory, list `*.md`, match the filename rule, read line 1 for the title,
store the file text as the body.

Do not run the markdown parser at index time. Search needs the raw text.
HTML needs a parse later, when a page is rendered.

Store documents in a new table. Do not reuse `type` with
`kind = 'document'`. A document has no members, no modifiers, and no nested
types.

```
document (
    origin,          -- same origin as the package
    package,         -- tcl, fetch, …
    kind,            -- tutorial | howto | reference | guide
    slug,            -- empty, or first-script, or expr-functions
    title,           -- line 1, or the fallback
    body,            -- markdown source, not HTML
    source           -- path on disk
)
```

Unique on `(origin, package, kind, slug)`.

Bump `Schema.VERSION`. An old index is derived data. Throw it away and build
it again.

Include matching `.md` files in the project stamp. Today the stamp hashes
`.java` files only. An edit to `howto-repl.md` must invalidate the origin.

Full-text search already indexes `doc` text with Porter stemming. Insert each
document into `search`:

- `symbol` is `package/kind` when the slug is empty, else
  `package/kind/slug` (`tcl/tutorial`, `tcl/reference/commands`)
- `kind` is the document kind (`tutorial`, `howto`, `reference`, `guide`)
- `doc` is the title plus the body

A search for `TCL BUSY` finds the reference file that contains it. A search
for `first script` finds that tutorial.

The package row does not store bodies. `tuul docs tcl --json` stays a
description of the package. It gains a list of `{kind, slug, title}`. The
body loads when that document is asked for.

Vendor jars and the JDK have no Diataxis files today. Index project sources
first. The same filename rule applies later if a sources jar ships these
files.

## Lookup, CLI, and URLs

`Index.lookup` stays for types, packages, and modules.

Add `Index.document(package, kind, slug)`. An unknown triple is a miss, the
same way an unknown type is a miss.

Add `Index.documents(package)` and `Index.documents(package, kind)` for the
hub pages.

CLI:

```
$ tuul docs tcl
package tcl
  Embeds Tcl syntax around JVM objects.
  at src/tcl/package-info.java:19

  tutorial     Tutorial: a first script
  tutorial     Nested eval                    first-script
  howto        Run an evaluator as a REPL     repl
  reference    Reference
  reference    Commands                       commands
  guide        Explanation

  tcl.Command
  tcl.Expr
  …

$ tuul docs tcl/tutorial
$ tuul docs tcl/tutorial/first-script
$ tuul docs tcl/reference
$ tuul docs tcl/reference/commands
```

`tuul docs tcl/tutorial` with **one** tutorial (empty slug only) prints that
document.

`tuul docs tcl/tutorial` with **several** tutorials lists them, unless an
empty-slug file exists. Then it prints that intro. The list is still on the
package page.

`tuul docs tcl/tutorial/first-script` prints that file's markdown. An agent
asked for a document. Give it the document, not HTML.

`--json` for one document:

```
{
  "kind": "tutorial",
  "package": "tcl",
  "slug": "first-script",
  "title": "Tutorial: a first script",
  "doc": "# Tutorial: a first script\n\n…",
  "source": "src/tcl/tutorial-01-first-script.md"
}
```

Browser routes:

| What | Route |
|---|---|
| package, type, module | `/symbols/{name}` (unchanged) |
| kind intro, or kind list | `/symbols/{name}/{kind}` |
| one document with a slug | `/symbols/{name}/{kind}/{slug}` |

`{name}` already uses dots (`java.util.List`). A second segment cannot clash
with a type name. `{slug}` may contain hyphens. It does not contain slashes.

Content negotiation stays as it is for symbols. JSON is the object above.
HTML is the rendered page.

Do not serve the raw `.md` file as an asset. Assets are digested and cached
as immutable files. A document is content. It goes through the index and
through `Markdown.render`, the same as a doc comment.

## Browser pages

**Package page.** Keep the overview, the package list, and the type list.
Add four optional sections above Types, one per kind that has files. Each
row is a title and a link. Empty-slug documents use the kind URL. This is
the hub.

**Kind page.** `/symbols/tcl/reference` with several reference files and no
intro file lists those files. With an intro file, it is that document, and
the other files of the same kind appear as a list under it.

**Document page.** Trail: tuul → package → kind → title. Heading is the
title. Badge is the kind. Body is `Markdown.render` into the response
writer. Do not build an HTML string first.

**Search result.** A document is a row with its own kind. The letter is `D`
(uppercase: it has a page of its own). The word is the kind (`tutorial`,
not `document`). The link is the document URL. The summary is the first
sentence after line 1, capped the way type results already cap a comment.

Do not put documents in the sidebar tree. The tree is one level: packages
and modules. A document is reached from its package, from search, or from a
URL.

## Links inside a document

The files already point at each other:

```
Facts about commands are in [reference.md](reference.md).
```

After a split they will point at siblings:

```
See [commands](reference-commands.md).
```

They also point at Java names in prose. The browser already resolves
`[Type#member]` through `symbols.References` and `markdown.Links`.

Extend that resolver for a document page:

1. A defined markdown link wins. The document means what it wrote.
2. A relative target that matches the filename rule and exists in this
   package becomes that document's URL. Keep a `#fragment` if one is
   present.
3. A leftover `[Type#member]` label still resolves through the symbol index.
4. Anything else stays as text.

Resolve by filename, not by parsing the destination document. The same rule
that discovered the file now discovers the link.

## What this does not do

- It does not generate a second site (javadoc `-d`, mkdocs, a static tree of
  HTML).
- It does not execute tutorial code. Go's `Example*` functions are tests.
  Markdown fences are not.
- It does not merge reference files into type comments. The interpreter
  contract and the Java API remain two texts.
- It does not parse `README.md`, front matter, or headings below line 1 in
  order to classify a file.
- It does not invent a `tutorial/` directory inside the package.
- It does not extract a table of contents from headings at index time.
  Headings exist for the reader of that page.

## Why this stays maintainable

One pipeline. Comments and documents both become markdown, then HTML or
JSON. `Views.documentation` already renders a comment. A document page
calls the same renderer with a longer string.

One convention. The filename is kind, slug, and order. Line 1 is the title.
No annotation, no dummy type, no include tag, no parser in the indexer.

One hub. The package page lists types and documents, grouped by kind. A
reader who knows the package name can find either kind of text in one step.

Many files per kind without new machinery. The second how-to is a second
file. The indexer already lists a directory.

Two sources, kept apart on purpose. Short overviews and API contracts belong
in Java comments, because `tuul docs` and javac already read them. Long
Diataxis pages belong in markdown, because that is the format they are
already written in, and because godoc itself refused to swallow narrative
into `doc.go`.

## Implementation order

1. Stamp matching `.md` files. Discover by filename when a project package
   is indexed. Store `(kind, slug, title, body)`. Expose `Index.document`
   and `Index.documents`. Reject collisions.
2. Extend `Docs.describe` for a package with the document list. Accept
   `tcl/tutorial` and `tcl/tutorial/first-script` in `tuul docs`.
3. Add the document routes and pages. Group documents on the package page.
   Negotiate JSON the way a symbol already does.
4. Resolve sibling `.md` links by the same filename rule. Index document
   bodies for search. Add a result kind for a document.

Each step is usable on its own. After step 1, an agent can query the index.
After step 2, the CLI matches the browser. After step 3, a person can read.
After step 4, search and in-page links catch up.
