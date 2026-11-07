// Events have no pictures, so each gets a poster: its name set large on a violet of its own. The violet comes from
// the event's id, so an event looks the same on every page.

const HUE_FROM = 255;
const HUE_TO = 300;

/** A hue in [255, 300] that depends only on the id. */
export function hueFor(id) {
  let hash = 2166136261;
  for (const char of String(id)) {
    hash ^= char.codePointAt(0);
    hash = Math.imul(hash, 16777619) >>> 0;
  }
  return HUE_FROM + (hash % (HUE_TO - HUE_FROM + 1));
}

/**
 * A poster element. `variant` sets its shape: "feature" (16:8, home page), "banner" (16:6, event page), "card" (16:9,
 * grids) or "thumb" (small, lists and chat). The name is set as text, never as HTML.
 */
export function posterElement(event, variant = "card") {
  const poster = document.createElement("div");
  poster.className = `poster poster-${variant}`;
  poster.style.setProperty("--hue", String(hueFor(event.id)));
  poster.setAttribute("aria-hidden", "true");
  const name = document.createElement("span");
  name.className = "poster-name";
  name.textContent = event.name;
  poster.append(name);
  return poster;
}
