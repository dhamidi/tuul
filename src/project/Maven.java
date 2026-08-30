package project;

import fetch.HttpException;
import fetch.Session;
import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
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

/// Resolves one deterministic Maven graph for all requested roots.
///
/// The resolver supports parent inheritance, properties, dependency management,
/// imported BOMs, compile, runtime, provided, and test scopes, optional
/// dependencies, path exclusions, jar and test-jar artifacts, classifiers, and
/// relocation. It does not activate Maven profiles. Dependencies in inactive
/// profiles do not enter the graph. It rejects unresolved properties, system
/// dependencies, and unknown artifact types.
///
/// The resolver uses a breadth-first walk. The nearest coordinate wins a
/// conflict. A root wins over a transitive dependency. Declaration order wins
/// at the same depth. [Resolution#omitted()] records every mediated coordinate.
final class Maven {

    private Maven() {}

    enum Graph { RUNTIME, TEST }

    record Node(Add.Coordinate coordinate, String scope, Graph graph, List<String> path,
            URI repository, String relocatedFrom) {
        Node {
            path = List.copyOf(path);
            relocatedFrom = relocatedFrom == null ? "" : relocatedFrom;
        }
    }

    record Omitted(Node node, Add.Coordinate selected, String reason) {}

    record Resolution(List<Add.Coordinate> roots, List<Node> runtime, List<Node> test,
            List<Omitted> omitted) {
        Resolution {
            roots = List.copyOf(roots);
            runtime = List.copyOf(runtime);
            test = List.copyOf(test);
            omitted = List.copyOf(omitted);
        }

        List<Node> selected() {
            var selected = new LinkedHashMap<String, Node>();
            for (var node : runtime) selected.putIfAbsent(node.coordinate().text(), node);
            for (var node : test) selected.putIfAbsent(node.coordinate().text(), node);
            return List.copyOf(selected.values());
        }
    }

    record PomDocument(String xml, URI repository) {}

    interface PomSource {
        PomDocument get(Add.Coordinate coordinate) throws IOException;
    }

    static final class Resolver {
        private final PomSource source;
        private final Map<Add.Coordinate, Pom> poms = new HashMap<>();

        Resolver(Session session, List<URI> repositories) {
            this(coordinate -> document(session, repositories, coordinate));
        }

        Resolver(PomSource source) {
            this.source = source;
        }

        Resolution resolve(List<Add.Coordinate> roots) throws IOException {
            if (roots.isEmpty()) throw new IOException("Maven resolution needs at least one root");
            var omitted = new ArrayList<Omitted>();
            var runtime = walk(roots, Graph.RUNTIME, omitted);
            var test = walk(roots, Graph.TEST, omitted);
            return new Resolution(roots, runtime, test, omitted);
        }

        private List<Node> walk(List<Add.Coordinate> roots, Graph graph, List<Omitted> omitted)
                throws IOException {
            var queue = new ArrayDeque<Candidate>();
            for (var root : roots) queue.add(new Candidate(root, "compile", List.of(root.text()), Set.of(), ""));
            var selected = new LinkedHashMap<String, Node>();
            while (!queue.isEmpty()) {
                var candidate = relocate(queue.removeFirst());
                if (candidate.excluded().contains(candidate.coordinate().dependencyKey())) continue;
                var pom = pom(candidate.coordinate());
                var coordinate = candidate.coordinate().type().equals("jar")
                        ? candidate.coordinate().withType(pom.packaging()) : candidate.coordinate();
                if (coordinate.type().equals("pom")) {
                    enqueue(queue, candidate.withCoordinate(coordinate), pom, graph);
                    continue;
                }
                supported(coordinate);
                var node = new Node(coordinate, candidate.scope(), graph, candidate.path(),
                        pom.repository(), candidate.relocatedFrom());
                var prior = selected.putIfAbsent(coordinate.conflictKey(), node);
                if (prior != null) {
                    omitted.add(new Omitted(node, prior.coordinate(), "nearer or earlier declaration selected"));
                    continue;
                }
                enqueue(queue, candidate.withCoordinate(coordinate), pom, graph);
            }
            return List.copyOf(selected.values());
        }

        private Candidate relocate(Candidate candidate) throws IOException {
            var current = candidate;
            var seen = new LinkedHashSet<String>();
            while (true) {
                if (!seen.add(current.coordinate().text())) {
                    throw new IOException("Maven relocation cycle at " + current.coordinate().text());
                }
                var relocation = pom(current.coordinate()).relocation();
                if (relocation == null) return current;
                var path = new ArrayList<>(current.path());
                path.add(relocation.text());
                current = new Candidate(relocation, current.scope(), path, current.excluded(),
                        candidate.coordinate().text());
            }
        }

        private void enqueue(ArrayDeque<Candidate> queue, Candidate parent, Pom pom, Graph graph)
                throws IOException {
            for (var declaration : pom.dependencies()) {
                var dependency = declaration.managed(pom.managed());
                dependency.validate(parent.coordinate());
                if (!included(dependency, graph) || dependency.optional()) continue;
                var coordinate = dependency.coordinate();
                supported(coordinate);
                var excluded = new LinkedHashSet<>(parent.excluded());
                excluded.addAll(dependency.exclusions());
                var path = new ArrayList<>(parent.path());
                path.add(coordinate.text());
                queue.addLast(new Candidate(coordinate, dependency.scope(), path, Set.copyOf(excluded), ""));
            }
        }

        private static boolean included(Dependency dependency, Graph graph) throws IOException {
            return switch (dependency.scope()) {
                case "compile", "runtime" -> true;
                case "test", "provided" -> graph == Graph.TEST;
                case "import" -> false;
                case "system" -> throw new IOException("unsupported Maven system dependency "
                        + dependency.group() + ":" + dependency.artifact());
                default -> throw new IOException("unsupported Maven scope '" + dependency.scope() + "' for "
                        + dependency.group() + ":" + dependency.artifact());
            };
        }

        private Pom pom(Add.Coordinate requested) throws IOException {
            var coordinate = requested.pomCoordinate();
            var known = poms.get(coordinate);
            if (known != null) return known;
            var found = source.get(coordinate);
            var project = parse(found.xml(), coordinate.pomUri(found.repository())).getDocumentElement();
            var parentElement = child(project, "parent");
            var parentCoordinate = parentElement == null ? null : parent(parentElement, coordinate);
            var inherited = parentCoordinate == null ? Pom.empty(found.repository()) : pom(parentCoordinate);

            var properties = new LinkedHashMap<>(inherited.properties());
            builtins(properties, coordinate, parentCoordinate);
            var propertiesElement = child(project, "properties");
            if (propertiesElement != null) {
                for (var node = propertiesElement.getFirstChild(); node != null; node = node.getNextSibling()) {
                    if (node instanceof Element element) properties.put(element.getTagName(), value(element));
                }
            }
            interpolateAll(properties, coordinate);
            var group = resolved(text(project, "groupId"), properties, coordinate.group(), "project group", coordinate);
            var artifact = resolved(text(project, "artifactId"), properties, coordinate.artifact(),
                    "project artifact", coordinate);
            var version = resolved(text(project, "version"), properties, coordinate.version(),
                    "project version", coordinate);
            var effective = Add.Coordinate.of(group, artifact, version, "jar", "");
            builtins(properties, effective, parentCoordinate);
            interpolateAll(properties, coordinate);

            var managed = new LinkedHashMap<>(inherited.managed());
            var management = child(project, "dependencyManagement");
            if (management != null) {
                for (var declaration : dependencies(child(management, "dependencies"), properties, coordinate)) {
                    if (declaration.imported()) {
                        var imported = declaration.managed(managed).coordinate();
                        managed.putAll(pom(imported).managed());
                    } else {
                        managed.put(declaration.managementKey(), declaration);
                    }
                }
            }
            var dependencies = new ArrayList<>(inherited.dependencies());
            dependencies.addAll(dependencies(child(project, "dependencies"), properties, coordinate));
            dependencies.addAll(activeProfileDependencies(project, properties, coordinate));
            var packaging = resolved(text(project, "packaging"), properties, "jar", "packaging", coordinate);
            var relocation = relocation(project, properties, effective);
            var parsed = new Pom(group, artifact, version, packaging, Map.copyOf(properties), Map.copyOf(managed),
                    List.copyOf(dependencies), relocation, found.repository());
            poms.put(coordinate, parsed);
            return parsed;
        }

        private static List<Dependency> activeProfileDependencies(Element project, Map<String, String> properties,
                Add.Coordinate coordinate) throws IOException {
            var profiles = child(project, "profiles");
            if (profiles == null) return List.of();
            var result = new ArrayList<Dependency>();
            for (var node = profiles.getFirstChild(); node != null; node = node.getNextSibling()) {
                if (!(node instanceof Element profile) || !profile.getTagName().equals("profile")) continue;
                var activation = child(profile, "activation");
                if (activation == null) continue;
                var activeByDefault = text(activation, "activeByDefault");
                if (activeByDefault.equalsIgnoreCase("true")) {
                    result.addAll(dependencies(child(profile, "dependencies"), properties, coordinate));
                    continue;
                }
                if (!activeByDefault.isEmpty() || hasElementChildren(activation)) {
                    throw new IOException("unsupported Maven profile activation '" + text(profile, "id")
                            + "' in " + coordinate.text());
                }
            }
            return result;
        }

        private static Add.Coordinate relocation(Element project, Map<String, String> properties,
                Add.Coordinate coordinate) throws IOException {
            var relocation = child(child(project, "distributionManagement"), "relocation");
            if (relocation == null) return null;
            return Add.Coordinate.of(
                    resolved(text(relocation, "groupId"), properties, coordinate.group(), "relocation group", coordinate),
                    resolved(text(relocation, "artifactId"), properties, coordinate.artifact(), "relocation artifact", coordinate),
                    resolved(text(relocation, "version"), properties, coordinate.version(), "relocation version", coordinate),
                    coordinate.type(), coordinate.classifier());
        }

        private static PomDocument document(Session session, List<URI> repositories, Add.Coordinate coordinate)
                throws IOException {
            Exception last = null;
            for (var repository : repositories) {
                var uri = coordinate.pomUri(repository);
                try (var response = session.get(uri).timeout(Duration.ofMinutes(2)).send()) {
                    if (response.status() == 404) {
                        last = new HttpException(404, uri, response.headers());
                        continue;
                    }
                    response.requireSuccess();
                    return new PomDocument(response.text(), repository);
                } catch (HttpException failure) {
                    last = failure;
                    if (failure.status() != 404) break;
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted while resolving " + coordinate.text(), interrupted);
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

        private static Add.Coordinate parent(Element parent, Add.Coordinate child) throws IOException {
            var group = text(parent, "groupId");
            var artifact = text(parent, "artifactId");
            var version = text(parent, "version");
            if (group.isEmpty() || artifact.isEmpty() || version.isEmpty() || containsProperty(group + artifact + version)) {
                throw new IOException("unsupported unresolved Maven parent in " + child.text());
            }
            return Add.Coordinate.of(group, artifact, version, "pom", "");
        }

        private static List<Dependency> dependencies(Element container, Map<String, String> properties,
                Add.Coordinate owner) throws IOException {
            if (container == null) return List.of();
            var result = new ArrayList<Dependency>();
            for (var node = container.getFirstChild(); node != null; node = node.getNextSibling()) {
                if (!(node instanceof Element element) || !element.getTagName().equals("dependency")) continue;
                var exclusions = new LinkedHashSet<String>();
                var exclusionsElement = child(element, "exclusions");
                if (exclusionsElement != null) {
                    for (var item = exclusionsElement.getFirstChild(); item != null; item = item.getNextSibling()) {
                        if (!(item instanceof Element exclusion) || !exclusion.getTagName().equals("exclusion")) continue;
                        exclusions.add(required(text(exclusion, "groupId"), properties, "exclusion group", owner)
                                + ":" + required(text(exclusion, "artifactId"), properties,
                                        "exclusion artifact", owner));
                    }
                }
                result.add(new Dependency(
                        required(text(element, "groupId"), properties, "dependency group", owner),
                        required(text(element, "artifactId"), properties, "dependency artifact", owner),
                        optional(text(element, "version"), properties, owner),
                        resolved(text(element, "scope"), properties, "compile", "dependency scope", owner),
                        resolved(text(element, "type"), properties, "jar", "dependency type", owner),
                        resolved(text(element, "classifier"), properties, "", "dependency classifier", owner),
                        resolved(text(element, "optional"), properties, "false", "dependency optional", owner)
                                .equalsIgnoreCase("true"), Set.copyOf(exclusions)));
            }
            return List.copyOf(result);
        }

        private static void supported(Add.Coordinate coordinate) throws IOException {
            if (!Set.of("jar", "test-jar", "pom").contains(coordinate.type())) {
                throw new IOException("unsupported Maven artifact type '" + coordinate.type() + "' for "
                        + coordinate.text());
            }
        }

        private static void builtins(Map<String, String> properties, Add.Coordinate coordinate,
                Add.Coordinate parent) {
            properties.put("project.groupId", coordinate.group());
            properties.put("project.artifactId", coordinate.artifact());
            properties.put("project.version", coordinate.version());
            properties.put("pom.groupId", coordinate.group());
            properties.put("pom.artifactId", coordinate.artifact());
            properties.put("pom.version", coordinate.version());
            if (parent == null) return;
            properties.put("project.parent.groupId", parent.group());
            properties.put("project.parent.artifactId", parent.artifact());
            properties.put("project.parent.version", parent.version());
        }

        private static void interpolateAll(Map<String, String> properties, Add.Coordinate owner) throws IOException {
            for (var pass = 0; pass < 20; pass++) {
                var changed = false;
                for (var entry : properties.entrySet()) {
                    var value = interpolate(entry.getValue(), properties);
                    if (!value.equals(entry.getValue())) {
                        entry.setValue(value);
                        changed = true;
                    }
                }
                if (!changed) break;
            }
            for (var entry : properties.entrySet()) {
                if (containsProperty(entry.getValue())) throw new IOException("unresolved Maven property "
                        + entry.getValue() + " in " + owner.text());
            }
        }

        private static String optional(String text, Map<String, String> properties, Add.Coordinate owner)
                throws IOException {
            var value = interpolate(text, properties).trim();
            if (containsProperty(value)) throw unresolved(value, owner);
            return value.isEmpty() ? null : value;
        }

        private static String required(String text, Map<String, String> properties, String field,
                Add.Coordinate owner) throws IOException {
            var value = resolved(text, properties, "", field, owner);
            if (value.isEmpty()) throw new IOException("missing Maven " + field + " in " + owner.text());
            return value;
        }

        private static String resolved(String text, Map<String, String> properties, String fallback, String field,
                Add.Coordinate owner) throws IOException {
            var value = interpolate(text, properties).trim();
            if (value.isEmpty()) value = fallback;
            if (containsProperty(value)) throw unresolved(value, owner);
            return value;
        }

        private static IOException unresolved(String value, Add.Coordinate owner) {
            return new IOException("unresolved Maven property '" + value + "' in " + owner.text());
        }

        private static boolean containsProperty(String value) { return value.contains("${"); }

        private static String interpolate(String text, Map<String, String> properties) {
            var result = text == null ? "" : text;
            for (var entry : properties.entrySet()) result = result.replace("${" + entry.getKey() + "}", entry.getValue());
            return result;
        }

        private static boolean hasElementChildren(Element element) {
            for (var node = element.getFirstChild(); node != null; node = node.getNextSibling()) {
                if (node instanceof Element child && !child.getTagName().equals("activeByDefault")) return true;
            }
            return false;
        }

        private static String text(Element parent, String name) {
            var element = child(parent, name);
            return element == null ? "" : value(element);
        }

        private static String value(Element element) { return element.getTextContent().trim(); }

        private static Element child(Element parent, String name) {
            if (parent == null) return null;
            for (var node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
                if (node instanceof Element element && element.getTagName().equals(name)) return element;
            }
            return null;
        }
    }

    private record Candidate(Add.Coordinate coordinate, String scope, List<String> path,
            Set<String> excluded, String relocatedFrom) {
        Candidate {
            path = List.copyOf(path);
            excluded = Set.copyOf(excluded);
        }

        Candidate withCoordinate(Add.Coordinate value) {
            return new Candidate(value, scope, path, excluded, relocatedFrom);
        }
    }

    private record Pom(String group, String artifact, String version, String packaging,
            Map<String, String> properties, Map<String, Dependency> managed,
            List<Dependency> dependencies, Add.Coordinate relocation, URI repository) {
        static Pom empty(URI repository) {
            return new Pom("", "", "", "pom", Map.of(), Map.of(), List.of(), null, repository);
        }
    }

    private record Dependency(String group, String artifact, String version, String scope,
            String type, String classifier, boolean optional, Set<String> exclusions) {
        boolean imported() { return type.equals("pom") && scope.equals("import"); }

        String managementKey() { return group + ":" + artifact + ":" + type + ":" + classifier; }

        Dependency managed(Map<String, Dependency> managed) {
            var defaults = managed.get(managementKey());
            if (defaults == null && type.equals("jar") && classifier.isEmpty()) {
                defaults = managed.get(group + ":" + artifact + ":jar:");
            }
            if (defaults == null) return this;
            return new Dependency(group, artifact, version == null ? defaults.version() : version,
                    scope.equals("compile") && !defaults.scope().equals("compile") ? defaults.scope() : scope,
                    type.equals("jar") && !defaults.type().equals("jar") ? defaults.type() : type,
                    classifier.isEmpty() ? defaults.classifier() : classifier, optional, exclusions);
        }

        void validate(Add.Coordinate owner) throws IOException {
            if (version == null || version.isEmpty()) throw new IOException("missing managed version for "
                    + group + ":" + artifact + " required by " + owner.text());
        }

        Add.Coordinate coordinate() {
            var selectedClassifier = classifier;
            if (selectedClassifier.isEmpty() && type.equals("test-jar")) selectedClassifier = "tests";
            return Add.Coordinate.of(group, artifact, version, type, selectedClassifier);
        }
    }
}
