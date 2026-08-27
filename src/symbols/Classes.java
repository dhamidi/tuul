package symbols;

import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.ClassSignature;
import java.lang.classfile.FieldModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.MethodSignature;
import java.lang.classfile.Signature;
import java.lang.reflect.AccessFlag;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;

/// Reads a class file with the JDK's own class file parser and reports the
/// symbols in it. No reflection, no class loading: the bytes are the source of
/// truth, which is what makes this work on code that cannot run here.
public final class Classes {

    private static final List<AccessFlag> ORDER = List.of(
            AccessFlag.PUBLIC, AccessFlag.PROTECTED, AccessFlag.PRIVATE,
            AccessFlag.ABSTRACT, AccessFlag.STATIC, AccessFlag.FINAL,
            AccessFlag.SYNCHRONIZED, AccessFlag.NATIVE, AccessFlag.TRANSIENT, AccessFlag.VOLATILE);

    private Classes() {}

    public static TypeInfo inspect(byte[] classFile) {
        var model = ClassFile.of().parse(classFile);
        var signature = model.findAttribute(Attributes.signature())
                .map(attribute -> ClassSignature.parseFrom(attribute.signature().stringValue()));
        var kind = kind(model);
        var name = Signatures.name(model.thisClass().asSymbol());
        var simple = name.substring(name.lastIndexOf('.') + 1);
        return new TypeInfo(
                name,
                kind,
                declaredModifiers(modifiers(model.flags().flagsMask(), AccessFlag.Location.CLASS), kind),
                signature.map(ClassSignature::typeParameters).orElse(List.of()).stream().map(Signatures::parameter).toList(),
                superclass(model, signature, kind),
                interfaces(model, signature),
                permits(model),
                nested(model, permits(model)),
                model.methods().stream().filter(Classes::declared).map(method -> method(method, simple)).toList(),
                model.fields().stream().filter(Classes::declared).map(Classes::field).toList(),
                "",
                List.of());
    }

    /// The subtypes a sealed type allows, which are its cases — the most
    /// important thing about it, and the only thing on the page of an interface
    /// that declares no methods.
    private static List<String> permits(ClassModel model) {
        return model.findAttribute(Attributes.permittedSubclasses())
                .map(attribute -> attribute.permittedSubclasses().stream()
                        .map(entry -> Signatures.name(entry.asSymbol()))
                        .toList())
                .orElse(List.of());
    }

    /// The types declared inside this one.
    ///
    /// `InnerClasses` names every nested type the class file mentions,
    /// including the ones it is nested in itself, so only those whose outer
    /// class is this one are declarations here. A sealed type's cases are
    /// usually also nested in it, and naming them twice on one page tells the
    /// reader nothing the second time.
    private static List<String> nested(ClassModel model, List<String> permitted) {
        var here = model.thisClass().asInternalName();
        return model.findAttribute(Attributes.innerClasses())
                .map(attribute -> attribute.classes().stream()
                        .filter(inner -> inner.outerClass()
                                .map(outer -> outer.asInternalName().equals(here))
                                .orElse(false))
                        .map(inner -> Signatures.name(inner.innerClass().asSymbol()))
                        .filter(name -> !permitted.contains(name))
                        .toList())
                .orElse(List.of());
    }

    /// `record`, `enum` and `interface` carry flags that cannot be written in
    /// source — reporting them would describe a declaration nobody typed.
    private static List<String> declaredModifiers(List<String> modifiers, TypeInfo.Kind kind) {
        if (kind == TypeInfo.Kind.CLASS) return modifiers;
        var implied = kind == TypeInfo.Kind.INTERFACE || kind == TypeInfo.Kind.ANNOTATION
                ? Set.of("abstract")
                : Set.of("abstract", "final");
        return modifiers.stream().filter(modifier -> !implied.contains(modifier)).toList();
    }

    private static TypeInfo.Kind kind(ClassModel model) {
        var mask = model.flags().flagsMask();
        if ((mask & ClassFile.ACC_ANNOTATION) != 0) return TypeInfo.Kind.ANNOTATION;
        if ((mask & ClassFile.ACC_INTERFACE) != 0) return TypeInfo.Kind.INTERFACE;
        if ((mask & ClassFile.ACC_ENUM) != 0) return TypeInfo.Kind.ENUM;
        if (model.findAttribute(Attributes.record()).isPresent()) return TypeInfo.Kind.RECORD;
        return TypeInfo.Kind.CLASS;
    }

    /// The declared supertype, minus the ones the compiler puts there anyway.
    private static String superclass(ClassModel model, Optional<ClassSignature> signature, TypeInfo.Kind kind) {
        if (kind != TypeInfo.Kind.CLASS) return "";
        var name = signature
                .map(parsed -> Signatures.render(parsed.superclassSignature()))
                .orElseGet(() -> model.superclass().map(entry -> Signatures.name(entry.asSymbol())).orElse(""));
        return name.equals("java.lang.Object") ? "" : name;
    }

    private static List<String> interfaces(ClassModel model, Optional<ClassSignature> signature) {
        return signature
                .map(parsed -> parsed.superinterfaceSignatures().stream().map(Signatures::render).toList())
                .orElseGet(() -> model.interfaces().stream().map(entry -> Signatures.name(entry.asSymbol())).toList());
    }

    private static TypeInfo.Method method(MethodModel model, String simpleName) {
        var signature = model.findAttribute(Attributes.signature())
                .map(attribute -> MethodSignature.parseFrom(attribute.signature().stringValue()));
        var constructor = model.methodName().equalsString("<init>");
        var returns = constructor ? "" : signature
                .map(parsed -> Signatures.render(parsed.result()))
                .orElseGet(() -> Signatures.name(model.methodTypeSymbol().returnType()));
        var types = signature
                .map(parsed -> parsed.arguments().stream().map(Signatures::render).toList())
                .orElseGet(() -> model.methodTypeSymbol().parameterList().stream().map(Signatures::name).toList());
        var mask = model.flags().flagsMask();
        return new TypeInfo.Method(
                constructor ? simpleName : model.methodName().stringValue(),
                returns,
                named(spread(types, (mask & ClassFile.ACC_VARARGS) != 0), parameterNames(model)),
                modifiers(mask, AccessFlag.Location.METHOD),
                "",
                List.of());
    }

    private static TypeInfo.Field field(FieldModel model) {
        var type = model.findAttribute(Attributes.signature())
                .map(attribute -> Signatures.render(Signature.parseFrom(attribute.signature().stringValue())))
                .orElseGet(() -> Signatures.name(model.fieldTypeSymbol()));
        return new TypeInfo.Field(
                model.fieldName().stringValue(),
                type,
                modifiers(model.flags().flagsMask(), AccessFlag.Location.FIELD),
                "",
                List.of());
    }

    /// Parameter names survive only when the sources were compiled with
    /// `-parameters`, which is how [Sources] compiles them.
    private static List<String> parameterNames(MethodModel model) {
        return model.findAttribute(Attributes.methodParameters())
                .map(attribute -> attribute.parameters().stream()
                        .map(parameter -> parameter.name().map(name -> name.stringValue()).orElse(""))
                        .toList())
                .orElse(List.of());
    }

    /// The last parameter of a varargs method is written `T...`, not `T[]`.
    private static List<String> spread(List<String> types, boolean varargs) {
        if (!varargs || types.isEmpty()) return types;
        var last = types.getLast();
        if (!last.endsWith("[]")) return types;
        var spread = new ArrayList<>(types);
        spread.set(spread.size() - 1, last.substring(0, last.length() - 2) + "...");
        return List.copyOf(spread);
    }

    private static List<TypeInfo.Parameter> named(List<String> types, List<String> names) {
        return IntStream.range(0, types.size())
                .mapToObj(i -> new TypeInfo.Parameter(types.get(i), names.size() == types.size() ? names.get(i) : ""))
                .toList();
    }

    /// Skips what the compiler generated: bridges, synthetic accessors and the
    /// static initialiser.
    private static boolean declared(MethodModel model) {
        var mask = model.flags().flagsMask();
        if ((mask & (ClassFile.ACC_SYNTHETIC | ClassFile.ACC_BRIDGE)) != 0) return false;
        return !model.methodName().equalsString("<clinit>");
    }

    private static boolean declared(FieldModel model) {
        return (model.flags().flagsMask() & ClassFile.ACC_SYNTHETIC) == 0;
    }

    private static List<String> modifiers(int mask, AccessFlag.Location location) {
        Set<AccessFlag> flags;
        try {
            flags = AccessFlag.maskToAccessFlags(mask, location);
        } catch (IllegalArgumentException e) {
            return List.of();
        }
        return ORDER.stream().filter(flags::contains).map(flag -> flag.name().toLowerCase(Locale.ROOT)).toList();
    }
}
