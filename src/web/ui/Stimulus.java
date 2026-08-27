package web.ui;

/// The attributes [Stimulus](https://stimulus.hotwired.dev) reads.
///
/// Stimulus is configured entirely in markup: an element says which controller
/// it belongs to, which of its children are targets, and which events call
/// which methods. All of that is `data-` attributes with a naming convention,
/// and a convention spelled out by hand is a convention that gets spelled
/// wrong — a target attribute is `data-<controller>-target`, but a value is
/// `data-<controller>-<name>-value`, and nothing tells you when you get it
/// backwards except the behaviour not happening.
public final class Stimulus {

    private Stimulus() {}

    /// Which controllers this element is. Several is normal.
    public static Attribute controller(String... controllers) {
        return Attributes.data("controller", String.join(" ", controllers));
    }

    /// What this element does, as descriptors — build them with [#on] or write
    /// them out.
    public static Attribute action(String... descriptors) {
        return Attributes.data("action", String.join(" ", descriptors));
    }

    /// `click->clipboard#copy`, the descriptor Stimulus wants. The event may
    /// carry modifiers of its own, as in `keydown.esc`.
    public static String on(String event, String controller, String method) {
        return event + "->" + controller + "#" + method;
    }

    /// `data-clipboard-target="source"` — what this element is *to* its
    /// controller.
    public static Attribute target(String controller, String name) {
        return Attributes.data(controller + "-target", name);
    }

    /// `data-clipboard-url-value="/x"` — a value the controller reads, and can
    /// be told about when it changes.
    public static Attribute value(String controller, String name, String value) {
        return Attributes.data(controller + "-" + name + "-value", value);
    }

    /// `data-clipboard-supported-class="bg-green"` — a class name the
    /// controller applies, kept out of the JavaScript.
    public static Attribute classes(String controller, String name, String value) {
        return Attributes.data(controller + "-" + name + "-class", value);
    }

    /// `data-clipboard-id-param="3"` — an argument passed to the action this
    /// element triggers.
    public static Attribute param(String controller, String name, String value) {
        return Attributes.data(controller + "-" + name + "-param", value);
    }

    /// `data-clipboard-result-outlet=".result"` — another controller, found by
    /// selector, that this one may talk to.
    public static Attribute outlet(String controller, String name, String selector) {
        return Attributes.data(controller + "-" + name + "-outlet", selector);
    }
}
