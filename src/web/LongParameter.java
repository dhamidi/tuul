package web;

import json.Json;

/// A signed 64-bit whole number.
public record LongParameter(String name) implements Parameter<Long> {

    public LongParameter {
        if (name.isBlank()) throw new IllegalArgumentException("a parameter needs a name");
    }

    @Override
    public Long parse(String text) {
        return Long.valueOf(text.strip());
    }

    @Override
    public Json json(Long value) {
        return Json.of(value.doubleValue());
    }

    @Override
    public String invalid() {
        return "must be a whole number";
    }
}
