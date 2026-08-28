package symbols;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/// What a class file says about a type: what it is, what it extends, and what
/// it declares. Names are fully qualified — shortening them is a rendering
/// decision, not a fact about the symbol.
///
/// `permits` and `nested` are the two things a reader cannot otherwise see. A
/// sealed type's permitted subtypes are its cases, and a type that declares
/// nothing but other types — a marker interface with records under it — has a
/// page with nothing on it unless they are named.
///
/// A package and a module are symbols too, and the same record carries them: a
/// name, a doc comment out of `package-info.java` or `module-info.java`, and
/// `nested` holding what they contain — a module's packages, a package's
/// subpackages and then its types. They declare no members, so the rest is
/// empty, which is why one record serves rather than three.
///
/// `source` and `line` say where the declaration is written, so a reader can go
/// and look. A line is only known where the source was read, which is wherever
/// a doc comment was: javac's `-g:none` leaves no line numbers in the class
/// file, and the source it parses for comments has them exactly.
public record TypeInfo(
        String name,
        Kind kind,
        List<String> modifiers,
        List<String> typeParameters,
        String superclass,
        List<String> interfaces,
        List<String> permits,
        List<String> nested,
        List<Method> methods,
        List<Field> fields,
        String doc,
        List<Tag> tags,
        String source,
        int line) {

    public enum Kind {
        CLASS, INTERFACE, RECORD, ENUM, ANNOTATION, PACKAGE, MODULE;

        /// How the type would be written in source.
        public String keyword() {
            return this == ANNOTATION ? "@interface" : name().toLowerCase(Locale.ROOT);
        }

        /// Whether this names a group of symbols rather than a symbol with
        /// members. What `nested` means turns on this, and so does the word a
        /// renderer puts in front of it.
        public boolean grouping() {
            return this == PACKAGE || this == MODULE;
        }
    }

    /// A block tag out of a doc comment: `@param id the invoice number` is the
    /// tag `param`, the name `id` and the rest as text. `@return` and the other
    /// nameless tags leave the name empty.
    public record Tag(String tag, String name, String text) {

        public String line() {
            return ("@" + tag + " " + name).strip() + (text.isEmpty() ? "" : " " + text);
        }
    }

    /// One parameter. The name is there when the class file was compiled with
    /// `-parameters`, and empty when it was not.
    public record Parameter(String type, String name) {

        public String text() {
            return name.isEmpty() ? type : type + " " + name;
        }
    }

    /// A method or a constructor. Constructors carry the simple type name and
    /// an empty return type, the way they are written.
    public record Method(String name, String returns, List<Parameter> parameters, List<String> modifiers, String doc,
            List<Tag> tags, int line) {

        public String signature() {
            var parameters_ = parameters.stream().map(Parameter::text).toList();
            return (returns.isEmpty() ? "" : returns + " ") + name + "(" + String.join(", ", parameters_) + ")";
        }

        public boolean constructor() {
            return returns.isEmpty();
        }

        public boolean api() {
            return modifiers.contains("public") || modifiers.contains("protected");
        }

        public Method documented(String doc, List<Tag> tags, int line) {
            return new Method(name, returns, parameters, modifiers, doc, tags, line);
        }

        /// Names the parameters from the source, for the class files that were
        /// compiled without `-parameters` — which is most jars, and the JDK.
        public Method named(List<String> names) {
            if (names.size() != parameters.size()) return this;
            if (parameters.stream().noneMatch(parameter -> parameter.name().isEmpty())) return this;
            var named = new ArrayList<Parameter>();
            for (var i = 0; i < names.size(); i++) named.add(new Parameter(parameters.get(i).type(), names.get(i)));
            return new Method(name, returns, List.copyOf(named), modifiers, doc, tags, line);
        }
    }

    public record Field(String name, String type, List<String> modifiers, String doc, List<Tag> tags, int line) {

        public String signature() {
            return type + " " + name;
        }

        public boolean api() {
            return modifiers.contains("public") || modifiers.contains("protected");
        }

        public Field documented(String doc, List<Tag> tags, int line) {
            return new Field(name, type, modifiers, doc, tags, line);
        }
    }

    /// `class invoicing.Invoice<T>` — the head of the declaration.
    public String declaration() {
        var parameters = typeParameters.isEmpty() ? "" : "<" + String.join(", ", typeParameters) + ">";
        return kind.keyword() + " " + name + parameters;
    }

    public String simpleName() {
        return name.substring(name.lastIndexOf('.') + 1);
    }

    public TypeInfo documented(String doc, List<Tag> tags, List<Method> methods, List<Field> fields, int line) {
        return new TypeInfo(name, kind, modifiers, typeParameters, superclass, interfaces, permits, nested,
                methods, fields, doc, tags, source, line);
    }

    /// The same type, saying where it is written. The location is known by
    /// whoever found the source, which is not whoever read the class file.
    public TypeInfo at(String source) {
        return new TypeInfo(name, kind, modifiers, typeParameters, superclass, interfaces, permits, nested,
                methods, fields, doc, tags, source, line);
    }
}
