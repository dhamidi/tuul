package symbols;

import com.sun.source.doctree.DeprecatedTree;
import com.sun.source.doctree.DocTree;
import com.sun.source.doctree.EndElementTree;
import com.sun.source.doctree.EntityTree;
import com.sun.source.doctree.IndexTree;
import com.sun.source.doctree.LinkTree;
import com.sun.source.doctree.LiteralTree;
import com.sun.source.doctree.ParamTree;
import com.sun.source.doctree.RawTextTree;
import com.sun.source.doctree.ReferenceTree;
import com.sun.source.doctree.ReturnTree;
import com.sun.source.doctree.SeeTree;
import com.sun.source.doctree.SinceTree;
import com.sun.source.doctree.StartElementTree;
import com.sun.source.doctree.SummaryTree;
import com.sun.source.doctree.TextTree;
import com.sun.source.doctree.ThrowsTree;
import com.sun.source.doctree.UnknownBlockTagTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.DocTrees;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;

/// Doc comments, which class files do not carry — the only thing tuul has to
/// read out of Java source rather than out of bytes.
///
/// javac does the reading: parse only, no attribution, no classpath, so a
/// single file out of `src.zip` is enough and nothing has to resolve. The
/// comments come back keyed by where they were written — `String#length()`,
/// `Map.Entry#getKey()` — and [#attach] matches those keys against the members
/// that came out of the class file.
public final class Javadoc {

    private static final Pattern ANNOTATIONS = Pattern.compile("@\\w+(\\.\\w+)*");
    private static final Pattern GENERICS = Pattern.compile("<[^<>]*>");
    private static final Pattern QUALIFIERS = Pattern.compile("(\\w+\\.)+");
    private static final Pattern LINKS = Pattern.compile("\\[#?([^\\]]+)\\](\\([^)]*\\))?");
    private static final Pattern FENCES = Pattern.compile("(?m)^\\s*```.*$");

    /// HTML that stands a block apart from what surrounds it.
    private static final Set<String> PARAGRAPH = Set.of(
            "p", "pre", "blockquote", "div", "ul", "ol", "dl", "table", "hr",
            "h1", "h2", "h3", "h4", "h5", "h6");

    /// HTML that starts a new line without standing apart.
    private static final Set<String> LINE = Set.of("br", "li", "tr", "dt", "dd");

    /// HTML whose content is laid out by hand and must not be squeezed.
    private static final Set<String> PREFORMATTED = Set.of("pre");

    private Javadoc() {}

    /// What the source says about a declaration and the class file cannot: the
    /// prose, the block tags under it, and the names of the parameters.
    public record Comment(String doc, List<TypeInfo.Tag> tags, List<String> parameters, int line) {

        static final Comment NONE = new Comment("", List.of(), List.of(), 0);

        boolean empty() {
            return doc.isEmpty() && tags.isEmpty() && parameters.isEmpty() && line == 0;
        }
    }

    /// The comment on the file itself rather than on anything in it — which is
    /// the only kind `package-info.java` and `module-info.java` have.
    ///
    /// A package says what it is for in a comment attached to its package
    /// declaration, and a module in one attached to its module declaration.
    /// Neither is a class, so neither is reachable by walking declarations; both
    /// hang off the compilation unit, and this asks it directly.
    public static Comment file(String source, String fileName) {
        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) return Comment.NONE;
        try {
            var files = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8);
            var task = (JavacTask) compiler.getTask(
                    null, files, diagnostic -> {}, List.of("-proc:none"), null, List.of(new Text(fileName, source)));
            var trees = DocTrees.instance(task);
            for (var unit : task.parse()) {
                var at = unit.getModule() != null ? unit.getModule() : unit.getPackage();
                if (at == null) continue;
                var comment = trees.getDocCommentTree(new TreePath(new TreePath(unit), at));
                if (comment == null) continue;
                return new Comment(flatten(comment.getFullBody()), tags(comment.getBlockTags()), List.of(),
                        line(trees, unit, at));
            }
        } catch (IOException | RuntimeException unreadable) {
            return Comment.NONE;
        }
        return Comment.NONE;
    }

    /// Which line a declaration starts on, counted by javac rather than by us.
    ///
    /// This is where line numbers come from at all: `symbols.Sources` compiles
    /// with `-g:none`, so no class file carries a `LineNumberTable`, and turning
    /// that on would pay for every line of every method to answer a question
    /// about one. The source is already being parsed here to read the comment,
    /// and the parse knows exactly where everything is.
    private static int line(DocTrees trees, CompilationUnitTree unit, Tree node) {
        if (node == null) return 0;
        var start = trees.getSourcePositions().getStartPosition(node);
        return start < 0 ? 0 : (int) unit.getLineMap().getLineNumber(start);
    }

    /// Every doc comment in one source file, keyed by declaration.
    public static Map<String, Comment> of(String source, String fileName) {
        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) return Map.of();
        var docs = new LinkedHashMap<String, Comment>();
        try {
            var files = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8);
            var task = (JavacTask) compiler.getTask(
                    null, files, diagnostic -> {}, List.of("-proc:none"), null, List.of(new Text(fileName, source)));
            var trees = DocTrees.instance(task);
            for (var unit : task.parse()) new Walk(trees, docs).scan(unit, null);
        } catch (IOException | RuntimeException e) {
            return Map.of();
        }
        return docs;
    }

    /// Puts the comments where they belong. `path` is the type as it is written
    /// inside its file — `TypeInfo.Kind` for a nested type.
    public static TypeInfo attach(TypeInfo type, Map<String, Comment> docs, String path) {
        if (docs.isEmpty()) return type;
        var here = docs.getOrDefault(path, Comment.NONE);
        return type.documented(
                here.doc(),
                here.tags(),
                type.methods().stream().map(method -> documented(method, find(docs, path, method))).toList(),
                type.fields().stream()
                        .map(field -> documented(field, docs.getOrDefault(path + "#" + field.name(), Comment.NONE)))
                        .toList(),
                here.line());
    }

    private static TypeInfo.Method documented(TypeInfo.Method method, Comment comment) {
        return method.documented(comment.doc(), comment.tags(), comment.line()).named(comment.parameters());
    }

    private static TypeInfo.Field documented(TypeInfo.Field field, Comment comment) {
        return field.documented(comment.doc(), comment.tags(), comment.line());
    }

    /// Matches on erased parameter types, and falls back to the name alone when
    /// there is only one method with it — spelling differs between source and
    /// class file more often than arity does.
    private static Comment find(Map<String, Comment> docs, String path, TypeInfo.Method method) {
        var name = method.constructor() ? "<init>" : method.name();
        var exact = docs.get(path + "#" + signature(name, method.parameters().stream().map(TypeInfo.Parameter::type).toList()));
        if (exact != null) return exact;
        var prefix = path + "#" + name + "(";
        var candidates = docs.entrySet().stream()
                .filter(doc -> doc.getKey().startsWith(prefix))
                .map(Map.Entry::getValue)
                .toList();
        return candidates.size() == 1 ? candidates.getFirst() : Comment.NONE;
    }

    /// `@param`, `@return`, `@throws` and the rest, in the order they were
    /// written. Tags nobody has a use for are left out rather than guessed at.
    private static List<TypeInfo.Tag> tags(List<? extends DocTree> blocks) {
        var tags = new ArrayList<TypeInfo.Tag>();
        for (var block : blocks) {
            switch (block) {
                case ParamTree tree -> tags.add(tag("param", parameter(tree), flatten(tree.getDescription())));
                case ReturnTree tree -> tags.add(tag("return", "", flatten(tree.getDescription())));
                case ThrowsTree tree -> tags.add(tag(tree.getTagName(), reference(tree), flatten(tree.getDescription())));
                case SeeTree tree -> tags.add(tag("see", "", flatten(tree.getReference())));
                case SinceTree tree -> tags.add(tag("since", "", flatten(tree.getBody())));
                case DeprecatedTree tree -> tags.add(tag("deprecated", "", flatten(tree.getBody())));
                case UnknownBlockTagTree tree -> tags.add(tag(tree.getTagName(), "", flatten(tree.getContent())));
                default -> {}
            }
        }
        return List.copyOf(tags);
    }

    private static TypeInfo.Tag tag(String tag, String name, String text) {
        return new TypeInfo.Tag(tag, name, text);
    }

    /// A type parameter keeps its angle brackets, so `@param <T>` cannot be
    /// mistaken for a parameter named T.
    private static String parameter(ParamTree tree) {
        return tree.isTypeParameter() ? "<" + tree.getName() + ">" : tree.getName().toString();
    }

    private static String reference(ThrowsTree tree) {
        return tree.getExceptionName() == null ? "" : tree.getExceptionName().getSignature();
    }

    private static String signature(String name, List<String> types) {
        return name + "(" + types.stream().map(Javadoc::erase).collect(Collectors.joining(",")) + ")";
    }

    /// `java.util.List<? extends T>` and `List<T>` are the same parameter as far
    /// as a doc comment is concerned; so are `T...` and `T[]`.
    private static String erase(String type) {
        var erased = ANNOTATIONS.matcher(type).replaceAll("");
        while (true) {
            var next = GENERICS.matcher(erased).replaceAll("");
            if (next.equals(erased)) break;
            erased = next;
        }
        return QUALIFIERS.matcher(erased.replace("...", "[]").replaceAll("\\s+", "")).replaceAll("");
    }

    /// Flattens doc markup to the text a reader would see: `{@code x}` becomes
    /// x, `{@link Foo}` becomes Foo, and a markdown comment — the `///` kind
    /// this project writes — keeps its text without the link brackets and
    /// backticks.
    ///
    /// HTML keeps its shape rather than becoming a space. A comment in the JDK
    /// is HTML: `<p>` between paragraphs, `<pre>` around an example, `<li>` per
    /// item. Flattening those to spaces is what turned `java.lang.String`'s
    /// documentation into one immense sentence with a code sample lying down in
    /// the middle of it.
    private static String flatten(List<? extends DocTree> parts) {
        var text = new StringBuilder();
        write(parts, text);
        return text.toString()
                .strip()
                .replaceAll("(?<=\\S)[ \t]{2,}", " ")
                .replaceAll("[ \t]+\n", "\n")
                // A line of prose begins with the single space left where the
                // source wrapped; a line of code begins with the author's
                // indentation. Only the first is worth removing, so only a lone
                // space before a non-space goes.
                .replaceAll("(?m)^ (?=\\S)", "")
                .replaceAll("\n{3,}", "\n\n");
    }

    /// Appends without normalising, so that a nested body — the summary inside
    /// `{@summary}` — is written into the same text as everything around it and
    /// tidied once at the end.
    private static void write(List<? extends DocTree> parts, StringBuilder text) {
        var preformatted = 0;
        for (var part : parts) {
            switch (part) {
                case TextTree tree -> text.append(preformatted > 0 ? tree.getBody() : squeeze(tree.getBody()));
                case LiteralTree tree -> text.append(tree.getBody().getBody());
                case LinkTree tree -> text.append(reference(tree));
                case EntityTree tree -> text.append(entity(tree));
                case RawTextTree tree -> text.append(markdown(tree.getContent()));
                case ReferenceTree tree -> text.append(tree.getSignature());
                case SummaryTree tree -> write(tree.getSummary(), text);
                case IndexTree tree -> write(List.of(tree.getSearchTerm()), text);
                case StartElementTree tree -> {
                    if (PREFORMATTED.contains(name(tree.getName()))) preformatted++;
                    text.append(gap(name(tree.getName())));
                }
                case EndElementTree tree -> {
                    if (PREFORMATTED.contains(name(tree.getName()))) preformatted = Math.max(0, preformatted - 1);
                    text.append(PARAGRAPH.contains(name(tree.getName())) ? "\n\n" : "");
                }
                default -> {}
            }
        }
    }

    /// Whitespace in prose is whitespace; inside `<pre>` it is the author's
    /// layout, and squeezing it is how an example becomes a paragraph.
    private static String squeeze(String body) {
        return body.replaceAll("\\s+", " ");
    }

    private static String name(javax.lang.model.element.Name element) {
        return element.toString().toLowerCase(Locale.ROOT);
    }

    private static String gap(String element) {
        if (PARAGRAPH.contains(element)) return "\n\n";
        return LINE.contains(element) ? "\n" : "";
    }

    private static String reference(LinkTree tree) {
        if (tree.getReference() == null) return "";
        var signature = tree.getReference().getSignature();
        return signature.startsWith("#") ? signature.substring(1) : signature;
    }

    /// Markdown comments keep their line structure — an example in a doc
    /// comment is worth more with its line breaks than without.
    private static String markdown(String text) {
        return FENCES.matcher(LINKS.matcher(text).replaceAll("$1").replace("`", "")).replaceAll("");
    }

    private static String entity(EntityTree tree) {
        var name = tree.getName().toString();
        return switch (name) {
            case "lt" -> "<";
            case "gt" -> ">";
            case "amp" -> "&";
            case "quot" -> "\"";
            case "nbsp" -> " ";
            default -> name.startsWith("#") ? String.valueOf((char) Integer.parseInt(name.substring(1))) : "";
        };
    }

    /// Walks the declarations, not the code: method bodies hold nothing worth
    /// documenting.
    private static final class Walk extends TreePathScanner<Void, Void> {

        private final DocTrees trees;
        private final Map<String, Comment> docs;
        private final Deque<String> enclosing = new ArrayDeque<>();

        private Walk(DocTrees trees, Map<String, Comment> docs) {
            this.trees = trees;
            this.docs = docs;
        }

        @Override
        public Void visitClass(ClassTree node, Void ignored) {
            if (node.getSimpleName().isEmpty()) return null;
            enclosing.addLast(node.getSimpleName().toString());
            put(String.join(".", enclosing), List.of(), node);
            var scanned = super.visitClass(node, ignored);
            enclosing.removeLast();
            return scanned;
        }

        @Override
        public Void visitMethod(MethodTree node, Void ignored) {
            var types = node.getParameters().stream().map(parameter -> parameter.getType().toString()).toList();
            var names = node.getParameters().stream().map(parameter -> parameter.getName().toString()).toList();
            put(String.join(".", enclosing) + "#" + signature(node.getName().toString(), types), names, node);
            return null;
        }

        @Override
        public Void visitVariable(VariableTree node, Void ignored) {
            if (getCurrentPath().getParentPath().getLeaf() instanceof ClassTree) {
                put(String.join(".", enclosing) + "#" + node.getName(), List.of(), node);
            }
            return null;
        }

        private void put(String key, List<String> parameters, Tree node) {
            var comment = trees.getDocCommentTree(getCurrentPath());
            var line = line(trees, getCurrentPath().getCompilationUnit(), node);
            var doc = comment == null
                    ? new Comment("", List.of(), parameters, line)
                    : new Comment(flatten(comment.getFullBody()), tags(comment.getBlockTags()), parameters, line);
            if (!doc.empty()) docs.put(key, doc);
        }
    }

    /// Source held in memory, wherever it came from: a file, a zip entry, a
    /// pipe.
    private static final class Text extends SimpleJavaFileObject {

        private final String source;

        private Text(String name, String source) {
            super(URI.create("source:///" + name), JavaFileObject.Kind.SOURCE);
            this.source = source;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return source;
        }
    }
}
