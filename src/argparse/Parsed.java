package argparse;

import json.Json;

/// What a command line turned out to be.
///
/// Three outcomes, because there are three things a program does next: get on
/// with it, print the help somebody asked for, or explain what was wrong. All
/// three carry the command they are about, so the help and the explanation are
/// about the command the user was actually trying to run rather than the one at
/// the top.
public sealed interface Parsed {

    /// The path as it was typed — `tuul docs` — for the usage line.
    String path();

    Command command();

    /// What was said, as JSON: every option under its own name, every argument
    /// under its own, defaults filled in.
    record Values(String path, Command command, Json.Object values) implements Parsed {}

    record Help(String path, Command command) implements Parsed {}

    record Failure(String path, Command command, String reason) implements Parsed {}
}
