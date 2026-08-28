/// The text rules that markup depends on and that nothing else should repeat.
///
/// One class, and a package for it, because of where it has to be reachable
/// from rather than how large it is: `web.ui`, `web.assets` and `web` all write
/// elements, and `web.ui` is above the other two. A rule that decides whether
/// generated markup means what somebody wrote has to sit below everything that
/// writes markup, or it gets copied — which is what happened, twice, and both
/// copies were weaker than the original.
package web.text;
