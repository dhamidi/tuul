package project;

import fetch.HttpException;
import fetch.Session;
import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

/// Resolves Maven POM files with the JDK XML DOM parser.
///
/// The resolver reads dependency metadata only. It does not write files. A
/// caller can therefore resolve the graph before it schedules artifact effects.
final class Maven {

    private Maven() {}

    static final class Resolver {
        private final Session session;
        private final List<URI> repositories;
        private final Map<Add.Coordinate, Pom> poms = new HashMap<>();
        private final Set<Add.Coordinate> visited = new HashSet<>();
        private final LinkedHashMap<String, Add.Coordinate> artifacts = new LinkedHashMap<>();

        Resolver(Session session, List<URI> repositories) {
            this.session = session;
            this.repositories = repositories;
        }

        List<Add.Coordinate> resolve(Add.Coordinate root) throws IOException {
            visit(root, true, Set.of());
            return List.copyOf(artifacts.values());
        }

        private void visit(Add.Coordinate coordinate, boolean direct, Set<String> excluded)
                throws IOException {
            if (excluded.contains(key(coordinate)) || !visited.add(coordinate)) return;
            var pom = pom(coordinate);
            if (!pom.packaging().equals("pom")) artifacts.putIfAbsent(coordinate.text(), coordinate);
            for (var dependency : pom.dependencies()) {
                if (!wanted(dependency, direct)) continue;
                var child = dependency.coordinate(pom);
                if (child == null) continue;
                var nextExcluded = new LinkedHashSet<>(excluded);
                nextExcluded.addAll(dependency.exclusions());
                visit(child, false, Set.copyOf(nextExcluded));
            }
        }

        private Pom pom(Add.Coordinate coordinate) throws IOException {
            var known = poms.get(coordinate);
            if (known != null) return known;
            var document = document(coordinate);
            var project = document.getDocumentElement();
            var parentElement = child(project, "parent");
            var parent = parentElement == null ? null : parentCoordinate(parentElement);
            var inherited = parent == null ? Pom.empty() : pom(parent);

            var properties = new LinkedHashMap<>(inherited.properties());
            properties.put("project.groupId", coordinate.group());
            properties.put("project.artifactId", coordinate.artifact());
            properties.put("project.version", coordinate.version());
            properties.put("pom.groupId", coordinate.group());
            properties.put("pom.artifactId", coordinate.artifact());
            properties.put("pom.version", coordinate.version());
            if (parent != null) {
                properties.put("project.parent.groupId", parent.group());
                properties.put("project.parent.artifactId", parent.artifact());
                properties.put("project.parent.version", parent.version());
            }
            var propertiesElement = child(project, "properties");
            if (propertiesElement != null) {
                for (var node = propertiesElement.getFirstChild(); node != null; node = node.getNextSibling()) {
                    if (node instanceof Element element) properties.put(element.getTagName(), value(element));
                }
            }
            properties.replaceAll((name, value) -> interpolate(value, properties));

            var group = interpolate(text(project, "groupId"), properties);
            if (group.isEmpty()) group = coordinate.group();
            var version = interpolate(text(project, "version"), properties);
            if (version.isEmpty()) version = coordinate.version();
            properties.put("project.groupId", group);
            properties.put("pom.groupId", group);
            properties.put("project.version", version);
            properties.put("pom.version", version);

            var managed = new LinkedHashMap<String, Dependency>();
            managed.putAll(inherited.managed());
            var management = child(project, "dependencyManagement");
            if (management != null) {
                for (var dependency : dependencies(child(management, "dependencies"), properties)) {
                    if (dependency.imported()) {
                        var imported = dependency.coordinate(new Pom(group, coordinate.artifact(), version,
                                "jar", properties, managed, List.of()));
                        if (imported != null) managed.putAll(pom(imported).managed());
                    } else if (dependency.version() != null) {
                        managed.put(key(dependency.group(), dependency.artifact()), dependency);
                    }
                }
            }
            var parsed = new Pom(group, coordinate.artifact(), version,
                    interpolate(text(project, "packaging"), properties, "jar"),
                    Map.copyOf(properties), Map.copyOf(managed),
                    dependencies(child(project, "dependencies"), properties));
            poms.put(coordinate, parsed);
            return parsed;
        }

        private Document document(Add.Coordinate coordinate) throws IOException {
            Exception last = null;
            for (var repository : repositories) {
                var uri = coordinate.pomUri(repository);
                try (var response = session.get(uri).timeout(Duration.ofMinutes(2)).send()) {
                    if (response.status() == 404) {
                        last = new HttpException(404, uri, response.headers());
                        continue;
                    }
                    response.requireSuccess();
                    return parse(response.text(), uri);
                } catch (HttpException failure) {
                    last = failure;
                    if (failure.status() != 404) break;
                } catch (Exception failure) {
                    last = failure;
                    break;
                }
            }
            var detail = last == null ? "POM was not found in the configured repositories"
                    : last.getMessage() == null ? last.toString() : last.getMessage();
            throw new IOException("could not resolve " + coordinate.text() + ": " + detail, last);
        }

        private static Document parse(String xml, URI uri) throws IOException {
            try {
                var factory = DocumentBuilderFactory.newInstance();
                factory.setNamespaceAware(false);
                factory.setXIncludeAware(false);
                factory.setExpandEntityReferences(false);
                factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
                factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
                factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
                factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
                return factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
            } catch (ParserConfigurationException | SAXException | IOException failure) {
                throw new IOException("invalid Maven POM from " + uri + ": " + failure.getMessage(), failure);
            }
        }

        private static Add.Coordinate parentCoordinate(Element parent) {
            var group = text(parent, "groupId");
            var artifact = text(parent, "artifactId");
            var version = text(parent, "version");
            return group.isEmpty() || artifact.isEmpty() || version.isEmpty()
                    ? null : Add.Coordinate.of(group, artifact, version, "");
        }

        private static List<Dependency> dependencies(Element container, Map<String, String> properties) {
            if (container == null) return List.of();
            var result = new ArrayList<Dependency>();
            for (var node = container.getFirstChild(); node != null; node = node.getNextSibling()) {
                if (!(node instanceof Element element) || !element.getTagName().equals("dependency")) continue;
                var exclusions = new LinkedHashSet<String>();
                var exclusionsElement = child(element, "exclusions");
                if (exclusionsElement != null) {
                    for (var node2 = exclusionsElement.getFirstChild(); node2 != null; node2 = node2.getNextSibling()) {
                        if (node2 instanceof Element exclusion && exclusion.getTagName().equals("exclusion")) {
                            exclusions.add(key(interpolate(text(exclusion, "groupId"), properties),
                                    interpolate(text(exclusion, "artifactId"), properties)));
                        }
                    }
                }
                result.add(new Dependency(
                        interpolate(text(element, "groupId"), properties),
                        interpolate(text(element, "artifactId"), properties),
                        optional(text(element, "version"), properties),
                        interpolate(text(element, "scope"), properties, "compile"),
                        interpolate(text(element, "type"), properties, "jar"),
                        interpolate(text(element, "classifier"), properties),
                        interpolate(text(element, "optional"), properties, "false").equalsIgnoreCase("true"),
                        Set.copyOf(exclusions)));
            }
            return List.copyOf(result);
        }

        private static String optional(String text, Map<String, String> properties) {
            var value = interpolate(text, properties);
            return value.isEmpty() ? null : value;
        }

        private static boolean wanted(Dependency dependency, boolean direct) {
            if (dependency.group().isEmpty() || dependency.artifact().isEmpty()) return false;
            if (dependency.scope().equals("test") || dependency.scope().equals("provided")
                    || dependency.scope().equals("system") || dependency.scope().equals("import")) return false;
            return direct || !dependency.optional();
        }

        private static String key(Add.Coordinate coordinate) {
            return key(coordinate.group(), coordinate.artifact());
        }

        private static String key(String group, String artifact) {
            return group + ":" + artifact;
        }

        private static String interpolate(String text, Map<String, String> properties) {
            var result = text == null ? "" : text;
            for (var pass = 0; pass < 20; pass++) {
                var changed = false;
                for (var entry : properties.entrySet()) {
                    var token = "${" + entry.getKey() + "}";
                    if (result.contains(token)) {
                        result = result.replace(token, entry.getValue());
                        changed = true;
                    }
                }
                if (!changed) break;
            }
            return result.trim();
        }

        private static String interpolate(String text, Map<String, String> properties, String fallback) {
            var value = interpolate(text, properties);
            return value.isEmpty() ? fallback : value;
        }

        private static String text(Element parent, String name) {
            var element = child(parent, name);
            return element == null ? "" : value(element);
        }

        private static String value(Element element) {
            return element.getTextContent().trim();
        }

        private static Element child(Element parent, String name) {
            if (parent == null) return null;
            for (var node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
                if (node instanceof Element element && element.getTagName().equals(name)) return element;
            }
            return null;
        }
    }

    private record Pom(String group, String artifact, String version, String packaging,
            Map<String, String> properties, Map<String, Dependency> managed,
            List<Dependency> dependencies) {
        static Pom empty() {
            return new Pom("", "", "", "jar", Map.of(), Map.of(), List.of());
        }
    }

    private record Dependency(String group, String artifact, String version, String scope,
            String type, String classifier, boolean optional, Set<String> exclusions) {
        boolean imported() {
            return type.equals("pom") && scope.equals("import");
        }

        Add.Coordinate coordinate(Pom pom) {
            var managedDependency = version == null ? pom.managed().get(group + ":" + artifact) : null;
            var selected = managedDependency == null ? this : managedDependency;
            if (selected.version() == null || selected.version().isEmpty()) return null;
            var selectedClassifier = classifier.isEmpty() ? selected.classifier() : classifier;
            if (selectedClassifier.isEmpty() && selected.type().equals("test-jar")) selectedClassifier = "tests";
            return Add.Coordinate.of(group, artifact, selected.version(), selectedClassifier);
        }
    }
}
