package browser;

import static web.ui.Attributes.id;
import static web.ui.Tags.text;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import json.Json;
import markdown.Links;
import web.Router;
import web.ui.Component;
import web.ui.Html;
import web.ui.Microdata;
import web.ui.Props;
import web.ui.Turbo;
import web.ui.Ui;

/// One member of a type, the way this browser shows it.
///
/// An application has markup of its own that no design system should carry — a
/// member row is about symbols, not about cards and stacks — so it is a
/// component here rather than a component in [web.ui]. It is an ordinary class
/// in an ordinary package implementing [Component], and it goes anywhere a node
/// goes: inside a [Ui#items], inside a `div`, inside another component.
///
/// It takes the two things a design component cannot: the router, because a
/// signature's type names are links and only the route table knows where a
/// symbol lives, and the member itself as JSON, because that is the shape the
/// index answers in.
public record Member(Router routes, Links links, Props props, Json.Object member) implements Component {

    /// A qualified name in a signature — something with a dot in it, which is a
    /// type this browser can show, as against a parameter name, which is not.
    private static final Pattern QUALIFIED = Pattern.compile("[A-Za-z_$][\\w$]*(\\.[A-Za-z_$][\\w$]*)+");

    public Member {
        props.only("anchor");
    }

    public static Member of(Router routes, Json.Object member, String anchor) {
        return of(routes, Links.NONE, member, anchor);
    }

    /// The same row, with `links` given the cross-references in the member's
    /// own prose. A method's comment names other symbols exactly as its type's
    /// does, and a page where the type's references are links and its methods'
    /// are brackets is a page that looks broken in the middle.
    public static Member of(Router routes, Links links, Json.Object member, String anchor) {
        return new Member(routes, links, Props.of("anchor", anchor), member);
    }

    @Override
    public Html render() {
        return Ui.item(id(props.text("anchor", "")), Microdata.scope(), Microdata.type("/Member"),
                Ui.mono(Microdata.of("signature"), signature(member.string("signature", ""))),
                documentation(member.string("doc", "")),
                tags());
    }

    /// A signature with every type in it a link, because following a return
    /// type to its own page is how somebody reads an API.
    private Html signature(String signature) {
        var parts = new ArrayList<Html>();
        var names = QUALIFIED.matcher(signature);
        var written = 0;
        while (names.find()) {
            if (names.start() > written) parts.add(text(signature.substring(written, names.start())));
            parts.add(Ui.anchor(
                    Props.of("href", routes.path(Routes.SYMBOL.with(Routes.NAME, names.group())), "frame", Turbo.TOP),
                    text(names.group())));
            written = names.end();
        }
        if (written < signature.length()) parts.add(text(signature.substring(written)));
        return Html.fragment(parts);
    }

    /// The same rendering the rest of the browser uses, so a code example in a
    /// member's comment looks like one in a type's.
    private Html documentation(String doc) {
        return Views.documentation(links, doc);
    }

    private Html tags() {
        var tags = new ArrayList<Json.Object>();
        for (var tag : member.list("tags")) {
            if (tag instanceof Json.Object entry) tags.add(entry);
        }
        if (tags.isEmpty()) return Html.nothing();
        return Ui.items(Html.each(List.copyOf(tags), Member::tag));
    }

    /// One tag. What it names is set apart from what it says about it: they
    /// shared a weight and a face, so `@param original A String` read as one
    /// run of words with no boundary in it.
    static Html tag(Json.Object tag) {
        var named = tag.string("name", "");
        return Ui.item(Ui.group(Props.of("gap", "sm", "align", "baseline"),
                Ui.badge(Props.of("tone", "plain"), text("@" + tag.string("tag", ""))),
                named.isEmpty() ? Html.nothing() : Ui.mono(text(named)),
                Ui.prose(Props.of("tone", "muted"), text(tag.string("text", "")))));
    }
}
