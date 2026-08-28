// A Stimulus controller for web.ui's Sidebar: the narrow half of a component
// whose wide half needs no JavaScript at all.
//
// The panel is a <dialog open>, so it is on the page and laid out beside the
// content wherever there is room. This only matters where there is not: the
// opener is a link to a page saying what the sidebar says, and this turns it
// into a button that opens the panel instead.
//
// Registered by the application:
//
//     import { Application } from "@hotwired/stimulus"
//     import Sidebar from "@tuul/ui-sidebar"
//
//     const application = Application.start()
//     application.register("ui-sidebar", Sidebar)

import { Controller } from "@hotwired/stimulus"

export default class Sidebar extends Controller {
  static targets = ["panel"]

  // The opener says what it controls and whether it is open. Both are read by
  // anything that navigates the page by its structure rather than by sight,
  // and neither is true until the panel can actually be opened — which is now,
  // because this ran.
  connect() {
    this.opener = this.element.querySelector("[aria-controls]")
    if (!this.opener) return
    this.opener.setAttribute("role", "button")
    this.opened = () => this.mark()
    this.closed = () => this.restore()
    this.panelTarget.addEventListener("close", this.closed)
    this.mark()
  }

  disconnect() {
    if (this.panelTarget) this.panelTarget.removeEventListener("close", this.closed)
  }

  // showModal() is the reason this is a <dialog>: focus goes inside and stays
  // there, Escape closes, the rest of the page is inert, and a backdrop appears
  // — four things a hand-written drawer gets wrong one at a time.
  //
  // It also refuses an element that already has `open`, which is exactly the
  // attribute that makes the wide pane exist without any script. The two halves
  // of this component want opposite things from one attribute, so the drawer
  // gives it up for as long as it is a drawer: close() drops `open`, showModal
  // takes it back as a modal, and the close handler puts the plain one back so
  // that widening the window still finds a pane.
  //
  // preventDefault only when the panel can be opened. If the dialog is missing
  // or the browser will not open it, the link is still a link and still goes
  // somewhere, which is the whole reason it is a link.
  open(event) {
    const panel = this.panelTarget
    if (!panel || typeof panel.showModal !== "function") return
    event.preventDefault()
    if (panel.hasAttribute("open") && !panel.matches(":modal")) panel.close()
    panel.showModal()
    this.mark()
  }

  close() {
    if (this.panelTarget.open) this.panelTarget.close()
  }

  // A closed dialog has no `open`, and a dialog with no `open` is display:none
  // in every browser — so a reader who opens the drawer, closes it and then
  // widens the window would find the pane gone. Putting the attribute back
  // restores what the server sent, and at narrow widths the stylesheet hides it
  // again anyway.
  restore() {
    this.panelTarget.setAttribute("open", "")
    this.mark()
  }

  // Closing returns focus to what opened it. The browser does this for a
  // dialog opened by a <form method=dialog> button and not for one opened by
  // script, so it is done here — a reader who tabs to the menu, opens it and
  // closes it should be back where they were rather than at the top of the
  // document.
  mark() {
    if (!this.opener) return
    const open = this.panelTarget.hasAttribute("open") && this.panelTarget.matches(":modal")
    this.opener.setAttribute("aria-expanded", open ? "true" : "false")
    if (!open && document.activeElement === document.body) this.opener.focus()
  }
}
