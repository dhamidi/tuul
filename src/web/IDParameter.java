package web;

import json.Json;

/// A positive 64-bit numeric identifier.
public record IDParameter(String name) implements Parameter<Long> {

    public IDParameter {
        if (name.isBlank()) throw new IllegalArgumentException("a parameter needs a name");
    }

    @Override
    public Long parse(String text) {
        var value = Long.parseLong(text.strip());
        if (value < 1) throw new IllegalArgumentException("an ID is positive");
        return value;
    }

    @Override
    public Json json(Long value) {
        return Json.of(value.doubleValue());
    }

    @Override
    public String invalid() {
        return "must be a positive whole number";
    }
}
