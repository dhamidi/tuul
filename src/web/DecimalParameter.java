package web;

import json.Json;

/// A finite double-precision number.
public record DecimalParameter(String name) implements Parameter<Double> {

    public DecimalParameter {
        if (name.isBlank()) throw new IllegalArgumentException("a parameter needs a name");
    }

    @Override
    public Double parse(String text) {
        var value = Double.parseDouble(text.strip());
        if (!Double.isFinite(value)) throw new IllegalArgumentException("a decimal is finite");
        return value;
    }

    @Override
    public Json json(Double value) {
        return Json.of(value);
    }

    @Override
    public String invalid() {
        return "must be a number";
    }
}
