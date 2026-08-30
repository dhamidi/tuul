/// Serves the symbol index as searchable HTML and JSON documentation.
///
/// [browser.Browser] is the application entry point. Call
/// [browser.Browser#of(java.util.List,java.util.List)] to open project sources.
/// Call [browser.Browser#handler()] to embed the application. Call
/// [browser.Browser#serve(java.util.List,java.util.List,int,java.io.Writer)] to
/// run the HTTP server.
///
/// ## Architecture
///
/// The browser reads a [symbols.Catalog]. It does not compile Java source for
/// each request. An HTML page normally costs an index query and a streamed
/// render.
///
/// The application uses the same message flow for search, symbols, and package
/// documents:
///
/// 1. [browser.Routes] names the request.
/// 2. A page update validates the route values.
/// 3. An effect reads the catalog.
/// 4. The effect emits a JSON description.
/// 5. [browser.Views] writes HTML from that description.
///
/// The command `tuul docs --json` uses the same descriptions. The HTML view
/// must not add facts that the JSON description does not contain.
///
/// [browser.Search] renders the search form. [browser.Symbols] owns symbol and
/// search state. [browser.Documents] owns package-document state. [browser.Views]
/// contains pure view functions. [browser.Browser] connects these parts to the
/// catalog and the web features.
///
/// The browser streams Markdown into the response writer. It does not first
/// build one HTML string. The browser uses Turbo frames for search results and
/// for the content pane. A request without a frame header receives a complete
/// page.
///
/// ## Documentation model
///
/// A package has three documentation layers. Keep each layer in its source
/// format.
///
/// - The overview is the comment in `package-info.java`. It answers what the
///   package is.
/// - API comments are on Java types and members. They answer what a Java name
///   does.
/// - Diataxis documents are Markdown files beside the package source. They
///   answer tutorial, task, reference, and design questions.
///
/// Do not move a long narrative document into a dummy Java type. Do not add an
/// include tag to `package-info.java`. A package document is content. It is not
/// a Java symbol and it has no members or modifiers.
///
/// A package page is the hub. It contains the short package overview, package
/// documents, nested packages, and types. A reader can start with one Markdown
/// file for each Diataxis kind. The UI exposes that file's level-two headings
/// as a compact outline. The author can split the file later without changing
/// the model.
///
/// ## Package-document filenames
///
/// The filename defines document identity. A matching file has one of these
/// forms:
///
/// ```
/// <kind>.md
/// <kind>-<slug>.md
/// <kind>-<nn>-<slug>.md
/// ```
///
/// The supported kinds are:
///
/// - `tutorial` teaches through a first working result.
/// - `howto` gives steps for one task.
/// - `reference` states facts, tables, and rules.
/// - `guide` explains design and concepts.
///
/// The `explanation` prefix is an input alias for `guide`. New files use
/// `guide`. Do not add a fifth kind.
///
/// The slug is the remaining filename without `.md`. It can contain hyphens.
/// The optional numeric prefix controls filename order. It is not part of the
/// slug. Use two digits when a package has a planned sequence.
///
/// A file without a slug is the introduction for its kind. For example,
/// `tutorial.md` has the route `/symbols/tcl/tutorial`. The file
/// `tutorial-01-first-script.md` has the route
/// `/symbols/tcl/tutorial/first-script`.
///
/// Names are lowercase. The index ignores `Tutorial.md`. It also ignores
/// `README.md`, `ORIGIN.md`, `AGENTS.md`, tool instruction files, notes, and
/// Markdown files without a supported prefix.
///
/// Keep document files in the Java package directory. Do not create a
/// `tutorial/`, `howto/`, `reference/`, or `guide/` directory. Java tools can
/// interpret such a directory as a subpackage.
///
/// Each package owns its directory only. A file in `src/web/ui/` belongs to
/// `web.ui`. It does not belong to `web`.
///
/// ## Titles, order, and collisions
///
/// Line 1 supplies the title when it starts with `# `. Otherwise, the title is
/// the slug with hyphens changed to spaces. An introduction falls back to its
/// kind name.
///
/// The index does not scan later headings to find a title. It does not accept
/// YAML or a Setext heading as identity metadata.
///
/// Documents sort by kind and filename. The file without a slug comes first in
/// its kind. The optional numeric prefix gives an explicit reading order.
///
/// The unique key is package, kind, and slug. Two files that normalize to one
/// key are an indexing error. For example, `guide.md` and `explanation.md`
/// cannot exist together in one package.
///
/// ## Discovery and storage
///
/// The project index discovers matching Markdown files while it indexes a
/// package. It stores package, kind, slug, title, body, and source path in the
/// document table. It does not store a document as a type row.
///
/// Discovery reads the filename, line 1, and raw body. It does not parse a
/// Markdown tree. Matching Markdown files contribute to the project stamp. A
/// document edit therefore invalidates the derived index.
///
/// Search stores each document with its own route name and Diataxis kind. The
/// searchable text contains the title and raw body. The package row stores a
/// list of document descriptions. It does not copy all document bodies.
///
/// The package description derives a level-two outline when it is requested.
/// [symbols.Document#sections()] performs this render-time parse. Discovery and
/// identity stay filename-based.
///
/// [markdown.Outline] makes stable fragment identifiers from visible heading
/// text. Repeated headings receive numeric suffixes. The document renderer
/// writes the same identifiers. This makes package outline links and document
/// targets one contract.
///
/// ## Lookup and routes
///
/// [symbols.Catalog#lookup(String)] remains the lookup for packages, modules,
/// and Java types. [symbols.Catalog#document(String,String,String)] reads one
/// package document. [symbols.Catalog#documents(String)] lists the documents
/// on a package hub.
///
/// [browser.Routes] defines these content routes:
///
/// - `/symbols/{name}` serves a package, module, or Java type.
/// - `/symbols/{name}/{kind}` serves an introduction or a kind list.
/// - `/symbols/{name}/{kind}/{slug}` serves one document.
///
/// A kind route selects the file without a slug when that file exists. The
/// page then lists the other files of the same kind. If no introduction
/// exists, the route lists all files of that kind.
///
/// Content negotiation can return HTML or JSON. JSON for one document contains
/// kind, package, slug, title, body, and source path. The CLI returns Markdown
/// text for a document request. It does not return HTML.
///
/// The browser does not serve the source `.md` file as an immutable asset.
/// Package documents go through the catalog, link resolver, Markdown renderer,
/// and normal HTTP negotiation.
///
/// ## Document links
///
/// A normal Markdown destination has priority. A relative destination that
/// names an indexed package document becomes that document's browser route. A
/// fragment on the source destination remains on the route.
///
/// An unresolved reference label such as `[Type#member]` can resolve through
/// [symbols.References]. Other unresolved labels remain text. This preserves
/// the Markdown meaning and adds symbol links only when the document did not
/// define a destination.
///
/// ## Page behavior
///
/// The package page groups documents by Diataxis kind. Each document title is
/// a direct link. Its optional disclosure lists level-two headings. This keeps
/// a one-file start compact and makes a long file navigable.
///
/// A document page has a breadcrumb trail, title, kind badge, package metadata,
/// and rendered Markdown. Its source level-one title is not rendered again.
/// The page renderer adds fragment targets to all Markdown headings.
///
/// A document search result uses its Diataxis kind and its document URL. The
/// summary starts after the source title. Documents do not appear in the
/// sidebar tree. A reader reaches them from a package, search, or a direct URL.
///
/// ## Scope limits
///
/// This package does not generate a static documentation site. It does not
/// execute tutorial code. It does not parse `README.md` or front matter. It
/// does not merge reference documents into API comments. It does not extract
/// heading outlines at index time.
///
/// Keep one pipeline. Comments and documents both become Markdown, then HTML
/// or JSON. Keep one identity convention. The filename supplies kind, slug,
/// and order. Keep one hub. The package page connects API names and narrative
/// documents without making either one pretend to be the other.
package browser;
