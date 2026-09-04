/// The Tuul library and tool support packages. The CLI is a separate named
/// module in `entrypoints/` and requires this module.
module tuul {
    requires java.compiler;
    requires java.management;
    requires java.scripting;
    requires java.xml;
    requires jdk.compiler;
    requires jdk.httpserver;
    requires static jdk.jfr;

    requires transitive java.net.http;

    uses reload.Program;

    uses com.sun.net.httpserver.HttpHandler;
    uses java.nio.file.spi.FileSystemProvider;
    uses java.util.spi.ToolProvider;
    uses javax.annotation.processing.Processor;
    uses javax.management.remote.JMXConnectorProvider;
    uses javax.management.remote.JMXConnectorServerProvider;
    uses javax.script.ScriptEngineFactory;
    uses javax.xml.datatype.DatatypeFactory;
    uses javax.xml.parsers.DocumentBuilderFactory;
    uses javax.xml.parsers.SAXParserFactory;
    uses javax.xml.stream.XMLEventFactory;
    uses javax.xml.stream.XMLInputFactory;
    uses javax.xml.stream.XMLOutputFactory;
    uses javax.xml.transform.TransformerFactory;
    uses javax.xml.validation.SchemaFactory;
    uses javax.xml.xpath.XPathFactory;
    uses org.xml.sax.XMLReader;
    uses com.sun.source.util.Plugin;

    exports actors;
    exports actors.transport;
    exports application;
    exports argparse;
    exports browser;
    exports compiler;
    exports docs;
    exports eventstream;
    exports fetch;
    exports ffi;
    exports json;
    exports jsonrpc2;
    exports jsonrpc2.transport;
    exports jsonschema;
    exports markdown;
    exports modules;
    exports peg;
    exports project;
    exports reload;
    exports selftest;
    exports settings;
    exports sqlite3;
    exports symbols;
    exports tcl;
    exports tuul;
    exports uritemplates;
    exports web;
    exports web.assets;
    exports web.cable;
    exports web.dispatch;
    exports web.forms;
    exports web.hyperspec;
    exports web.serve;
    exports web.sessions;
    exports web.text;
    exports web.ui;
    exports web.uploads;
    exports web.reload;
}
