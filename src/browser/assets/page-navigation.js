// The page-local outline and the current package row.
//
// The outline lives inside the content frame, while this controller lives on
// the shell. Rebuilding it after a frame load keeps both navigations in step
// without copying the symbol index into the page.

import { Controller } from "@hotwired/stimulus"

export default class PageNavigation extends Controller {
  static targets = ["outline"]

  connect() {
    this.element.classList.add("page-navigation--ready")
    this.frameLoaded = () => this.refresh()
    this.pageLoaded = () => this.refresh()
    this.historyChanged = () => this.refresh()
    this.hashChanged = () => this.updateOutline()
    this.windowLoaded = () => this.scheduleHashSync()
    this.pageShown = () => this.scheduleHashSync()
    this.outsideClicked = event => {
      if (!event.target.closest?.(".page-nav")) this.closeOutline()
    }
    this.keyPressed = event => {
      if (event.key !== "Escape" || !this.hasOutlineTarget) return
      const title = this.outlineTarget.querySelector(".page-nav-title")
      if (!this.outlineTarget.classList.contains("page-nav--open")) return
      this.closeOutline()
      title?.focus()
    }
    document.addEventListener("turbo:frame-load", this.frameLoaded)
    document.addEventListener("turbo:load", this.pageLoaded)
    window.addEventListener("popstate", this.historyChanged)
    window.addEventListener("hashchange", this.hashChanged)
    window.addEventListener("load", this.windowLoaded)
    window.addEventListener("pageshow", this.pageShown)
    document.addEventListener("click", this.outsideClicked)
    document.addEventListener("keydown", this.keyPressed)
    this.refresh()
    this.scheduleHashSync()
  }

  disconnect() {
    this.element.classList.remove("page-navigation--ready")
    document.removeEventListener("turbo:frame-load", this.frameLoaded)
    document.removeEventListener("turbo:load", this.pageLoaded)
    window.removeEventListener("popstate", this.historyChanged)
    window.removeEventListener("hashchange", this.hashChanged)
    window.removeEventListener("load", this.windowLoaded)
    window.removeEventListener("pageshow", this.pageShown)
    document.removeEventListener("click", this.outsideClicked)
    document.removeEventListener("keydown", this.keyPressed)
    if (this.hashFrame) cancelAnimationFrame(this.hashFrame)
  }

  refresh() {
    this.markSidebar()
    this.buildOutline()
  }

  // Keep the narrow header quiet until somebody asks to search, as VitePress
  // does. The input remains an ordinary form control; the icon only reveals it
  // and gives it focus, so keyboard and no-script search keep their usual path.
  toggleSearch(event) {
    if (!window.matchMedia("(max-width: 59.999rem)").matches) return
    const form = event.target.closest("form.search")
    const input = form?.querySelector("input[name='q']")
    if (!form || !input || form.classList.contains("search--open")) return
    event.preventDefault()
    form.classList.add("search--open")
    input.focus()
  }

  closeOutline() {
    if (!this.hasOutlineTarget) return
    this.outlineTarget.classList.remove("page-nav--open")
    this.outlineTarget.querySelector(".page-nav-title")?.setAttribute("aria-expanded", "false")
  }

  markSidebar() {
    const path = window.location.pathname
    const rows = [...this.element.querySelectorAll(".ui-sidebar a.ui-row[href]")]
    const matches = rows
      .map(row => ({ row, path: new URL(row.href, window.location.href).pathname }))
      .filter(({ path: target }) => this.matches(path, target))
      .sort((left, right) => right.path.length - left.path.length)
    const current = matches[0]
    for (const row of rows) {
      const active = row === current?.row
      row.classList.toggle("ui-row--current", active)
      if (active) row.setAttribute("aria-current", path === current.path ? "page" : "location")
      else row.removeAttribute("aria-current")
    }
    current?.row.closest("details")?.setAttribute("open", "")
  }

  matches(path, target) {
    return path === target || path.startsWith(`${target}.`) || path.startsWith(`${target}/`)
  }

  buildOutline() {
    if (!this.hasOutlineTarget) return
    const body = this.element.querySelector("#content .page-body")
    const outline = this.outlineTarget
    outline.replaceChildren()
    outline.classList.remove("page-nav--open")
    if (!body) {
      outline.hidden = true
      return
    }

    const headings = [...body.querySelectorAll("h2, h3, h4")]
      .filter(heading => !heading.closest("li"))
    const used = new Set([...body.querySelectorAll("[id]")].map(element => element.id))
    for (const heading of headings) {
      if (!heading.id) heading.id = this.idFor(heading.textContent, used)
      used.add(heading.id)
    }

    const candidates = new Set([
      ...headings,
      ...body.querySelectorAll("li[id]"),
      ...body.querySelectorAll("[itemprop=\"contains\"] a")
    ])
    const nodes = [...body.querySelectorAll("h2, h3, h4, li[id], [itemprop=\"contains\"] a")]
      .filter(node => candidates.has(node))
    const entries = nodes.length ? [{ href: "#", label: "Return to top", local: true }] : []
    for (const node of nodes) {
      const container = node.matches('[itemprop="contains"] a')
      const id = container ? "" : node.id
      const href = container ? node.getAttribute("href") : `#${id}`
      if (!href || entries.some(entry => entry.href === href)) continue
      entries.push({ href, label: this.label(node), local: !container })
    }
    if (!entries.length) {
      outline.hidden = true
      return
    }

    const list = document.createElement("ol")
    for (const entry of entries) {
      const item = document.createElement("li")
      const link = document.createElement("a")
      link.href = entry.href
      link.textContent = entry.label
      if (entry.local) link.dataset.turbo = "false"
      else link.dataset.turboFrame = "_top"
      item.append(link)
      list.append(item)
    }
    const title = document.createElement("button")
    title.type = "button"
    title.className = "page-nav-title"
    title.textContent = "On this page"
    title.setAttribute("aria-expanded", "false")
    title.setAttribute("aria-controls", "page-nav-links")
    title.addEventListener("click", () => {
      if (!window.matchMedia("(max-width: 79.999rem)").matches) return
      const open = outline.classList.toggle("page-nav--open")
      title.setAttribute("aria-expanded", open ? "true" : "false")
    })
    const heading = document.createElement("span")
    heading.className = "page-nav-heading"
    heading.textContent = "On this page"
    list.id = "page-nav-links"
    list.addEventListener("click", event => {
      if (!event.target.closest("a")) return
      this.closeOutline()
    })
    outline.append(title, heading, list)
    outline.hidden = false
    this.outlineLinks = [...list.querySelectorAll("a")]
    this.updateOutline()
    this.scheduleHashSync()
  }

  scheduleHashSync() {
    if (!("requestAnimationFrame" in window)) return this.updateOutline()
    if (this.hashFrame) cancelAnimationFrame(this.hashFrame)
    this.hashFrame = requestAnimationFrame(() => {
      this.hashFrame = null
      this.updateOutline()
    })
  }

  label(node) {
    if (node.matches("li[id]")) {
      const signature = node.querySelector('[itemprop="signature"]')
      return (signature ?? node).textContent.trim().replace(/\s+/g, " ")
    }
    return node.textContent.trim().replace(/\s+/g, " ")
  }

  idFor(text, used) {
    const base = text.toLowerCase().trim().replace(/[^a-z0-9]+/g, "-").replace(/^-|-$/g, "") || "section"
    let id = base
    let number = 2
    while (used.has(id)) id = `${base}-${number++}`
    return id
  }

  updateOutline() {
    if (!this.outlineLinks) return
    const hash = decodeURIComponent(window.location.hash.slice(1))
    if (!hash) {
      for (const link of this.outlineLinks) link.removeAttribute("aria-current")
      return
    }
    this.setCurrent(hash)
  }

  setCurrent(id) {
    for (const link of this.outlineLinks ?? []) {
      if (link.getAttribute("href") === `#${id}`) link.setAttribute("aria-current", "location")
      else link.removeAttribute("aria-current")
    }
  }
}
