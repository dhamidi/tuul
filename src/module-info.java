/// The Tuul library and tool support packages. The CLI is a separate named
/// module in `entrypoints/` and requires this module.
module tuul {
    requires java.compiler;
    requires java.xml;
    requires jdk.compiler;
    requires jdk.httpserver;
    requires static jdk.jfr;

    requires transitive java.net.http;

    uses reload.Program;

    uses com.sun.net.httpserver.HttpHandler;

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
