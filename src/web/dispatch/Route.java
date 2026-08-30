package web.dispatch;

import java.util.List;
import java.util.Locale;
import uritemplates.Part;
import uritemplates.Template;
import uritemplates.TemplateException;
import web.RouteRef;

/// One registered method of a [RouteRef].
///
/// The template can differ from the reference after a router mounts it. The
/// reference keeps its identity while this template gains the mount prefix.
public record Route(RouteRef reference, String method, Template template) {

    public Route {
        if (method.isBlank()) throw new DispatchException("route " + reference.name() + " needs a method");
        method = method.toUpperCase(Locale.ROOT);
        if (!template.matchable()) {
            throw new DispatchException("route " + reference.name() + " cannot be recognised: " + template.text()
                    + " — only {var} and {/var}, without modifiers, can be read backwards");
        }
    }

    public static Route of(RouteRef reference, String method) {
        return of(reference, method, reference.template());
    }

    public static Route of(RouteRef reference, String method, String template) {
        return new Route(reference, method, read(reference.name(), template));
    }

    public String name() {
        return reference.name();
    }

    /// Whether this route answers a request made with this method. A GET route
    /// answers HEAD as well, because the two differ in what is sent back rather
    /// than in what is being asked for, and saying so twice in a route table is
    /// how the two drift apart.
    public boolean accepts(String method) {
        return this.method.equals(method) || (this.method.equals("GET") && method.equals("HEAD"));
    }

    /// The methods this route answers, which is what a 405 has to report.
    public List<String> answers() {
        return method.equals("GET") ? List.of("GET", "HEAD") : List.of(method);
    }

    /// How many characters of the template are fixed text. More of them means a
    /// more specific route — see [Routes#recognise].
    public int literals() {
        var length = 0;
        for (var part : template.parts()) {
            if (part instanceof Part.Literal(var text)) length += text.length();
        }
        return length;
    }

    /// How many variables the template has, counting a variable named twice
    /// twice, since it is written twice.
    public int variables() {
        var count = 0;
        for (var part : template.parts()) {
            if (part instanceof Part.Expression expression) count += expression.variables().size();
        }
        return count;
    }

    private static Template read(String name, String text) {
        try {
            return Template.of(text);
        } catch (TemplateException e) {
            throw new DispatchException("route " + name + " has no usable template: " + e.getMessage());
        }
    }

    @Override
    public String toString() {
        return method + " " + template.text() + " (" + name() + ")";
    }
}
