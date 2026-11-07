// Events have no pictures, so each gets a poster: its name set large on a violet of its own. The violet comes from
// the event's id, so an event looks the same on every page.

const HUE_FROM = 255;
const HUE_TO = 300;

function hash(id, seed = 2166136261) {
  let value = seed;
  for (const char of String(id)) {
    value ^= char.codePointAt(0);
    value = Math.imul(value, 16777619) >>> 0;
  }
  return value;
}

/** A hue in [255, 300] that depends only on the id. */
export function hueFor(id) {
  return HUE_FROM + (hash(id) % (HUE_TO - HUE_FROM + 1));
}

/** Where the poster's light falls, in percent: x in [10, 90], y in [0, 40]. Depends only on the id. */
export function spotFor(id) {
  const value = hash(id, 374761393);
  return { x: 10 + (value % 81), y: (value >>> 8) % 41 };
}

/**
 * A poster element. `variant` sets its shape: "feature" (16:8, home page), "banner" (16:6, event page), "card" (16:9,
 * grids) or "thumb" (small, lists and chat). The name is set as text, never as HTML.
 */
export function posterElement(event, variant = "card") {
  const poster = document.createElement("div");
  poster.className = `poster poster-${variant}`;
  const spot = spotFor(event.id);
  poster.style.setProperty("--hue", String(hueFor(event.id)));
  poster.style.setProperty("--spot-x", `${spot.x}%`);
  poster.style.setProperty("--spot-y", `${spot.y}%`);
  poster.setAttribute("aria-hidden", "true");
  const name = document.createElement("span");
  name.className = "poster-name";
  name.textContent = event.name;
  poster.append(name);
  return poster;
}
