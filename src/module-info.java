/// The Tuul library packages. The build compiles these packages as one module.
/// A project declares `requires tuul` to use them. The CLI stays outside this
/// module and runs from the classpath.
module tuul {
    requires java.compiler;
    requires java.xml;
    requires jdk.compiler;
    requires jdk.httpserver;
    requires static jdk.jfr;

    requires transitive java.net.http;

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
}
