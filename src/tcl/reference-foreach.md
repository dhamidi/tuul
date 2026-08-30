# foreach

## Syntax

`foreach varName collection body`

`foreach {k v} collection body`

## Result

Return the empty string after the command visits the collection.

## Errors

Raise an error for invalid arguments or an empty variable list.
Raise an error when the collection is unsupported.

## Example

```tcl
foreach item {one two} {
    set last $item
}
```

## Behavior

Eval `body` once per element. `break` and `continue` work.

`collection` is, in order:

1. `Iterable` (including `Collection`)
2. `Iterator`
3. `Stream` (consumed)
4. an array
5. `Map` — each entry is a key and a value, so `{k v}`
6. `String` — `Values.list`

A `Stream` used here cannot be used again.
