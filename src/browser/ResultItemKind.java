package browser;

import static web.ui.Attributes.classes;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import web.ui.Attributes;
import web.ui.Component;
import web.ui.Html;
import web.ui.Node;
import web.ui.Props;
import web.ui.Stimulus;
import web.ui.Tags;

/// What kind of thing a symbol is, as one letter in a small square.
///
/// A result list is scanned rather than read, and a word set in a badge is
/// scanned at the speed of reading. A letter in a square is scanned at the
/// speed of looking: nine kinds down the left of a list of results become nine
/// marks in a column, and the eye finds the methods among the classes without
/// reading a word. Pressing the square says the word, for anybody who has not
/// yet learned which letter is which.
///
/// **The letter is not arbitrary.** Uppercase is a symbol that has a page of
/// its own — a class, a record, an interface, an annotation, an enum, a package,
/// a module. Lowercase is a symbol that lives on somebody else's page: a method
/// and a field are anchors on the page of the type that declares them. That is
/// the same distinction [ResultRow] makes when it builds the link, and it is the
/// thing a reader of a result list most wants to know before they click.
///
/// It is also what settles the one clash there is. `module` and `method` both
/// begin with M, so `M` is the module and `m` is the method, and the case says
/// which — a difference in shape, not only in colour, because colour alone is
/// not a signal every reader has (WCAG 1.4.1). The colours are a second,
/// redundant signal: a module is a container and takes the container colour, a
/// method is a member and takes the member colour.
///
/// `modifiers` is a space-separated list, the way [symbols.Store] writes one.
/// Three of them change the drawing: `static` turns the square into a rhombus,
/// `abstract` breaks its outline, and `private` dulls it. All of them are said
/// to a screen reader and shown in the tooltip, because a shape nothing names
/// is a shape only somebody who already knows can read.
///
/// **`private` cannot appear in this browser.** `Schema` does not index a
/// private member — a result that leads nowhere is worse than no result — and
/// a symbol page is rendered from `Docs.describe(type, false)`, which leaves
/// private members out for the same reason. The dulling is implemented and
/// tested because it is what was asked for and because `tuul docs --all` knows
/// about private members even though no page does; nothing in `tuul browse`
/// renders it today.
///
/// Modifiers are absent more often than not — a `class` has none to speak of,
/// and every kind renders without them.
public record ResultItemKind(Props props, Node[] content) implements Component {

    /// The Stimulus identifier this is driven by, the module the controller is
    /// imported from, and the file it lives in. Named here for the reason
    /// [web.ui.Ui#CONTROLLER] is named there: a disagreement between the pin,
    /// the file and the identifier is a chip that silently never opens.
    public static final String CONTROLLER = "result-kind";

    public static final String MODULE = "@tuul/result-kind";

    public static final String FILE = "kind.js";

    /// The modifiers that change what is drawn. The rest are said and not
    /// drawn: a chip with a mark for every modifier Java has is a chip nobody
    /// can read.
    private static final Set<String> DRAWN = Set.of("static", "abstract", "private");

    /// One kind of symbol: the word for it, the letter that stands for it, and
    /// the family whose colour it takes.
    ///
    /// The families group kinds that answer the same question — a class and a
    /// record are both a type you can hold, an interface and an annotation are
    /// both a contract, a package and a module are both a container, a method
    /// and a field are both a member of something else. Two kinds in one family
    /// always have different letters, so the colour never has to carry the
    /// difference on its own.
    public enum Of {
        CLASS("class", "C", "type"),
        RECORD("record", "R", "type"),
        INTERFACE("interface", "I", "contract"),
        ANNOTATION("annotation", "A", "contract"),
        ENUM("enum", "E", "value"),
        PACKAGE("package", "P", "container"),
        MODULE("module", "M", "container"),
        METHOD("method", "m", "member"),
        FIELD("field", "f", "member"),
        OTHER("symbol", "?", "plain");

        private final String word;
        private final String letter;
        private final String family;

        Of(String word, String letter, String family) {
            this.word = word;
            this.letter = letter;
            this.family = family;
        }

        public String word() {
            return word;
        }

        public String letter() {
            return letter;
        }

        public String family() {
            return family;
        }

        /// The kind a string names, whichever of the three spellings it arrives
        /// in.
        ///
        /// The index files a type's kind as the enum spells it — `CLASS`,
        /// `ANNOTATION` — because that is what `TypeInfo.Kind#name` gives, and
        /// files a member's as the word the schema constrains it to, `method`
        /// or `field`. A symbol page is rendered from `Docs.describe`, which
        /// uses `TypeInfo.Kind#keyword` instead, and that spells an annotation
        /// `@interface`. All three reach this, so all three are answered here
        /// rather than guessed at by whoever calls.
        ///
        /// A kind nobody knows is [#OTHER] rather than a failure. The index is
        /// a file on disk that an older or newer tuul may have written, and a
        /// result that renders as a question mark is better than a page that
        /// will not render.
        public static Of of(String kind) {
            return switch (kind.strip().toLowerCase(Locale.ROOT)) {
                case "class" -> CLASS;
                case "record" -> RECORD;
                case "interface" -> INTERFACE;
                case "annotation", "@interface" -> ANNOTATION;
                case "enum" -> ENUM;
                case "package" -> PACKAGE;
                case "module" -> MODULE;
                case "method" -> METHOD;
                case "field" -> FIELD;
                default -> OTHER;
            };
        }
    }

    public ResultItemKind {
        props.only("kind", "modifiers");
    }

    public static ResultItemKind of(String kind, String modifiers, Node... content) {
        return new ResultItemKind(Props.of("kind", kind, "modifiers", modifiers), content);
    }

    public static ResultItemKind of(String kind, Node... content) {
        return new ResultItemKind(Props.of("kind", kind), content);
    }

    /// The chip.
    ///
    /// The root is a `<data>` carrying the kind as its machine-readable value,
    /// which is what lets a caller put `itemprop="kind"` on it and get the word
    /// back rather than the letter and the word run together. Caller attributes
    /// land on that root — [Component#rooted] — because an attribute a caller
    /// wrote was addressed to the thing this component *is*.
    ///
    /// The control is a `<button type="button">`, not a div that listens for a
    /// click: it has to be reachable by Tab, operable by Enter and Space, and
    /// announce that it expands something, and a button is all three for free.
    /// `type="button"` matters — the default is `submit`, and a chip that
    /// submitted the search form would be a chip that navigated.
    ///
    /// The accessible name is a span of its own, clipped rather than hidden, and
    /// it holds the modifiers as well as the word: `static method`, where the
    /// eye gets `m` in a rhombus. It does not depend on the expanding word,
    /// which is clipped to nothing when the chip is closed, so the full word is
    /// announced whether or not the chip is open. The letter and the expanding
    /// word are both `aria-hidden`, so nothing is said twice.
    @Override
    public Html render() {
        var of = Of.of(props.text("kind", ""));
        var modifiers = modifiers();
        var said = String.join(" ", concat(modifiers, of.word()));

        var chip = Html.element("button",
                Attributes.type("button"),
                classes("kind-chip"),
                Attributes.aria("expanded", "false"),
                Attributes.attribute("title", said),
                Stimulus.action(Stimulus.on("click", CONTROLLER, "toggle")),
                Html.element("span", classes("kind-mark"), Attributes.aria("hidden", "true"),
                        Tags.text(of.letter())),
                Html.element("span", classes("ui-hidden"), Tags.text(said)),
                Html.element("span", classes("kind-word"), Attributes.aria("hidden", "true"),
                        Tags.text(of.word())));

        var nodes = new ArrayList<Node>(List.of(Component.rooted(names(of, modifiers), content)));
        nodes.add(Attributes.value(of.word()));
        nodes.add(Stimulus.controller(CONTROLLER));
        nodes.add(chip);
        return Html.element("data", nodes.toArray(new Node[0]));
    }

    /// The class names the stylesheet hangs on: the component, its family, and
    /// one for each modifier that is drawn.
    private static String names(Of of, List<String> modifiers) {
        var names = new StringBuilder("kind kind--").append(of.family());
        for (var modifier : modifiers) {
            if (DRAWN.contains(modifier)) names.append(" kind--").append(modifier);
        }
        return names.toString();
    }

    /// The modifiers, in the order they were written, without repeats and
    /// without blanks. An absent prop is an empty list, which is the ordinary
    /// case: a search result for a class has nothing to say here.
    private List<String> modifiers() {
        var kept = new LinkedHashSet<String>();
        for (var modifier : props.text("modifiers", "").strip().split("\\s+")) {
            if (!modifier.isBlank()) kept.add(modifier.toLowerCase(Locale.ROOT));
        }
        return List.copyOf(kept);
    }

    private static List<String> concat(List<String> first, String last) {
        var all = new ArrayList<>(first);
        all.add(last);
        return all;
    }
}
