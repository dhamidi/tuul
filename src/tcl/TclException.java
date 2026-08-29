package tcl;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/// A non-zero Tcl return code, its result, and its return options.
///
/// [Error] is code 1. [Return], [Break], and [Continue] are codes 2 through 4.
/// Commands such as `catch` can inspect every code through [#code()].
@SuppressWarnings("serial")
public abstract sealed class TclException extends RuntimeException
        permits TclException.Error, TclException.Return, TclException.Break,
                TclException.Continue, TclException.Code {

    private final Object result;
    private final long code;
    private final Map<Object, Object> options;

    TclException(Object result, long code, Map<?, ?> options, Throwable cause) {
        super(Values.string(result), cause);
        this.result = result;
        this.code = code;
        var copy = new LinkedHashMap<Object, Object>();
        options.forEach(copy::put);
        copy.putIfAbsent("-code", code);
        copy.putIfAbsent("-level", 0L);
        this.options = copy;
    }

    /// Returns the Tcl result carried by this non-zero return code.
    public Object result() {
        return result;
    }

    /// Returns the numeric Tcl return code.
    public long code() {
        return code;
    }

    /// Returns a copy of the return-options dictionary.
    public Map<Object, Object> options() {
        return new LinkedHashMap<>(options);
    }

    /// Code 1. The methods expose the human and machine error traces.
    public static final class Error extends TclException {

        /// Creates an error with the `NONE` machine code.
        public Error(String message) {
            this(message, Map.of(), null);
        }

        /// Creates an error with the specified machine code.
        public Error(String message, List<?> errorCode) {
            this(message, Map.of("-errorcode", List.copyOf(errorCode)), null);
        }

        Error(Object result, Map<?, ?> options, Throwable cause) {
            super(result, 1L, errorOptions(result, options), cause);
        }

        private static Map<Object, Object> errorOptions(Object result, Map<?, ?> supplied) {
            var options = new LinkedHashMap<Object, Object>();
            supplied.forEach(options::put);
            options.put("-code", 1L);
            options.putIfAbsent("-level", 0L);
            options.putIfAbsent("-errorinfo", Values.string(result));
            options.putIfAbsent("-errorcode", List.of("NONE"));
            options.putIfAbsent("-errorline", 1L);
            options.putIfAbsent("-errorstack", List.of());
            return options;
        }

        /// Returns the human traceback.
        public String errorInfo() {
            return Values.string(options().get("-errorinfo"));
        }

        /// Returns the machine error-code list.
        public List<Object> errorCode() {
            return Values.list(options().get("-errorcode"));
        }

        /// Returns the source line of the command that failed.
        public long errorLine() {
            return Values.integer(options().get("-errorline"));
        }

        /// Returns the machine traceback as token and parameter pairs.
        public List<Object> errorStack() {
            return Values.list(options().get("-errorstack"));
        }

        @Override
        public synchronized Throwable getCause() {
            return super.getCause();
        }
    }

    /// Code 2. A proc consumes one level and then applies the requested code.
    public static final class Return extends TclException {

        Return(Object result, Map<?, ?> options) {
            super(result, 2L, options, null);
        }
    }

    /// Code 3. A loop consumes this code.
    public static final class Break extends TclException {

        /// Creates code 3 with an empty result.
        public Break() {
            super("", 3L, Map.of(), null);
        }

        Break(Object result, Map<?, ?> options) {
            super(result, 3L, options, null);
        }
    }

    /// Code 4. A loop consumes this code.
    public static final class Continue extends TclException {

        /// Creates code 4 with an empty result.
        public Continue() {
            super("", 4L, Map.of(), null);
        }

        Continue(Object result, Map<?, ?> options) {
            super(result, 4L, options, null);
        }
    }

    static final class Code extends TclException {

        Code(Object result, long code, Map<?, ?> options) {
            super(result, code, options, null);
        }
    }
}
