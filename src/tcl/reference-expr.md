# expr

## Syntax

`expr arg …`

## Result

Return a `Long` when the expression produces an integer.
Return a `Double` for other numeric results.
Return a boolean for comparisons.

## Errors

Raise an error for invalid expression syntax or unknown variables.
Raise an error for unsupported operators or division by zero.
Raise an error for an invalid function domain.

## Example

```tcl
set count 2
expr {$count + 1}
```

## Behavior


One argument: parse it as an expression. A lone `Long` stays a `Long`.

Several arguments: stringify, join with one space, then parse.

Operators, from tight to loose:

- unary `+` `-` `!` `~`
- `**`
- `*` `/` `%`
- `+` `-`
- `<<` `>>`
- `<` `>` `<=` `>=`
- `==` `!=`
- `eq` `ne`
- `&` `^` `|`
- `&&` `||`
- `?:`
- `( )`

`/` on two integers is integer division.

`==` / `!=`: two `Number` values compare as numbers. Else `Objects.equals`.

`eq` / `ne`: stringify, then compare.

`<` `>` `<=` `>=`: two `Number` values compare as numbers. Else
`Comparable`. Else error.

Functions: `int`, `double`, `abs`, `min`, `max`, `round`.

`$name` inside an expression finds the value. `[script]` inside an
expression evals that script. Use braces: `expr {$x + 1}`.
