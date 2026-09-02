# tuul docs reference

## Command

```
tuul docs [symbol] [flags]
tuul docs --search <text> [--json]
```

`tuul docs` with no symbol lists the roots. The roots are the project's
packages, the packages of the selected dependencies, and the JDK modules
that export something. Each name in the list is a name you can ask for.

## Name shapes

| Shape | Example | Answer |
| --- | --- | --- |
| Type | `json.Json`, `json.Json.Object` | The type: its comment, what it extends, what it declares, its public members. |
| Member | `json.Json#parse` | Every method or field of the type with that name, with the whole comment. |
| Package or module | `web`, `java.base` | The comment, the documents, the subpackages, and the public types. |
| Document | `web/tutorial`, `web/reference/uploads`, `fetch/readme` | The Markdown of one package document. |
| Document kind | `web/reference` | The introduction of that kind, or the list of documents of that kind. |

A nested type accepts dots: `json.Json.Object` finds `json.Json$Object`.

A document name is `package/kind` or `package/kind/slug`. The kinds are
`readme`, `tutorial`, `howto`, `reference`, and `guide`. A package's
`README.md` is its `readme` document. A file named `explanation.md` is a
`guide`.

## Flags

| Flag | Effect |
| --- | --- |
| `--json` | Print the answer as one JSON object. The text output is a rendering of this object and never adds a fact to it. |
| `--all` | Include private and package-private members. |
| `--members` | Describe every symbol the package or type holds, after the symbol itself. |
| `--recursive` | As `--members`, and into subpackages. |
| `--documents` | Print every document of the package in full, after the package. The README comes first. |
| `--code` | Print the source text instead of a description. See below. |
| `--doc`, `--source`, `--extends`, `--implements`, `--permits`, `--nested`, `--methods`, `--fields` | Print only that section, one value per line. |
| `--search <text>` | Search names and comments. See below. |
| `--source-path <dir>` | Read sources from `dir` instead of `src`. Repeatable. |
| `--vendor <dir>` | Read jars from `dir` instead of `vendor`. Repeatable. |

## Source code

`--code` prints source text and nothing else. You can compile or render the
output as it was written.

| Asked for | Printed |
| --- | --- |
| A top-level type | The whole source file. |
| A nested type | The declaration of that type and the comment above it. |
| A member | The declaration of each method or field with that name and the comment above it. |
| A package or module | Its `package-info.java` or `module-info.java`. |
| A document | Its Markdown. |

The source of a project type is the file under `src/`. The source of a
dependency type is an entry in its `-sources.jar`. The source of a JDK type
is an entry in `lib/src.zip`. A jar with no source archive has no source to
print. The command then exits with status 1. It prints `no source for <name>`.

## Search

`tuul docs --search <text>` splits the text into words. It finds symbols and
documents whose name or comment holds every word. When nothing holds every
word, it finds what holds any word. It then says so on standard error:

```
nothing holds every word of "event stream"; these hold some of them
```

Two words are also tried as one word. A search for `event stream` finds the
package `eventstream`.

The results are grouped. Each group is named by the longest prefix its
results share. That prefix is a package or a type. The name of a group is a
name you can ask for. Groups come in the order of their best result. A
symbol appears once. Overloads are one result.

```
eventstream
  eventstream.Parser
      Reads a text/event-stream.
  eventstream.Event#data
      The data lines, joined.
```

A dependency result names its artifact in brackets after the symbol.

## JSON

A type or package answers with these fields. `--members` adds `members`, a
list of the same objects. Section flags keep only the named fields.

```json
{"class":"json.Json","kind":"interface","doc":"...","modifiers":["public","sealed"],
 "typeParameters":[],"extends":"","implements":[],"permits":["json.Json.Object"],
 "nested":["json.Json.Object"],"source":"src/json/Json.java","line":12,"tags":[],
 "methods":[{"name":"parse","returns":"json.Json","parameters":["java.lang.String text"],
   "modifiers":["public","static"],"signature":"json.Json parse(java.lang.String text)",
   "line":40,"doc":"...","tags":[]}],
 "fields":[],
 "documents":[{"symbol":"json/reference","kind":"reference","slug":"","title":"JSON reference",
   "sections":[{"title":"Values","anchor":"values"}]}]}
```

`documents` is present for a package that has documents. With
`--documents`, each entry also carries `doc`, the Markdown body, and
`source`, the file.

A member answers with the type's object. In that object, `member` holds the
name. `methods` and `fields` hold only the members with that name.

A document answers with:

```json
{"symbol":"web/tutorial","kind":"tutorial","package":"web","slug":"","title":"Build a page",
 "doc":"# Build a page\n...","source":"src/web/tutorial.md"}
```

A search answers with:

```json
{"query":"event stream","every":true,
 "groups":[{"prefix":"eventstream",
   "matches":[{"symbol":"eventstream.Parser","kind":"class","modifiers":"public final",
     "doc":"...","origin":"project","source":"src/eventstream/Parser.java"}]}]}
```

`every` is false when the results hold only some of the words. `origin` is
`project`, `platform`, or the Maven coordinate of a dependency.

The roots answer with:

```json
{"roots":[{"root":"project","label":"This project","contains":["json","web"]}]}
```

## Exit status and standard error

| Status | Meaning |
| --- | --- |
| 0 | The answer was printed. |
| 1 | The name is unknown, the search found nothing, or the source is unavailable. Standard error says which. |

A line that starts with `warning:` on standard error means the answer came
from the last good index. The line names the reason, such as a project that
does not compile. The compiler's messages follow. The exit status stays 0.

`indexing...` on standard error means the index has worked for five seconds
without answering. The first search over a large dependency set can take
longer than that.

## The index

The index is `build/index.db`, an SQLite database. Everything the command
knows is in it. Deleting it costs the time to build it again.

The command refreshes the index when it needs to:

- A question about a project name compiles the project when a source file
  under `src/` changed since the last index. A question about a JDK or
  dependency name does not compile the project.
- A question about a document reads the Markdown files again when one
  changed. It does not compile Java.
- A search makes the project, the selected dependencies, and the exported
  JDK names complete.

When the project does not compile, the index keeps the rows of the last
build that compiled. It records the failure against the current sources.
The same broken sources are not compiled again until they change.

`tuul build` and `tuul docs` share class files. When `build/classes` matches
the current sources, the index reads it instead of running the compiler.
