package web.forms;

import java.util.Optional;
import json.Json;

/// Something a value has to be, beyond having the right shape.
///
/// A rule runs after coercion and sees the value as it will be captured, so a
/// range check compares numbers rather than re-parsing text. It runs only when
/// there is a value: a field that was left empty has already been answered by
/// whether it was required, and a rule that also complained would say the same
/// thing twice.
@FunctionalInterface
public interface Rule {

    /// What is wrong with this value, or nothing.
    Optional<String> check(Json value);
}
