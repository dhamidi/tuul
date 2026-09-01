# tuul docs

`tuul docs` answers questions about symbols from the command line. It reads
the symbol index in `build/index.db`. It updates the index when the sources
changed. An agent runs it mid-task. A person runs it from a shell.

The index holds three sources of symbols:

- the project's own sources under `src/`,
- the selected jars under `vendor/`, with their source archives,
- the running JDK.

Ask for a name. The answer describes what the name is.

```sh
$ tuul docs json.Json
$ tuul docs json.Json#parse
$ tuul docs web
$ tuul docs web/tutorial
$ tuul docs --search "event stream"
```

Add `--json` to get the same answer as one JSON object. Add `--code` to get
the source text of the symbol. Add `--documents` to read every document of a
package in one call.

A project that does not compile does not stop `tuul docs`. The command
answers from the last index that compiled. It prints a warning on standard
error that says so.

Read `docs/reference` for every name shape, flag, and JSON field. Read
`docs/howto` for the common tasks.
