// One event: its ticket classes with the tickets left, kept current over STOMP, and the order form.

import { api, formatDateTime, formatVnd, newKey, problemMessage, show, token } from "./api.js";
import { escapeHtml } from "./auth.js";

const LOW = 0.1;

/** Ticket class id → its stub, and what the page knows about the class. */
const classes = new Map();

let lastOrder = null;

export async function showEvent(root) {
  const id = new URLSearchParams(location.search).get("id");
  const message = root.querySelector("[data-page-message]");
  const response = id ? await api("GET", `/v1/events/${encodeURIComponent(id)}`) : { status: 404, body: null };
  if (response.status !== 200) {
    root.querySelector("[data-event]").hidden = true;
    show(message, response.status === 404 ? "Không tìm thấy sự kiện này." : problemMessage(response), "error");
    return;
  }
  const event = response.body;
  document.title = `${event.name} – TicketBox`;
  root.querySelector("[data-name]").textContent = event.name;
  root.querySelector("[data-starts-at]").textContent = formatDateTime(event.startsAt);
  root.querySelector("[data-starts-at]").dateTime = event.startsAt;

  const stubs = root.querySelector("[data-stubs]");
  stubs.innerHTML = event.ticketClasses.map(stub).join("");
  for (const ticketClass of event.ticketClasses) {
    classes.set(ticketClass.id, {
      ...ticketClass,
      element: stubs.querySelector(`[data-class="${ticketClass.id}"]`),
    });
    render(ticketClass.id, ticketClass.remaining, false);
  }

  watch(event.id, root.querySelector("[data-live]"));
  await setUpOrderForm(root.querySelector("[data-order]"));
}

function stub(ticketClass) {
  return `
    <li class="stub" data-class="${ticketClass.id}">
      <div class="stub-main">
        <h3 class="stub-name">${escapeHtml(ticketClass.name)}</h3>
        <span class="stub-price">${formatVnd(ticketClass.price)} <span class="muted small">một vé</span></span>
      </div>
      <div class="stub-tear" aria-live="polite">
        <span class="stub-count"></span>
        <span class="stub-of">còn lại / ${ticketClass.totalQty}</span>
      </div>
    </li>`;
}

/** Shows the tickets left in a class. `stamped` marks a change that came from someone else's purchase. */
function render(id, remaining, stamped) {
  const ticketClass = classes.get(id);
  ticketClass.remaining = remaining;
  const element = ticketClass.element;
  const count = element.querySelector(".stub-count");
  count.textContent = remaining === 0 ? "Hết vé" : String(remaining);
  count.classList.toggle("is-long", remaining >= 1000);
  element.classList.toggle("is-sold-out", remaining === 0);
  element.classList.toggle("is-low", remaining > 0 && remaining <= ticketClass.totalQty * LOW);
  if (stamped) {
    count.classList.remove("is-stamped");
    void count.offsetWidth; // restart the animation
    count.classList.add("is-stamped");
  }
  const option = document.querySelector(`option[value="${id}"]`);
  if (option) {
    option.disabled = remaining === 0;
    option.textContent = optionLabel(ticketClass);
  }
}

/**
 * Subscribes to every class of the event. Tickets are only ever taken, never given back, so the smaller of two counts
 * is the newer one: messages of two purchases made at the same moment can arrive in either order.
 */
function watch(eventId, indicator) {
  const scheme = location.protocol === "https:" ? "wss:" : "ws:";
  const client = new StompJs.Client({ brokerURL: `${scheme}//${location.host}/ws`, reconnectDelay: 3000 });
  let connectedBefore = false;
  client.onConnect = async () => {
    indicator.classList.add("is-on");
    indicator.textContent = "Số vé cập nhật trực tiếp";
    for (const id of classes.keys()) {
      client.subscribe(`/topic/ticket-classes/${id}`, (frame) => {
        const { remaining } = JSON.parse(frame.body);
        if (remaining < classes.get(id).remaining) {
          render(id, remaining, true);
        }
      });
    }
    if (connectedBefore) {
      // Purchases made while the socket was down were not heard: read the counts again.
      const response = await api("GET", `/v1/events/${encodeURIComponent(eventId)}`);
      if (response.status === 200) {
        for (const ticketClass of response.body.ticketClasses) {
          render(ticketClass.id, ticketClass.remaining, false);
        }
      }
    }
    connectedBefore = true;
  };
  client.onWebSocketClose = () => {
    indicator.classList.remove("is-on");
    indicator.textContent = "Mất kết nối, đang thử lại…";
  };
  client.activate();
}

function optionLabel(ticketClass) {
  const left = ticketClass.remaining === 0 ? "hết vé" : `còn ${ticketClass.remaining}`;
  return `${ticketClass.name}, ${formatVnd(ticketClass.price)} (${left})`;
}

async function setUpOrderForm(panel) {
  if (!token()) {
    panel.innerHTML = `
      <h2>Mua vé</h2>
      <p>Đăng nhập để mua vé bằng ví của bạn.</p>
      <div class="actions"><a class="button" href="login.html?next=${encodeURIComponent(
        location.pathname + location.search,
      )}">Đăng nhập</a></div>`;
    return;
  }
  const form = panel.querySelector("form");
  const message = panel.querySelector("[data-message]");
  const resend = panel.querySelector("[data-resend]");

  form.ticketClass.innerHTML = [...classes.values()]
    .map((c) => `<option value="${c.id}"${c.remaining === 0 ? " disabled" : ""}>${escapeHtml(optionLabel(c))}</option>`)
    .join("");
  const firstOnSale = [...classes.values()].find((c) => c.remaining > 0);
  if (firstOnSale) {
    form.ticketClass.value = firstOnSale.id;
  }
  if (!(await loadWallets(form.wallet, panel))) {
    return;
  }

  form.addEventListener("submit", async (event) => {
    event.preventDefault();
    lastOrder = {
      key: newKey(),
      body: {
        ticketClassId: form.ticketClass.value,
        walletId: form.wallet.value,
        quantity: Number(form.quantity.value),
      },
    };
    await send(form, message, resend);
  });
  resend.addEventListener("click", () => send(form, message, resend));
}

/** Sends the last order, with its key. A second send of the same order cannot buy twice. */
async function send(form, message, resend) {
  const buttons = [form.querySelector("button[type=submit]"), resend];
  buttons.forEach((button) => (button.disabled = true));
  show(message, "Đang gửi…");
  try {
    const response = await api("POST", "/v1/orders", { body: lastOrder.body, idempotencyKey: lastOrder.key });
    const replayed = response.headers.get("Idempotent-Replayed") === "true";
    const prefix = replayed ? "Đã trả lại kết quả cũ, không mua thêm. " : "";
    if (response.status === 201) {
      const order = response.body;
      const ticketClass = classes.get(order.ticketClassId);
      show(
        message,
        `${prefix}Đã mua ${order.quantity} vé ${ticketClass ? ticketClass.name : ""}, trả ${formatVnd(order.amount)}.`,
        "ok",
      );
    } else {
      show(message, prefix + problemMessage(response), "error");
    }
    await loadWallets(form.wallet);
  } catch {
    show(message, "Không gửi được: mất kết nối tới máy chủ. Bấm “Gửi lại (cùng key)” để thử lại an toàn.", "error");
  } finally {
    buttons.forEach((button) => (button.disabled = false));
    resend.hidden = false;
  }
}

/** Fills the wallet choice with the user's wallets and balances. False if there is none to pay with. */
async function loadWallets(select, panel) {
  const response = await api("GET", "/v1/wallets");
  if (response.status !== 200) {
    return false;
  }
  const wallets = response.body.items;
  if (wallets.length === 0 && panel) {
    panel.querySelector("form").hidden = true;
    show(panel.querySelector("[data-message]"), "");
    panel.querySelector("[data-no-wallet]").hidden = false;
    return false;
  }
  const chosen = select.value;
  select.innerHTML = wallets
    .map((w) => `<option value="${w.id}">Ví …${w.id.slice(-4)}: ${formatVnd(w.balance)}</option>`)
    .join("");
  if (chosen) {
    select.value = chosen;
  }
  return true;
}
