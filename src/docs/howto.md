# tuul docs how-to

## Find a symbol when you know only a word

Search for the word. Read the group names first. A group name is a package
or a type. You can ask for it next.

```sh
$ tuul docs --search "event stream"
eventstream
  eventstream.Parser
      Reads a text/event-stream.
$ tuul docs eventstream
```

Search for two or more words to narrow the answer. When no symbol holds
every word, the command shows the symbols that hold some of them. It says
so on standard error.

## Read a package

Ask for the package. The answer lists its documents, its subpackages, and
its types.

```sh
$ tuul docs web
```

Ask for the package with `--members` to describe every type in it in one
call. Add `--recursive` to include the subpackages.

```sh
$ tuul docs web --members
$ tuul docs web --recursive --json
```

## Read the documents of a package

Ask for the package with `--documents`. The answer prints every document in
full, the README first. Each document starts with a line that names it.

```sh
$ tuul docs fetch --documents
```

Ask for one document by name to read only that one.

```sh
$ tuul docs fetch/readme
$ tuul docs fetch/howto
$ tuul docs web/reference/uploads
```

## Read one member

Ask for `Type#member`. The answer holds every overload, each with the whole
comment.

```sh
$ tuul docs json.Json#parse
```

## Read the source code

Add `--code` to any name. The output is source text only.

```sh
$ tuul docs json.Json --code
$ tuul docs json.Json#parse --code
$ tuul docs java.util.HashMap#resize --code
```

Use `--source` to print only where the declaration is, as `file:line`.

## Get an answer a program can read

Add `--json` to any question. Read the field names in `docs/reference`.

```sh
$ tuul docs json.Json --json
$ tuul docs --search parse --json
```

## Ask when the project does not compile

Ask as usual. The answer comes from the last index that compiled. A
`warning:` line on standard error shows the compiler's messages. Fix the
sources. The next question compiles them again.

## Ask about a project in another directory

Name the directories.

```sh
$ tuul docs --source-path ../other/src --vendor ../other/vendor other.Type
```
