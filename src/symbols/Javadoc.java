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
import com.sun.source.util.SimpleDocTreeVisitor;
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

    static final String FORMAT = "markdown-visitor-1";

    private static final Pattern ANNOTATIONS = Pattern.compile("@\\w+(\\.\\w+)*");
    private static final Pattern GENERICS = Pattern.compile("<[^<>]*>");
    private static final Pattern QUALIFIERS = Pattern.compile("(\\w+\\.)+");

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

    /// The lines one declaration occupies, comment included.
    ///
    /// `path` is the type as it is written inside its file. For a nested type
    /// that is `TypeInfo.Kind`. `member` names a method or a field on that
    /// type, or is empty to name the type itself. An overloaded method has
    /// one span per overload, in source order. A name the file does not
    /// declare has no span.
    ///
    /// javac reports a declaration's start position at its first modifier,
    /// which skips the doc comment above it. This method walks back over the
    /// comment lines to include them, because the comment is the half of a
    /// declaration a reader came for.
    public static List<Span> spans(String source, String fileName, String path, String member) {
        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) return List.of();
        var spans = new ArrayList<Span>();
        try {
            var files = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8);
            var task = (JavacTask) compiler.getTask(
                    null, files, diagnostic -> {}, List.of("-proc:none"), null, List.of(new Text(fileName, source)));
            var trees = DocTrees.instance(task);
            var lines = source.split("\n", -1);
            for (var unit : task.parse()) new Spanning(trees, unit, path, member, lines, spans).scan(unit, null);
        } catch (IOException | RuntimeException unreadable) {
            return List.of();
        }
        return List.copyOf(spans);
    }

    /// The first and last line of a declaration, both inclusive and counted
    /// from one.
    public record Span(int first, int last) {

        /// The declaration's text, taken from the source it was found in.
        public String of(String source) {
            var lines = source.split("\n", -1);
            var from = Math.max(1, first);
            var to = Math.min(lines.length, last);
            if (from > to) return "";
            return String.join("\n", List.of(lines).subList(from - 1, to)) + "\n";
        }
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

    /// Converts the JDK's semantic Javadoc tree to the Markdown that tuul
    /// already renders. Blocks, lists, code, and links keep their meaning.
    private static String flatten(List<? extends DocTree> parts) {
        return new MarkdownWriter(parts).write();
    }

    /// The visitor emits Markdown rather than HTML because the browser supplies
    /// the final HTML and resolves references against its own symbol index.
    private static final class MarkdownWriter extends SimpleDocTreeVisitor<Void, Void> {

        private final List<? extends DocTree> parts;
        private final StringBuilder text = new StringBuilder();
        private final Deque<ListFrame> lists = new ArrayDeque<>();
        private final boolean markdown;
        private int preformatted;
        private boolean reference;

        private MarkdownWriter(List<? extends DocTree> parts) {
            this.parts = parts;
            this.markdown = parts.stream().anyMatch(part -> part instanceof RawTextTree);
        }

        private String write() {
            visit(parts, null);
            return text.toString().strip();
        }

        @Override
        public Void visitText(TextTree tree, Void ignored) {
            var body = preformatted > 0 || markdown ? tree.getBody() : squeeze(tree.getBody());
            if (reference && !body.isBlank()) {
                if (Character.isWhitespace(body.charAt(0)) && body.length() > 1
                        && Character.isLetterOrDigit(body.charAt(1))) {
                    body = body.substring(1);
                    text.append(' ');
                } else if (Character.isLetterOrDigit(body.charAt(0))) {
                    text.append(' ');
                }
            }
            if (preformatted > 0 && body.startsWith("\n") && !text.isEmpty()
                    && text.charAt(text.length() - 1) == '\n') {
                body = body.substring(1);
            }
            text.append(body);
            reference = false;
            return null;
        }

        @Override
        public Void visitRawText(RawTextTree tree, Void ignored) {
            text.append(tree.getContent());
            reference = false;
            return null;
        }

        @Override
        public Void visitLiteral(LiteralTree tree, Void ignored) {
            var body = tree.getBody().getBody();
            if (preformatted > 0) text.append(body);
            else if (tree.getKind() == DocTree.Kind.CODE) inlineCode(body);
            else text.append(body);
            reference = false;
            return null;
        }

        @Override
        public Void visitLink(LinkTree tree, Void ignored) {
            if (tree.getReference() == null) return null;
            var signature = tree.getReference().getSignature();
            var visible = tree.getLabel().isEmpty()
                    ? signature.startsWith("#") ? signature.substring(1) : signature
                    : new MarkdownWriter(tree.getLabel()).write();
            if (visible.equals(signature)) text.append('[').append(visible).append(']');
            else text.append('[').append(visible).append("][").append(signature).append(']');
            reference = true;
            return null;
        }

        @Override
        public Void visitEntity(EntityTree tree, Void ignored) {
            var entity = tree.getName().toString();
            text.append(switch (entity) {
                case "lt" -> "<";
                case "gt" -> ">";
                case "amp" -> "&";
                case "quot" -> "\"";
                case "nbsp" -> " ";
                default -> entity.startsWith("#")
                        ? String.valueOf((char) Integer.parseInt(entity.substring(1))) : "";
            });
            reference = false;
            return null;
        }

        @Override
        public Void visitReference(ReferenceTree tree, Void ignored) {
            text.append(tree.getSignature());
            reference = true;
            return null;
        }

        @Override
        public Void visitIdentifier(com.sun.source.doctree.IdentifierTree tree, Void ignored) {
            text.append(tree.getName());
            return null;
        }

        @Override
        public Void visitSummary(SummaryTree tree, Void ignored) {
            visit(tree.getSummary(), null);
            return null;
        }

        @Override
        public Void visitIndex(IndexTree tree, Void ignored) {
            visit(tree.getSearchTerm(), null);
            return null;
        }

        @Override
        public Void visitUnknownInlineTag(com.sun.source.doctree.UnknownInlineTagTree tree, Void ignored) {
            visit(tree.getContent(), null);
            return null;
        }

        @Override
        public Void visitStartElement(StartElementTree tree, Void ignored) {
            var element = name(tree.getName());
            switch (element) {
                case "p", "div", "section" -> blankLine();
                case "pre" -> {
                    blankLine();
                    text.append("```\n");
                    preformatted++;
                }
                case "ul" -> {
                    blankLine();
                    lists.push(new ListFrame(false));
                }
                case "ol" -> {
                    blankLine();
                    lists.push(new ListFrame(true));
                }
                case "li" -> {
                    line();
                    var list = lists.peek();
                    if (lists.size() > 1) text.append("  ".repeat(lists.size() - 1));
                    text.append(list == null || !list.ordered ? "- " : list.next() + ". ");
                }
                case "br" -> text.append("  \n");
                case "hr" -> {
                    blankLine();
                    text.append("---\n");
                }
                case "blockquote" -> {
                    blankLine();
                    text.append("> ");
                }
                case "h1", "h2", "h3", "h4", "h5", "h6" -> {
                    blankLine();
                    text.append("#".repeat(Integer.parseInt(element.substring(1)))).append(' ');
                }
                case "code" -> {
                    if (preformatted == 0) inlineCodeStart();
                }
                case "strong", "b" -> text.append("**");
                case "em", "i" -> text.append('*');
                default -> { }
            }
            return null;
        }

        @Override
        public Void visitEndElement(EndElementTree tree, Void ignored) {
            var element = name(tree.getName());
            switch (element) {
                case "pre" -> {
                    line();
                    text.append("```\n");
                    preformatted = Math.max(0, preformatted - 1);
                    blankLine();
                }
                case "p", "div", "section", "blockquote", "h1", "h2", "h3", "h4", "h5", "h6" -> blankLine();
                case "ul", "ol" -> {
                    line();
                    if (!lists.isEmpty()) lists.pop();
                    blankLine();
                }
                case "li" -> line();
                case "code" -> {
                    if (preformatted == 0) inlineCodeEnd();
                }
                case "strong", "b" -> text.append("**");
                case "em", "i" -> text.append('*');
                default -> { }
            }
            return null;
        }

        @Override
        public Void visitComment(com.sun.source.doctree.CommentTree tree, Void ignored) {
            text.append(tree.getBody());
            return null;
        }

        @Override
        public Void visitErroneous(com.sun.source.doctree.ErroneousTree tree, Void ignored) {
            text.append(tree.getBody());
            return null;
        }

        private void inlineCode(String body) {
            var fence = "`".repeat(Math.max(1, longestBackticks(body) + 1));
            text.append(fence).append(body).append(fence);
        }

        private void inlineCodeStart() {
            text.append('`');
        }

        private void inlineCodeEnd() {
            text.append('`');
        }

        private void blankLine() {
            line();
            if (text.length() < 2 || text.charAt(text.length() - 2) != '\n') text.append('\n');
        }

        private void line() {
            while (!text.isEmpty() && (text.charAt(text.length() - 1) == ' '
                    || text.charAt(text.length() - 1) == '\t')) {
                text.deleteCharAt(text.length() - 1);
            }
            if (!text.isEmpty() && text.charAt(text.length() - 1) != '\n') text.append('\n');
        }

        private static int longestBackticks(String body) {
            var longest = 0;
            var run = 0;
            for (var character : body.toCharArray()) {
                if (character == '`') longest = Math.max(longest, ++run);
                else run = 0;
            }
            return longest;
        }

        private static String squeeze(String body) {
            return body.replaceAll("\\s+", " ");
        }

        private static String name(javax.lang.model.element.Name element) {
            return element.toString().toLowerCase(Locale.ROOT);
        }

        private static final class ListFrame {
            private final boolean ordered;
            private int number = 1;

            private ListFrame(boolean ordered) {
                this.ordered = ordered;
            }

            private int next() {
                return number++;
            }
        }
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

    /// Finds where one declaration starts and ends, by the same walk that
    /// finds its comment.
    private static final class Spanning extends TreePathScanner<Void, Void> {

        private final DocTrees trees;
        private final CompilationUnitTree unit;
        private final String path;
        private final String member;
        private final String[] lines;
        private final List<Span> spans;
        private final Deque<String> enclosing = new ArrayDeque<>();

        private Spanning(DocTrees trees, CompilationUnitTree unit, String path, String member, String[] lines,
                List<Span> spans) {
            this.trees = trees;
            this.unit = unit;
            this.path = path;
            this.member = member;
            this.lines = lines;
            this.spans = spans;
        }

        @Override
        public Void visitClass(ClassTree node, Void ignored) {
            if (node.getSimpleName().isEmpty()) return null;
            enclosing.addLast(node.getSimpleName().toString());
            if (member.isEmpty() && String.join(".", enclosing).equals(path)) {
                spans.add(span(node));
                enclosing.removeLast();
                return null;
            }
            var scanned = super.visitClass(node, ignored);
            enclosing.removeLast();
            return scanned;
        }

        @Override
        public Void visitMethod(MethodTree node, Void ignored) {
            if (here() && named(node.getName().toString())) spans.add(span(node));
            return null;
        }

        @Override
        public Void visitVariable(VariableTree node, Void ignored) {
            if (here() && getCurrentPath().getParentPath().getLeaf() instanceof ClassTree
                    && member.equals(node.getName().toString())) {
                spans.add(span(node));
            }
            return null;
        }

        private boolean here() {
            return !member.isEmpty() && String.join(".", enclosing).equals(path);
        }

        /// A constructor is written with the type's name and parsed as `<init>`.
        private boolean named(String name) {
            return member.equals(name) || (name.equals("<init>") && member.equals(enclosing.peekLast()));
        }

        private Span span(Tree node) {
            var positions = trees.getSourcePositions();
            var start = positions.getStartPosition(node);
            var end = positions.getEndPosition(node);
            var first = start < 0 ? 1 : (int) unit.getLineMap().getLineNumber(start);
            var last = end < 0 ? first : (int) unit.getLineMap().getLineNumber(Math.max(start, end - 1));
            return new Span(commented(first), last);
        }

        /// The line the comment above a declaration starts on, or the
        /// declaration's own line when nothing is written above it.
        private int commented(int first) {
            var at = first;
            while (at > 1) {
                var above = lines[at - 2].strip();
                if (above.startsWith("///") || above.startsWith("/**") || above.startsWith("*")
                        || above.startsWith("@")) {
                    at--;
                    continue;
                }
                break;
            }
            return at;
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
