package web;

import json.Json;

/// A signed 32-bit whole number.
public record IntegerParameter(String name) implements Parameter<Integer> {

    public IntegerParameter {
        if (name.isBlank()) throw new IllegalArgumentException("a parameter needs a name");
    }

    @Override
    public Integer parse(String text) {
        return Integer.valueOf(text.strip());
    }

    @Override
    public Json json(Integer value) {
        return Json.of(value.doubleValue());
    }

    @Override
    public String invalid() {
        return "must be a whole number";
    }
}
