# switch

## Syntax

`switch ?option …? string pattern body ?pattern body …?`

`switch ?option …? string {pattern body …}`

## Result

Return the result of the first matching body. Return the empty string when no pattern matches.

## Errors

Raise an error for an unknown option, an odd pattern/body list, or invalid list syntax.

## Example

```tcl
switch -exact $state active {set result yes} default {set result no}
switch -instanceof $value Json.Num {$value value} default {error "not a number"}
```

## Behavior

Compare `string` to each `pattern` in order. Eval the first matching `body`.
Return that result. If no pattern matches, return `""`.

Options:

| Option | Match |
|---|---|
| `-exact` | string equality. This is the default. |
| `-glob` | glob. See [Glob](reference-runtime.md#glob). |
| `-regexp` | `java.util.regex.Pattern`. Not Tcl ARE. |
| `-instanceof` | `string` is any value. A pattern is a class command name or a `Class`. The first class the value is an instance of matches. See [instanceof](reference-instanceof.md). |
| `-nocase` | fold case for `-exact` and `-glob`. `CASE_INSENSITIVE` for `-regexp`. |
| `--` | end of options |

If there is one argument after `string`, parse it as a Tcl list of pattern
and body pairs.

A pattern named `default` matches when no earlier pattern matched.

With `-instanceof`, `string` stays an object. `null` matches only
`default`. `-nocase` has no effect. An unknown class name is an error with
code `TCL LOOKUP CLASS`.

A body that is `-` uses the next body that is not `-`.

An odd number of pattern and body words is an error.
