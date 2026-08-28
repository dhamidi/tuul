// A kind chip that says its own word.
//
// The chip is a letter in a square — C for a class, m for a method — and
// pressing it opens the square into a badge holding the full word. That is the
// whole of the behaviour: one attribute, `aria-expanded`, which the stylesheet
// reads to draw the open state and which a screen reader reads to say it.
//
// The chip sits next to a link, and Turbo turns a click on a link into a
// navigation. So the event is stopped here: without that, a click that lands on
// the chip while the pointer is over the row still reaches whatever is
// listening above it, and pressing a chip would leave the page.
//
// Nothing here animates anything. The transition belongs to the stylesheet,
// which is also where `prefers-reduced-motion` turns it off — a controller that
// animated by hand would have to be told about that setting, and would be told
// wrong the day somebody changed it.

import { Controller } from "@hotwired/stimulus"

export default class ResultKind extends Controller {
  toggle(event) {
    // preventDefault as well as stopPropagation: the chip is a
    // `type="button"`, so it submits nothing, but a chip that ever ends up
    // inside a form or a link should still do nothing but open.
    event.preventDefault()
    event.stopPropagation()
    const chip = event.currentTarget
    const open = chip.getAttribute("aria-expanded") === "true"
    chip.setAttribute("aria-expanded", open ? "false" : "true")
  }
}
