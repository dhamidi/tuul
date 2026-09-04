# Tutorial: embed the reload coordinator

Use one coordinator to replace a complete set of definitions without changing
work that already acquired the prior generation.

For exact behavior, read [reference.md](reference.md).

## Define a capability

Create the capability key in host code. Every generation uses that same key:

```java
var message = Capability.<String>create();
```

The host owns the key. Candidate code supplies its value.

## Start the coordinator

Connect an in-process source when another component submits revisions:

```java
var source = new MemoryRevisionSource();
var reload = new Reload().source(source);
```

`source` produces revisions. `reload` validates, activates, and retires their
generations.

## Submit the first generation

Submit a program that attaches the first value:

```java
source.submit(Revision.of("one",
        () -> Generation.empty().with(message, "hello")));
```

Acquire a lease before reading a generation:

```java
try (var lease = reload.lease().orElseThrow()) {
    System.out.println(lease.generation().capability(message).orElseThrow());
}
```

The program prints `hello`. Closing the lease permits that generation to
retire after a replacement activates.

## Replace the generation

Submit another complete program:

```java
source.submit(Revision.of("two",
        () -> Generation.empty().with(message, "hello again")));
```

A new lease reads `hello again`. A lease acquired before the replacement keeps
reading `hello` until it closes.

## Reject a candidate

Submit a program that throws:

```java
source.submit(Revision.of("broken", () -> {
    throw new IllegalStateException("not ready");
}));
```

The coordinator records a problem for `broken`. The next lease still acquires
revision `two`.

## Stop the coordinator

Close the coordinator when the host stops:

```java
reload.close();
```

Close stops its sources and refuses new leases. A lease already acquired keeps
its generation until that lease closes.

Use the [web.reload tutorial](../web/reload/tutorial.md) when the leased
capability is an HTTP handler.
