# tcl

A Tcl interpreter that holds JVM objects as values. This directory also
contains a stream-friendly REPL driver. These documents are the design and
the contract.

The host passes objects and classes into the interpreter. A script finds
those objects, calls procs and Java methods, creates objects with `new`,
tests them with `instanceof`, and returns an object.

There is no I/O in this package. The host reads and writes. `eval` accepts a
`Reader`. The interpreter does not open a file.

One interpreter runs on one thread at a time. The host owns virtual threads,
streams, and Flow. The script sees objects and method calls.

`Repl` does not own an interpreter. The host supplies an evaluator, so the
same driver can run the interpreter over a terminal, a pipe, or a test
reader.

## Documents

Each document answers one kind of question.

| You want | Read |
|---|---|
| To learn by a first program | [tutorial.md](tutorial.md) |
| To complete a task | [howto.md](howto.md) |
| A fact, a table, or a rule | [reference.md](reference.md) |
| To know why the design is this way | [explanation.md](explanation.md) |

Start with the tutorial if you did not use this interpreter before. Use the
reference when you implement the package or when you write a script.
