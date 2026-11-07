// The home page: the soonest event, large, then the next ones as posters.

import { api, formatDateTime, formatVnd, problemMessage, show } from "./api.js";
import { eventCard } from "./events.js";
import { locale, t } from "./i18n.js";
import { posterElement } from "./poster.js";

const NEXT = 6;

export async function showHome(root) {
  const message = root.querySelector("[data-message]");
  const response = await api("GET", `/v1/events?page=0&size=${NEXT + 1}`);
  if (response.status !== 200) {
    show(message, problemMessage(response), "error");
    return;
  }
  const [first, ...next] = response.body.items;
  if (!first) {
    root.querySelector("[data-empty]").hidden = false;
    return;
  }
  await showFeature(root.querySelector("[data-feature]"), first);
  if (next.length > 0) {
    root.querySelector("[data-grid]").replaceChildren(...next.map(eventCard));
    root.querySelector("[data-upcoming]").hidden = false;
  }
}

async function showFeature(section, event) {
  const href = `event.html?id=${encodeURIComponent(event.id)}`;
  const posterLink = section.querySelector("[data-poster]");
  posterLink.href = href;
  posterLink.replaceChildren(posterElement(event, "feature"));
  section.querySelector("[data-name]").textContent = event.name;
  const date = section.querySelector("[data-date]");
  date.dateTime = event.startsAt;
  date.textContent = formatDateTime(event.startsAt);
  section.querySelector("[data-price]").textContent = formatVnd(event.minPrice);
  section.querySelector("[data-buy]").href = href;
  section.hidden = false;

  // The tickets left are not in the list: read the event itself. The section is useful without them.
  const details = await api("GET", `/v1/events/${encodeURIComponent(event.id)}`);
  if (details.status === 200) {
    const left = details.body.ticketClasses.reduce((sum, ticketClass) => sum + ticketClass.remaining, 0);
    section.querySelector("[data-left]").textContent =
      left === 0 ? t("home.soldOut") : t("home.left", { count: left.toLocaleString(locale()) });
  }
}
