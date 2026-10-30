// The upcoming events, a page at a time, soonest first.

import { api, formatVnd, problemMessage, show } from "./api.js";
import { escapeHtml } from "./auth.js";

const SIZE = 10;

const day = new Intl.DateTimeFormat("vi-VN", { day: "2-digit" });
const monthYear = new Intl.DateTimeFormat("vi-VN", { month: "short", year: "numeric" });
const weekdayTime = new Intl.DateTimeFormat("vi-VN", { weekday: "long", hour: "2-digit", minute: "2-digit" });

export async function showEvents(list, pager, message) {
  const page = Math.max(0, Number(new URLSearchParams(location.search).get("page")) || 0);
  const response = await api("GET", `/v1/events?page=${page}&size=${SIZE}`);
  if (response.status !== 200) {
    show(message, problemMessage(response), "error");
    return;
  }
  const { items, totalItems } = response.body;
  if (items.length === 0) {
    show(message, page === 0 ? "Chưa có sự kiện nào sắp diễn ra." : "Trang này không còn sự kiện nào.");
  }
  list.innerHTML = items.map(row).join("");

  const pages = Math.max(1, Math.ceil(totalItems / SIZE));
  pager.querySelector("[data-where]").textContent = `Trang ${page + 1} / ${pages}`;
  link(pager.querySelector("[data-previous]"), page > 0 ? page - 1 : null);
  link(pager.querySelector("[data-next]"), page + 1 < pages ? page + 1 : null);
}

function row(event) {
  const startsAt = new Date(event.startsAt);
  return `
    <li>
      <a href="event.html?id=${encodeURIComponent(event.id)}">
        <time class="listing-date" datetime="${escapeHtml(event.startsAt)}">
          <span class="listing-day">${day.format(startsAt)}</span>
          <span class="listing-month">${monthYear.format(startsAt)}</span>
        </time>
        <span>
          <span class="listing-name">${escapeHtml(event.name)}</span><br>
          <span class="muted small">${weekdayTime.format(startsAt)}</span>
        </span>
        <span class="listing-price">
          <span class="muted small">Giá từ</span>
          <strong>${formatVnd(event.minPrice)}</strong>
        </span>
      </a>
    </li>`;
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
