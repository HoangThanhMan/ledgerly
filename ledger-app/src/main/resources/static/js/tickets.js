// My ticket orders, newest first. An order names its event and class by id, so their names are read from the events.

import { api, formatVnd, problemMessage, requireSignIn, show } from "./api.js";
import { escapeHtml } from "./auth.js";

const SIZE = 20;

const when = new Intl.DateTimeFormat("vi-VN", { dateStyle: "short", timeStyle: "short" });

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
  root.querySelector("[data-orders]").hidden = false;
  root.querySelector("[data-rows]").innerHTML = items.map((order) => row(order, events.get(order.eventId))).join("");

  const pages = Math.ceil(totalItems / SIZE);
  if (pages > 1) {
    const pager = root.querySelector("[data-pager]");
    pager.hidden = false;
    pager.querySelector("[data-where]").textContent = `Trang ${page + 1} / ${pages}`;
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

function row(order, event) {
  const ticketClass = event?.ticketClasses.find((c) => c.id === order.ticketClassId);
  const eventCell = event
    ? `<a href="event.html?id=${event.id}">${escapeHtml(event.name)}</a>`
    : '<span class="muted">Sự kiện không còn</span>';
  return `
    <tr>
      <td>${eventCell}</td>
      <td>${ticketClass ? escapeHtml(ticketClass.name) : ""}</td>
      <td class="num">${order.quantity}</td>
      <td class="num">${formatVnd(order.amount)}</td>
      <td>${when.format(new Date(order.createdAt))}</td>
    </tr>`;
}

function link(anchor, page) {
  if (page === null) {
    anchor.removeAttribute("href");
    anchor.classList.add("is-disabled");
  } else {
    anchor.href = `tickets.html?page=${page}`;
  }
}
