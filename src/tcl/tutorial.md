# Tutorial: a first script

This tutorial shows one small program. You create an interpreter. You pass a
Java object into it. A script calls methods on that object and returns a
number.

You need JDK 27. You do not need Tcl on this machine.

## The Java object

The object is a counter. The script must not know how the counter stores the
number. It only calls `inc` and `get`.

```java
record Counter(java.util.concurrent.atomic.AtomicInteger n) {
    public int inc() {
        return n.incrementAndGet();
    }

    public int get() {
        return n.get();
    }
}
```

## Create the interpreter

`Tcl.of()` creates an interpreter. Builtins live in the root namespace `::`.
The current namespace is `::`.

```java
var tcl = Tcl.of();
```

`set` stores a value under a name. Names on the Java side resolve from `::`.
This call stores the counter as `::c`.

```java
tcl.set("c", new Counter(new java.util.concurrent.atomic.AtomicInteger(0)));
```

## Run a script

The script defines a proc. The proc calls `$c inc` in a loop. The last command
in the proc is `$c get`. That value is the result of the proc.

```java
var result = tcl.eval("""
    proc bump {times} {
        set i 0
        while {$i < $times} {
            $c inc
            incr i
        }
        $c get
    }
    bump 10
    """);
```

`result` is `Integer` `10`.

Pass a `Reader` to `eval` when the script is not one string. The interpreter
reads one command at a time. It does not load the rest of the reader first.

## What the script did

1. `proc bump` registers a command named `bump` in `::`.
2. `bump 10` calls that command. The interpreter pushes a frame. The frame
   holds local variables. The current namespace stays `::`.
3. `set i 0` stores the string `"0"` in local `i`. Script text is a string
   until a command asks for a number.
4. `while {$i < $times}` evals the test as an expression. `expr` reads `i` and
   `times` as numbers.
5. `$c inc` is not a builtin. After substitution, the first word is the
   `Counter` object. The interpreter calls Java method `inc` with no arguments.
6. `incr i` stores a `Long`. After the first increment, `i` is not a string.
7. `$c get` returns `Integer` `10`. That is the result of `bump`.

## Lone substitution keeps the object

`$c` is the `Counter`. It is not the string form of the counter.

`"$c"` is a string. The interpreter builds that string with `Values.string`.

Use `$c inc` to call a method. Use `"counter=$c"` only when you want text.

## Next

- [howto.md](howto.md) shows other tasks: `invoke`, a namespace, `catch`,
  `foreach`, a `Stream`.
- [reference.md](reference.md) lists every command and the value rules.
- [explanation.md](explanation.md) says why values are JVM objects and why
  namespaces start at `::`.
