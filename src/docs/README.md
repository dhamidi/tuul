# tuul docs

Run `tuul docs` to read the API of a type, a package, or a dependency
without opening its source. It reads the symbol index in `build/index.db`
and updates the index when the sources changed.

The index holds three sources of symbols:

- the project's own sources under `src/`,
- the selected jars under `vendor/`, with their source archives,
- the running JDK.

Pass a name. The answer is the comment, the members, and the location of
what the name refers to.

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
