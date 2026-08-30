package web;

/// Text that keeps every character exactly as it arrived.
public record StringParameter(String name) implements Parameter<String> {

    public StringParameter {
        if (name.isBlank()) throw new IllegalArgumentException("a parameter needs a name");
    }

    @Override
    public String parse(String text) {
        return text;
    }

    @Override
    public boolean keepsBlank() {
        return true;
    }
}
