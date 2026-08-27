package uritemplates;

import java.util.List;

/// A template is a sequence of these, and nothing else: text that is already
/// part of the URI, and expressions that become part of it once there are
/// values.
public sealed interface Part {

    /// Text that stands as written.
    record Literal(String text) implements Part {}

    /// `{...}` — an operator and the variables it applies to.
    record Expression(Operator operator, List<Varspec> variables) implements Part {

        public Expression {
            variables = List.copyOf(variables);
        }
    }
}
