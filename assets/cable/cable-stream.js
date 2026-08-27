// A Stimulus controller that hands an EventSource to Turbo, which is the whole
// of the client side of web.cable: Turbo already knows how to apply a
// <turbo-stream> element, and connectStreamSource is how it is told where more
// of them will arrive.
//
// Registered by the application:
//
//     import { Application } from "@hotwired/stimulus"
//     import CableStream from "@tuul/cable-stream"
//
//     const application = Application.start()
//     application.register("cable-stream", CableStream)
//
// The element web.cable renders is data-turbo-permanent, so a Turbo navigation
// leaves it alone and connect() runs once per tab rather than once per page.

import { Controller } from "@hotwired/stimulus"
import * as Turbo from "@hotwired/turbo"

export default class CableStream extends Controller {
  static values = { url: String }

  connect() {
    this.source = new EventSource(this.urlValue)
    Turbo.connectStreamSource(this.source)
  }

  disconnect() {
    if (!this.source) return
    Turbo.disconnectStreamSource(this.source)
    // The browser reconnects an EventSource on its own, forever, including
    // after the element that made it is gone. Closing it is the only way to
    // say that nobody is listening any more.
    this.source.close()
    this.source = null
  }
}
