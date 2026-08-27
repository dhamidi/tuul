package uritemplates;

/// One variable inside an expression, and what the template asks be done with
/// it.
///
/// The two modifiers are exclusive and mean different things: a prefix takes
/// the first characters of a string, and explode spreads a list or a map out
/// instead of joining it. `maxLength` is absent as -1 rather than an Optional,
/// because it is read once per value expanded and a template can hold many.
public record Varspec(String name, int maxLength, boolean explode) {

    public static final int WHOLE = -1;

    public Varspec {
        if (maxLength != WHOLE && explode) {
            throw new TemplateException(name + " has both a prefix and an explode modifier");
        }
    }

    public static Varspec of(String name) {
        return new Varspec(name, WHOLE, false);
    }

    public boolean truncated() {
        return maxLength != WHOLE;
    }

    /// How the varspec was written, which is what an error message should say.
    public String written() {
        if (explode) return name + "*";
        return truncated() ? name + ":" + maxLength : name;
    }
}
