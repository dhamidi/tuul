package ffi;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.regex.Pattern;

/// Reads the functions out of a preprocessed C header.
///
/// The C preprocessor does the hard part — macros, conditionals, includes — so
/// what arrives here is plain declarations. This only has to know how a
/// declaration is shaped, and how a C type maps onto a machine type, which is
/// all a binding needs.
public final class Header {

    /// What a value is, once typedefs are followed to the bottom.
    public enum Kind {
        VOID, BYTE, SHORT, INT, LONG, FLOAT, DOUBLE, POINTER
    }

    /// A C type: how it was written, and what it turns out to be.
    public record Type(String c, Kind kind) {}

    public record Parameter(Type type, String name) {}

    /// One exported function. `variadic` means the C declaration ended in
    /// `...`, which a call site has to describe for itself.
    public record Function(String name, Type returns, List<Parameter> parameters, boolean variadic) {}

    /// A `#define` worth keeping: a result code, a flag, a limit. The value is
    /// the C expression, which is a Java expression too once the names in it
    /// are qualified.
    public record Constant(String name, String value, boolean text) {}

    /// What could be read, and what could not — a binding that quietly drops
    /// functions is worse than one that says which.
    public record Declarations(List<Function> functions, List<Constant> constants, List<String> skipped) {}

    private static final Pattern MARKER = Pattern.compile("^#\\s+\\d+\\s+\"([^\"]*)\".*");
    private static final Pattern TRAILING = Pattern.compile("([A-Za-z_]\\w*)\\s*(\\[\\s*\\])?$");
    private static final Pattern POINTER_NAME = Pattern.compile("\\(\\s*\\*\\s*([A-Za-z_]\\w*)\\s*\\)");
    private static final Pattern QUALIFIERS = Pattern.compile("\\b(const|volatile|struct|union|enum|extern|static|inline)\\b");
    private static final Pattern DEFINE = Pattern.compile("^#\\s*define\\s+(\\w+)\\s+(.+?)\\s*$");
    /// An identifier, and not the tail of a hex literal: `0x2` is a number,
    /// not a reference to something called `x2`.
    private static final Pattern NAMES = Pattern.compile("(?<![\\w.])[A-Za-z_]\\w*");
    private static final Pattern ARITHMETIC = Pattern.compile("[-+*/()|&^~<>\\s0-9A-Fa-fXx]*");

    private final Map<String, String> typedefs = new LinkedHashMap<>();

    private Header() {}

    /// Every function declared in `header`, ignoring whatever came in through
    /// the includes.
    public static Declarations read(String preprocessed, String header) {
        var reader = new Header();
        var source = only(preprocessed, header);
        var statements = split(source.declarations());
        statements.stream().filter(statement -> statement.startsWith("typedef ")).forEach(reader::typedef);

        var functions = new ArrayList<Function>();
        var skipped = new ArrayList<String>();
        for (var statement : statements) {
            if (!declaration(statement)) continue;
            var function = reader.function(statement);
            if (function != null) functions.add(function);
            else skipped.add(statement.length() > 100 ? statement.substring(0, 100) + "..." : statement);
        }
        return new Declarations(List.copyOf(functions), constants(source.defines(), prefix(header)), List.copyOf(skipped));
    }

    /// Constants are kept when they are a string, or an arithmetic expression
    /// over literals and constants that are themselves kept. Anything else —
    /// a cast, a call, a type — belongs to C and stays there.
    private static List<Constant> constants(Map<String, String> defines, String prefix) {
        var kept = new LinkedHashMap<String, Constant>();
        defines.forEach((name, value) -> {
            if (!name.startsWith(prefix) || value.isEmpty()) return;
            if (value.startsWith("\"")) kept.put(name, new Constant(name, value, true));
            else if (ARITHMETIC.matcher(NAMES.matcher(value).replaceAll("")).matches()) {
                kept.put(name, new Constant(name, value, false));
            }
        });
        while (kept.values().removeIf(constant -> !resolvable(constant, kept))) {
            // a constant that referred to a dropped one has to go as well
        }
        return List.copyOf(kept.values());
    }

    private static boolean resolvable(Constant constant, Map<String, Constant> kept) {
        if (constant.text()) return true;
        var names = NAMES.matcher(constant.value());
        while (names.find()) {
            if (!kept.containsKey(names.group())) return false;
        }
        return true;
    }

    /// `sqlite3.h` names its constants `SQLITE_`, and every header names them
    /// after itself.
    private static String prefix(String header) {
        var stem = header.replaceAll("\\.h$", "").replaceAll("[^A-Za-z0-9]", "");
        return stem.replaceAll("[0-9]+$", "").toUpperCase(Locale.ROOT) + "_";
    }

    /// A function declaration, as opposed to a typedef or the body of a struct
    /// — a header has no function bodies, so a brace is never one.
    private static boolean declaration(String statement) {
        return statement.contains("(")
                && !statement.contains("{")
                && !statement.startsWith("typedef ")
                && !statement.startsWith("struct ")
                && !statement.startsWith("union ")
                && !statement.startsWith("enum ");
    }

    /// The header's own declarations and its own `#define`s, separated —
    /// everything that came in through an include belongs to somebody else.
    private record Source(String declarations, Map<String, String> defines) {}

    private static Source only(String preprocessed, String header) {
        var kept = new StringBuilder();
        var defines = new LinkedHashMap<String, String>();
        var here = false;
        for (var line : preprocessed.split("\n")) {
            var marker = MARKER.matcher(line);
            if (marker.matches()) {
                here = marker.group(1).endsWith(header);
                continue;
            }
            if (!here) continue;
            if (!line.startsWith("#")) {
                kept.append(line).append('\n');
                continue;
            }
            var define = DEFINE.matcher(line);
            if (define.matches() && !define.group(1).contains("(")) defines.put(define.group(1), define.group(2));
        }
        return new Source(kept.toString(), defines);
    }

    /// C statements, one per semicolon that is not inside brackets.
    private static List<String> split(String text) {
        var statements = new ArrayList<String>();
        var statement = new StringBuilder();
        var depth = 0;
        for (var character : text.toCharArray()) {
            if (character == '(' || character == '[' || character == '{') depth++;
            if (character == ')' || character == ']' || character == '}') depth--;
            if (character == ';' && depth == 0) {
                var collapsed = String.join(" ", statement.toString().split("\\s+")).strip();
                if (!collapsed.isEmpty()) statements.add(collapsed);
                statement.setLength(0);
                continue;
            }
            statement.append(character);
        }
        return statements;
    }

    private void typedef(String statement) {
        var body = statement.substring("typedef ".length()).strip();
        var pointer = POINTER_NAME.matcher(body);
        if (body.contains("(*") && pointer.find()) {
            typedefs.put(pointer.group(1), "void *");
            return;
        }
        var name = TRAILING.matcher(body);
        if (!name.find()) return;
        typedefs.put(name.group(1), body.substring(0, name.start()).strip());
    }

    private Function function(String statement) {
        var open = statement.indexOf('(');
        var head = statement.substring(0, open);
        var name = TRAILING.matcher(head);
        if (!name.find()) return null;

        var returns = type(head.substring(0, name.start()));
        if (returns == null) return null;

        var arguments = statement.substring(open + 1, statement.lastIndexOf(')'));
        var variadic = arguments.contains("...");
        var parameters = new ArrayList<Parameter>();
        for (var argument : arguments(arguments)) {
            if (argument.equals("...")) continue;
            var parameter = parameter(argument);
            if (parameter == null) return null;
            parameters.add(parameter);
        }
        return new Function(name.group(1), returns, named(parameters), variadic);
    }

    /// Arguments, split on the commas that are not inside a function pointer.
    private static List<String> arguments(String text) {
        var arguments = new ArrayList<String>();
        var argument = new StringBuilder();
        var depth = 0;
        for (var character : text.toCharArray()) {
            if (character == '(') depth++;
            if (character == ')') depth--;
            if (character == ',' && depth == 0) {
                arguments.add(argument.toString().strip());
                argument.setLength(0);
                continue;
            }
            argument.append(character);
        }
        var last = argument.toString().strip();
        if (!last.isEmpty() && !last.equals("void")) arguments.add(last);
        return arguments;
    }

    private Parameter parameter(String text) {
        if (text.contains("(*")) {
            var name = POINTER_NAME.matcher(text);
            return new Parameter(new Type(text, Kind.POINTER), name.find() ? name.group(1) : "");
        }
        var trailing = TRAILING.matcher(text);
        if (trailing.find() && !known(trailing.group(1))) {
            var type = type(text.substring(0, trailing.start()));
            if (type == null) return null;
            return new Parameter(type, trailing.group(1));
        }
        var type = type(text);
        return type == null ? null : new Parameter(type, "");
    }

    /// A trailing word is part of the type when it names one, and the
    /// parameter's own name when it does not.
    private boolean known(String word) {
        return typedefs.containsKey(word) || primitive(word) != null;
    }

    private Type type(String text) {
        var c = text.strip();
        if (c.isEmpty()) return null;
        if (c.contains("*") || c.contains("[")) return new Type(c, Kind.POINTER);

        var base = QUALIFIERS.matcher(c).replaceAll(" ").strip().replaceAll("\\s+", " ");
        var primitive = primitive(base);
        if (primitive != null) return new Type(c, primitive);

        var definition = typedefs.get(base);
        if (definition == null) return null;
        var resolved = type(definition);
        return resolved == null ? null : new Type(c, resolved.kind());
    }

    private static Kind primitive(String base) {
        return switch (base) {
            case "void" -> Kind.VOID;
            case "char", "signed char", "unsigned char" -> Kind.BYTE;
            case "short", "short int", "unsigned short", "unsigned short int" -> Kind.SHORT;
            case "int", "signed", "signed int", "unsigned", "unsigned int" -> Kind.INT;
            case "long", "long int", "unsigned long", "unsigned long int",
                 "long long", "long long int", "unsigned long long", "unsigned long long int" -> Kind.LONG;
            case "float" -> Kind.FLOAT;
            case "double", "long double" -> Kind.DOUBLE;
            default -> null;
        };
    }

    /// Every parameter ends up with a name, because a binding nobody can read
    /// is a binding nobody uses.
    private static List<Parameter> named(List<Parameter> parameters) {
        var named = new ArrayList<Parameter>();
        var taken = new ArrayList<String>();
        for (var parameter : parameters) {
            var name = parameter.name().isEmpty() ? "arg" + named.size() : parameter.name();
            while (taken.contains(name)) name = name + named.size();
            taken.add(name);
            named.add(new Parameter(parameter.type(), name));
        }
        return List.copyOf(named);
    }
}
