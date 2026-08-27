package symbols;

import java.util.Optional;

/// Where a type came from: the class file that says what it is, and the source
/// that says what it means — when there is one.
///
/// The three places tuul looks all answer with this: javac's output for the
/// project, a jar under `vendor/`, and `jrt:/` for the JDK.
public record Origin(byte[] classFile, Optional<String> source) {}
