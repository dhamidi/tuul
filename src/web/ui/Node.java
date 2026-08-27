package web.ui;

/// Anything that can appear inside an element: markup, or an attribute of it.
///
/// Attributes and children share one type so that a call reads the way the
/// markup does — `div(id("card"), h1(text("Hello")))` — and the element sorts
/// them out. Which kind comes first does not matter; the order within each kind
/// is the order it is written in.
public sealed interface Node permits Html, Attribute {}
