package jsonschema;

import java.net.URI;
import java.util.Map;
import json.Json;

/// One schema resource: a document, or a part of one that claimed its own `$id`.
///
/// A resource is the unit that a URI names and that `$anchor` and
/// `$dynamicAnchor` belong to. An `$id` inside a document starts a new resource
/// with its own anchors, so the store holds it as a separate entry under its
/// own URI.
///
/// `metaschema` is the `$schema` of this resource, or null when it declares
/// none. It decides which keywords this resource is evaluated with, so two
/// resources of one document can use different vocabularies.
public record Resource(
        URI id, Json root, URI metaschema, Map<String, Store.Located> anchors,
        Map<String, Store.Located> dynamicAnchors) {

    public Resource {
        anchors = Map.copyOf(anchors);
        dynamicAnchors = Map.copyOf(dynamicAnchors);
    }
}
