// The upcoming events as a grid of posters, a page at a time, soonest first. With ?q= it shows the events whose
// name matches instead.

import { api, formatDateTime, formatVnd, problemMessage, show } from "./api.js";
import { t } from "./i18n.js";
import { posterElement } from "./poster.js";
import { matches } from "./text.js";

const SIZE = 12;
// The most GET /v1/events gives in one page: a search looks through this many upcoming events.
const SEARCHED = 50;

/** One event of a grid: its poster, name, date and cheapest price, linking to the event page. */
export function eventCard(event) {
  const item = document.createElement("li");
  const link = document.createElement("a");
  link.className = "event-card";
  link.href = `event.html?id=${encodeURIComponent(event.id)}`;
  const name = document.createElement("span");
  name.className = "event-card-name";
  name.textContent = event.name;
  const date = document.createElement("time");
  date.className = "muted small";
  date.dateTime = event.startsAt;
  date.textContent = formatDateTime(event.startsAt);
  const price = document.createElement("span");
  price.className = "event-card-price";
  price.textContent = t("card.from", { price: formatVnd(event.minPrice) });
  link.append(posterElement(event, "card"), name, date, price);
  item.append(link);
  return item;
}

export async function showEvents(root) {
  const params = new URLSearchParams(location.search);
  const query = (params.get("q") || "").trim();
  const page = Math.max(0, Number(params.get("page")) || 0);
  const heading = root.querySelector("[data-heading]");
  const subtitle = root.querySelector("[data-subtitle]");
  const grid = root.querySelector("[data-grid]");
  const pager = root.querySelector("[data-pager]");
  const message = root.querySelector("[data-message]");

  const response = await api("GET", `/v1/events?page=${query ? 0 : page}&size=${query ? SEARCHED : SIZE}`);
  if (response.status !== 200) {
    show(message, problemMessage(response), "error");
    pager.hidden = true;
    return;
  }

  if (query) {
    const found = response.body.items.filter((event) => matches(event, query));
    heading.textContent = t("events.results", { count: found.length, q: query });
    subtitle.hidden = true;
    pager.hidden = true;
    if (found.length === 0) {
      heading.textContent = t("events.noResults", { q: query });
    }
    grid.replaceChildren(...found.map(eventCard));
    return;
  }

  const { items, totalItems } = response.body;
  if (items.length === 0) {
    show(message, t(page === 0 ? "events.none" : "events.emptyPage"));
  }
  grid.replaceChildren(...items.map(eventCard));
  const pages = Math.max(1, Math.ceil(totalItems / SIZE));
  pager.hidden = pages === 1;
  pager.querySelector("[data-where]").textContent = t("events.page", { page: page + 1, pages });
  link(pager.querySelector("[data-previous]"), page > 0 ? page - 1 : null);
  link(pager.querySelector("[data-next]"), page + 1 < pages ? page + 1 : null);
}

function link(anchor, page) {
  if (page === null) {
    anchor.removeAttribute("href");
    anchor.setAttribute("aria-disabled", "true");
    anchor.classList.add("is-disabled");
  } else {
    anchor.href = `events.html?page=${page}`;
    anchor.removeAttribute("aria-disabled");
    anchor.classList.remove("is-disabled");
  }
}
