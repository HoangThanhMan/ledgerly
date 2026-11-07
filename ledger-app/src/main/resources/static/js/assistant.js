// The chat with the assistant service. The assistant can only prepare orders: buying is the "Xác nhận mua" button,
// which confirms one proposal. Everything the assistant writes is shown as text, never as HTML.

import { api, formatVnd, problemMessage, requireSignIn, show } from "./api.js";

const ASSISTANT = window.LEDGERLY_ASSISTANT_URL ?? `${location.protocol}//${location.hostname}:8090`;
// Answers to a confirmation that a later press can change: in progress, rate limited, a service down.
const RETRYABLE = new Set([409, 429, 502, 503]);

export function startAssistant(main) {
  requireSignIn();
  const log = main.querySelector("[data-log]");
  const form = main.querySelector("[data-form]");
  const input = form.querySelector("input");
  const message = main.querySelector("[data-message]");
  let conversationId = null;

  form.addEventListener("submit", async (event) => {
    event.preventDefault();
    const text = input.value.trim();
    if (!text) {
      return;
    }
    show(message, "");
    addBubble(log, "me", text);
    input.value = "";
    setBusy(form, true);
    try {
      const response = await api("POST", `${ASSISTANT}/assistant/chat`, { body: { conversationId, message: text } });
      if (response.status !== 200) {
        show(message, problemMessage(response), "error");
        return;
      }
      conversationId = response.body.conversationId;
      addBubble(log, "assistant", response.body.reply);
      for (const proposal of response.body.proposals) {
        log.append(proposalCard(proposal));
      }
    } catch {
      show(message, "Không kết nối được tới trợ lý. Kiểm tra rằng dịch vụ trợ lý đang chạy.", "error");
    } finally {
      setBusy(form, false);
      input.focus();
      log.lastElementChild?.scrollIntoView({ block: "nearest" });
    }
  });
}

function addBubble(log, who, text) {
  const item = document.createElement("li");
  item.className = `bubble ${who}`;
  item.textContent = text;
  log.append(item);
}

function proposalCard(proposal) {
  const card = document.createElement("li");
  card.className = "proposal panel";
  const title = document.createElement("p");
  title.className = "proposal-title";
  title.textContent = proposal.eventName;
  const details = document.createElement("p");
  details.textContent =
    `${proposal.quantity} vé ${proposal.className} × ${formatVnd(String(proposal.unitPrice))} = ` +
    `${formatVnd(String(proposal.total))}, trả từ ví …${proposal.walletId.slice(-6)}`;
  const actions = document.createElement("div");
  actions.className = "actions";
  const button = document.createElement("button");
  button.type = "button";
  button.className = "button";
  button.textContent = "Xác nhận mua";
  const status = document.createElement("p");
  status.setAttribute("role", "status");
  status.className = "notice";
  actions.append(button);
  card.append(title, details, actions, status);

  button.addEventListener("click", async () => {
    button.disabled = true;
    show(status, "Đang mua…");
    try {
      const response = await api("POST", `${ASSISTANT}/assistant/proposals/${encodeURIComponent(proposal.id)}/confirm`);
      if (response.status === 201 || response.status === 200) {
        show(status, "Đã mua xong.", "ok");
        const link = document.createElement("a");
        link.href = "tickets.html";
        link.textContent = "Xem Vé của tôi";
        status.append(" ", link);
        return;
      }
      show(status, problemMessage(response), "error");
      // Ledgerly keeps a refusal (not enough money, sold out...) under the proposal's Idempotency-Key, so pressing
      // again would only replay it. Only a busy or unreachable service is worth another press.
      button.disabled = !RETRYABLE.has(response.status);
      if (button.disabled) {
        status.append(" Hãy nhờ trợ lý chuẩn bị một đề xuất mới.");
      }
    } catch {
      show(status, "Không kết nối được tới trợ lý. Bấm lại để thử lần nữa: bạn sẽ không bị trừ tiền hai lần.", "error");
      button.disabled = false;
    }
  });
  return card;
}

function setBusy(form, busy) {
  for (const control of form.elements) {
    control.disabled = busy;
  }
}
