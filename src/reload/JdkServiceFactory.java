package reload;

import java.nio.file.spi.FileSystemProvider;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.spi.ToolProvider;
import javax.management.remote.JMXConnectorProvider;
import javax.management.remote.JMXConnectorServerProvider;
import javax.script.ScriptEngineFactory;
import javax.xml.datatype.DatatypeFactory;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.SAXParserFactory;
import javax.xml.stream.XMLEventFactory;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.transform.TransformerFactory;
import javax.xml.validation.SchemaFactory;
import javax.xml.xpath.XPathFactory;
import org.xml.sax.XMLReader;
import com.sun.source.util.Plugin;
import javax.annotation.processing.Processor;

/// Creates generations that load the supported JDK services from the root module.
///
/// The factory delegates to [ServiceGenerationFactory]. It does not use the
/// system class loader, the thread context class loader, or a JDK global registry.
public final class JdkServiceFactory implements GenerationFactory {
    private static final Set<Class<?>> SUPPORTED = Set.of(
            FileSystemProvider.class, ScriptEngineFactory.class,
            ToolProvider.class,
            DatatypeFactory.class, DocumentBuilderFactory.class,
            SAXParserFactory.class, SchemaFactory.class, XMLInputFactory.class,
            XMLOutputFactory.class, XMLEventFactory.class,
            TransformerFactory.class, XPathFactory.class,
            XMLReader.class,
            JMXConnectorProvider.class, JMXConnectorServerProvider.class,
            Processor.class, Plugin.class);

    private final ServiceGenerationFactory delegate;

    /// Selects the supported service types to load in argument order.
    ///
    /// An unsupported or duplicate type throws `IllegalArgumentException`.
    /// An empty list creates generations with no JDK services.
    public JdkServiceFactory(List<Class<?>> services) {
        Objects.requireNonNull(services, "services");
        List<Class<?>> copy = services.stream().<Class<?>>map(service -> {
            Objects.requireNonNull(service, "service");
            if (!SUPPORTED.contains(service)) throw new IllegalArgumentException(
                    "unsupported JDK service: " + service.getName());
            return service;
        }).toList();
        if (copy.size() != copy.stream().distinct().count()) {
            throw new IllegalArgumentException("duplicate JDK service type");
        }
        delegate = new ServiceGenerationFactory(copy);
    }

    /// Selects the supported service types to load in argument order.
    public JdkServiceFactory(Class<?>... services) {
        this(List.of(services));
    }

    /// Loads the selected root providers and attaches them to the generation.
    @Override
    public Generation define(CandidateContext candidate) throws Exception {
        return delegate.define(Objects.requireNonNull(candidate, "candidate"));
    }

    /// Returns the supported service types in deterministic name order.
    public static List<Class<?>> supportedServices() {
        return SUPPORTED.stream().sorted((left, right) ->
                left.getName().compareTo(right.getName())).toList();
    }
}
