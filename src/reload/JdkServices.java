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

/// Loads the JDK services that are safe to own inside a reload generation.
public final class JdkServices {
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

    private JdkServices() {}

    /// Loads the selected supported services from the candidate root module.
    /// An unsupported or duplicate type throws `IllegalArgumentException`.
    public static Generation define(CandidateContext candidate,
            List<? extends Class<?>> serviceTypes) {
        Objects.requireNonNull(serviceTypes, "service types");
        var types = serviceTypes.stream().map(service -> {
            Objects.requireNonNull(service, "service");
            if (!SUPPORTED.contains(service)) throw new IllegalArgumentException(
                    "unsupported JDK service: " + service.getName());
            return service;
        }).toList();
        if (types.size() != types.stream().distinct().count()) {
            throw new IllegalArgumentException("duplicate JDK service type");
        }
        return Generation.services(candidate, types);
    }

    /// Loads the selected supported services from the candidate root module.
    public static Generation define(CandidateContext candidate, Class<?>... serviceTypes) {
        return define(candidate, List.of(serviceTypes));
    }

    /// Returns supported services in deterministic name order.
    public static List<Class<?>> supported() {
        return SUPPORTED.stream().sorted((left, right) ->
                left.getName().compareTo(right.getName())).toList();
    }
}
