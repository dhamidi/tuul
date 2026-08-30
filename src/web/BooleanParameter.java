package web;

import java.util.Locale;
import java.util.Set;
import json.Json;

/// A browser boolean. `1`, `true`, `on`, and `yes` are true. `0`, `false`,
/// `off`, `no`, and a blank are false.
public record BooleanParameter(String name) implements Parameter<Boolean> {

    private static final Set<String> TRUE = Set.of("1", "true", "on", "yes");
    private static final Set<String> FALSE = Set.of("", "0", "false", "off", "no");

    public BooleanParameter {
        if (name.isBlank()) throw new IllegalArgumentException("a parameter needs a name");
    }

    @Override
    public Boolean parse(String text) {
        var value = text.strip().toLowerCase(Locale.ROOT);
        if (TRUE.contains(value)) return true;
        if (FALSE.contains(value)) return false;
        throw new IllegalArgumentException("not a browser boolean");
    }

    @Override
    public String format(Boolean value) {
        return value ? "1" : "0";
    }

    @Override
    public Json json(Boolean value) {
        return Json.of(value);
    }
}
