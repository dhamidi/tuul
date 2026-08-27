package symbols;

import java.lang.classfile.Signature;
import java.lang.constant.ClassDesc;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/// Turns class file descriptors and generic signatures into the type names a
/// reader recognises: `java.util.List<java.lang.String>`, `int[]`, `T`.
public final class Signatures {

    private static final Pattern PACKAGE = Pattern.compile("(?<![\\w$.])([a-z][A-Za-z0-9_$]*\\.)+");

    private Signatures() {}

    /// A type from the constant pool, where generics have already been erased.
    public static String name(ClassDesc type) {
        if (type.isArray()) return name(type.componentType()) + "[]";
        if (type.isPrimitive()) return type.displayName();
        var simple = type.displayName().replace('$', '.');
        return type.packageName().isEmpty() ? simple : type.packageName() + "." + simple;
    }

    /// A type from a `Signature` attribute, generics and all.
    public static String render(Signature type) {
        return switch (type) {
            case Signature.BaseTypeSig base -> ClassDesc.ofDescriptor(String.valueOf(base.baseType())).displayName();
            case Signature.ArrayTypeSig array -> render(array.componentSignature()) + "[]";
            case Signature.TypeVarSig variable -> variable.identifier();
            case Signature.ClassTypeSig type_ -> clazz(type_);
        };
    }

    /// `T extends java.lang.Comparable<T>` — one declared type parameter.
    public static String parameter(Signature.TypeParam parameter) {
        var bounds = new ArrayList<String>();
        parameter.classBound().map(Signatures::render).filter(bound -> !bound.equals("java.lang.Object")).ifPresent(bounds::add);
        parameter.interfaceBounds().stream().map(Signatures::render).forEach(bounds::add);
        return bounds.isEmpty() ? parameter.identifier() : parameter.identifier() + " extends " + String.join(" & ", bounds);
    }

    /// Drops package prefixes: `java.lang.Comparable<a.b.Invoice>` reads as
    /// `Comparable<Invoice>`. For humans only — `--json` keeps the full names.
    public static String shorten(String type) {
        return PACKAGE.matcher(type).replaceAll("");
    }

    private static String clazz(Signature.ClassTypeSig type) {
        var outer = type.outerType();
        var base = outer.isPresent()
                ? clazz(outer.get()) + "." + type.className()
                : type.className().replace('/', '.').replace('$', '.');
        return type.typeArgs().isEmpty() ? base : base + "<" + arguments(type.typeArgs()) + ">";
    }

    private static String arguments(List<Signature.TypeArg> arguments) {
        return arguments.stream().map(Signatures::argument).collect(Collectors.joining(", "));
    }

    private static String argument(Signature.TypeArg argument) {
        return switch (argument) {
            case Signature.TypeArg.Unbounded _ -> "?";
            case Signature.TypeArg.Bounded bounded -> switch (bounded.wildcardIndicator()) {
                case NONE -> render(bounded.boundType());
                case EXTENDS -> "? extends " + render(bounded.boundType());
                case SUPER -> "? super " + render(bounded.boundType());
            };
        };
    }
}
