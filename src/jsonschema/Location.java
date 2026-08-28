package jsonschema;

import java.net.URI;

/// Where an error or an annotation comes from.
///
/// The specification asks for three locations, and each one answers a different
/// question. `keywordLocation` is the path the evaluation took from the root
/// schema, and it still holds the `$ref` steps it went through. That path says
/// *how* the keyword was reached. `absoluteKeywordLocation` is the same keyword
/// named by its own resource, so two different paths to one keyword produce one
/// absolute location. `instanceLocation` names the part of the instance that
/// the keyword looked at.
public record Location(String keywordLocation, URI absoluteKeywordLocation, String instanceLocation) {}
