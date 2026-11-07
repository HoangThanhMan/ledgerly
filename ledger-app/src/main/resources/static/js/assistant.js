// The chat with the assistant service, with past conversations in a sidebar. The assistant can only prepare orders:
// buying is the "Confirm purchase" button on a proposal's ticket. Everything the assistant writes is shown as text,
// never as HTML.

import { api, formatDateTime, formatVnd, problemMessage, requireSignIn, show } from "./api.js";
import { lang, t } from "./i18n.js";
import { posterElement } from "./poster.js";

const ASSISTANT = window.LEDGERLY_ASSISTANT_URL ?? `${location.protocol}//${location.hostname}:8090`;
// Answers to a confirmation that a later press can change: in progress, rate limited, a service down.
const RETRYABLE = new Set([409, 429, 502, 503]);
const DAY = 24 * 60 * 60 * 1000;

let conversationId = null;
// Counts the conversations shown. A reply that arrives after the user opened another one is for a view that is gone.
let view = 0;

export async function startAssistant(root) {
  requireSignIn();
  const form = root.querySelector("[data-form]");
  const input = form.querySelector("textarea");
  const message = root.querySelector("[data-message]");

  root.querySelector("[data-new]").addEventListener("click", () => {
    view += 1;
    setConversation(null);
    root.querySelector("[data-turns]").replaceChildren();
    root.querySelector("[data-welcome]").hidden = false;
    show(message, "");
    markCurrent(root);
    closeHistory(root);
    input.focus();
  });
  setUpSidebar(root);
  showSuggestions(root, (text) => send(root, text));

  form.addEventListener("submit", (event) => {
    event.preventDefault();
    const text = input.value.trim();
    if (text) {
      input.value = "";
      send(root, text);
    }
  });
  // Enter sends, Shift+Enter starts a new line.
  input.addEventListener("keydown", (event) => {
    if (event.key === "Enter" && !event.shiftKey && !event.isComposing) {
      event.preventDefault();
      form.requestSubmit();
    }
  });

  const wanted = new URLSearchParams(location.search).get("c");
  await Promise.all([loadHistory(root), wanted ? openConversation(root, wanted) : Promise.resolve()]);
}

function setConversation(id) {
  conversationId = id;
  const url = new URL(location.href);
  if (id) {
    url.searchParams.set("c", id);
  } else {
    url.searchParams.delete("c");
  }
  history.replaceState(null, "", url);
}

async function call(method, path, body) {
  try {
    return await api(method, ASSISTANT + path, { body });
  } catch {
    return null; // the assistant service could not be reached
  }
}

function failure(response) {
  return response ? problemMessage(response) : t("assistant.offline");
}

// History

async function loadHistory(root) {
  const response = await call("GET", "/assistant/conversations");
  if (response?.status !== 200) {
    return;
  }
  const groups = root.querySelector("[data-groups]");
  const items = response.body.items;
  root.querySelector("[data-no-history]").hidden = items.length > 0;
  const now = Date.now();
  const startOfToday = new Date().setHours(0, 0, 0, 0);
  const buckets = [
    ["assistant.today", (time) => time >= startOfToday],
    ["assistant.week", (time) => time >= now - 7 * DAY],
    ["assistant.older", () => true],
  ];
  const sorted = new Map(buckets.map(([key]) => [key, []]));
  for (const item of items) {
    const time = Date.parse(item.updatedAt);
    sorted.get(buckets.find(([, fits]) => fits(time))[0]).push(item);
  }
  const sections = [];
  for (const [key, list] of sorted) {
    if (list.length === 0) {
      continue;
    }
    const heading = element("h2", "history-group", t(key));
    const ul = element("ul", "history-list");
    for (const item of list) {
      const link = element("a", "", item.title || t("assistant.new"));
      link.href = `assistant.html?c=${encodeURIComponent(item.id)}`;
      link.dataset.id = item.id;
      link.addEventListener("click", (event) => {
        event.preventDefault();
        openConversation(root, item.id);
        closeHistory(root);
      });
      const li = element("li");
      li.append(link);
      ul.append(li);
    }
    sections.push(heading, ul);
  }
  groups.replaceChildren(...sections);
  markCurrent(root);
}

function markCurrent(root) {
  for (const link of root.querySelectorAll(".history-list a")) {
    if (link.dataset.id === conversationId) {
      link.setAttribute("aria-current", "page");
    } else {
      link.removeAttribute("aria-current");
    }
  }
}

async function openConversation(root, id) {
  const shown = ++view;
  const message = root.querySelector("[data-message]");
  const response = await call("GET", `/assistant/conversations/${encodeURIComponent(id)}`);
  if (shown !== view) {
    return; // another conversation was opened while this one loaded
  }
  if (response?.status !== 200) {
    setConversation(null);
    show(message, failure(response), "error");
    return;
  }
  show(message, "");
  setConversation(id);
  const turns = root.querySelector("[data-turns]");
  turns.replaceChildren();
  for (const entry of response.body.messages) {
    addTurn(turns, entry.role, entry.text, entry.proposals);
  }
  root.querySelector("[data-welcome]").hidden = response.body.messages.length > 0;
  markCurrent(root);
  turns.lastElementChild?.scrollIntoView({ block: "end" });
}

// The sidebar. On wide screens it can be collapsed to a narrow rail, and that choice is remembered; on narrow
// screens it is a drawer over the chat.

const SIDEBAR = "assistantSidebar";

function storedSidebar() {
  try {
    return localStorage.getItem(SIDEBAR);
  } catch {
    return null;
  }
}

function setUpSidebar(root) {
  const collapse = root.querySelector("[data-collapse]");
  const open = root.querySelector("[data-open]");
  const apply = (collapsed) => {
    root.classList.toggle("is-collapsed", collapsed);
    collapse.setAttribute("aria-expanded", String(!collapsed));
    collapse.setAttribute("aria-label", t(collapsed ? "assistant.expand" : "assistant.collapse"));
    collapse.title = collapse.getAttribute("aria-label");
  };
  // In the rail the new-conversation button is an icon alone: its name shows on hover.
  root.querySelector("[data-new]").title = t("assistant.new");
  apply(storedSidebar() === "collapsed");
  collapse.addEventListener("click", () => {
    const collapsed = !root.classList.contains("is-collapsed");
    apply(collapsed);
    try {
      localStorage.setItem(SIDEBAR, collapsed ? "collapsed" : "open");
    } catch {
      // Without storage the choice lasts until the page is left.
    }
  });
  open.addEventListener("click", () => {
    root.classList.add("is-history-open");
    open.setAttribute("aria-expanded", "true");
    // The drawer is hidden until the class above takes effect, and a hidden element takes no focus.
    requestAnimationFrame(() => requestAnimationFrame(() => root.querySelector("[data-new]").focus()));
  });
  root.querySelector("[data-close]").addEventListener("click", () => closeHistory(root, true));
  root.querySelector("[data-backdrop]").addEventListener("click", () => closeHistory(root));
  document.addEventListener("keydown", (event) => {
    if (event.key === "Escape" && root.classList.contains("is-history-open")) {
      closeHistory(root, true);
    }
  });
}

/** Closes the drawer of narrow screens; `returnFocus` puts the focus back on the button that opened it. */
function closeHistory(root, returnFocus = false) {
  const open = root.querySelector("[data-open]");
  root.classList.remove("is-history-open");
  open.setAttribute("aria-expanded", "false");
  if (returnFocus) {
    open.focus();
  }
}

// Chat

function showSuggestions(root, onPick) {
  const box = root.querySelector("[data-suggestions]");
  box.replaceChildren(
    ...["assistant.suggest1", "assistant.suggest2", "assistant.suggest3"].map((key) => {
      const chip = element("button", "chip", t(key));
      chip.type = "button";
      chip.addEventListener("click", () => onPick(t(key)));
      return chip;
    }),
  );
}

async function send(root, text) {
  const form = root.querySelector("[data-form]");
  const message = root.querySelector("[data-message]");
  const turns = root.querySelector("[data-turns]");
  const typing = root.querySelector("[data-typing]");
  root.querySelector("[data-welcome]").hidden = true;
  show(message, "");
  addTurn(turns, "user", text, []);
  setBusy(form, true);
  typing.hidden = false;
  typing.scrollIntoView({ block: "end" });
  const sentFrom = view;
  const response = await call("POST", "/assistant/chat", { conversationId, message: text, language: lang() });
  typing.hidden = true;
  setBusy(form, false);
  form.querySelector("textarea").focus();
  if (view !== sentFrom) {
    // The user moved to another conversation meanwhile: the reply is saved there, the history shows it.
    await loadHistory(root);
    return;
  }
  if (response?.status !== 200) {
    show(message, failure(response), "error");
    return;
  }
  const isNew = response.body.conversationId !== conversationId;
  setConversation(response.body.conversationId);
  addTurn(turns, "assistant", response.body.reply, response.body.proposals);
  turns.lastElementChild?.scrollIntoView({ block: "end" });
  if (isNew) {
    await loadHistory(root);
  }
}

function setBusy(form, busy) {
  for (const control of form.elements) {
    control.disabled = busy;
  }
}

function addTurn(turns, role, text, proposals) {
  const item = element("li", `turn turn-${role}`);
  if (role === "assistant") {
    item.append(element("span", "turn-mark", "L"));
    const body = element("div", "turn-body");
    body.append(element("p", "turn-text", text));
    for (const proposal of proposals ?? []) {
      body.append(ticket(proposal));
    }
    item.append(body);
  } else {
    item.append(element("p", "turn-text", text));
  }
  turns.append(item);
}

// A proposal, printed as a ticket: what would be bought and what it costs, and the button that buys it.

function ticket(proposal) {
  const card = element("article", "ticket");
  card.append(posterElement({ id: proposal.eventId, name: proposal.eventName }, "thumb"));
  const body = element("div", "ticket-body");
  body.append(element("h3", "ticket-event", proposal.eventName));
  if (proposal.startsAt) {
    body.append(element("p", "muted small", formatDateTime(proposal.startsAt)));
  }
  body.append(
    row(t("card.line", { name: proposal.className, quantity: proposal.quantity }), t("card.unit", { price: formatVnd(String(proposal.unitPrice)) })),
    row(
      t("card.wallet", { tail: proposal.walletId.slice(-6) }),
      proposal.balanceAfter === undefined ? "" : t("card.after", { amount: formatVnd(String(proposal.balanceAfter)) }),
    ),
  );
  const total = element("div", "ticket-total");
  total.append(element("span", "", t("card.total")), element("strong", "amount", formatVnd(String(proposal.total))));
  body.append(total);

  const button = element("button", "button ticket-confirm", t("card.confirm"));
  button.type = "button";
  const status = element("p", "ticket-status muted small", t("card.note"));
  status.setAttribute("role", "status");
  body.append(button, status);
  card.append(body);

  const state = (name, text, kind = "") => {
    card.dataset.state = name;
    status.className = `ticket-status small ${kind}`;
    status.textContent = text;
  };
  if (proposal.status === "confirmed") {
    bought(card, button, status);
  } else if (proposal.status === "refused") {
    button.disabled = true;
    state("refused", `${problemMessage({ status: 422, body: proposal.problem, headers: null })} ${t("card.askAgain")}`, "error");
  } else if (proposal.status === "expired") {
    button.disabled = true;
    state("expired", `${t("card.expired")} ${t("card.askAgain")}`, "muted");
  }

  button.addEventListener("click", async () => {
    button.disabled = true;
    button.textContent = t("card.buying");
    const response = await call("POST", `/assistant/proposals/${encodeURIComponent(proposal.id)}/confirm`);
    button.textContent = t("card.confirm");
    if (response && (response.status === 200 || response.status === 201)) {
      bought(card, button, status);
      return;
    }
    if (!response || RETRYABLE.has(response.status)) {
      button.disabled = false;
      state("open", `${failure(response)} ${t("card.retry")}`, "error");
      return;
    }
    // A refusal is kept under the proposal's key, so pressing again would only show it again.
    state(response.status === 404 ? "expired" : "refused", `${failure(response)} ${t("card.askAgain")}`, "error");
  });
  return card;
}

function bought(card, button, status) {
  card.dataset.state = "confirmed";
  button.remove();
  status.className = "ticket-status small ok";
  status.textContent = `${t("card.bought")} `;
  const link = element("a", "", t("card.viewTickets"));
  link.href = "tickets.html";
  status.append(link);
}

function row(left, right) {
  const line = element("div", "ticket-row");
  line.append(element("span", "", left), element("span", "", right));
  return line;
}

function element(tag, className = "", text) {
  const node = document.createElement(tag);
  if (className) {
    node.className = className;
  }
  if (text !== undefined) {
    node.textContent = text;
  }
  return node;
}
