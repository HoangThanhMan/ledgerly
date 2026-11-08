// My ticket orders, newest first. An order names its event and class by id, so their names are read from the events.

import { api, formatDateTime, formatVnd, problemMessage, requireSignIn, show } from "./api.js";
import { locale, t } from "./i18n.js";
import { posterElement } from "./poster.js";

const SIZE = 20;

export async function showTickets(root) {
  requireSignIn();
  const message = root.querySelector("[data-message]");
  const page = Math.max(0, Number(new URLSearchParams(location.search).get("page")) || 0);
  const response = await api("GET", `/v1/orders?page=${page}&size=${SIZE}`);
  if (response.status !== 200) {
    show(message, problemMessage(response), "error");
    return;
  }
  const { items, totalItems } = response.body;
  if (items.length === 0) {
    root.querySelector("[data-empty]").hidden = false;
    return;
  }
  const events = await eventsOf(items);
  const list = root.querySelector("[data-orders]");
  list.replaceChildren(...items.map((order) => row(order, events.get(order.eventId))));
  list.hidden = false;

  const pages = Math.ceil(totalItems / SIZE);
  if (pages > 1) {
    const pager = root.querySelector("[data-pager]");
    pager.hidden = false;
    pager.querySelector("[data-where]").textContent = t("events.page", { page: page + 1, pages });
    link(pager.querySelector("[data-previous]"), page > 0 ? page - 1 : null);
    link(pager.querySelector("[data-next]"), page + 1 < pages ? page + 1 : null);
  }
}

/** Event id → event, read once for each event the orders name. */
async function eventsOf(orders) {
  const ids = [...new Set(orders.map((order) => order.eventId))];
  const responses = await Promise.all(ids.map((id) => api("GET", `/v1/events/${id}`)));
  return new Map(ids.map((id, i) => [id, responses[i].status === 200 ? responses[i].body : null]));
}

function element(tag, className, text) {
  const node = document.createElement(tag);
  if (className) {
    node.className = className;
  }
  if (text !== undefined) {
    node.textContent = text;
  }
  return node;
}

/** One order: the event's poster, what was bought, what it cost and when. */
function row(order, event) {
  const ticketClass = event?.ticketClasses.find((c) => c.id === order.ticketClassId);
  const item = element("li", "order");
  item.append(posterElement(event ?? { id: order.eventId, name: "" }, "thumb"));

  const what = element("div", "order-what");
  if (event) {
    const name = element("a", "order-event", event.name);
    name.href = `event.html?id=${encodeURIComponent(event.id)}`;
    what.append(name, element("span", "muted small", formatDateTime(event.startsAt)));
  } else {
    what.append(element("span", "muted", t("tickets.gone")));
  }
  what.append(element("span", "order-line", t("tickets.line", { quantity: order.quantity, name: ticketClass?.name ?? "" })));

  const paid = element("div", "order-paid");
  const when = new Intl.DateTimeFormat(locale(), { dateStyle: "short", timeStyle: "short" }).format(new Date(order.createdAt));
  paid.append(element("strong", "amount", formatVnd(order.amount)), element("span", "muted small", t("tickets.boughtAt", { time: when })));
  item.append(what, paid);
  return item;
}

function link(anchor, page) {
  if (page === null) {
    anchor.removeAttribute("href");
    anchor.classList.add("is-disabled");
  } else {
    anchor.href = `tickets.html?page=${page}`;
  }
}
