package jsonschema;

/// Numbers, and exactly how far this package can be trusted with them.
///
/// **The limit, stated precisely.** [json.Json.Num] holds a `double`, so every
/// JSON number that reaches this package has already been rounded to the
/// nearest IEEE-754 binary64 value. This package cannot undo that, and three
/// keywords are affected:
///
/// - `type: "integer"` asks whether the number has no fractional part. Above
///   2^53 a `double` has no fractional part left to lose, so every finite value
///   above 2^53 is reported as an integer. `1e300` passes `type: "integer"`.
///   Below 2^53 the answer is exact.
/// - Integers beyond 2^53 are not distinguishable from their neighbours.
///   `9007199254740993` and `9007199254740992` are the same `double`, so `const`
///   and `enum` treat them as equal, and `minimum` and `maximum` cannot separate
///   them. A schema that must compare 64-bit identifiers exactly must compare
///   them as strings.
/// - `multipleOf` divides. `0.1` and `0.0001` are not representable in binary,
///   so an exact test would reject values that the specification calls valid.
///   [#multiple(double,double)] therefore accepts a quotient that is within
///   eight units in the last place of a whole number. That tolerance passes the
///   usual cases and it will accept a value that is wrong by roughly one part in
///   10^15. There is no way to do better without a decimal type in the parser.
///
/// None of this is hidden behind a flag or a mode. A caller who needs exact
/// arithmetic on large or decimal numbers needs a JSON parser that keeps the
/// literal text, and this project's parser does not.
public final class Numbers {

    /// Two to the power of 53: the last integer a `double` can hold together
    /// with its neighbour.
    public static final double EXACT = 9007199254740992.0;

    private Numbers() {}

    /// Whether this number is a whole number. Above [#EXACT] the answer is
    /// always true, because a `double` that large has no fractional part.
    public static boolean integral(double value) {
        return Double.isFinite(value) && value == Math.rint(value);
    }

    /// Whether `value` divides by `factor` with nothing left over, within the
    /// tolerance this file describes.
    public static boolean multiple(double value, double factor) {
        if (factor == 0) return false;
        var quotient = value / factor;
        if (!Double.isFinite(quotient)) return false;
        var nearest = Math.rint(quotient);
        return Math.abs(quotient - nearest) <= Math.ulp(quotient) * 8;
    }

    /// A number as it should read in a message: `3` and not `3.0`.
    public static String text(double value) {
        if (integral(value) && Math.abs(value) < 1e15) return Long.toString((long) value);
        return Double.toString(value);
    }
}
