package web;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/// A calendar date in ISO `YYYY-MM-DD` form.
public record DateParameter(String name) implements Parameter<LocalDate> {

    public DateParameter {
        if (name.isBlank()) throw new IllegalArgumentException("a parameter needs a name");
    }

    @Override
    public LocalDate parse(String text) {
        try {
            return LocalDate.parse(text.strip());
        } catch (DateTimeParseException invalid) {
            throw new IllegalArgumentException("not an ISO date", invalid);
        }
    }

    @Override
    public String invalid() {
        return "must be a date, as YYYY-MM-DD";
    }
}
