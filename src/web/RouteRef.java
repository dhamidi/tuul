package web;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import uritemplates.Template;
import uritemplates.TemplateException;

/// A named route and the types of the values in its path.
///
/// Define a reference once. Give it to [Router#get(RouteRef, Handler)] when the
/// application binds its handler. Give a bound copy to [Router#path(RouteRef)]
/// when the application builds a link. A mounted router still builds the
/// mounted path because the router resolves the reference.
///
/// ```
/// var id = new IDParameter("id");
/// var post = RouteRef.of("post", "/posts/{id}", id);
/// var router = Router.of().get(post, (request, response) ->
///         Responses.text("post " + id.get(request), response));
///
/// router.path(post.with(id, 42L)); // /posts/42
/// ```
public final class RouteRef {

    private final String name;
    private final String template;
    private final List<Parameter<?>> parameters;
    private final Map<String, String> values;

    private RouteRef(String name, String template, List<Parameter<?>> parameters, Map<String, String> values) {
        this.name = name;
        this.template = template;
        this.parameters = List.copyOf(parameters);
        this.values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    /// Defines a route. Each variable in the template has one parameter with
    /// the same name. A missing, extra, or duplicate parameter is refused now.
    public static RouteRef of(String name, String template, Parameter<?>... parameters) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("a route needs a name");
        var parsed = read(name, template);
        if (!parsed.matchable()) {
            throw new IllegalArgumentException("route " + name + " cannot be recognised: " + template
                    + " — only {var} and {/var}, without modifiers, can be read backwards");
        }
        var byName = new LinkedHashMap<String, Parameter<?>>();
        for (var parameter : parameters) {
            if (byName.putIfAbsent(parameter.name(), parameter) != null) {
                throw new IllegalArgumentException("route " + name + " has two parameters named " + parameter.name());
            }
        }
        var declared = new ArrayList<>(byName.keySet());
        if (!declared.equals(parsed.names())) {
            throw new IllegalArgumentException("route " + name + " needs parameters " + parsed.names()
                    + " in template order, not " + declared);
        }
        return new RouteRef(name, template, List.of(parameters), Map.of());
    }

    public String name() {
        return name;
    }

    public String template() {
        return template;
    }

    public List<Parameter<?>> parameters() {
        return parameters;
    }

    /// Binds one typed value and returns a new reference. The parameter must be
    /// the parameter declared by this route.
    public <T> RouteRef with(Parameter<T> parameter, T value) {
        if (value == null) throw new IllegalArgumentException("route " + name + " needs " + parameter.name());
        var expected = parameters.stream()
                .filter(candidate -> candidate.name().equals(parameter.name()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "route " + name + " has no parameter named " + parameter.name()));
        if (!expected.equals(parameter)) {
            throw new IllegalArgumentException("route " + name + " declares a different " + parameter.name());
        }
        var formatted = parameter.format(value);
        if (formatted == null) throw new IllegalArgumentException("route " + name + " needs " + parameter.name());
        try {
            parameter.parse(formatted);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("route " + name + " has an invalid " + parameter.name(), invalid);
        }
        var next = new LinkedHashMap<>(values);
        next.put(parameter.name(), formatted);
        return new RouteRef(name, template, parameters, next);
    }

    /// The formatted values already bound to this reference.
    public Map<String, String> values() {
        return values;
    }

    /// Parses values recovered from a path. Returns empty when any value is
    /// missing or invalid. A router then continues to the next route.
    public Optional<Map<String, Object>> parse(Map<String, String> text) {
        var parsed = new LinkedHashMap<String, Object>();
        try {
            for (var parameter : parameters) {
                var value = text.get(parameter.name());
                if (value == null) return Optional.empty();
                parsed.put(parameter.name(), parse(parameter, value));
            }
            return Optional.of(Collections.unmodifiableMap(parsed));
        } catch (IllegalArgumentException invalid) {
            return Optional.empty();
        }
    }

    public Optional<Parameter<?>> parameter(String name) {
        return parameters.stream().filter(parameter -> parameter.name().equals(name)).findFirst();
    }

    @SuppressWarnings("unchecked")
    private static <T> T parse(Parameter<?> parameter, String text) {
        return ((Parameter<T>) parameter).parse(text);
    }

    private static Template read(String name, String text) {
        try {
            return Template.of(text);
        } catch (TemplateException e) {
            throw new IllegalArgumentException("route " + name + " has no usable template: " + e.getMessage());
        }
    }

    @Override
    public String toString() {
        return name;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof RouteRef reference
                && name.equals(reference.name)
                && template.equals(reference.template)
                && parameters.equals(reference.parameters)
                && values.equals(reference.values);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, template, parameters, values);
    }
}
