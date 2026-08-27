// Search as somebody types, without asking the index about every keystroke.
//
// The form targets a Turbo Frame, so submitting it replaces the results and
// nothing else on the page. All this controller does is decide when a
// submission is worth making: a person typing "json.Json" produces nine
// keystrokes and should produce one search, not nine.

import { Controller } from "@hotwired/stimulus"

export default class Search extends Controller {
  static values = { delay: { type: Number, default: 150 } }

  connect() {
    this.pending = null
  }

  disconnect() {
    clearTimeout(this.pending)
    this.pending = null
  }

  ask() {
    clearTimeout(this.pending)
    // requestSubmit rather than submit: it fires the submit event, which is
    // what Turbo listens for. submit() would reload the page.
    this.pending = setTimeout(() => this.element.requestSubmit(), this.delayValue)
  }
}
