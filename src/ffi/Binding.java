package ffi;

import java.io.IOException;
import java.io.Writer;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/// Writes a Java binding for a C library: one method per function, with the
/// names and the argument order C uses, so anything written about the C library
/// still describes this.
///
/// The output is generated and committed rather than woven into the build. It
/// is ordinary Java — readable, greppable, and answerable by `tuul docs` — and
/// regenerating it is one command.
public final class Binding {

    private static final java.util.regex.Pattern QUALIFY = java.util.regex.Pattern.compile("(?<![\\w.])[A-Za-z_]\\w*");

    private static final Set<String> KEYWORDS = Set.of(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const", "continue",
            "default", "do", "double", "else", "enum", "extends", "final", "finally", "float", "for", "goto", "if",
            "implements", "import", "instanceof", "int", "interface", "long", "native", "new", "package", "private",
            "protected", "public", "return", "short", "static", "strictfp", "super", "switch", "synchronized", "this",
            "throw", "throws", "transient", "try", "void", "volatile", "while", "var", "record", "yield");

    private Binding() {}

    /// Streams the whole class into `out`.
    public static void write(String packageName, String type, String library, String header,
                             Header.Declarations declarations, Writer out) throws IOException {
        var functions = declarations.functions();
        head(packageName, type, library, header, functions.size(), out);
        constants(type, declarations.constants(), out);
        for (var index = 0; index < functions.size(); index++) {
            var function = functions.get(index);
            if (function.variadic()) variadic(function, out);
            else function(function, index, out);
        }
        out.write("}\n");
        out.flush();
    }

    /// The `#define`s, as they were written. References are qualified so the
    /// order they were written in does not matter.
    private static void constants(String type, List<Header.Constant> constants, Writer out) throws IOException {
        for (var constant : constants) {
            var value = constant.text()
                    ? constant.value()
                    : QUALIFY.matcher(constant.value()).replaceAll(type + ".$0");
            out.write("    public static final %s %s = %s;\n"
                    .formatted(constant.text() ? "String" : "int", constant.name(), value));
        }
        if (!constants.isEmpty()) out.write("\n");
    }

    private static void head(String packageName, String type, String library, String header, int count, Writer out)
            throws IOException {
        out.write("""
                package %s;

                import static java.lang.foreign.ValueLayout.ADDRESS;
                import static java.lang.foreign.ValueLayout.JAVA_BYTE;
                import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
                import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
                import static java.lang.foreign.ValueLayout.JAVA_INT;
                import static java.lang.foreign.ValueLayout.JAVA_LONG;
                import static java.lang.foreign.ValueLayout.JAVA_SHORT;

                import ffi.Library;
                import java.lang.foreign.FunctionDescriptor;
                import java.lang.foreign.MemoryLayout;
                import java.lang.foreign.MemorySegment;
                import java.lang.invoke.MethodHandle;
                import java.util.concurrent.atomic.AtomicReferenceArray;

                /// The %s C API — all %d functions of it — as C spells them.
                ///
                /// Generated from %s by `tuul bind %s`. Do not edit: change the
                /// header or the generator, and generate it again.
                ///
                /// A function is linked the first time it is called and not
                /// before: an API this size costs a third of a second to link in
                /// full, and no program uses all of it. One the library does not
                /// export fails only if something calls it.
                ///
                /// A variadic function answers with a call site rather than a
                /// result, since only the caller knows the shape of the arguments
                /// it is about to pass.
                public final class %s {

                    private static final Library LIBRARY = Library.open("%s");

                    private static final AtomicReferenceArray<MethodHandle> FUNCTIONS = new AtomicReferenceArray<>(%d);

                    private %s() {}

                """.formatted(packageName, library, count, header, library, type, library, count, type));
        out.write("""
                    private static MethodHandle link(int index, String name, FunctionDescriptor descriptor) {
                        var function = LIBRARY.optional(name, descriptor);
                        if (function == null) throw new UnsatisfiedLinkError("this build of %s does not export " + name);
                        FUNCTIONS.compareAndSet(index, null, function);
                        return FUNCTIONS.get(index);
                    }

                    private static RuntimeException failed(String name, Throwable cause) {
                        if (cause instanceof RuntimeException already) throw already;
                        return new IllegalStateException("calling " + name + " failed", cause);
                    }

                """.formatted(library));
    }

    private static void function(Header.Function function, int index, Writer out) throws IOException {
        var returns = java(function.returns().kind());
        out.write("    public static %s %s(%s) {\n".formatted(returns, function.name(), parameters(function)));
        out.write("        var function = FUNCTIONS.get(%d);\n".formatted(index));
        out.write("        if (function == null) function = link(%d, \"%s\",\n                %s);\n"
                .formatted(index, function.name(), descriptor(function)));
        out.write("        try {\n");
        var call = "function.invokeExact(%s)".formatted(arguments(function));
        out.write(returns.equals("void")
                ? "            %s;\n".formatted(call)
                : "            return (%s) %s;\n".formatted(returns, call));
        out.write("        } catch (Throwable e) {\n");
        out.write("            throw failed(\"%s\", e);\n".formatted(function.name()));
        out.write("        }\n    }\n\n");
    }

    /// A variadic function cannot be wrapped once: the descriptor depends on
    /// what the caller is about to pass, so the caller gets the handle.
    private static void variadic(Header.Function function, Writer out) throws IOException {
        out.write("    /// Variadic. Give it the layouts of the extra arguments and it answers\n");
        out.write("    /// with a handle for that call: %s(...).\n".formatted(function.name()));
        out.write("    public static MethodHandle %s(MemoryLayout... variadic) {\n".formatted(function.name()));
        out.write("        return LIBRARY.variadic(\"%s\",\n                %s, variadic);\n"
                .formatted(function.name(), descriptor(function)));
        out.write("    }\n\n");
    }

    private static String descriptor(Header.Function function) {
        var layouts = function.parameters().stream().map(parameter -> layout(parameter.type().kind())).toList();
        if (function.returns().kind() == Header.Kind.VOID) {
            return "FunctionDescriptor.ofVoid(" + String.join(", ", layouts) + ")";
        }
        var arguments = layouts.isEmpty() ? "" : ", " + String.join(", ", layouts);
        return "FunctionDescriptor.of(" + layout(function.returns().kind()) + arguments + ")";
    }

    private static String parameters(Header.Function function) {
        return String.join(", ", function.parameters().stream()
                .map(parameter -> java(parameter.type().kind()) + " " + name(parameter.name()))
                .toList());
    }

    private static String arguments(Header.Function function) {
        return String.join(", ", function.parameters().stream().map(parameter -> name(parameter.name())).toList());
    }

    private static String name(String name) {
        return KEYWORDS.contains(name) ? name + "_" : name;
    }

    private static String java(Header.Kind kind) {
        return switch (kind) {
            case VOID -> "void";
            case BYTE -> "byte";
            case SHORT -> "short";
            case INT -> "int";
            case LONG -> "long";
            case FLOAT -> "float";
            case DOUBLE -> "double";
            case POINTER -> "MemorySegment";
        };
    }

    private static String layout(Header.Kind kind) {
        return switch (kind) {
            case VOID -> throw new IllegalArgumentException("void is not a value");
            case BYTE -> "JAVA_BYTE";
            case SHORT -> "JAVA_SHORT";
            case INT -> "JAVA_INT";
            case LONG -> "JAVA_LONG";
            case FLOAT -> "JAVA_FLOAT";
            case DOUBLE -> "JAVA_DOUBLE";
            case POINTER -> "ADDRESS";
        };
    }
}
