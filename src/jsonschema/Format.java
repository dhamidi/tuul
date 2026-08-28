package jsonschema;

import java.util.function.Predicate;

/// One value of the `format` keyword, and the test behind it.
///
/// `format` is the second place the specification expects a caller to extend an
/// implementation, and a store holds these by name. A caller registers one with
/// [Store#format(Format)] and it takes effect at once. A name the store does
/// not know is never a failure: the specification says an implementation must
/// not fail an instance for a format it cannot test.
///
/// The test only ever sees a string. `format` says nothing about an instance of
/// any other type.
public interface Format {

    String name();

    boolean matches(String value);

    static Format of(String name, Predicate<String> test) {
        return new Named(name, test);
    }

    record Named(String name, Predicate<String> test) implements Format {

        @Override
        public boolean matches(String value) {
            return test.test(value);
        }
    }
}
