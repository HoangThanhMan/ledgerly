// My wallet: open one, see the balance and every change to it, and deposit demo money.

import { api, formatVnd, newKey, problemMessage, requireSignIn, show } from "./api.js";

const PAGE = 20;
const AMOUNT = /^[1-9][0-9]{0,17}$/;

const when = new Intl.DateTimeFormat("vi-VN", { dateStyle: "short", timeStyle: "short" });

let wallet = null;
let nextCursor = null;

export async function showWallet(root) {
  requireSignIn();
  const message = root.querySelector("[data-message]");
  const response = await api("GET", "/v1/wallets");
  if (response.status !== 200) {
    show(message, problemMessage(response), "error");
    return;
  }
  const wallets = response.body.items;
  if (wallets.length === 0) {
    setUpOpening(root);
    return;
  }
  const wanted = new URLSearchParams(location.search).get("id");
  wallet = wallets.find((w) => w.id === wanted) || wallets[0];
  if (wallets.length > 1) {
    showChoice(root.querySelector("[data-choice]"), wallets);
  }
  root.querySelector("[data-wallet]").hidden = false;
  showBalance(root);
  setUpDeposit(root.querySelector("[data-deposit]"));
  root.querySelector("[data-more]").addEventListener("click", () => loadEntries(root, false));
  await loadEntries(root, true);
}

function setUpOpening(root) {
  const section = root.querySelector("[data-open]");
  section.hidden = false;
  section.querySelector("button").addEventListener("click", async (event) => {
    event.target.disabled = true;
    const response = await api("POST", "/v1/wallets", { body: { currency: "VND" } });
    if (response.status === 201) {
      location.reload();
      return;
    }
    show(root.querySelector("[data-message]"), problemMessage(response), "error");
    event.target.disabled = false;
  });
}

function showChoice(nav, wallets) {
  nav.hidden = false;
  nav.querySelector("ul").innerHTML = wallets
    .map((w) => {
      const current = w.id === wallet.id ? ' aria-current="page"' : "";
      return `<li><a href="wallet.html?id=${w.id}"${current}>Ví …${w.id.slice(-4)}</a></li>`;
    })
    .join("");
}

function showBalance(root) {
  root.querySelector("[data-balance]").textContent = formatVnd(wallet.balance);
  root.querySelector("[data-wallet-id]").textContent = wallet.id;
}

async function refreshBalance(root) {
  const response = await api("GET", `/v1/wallets/${wallet.id}`);
  if (response.status === 200) {
    wallet = response.body;
    showBalance(root);
  }
}

function setUpDeposit(form) {
  const message = form.querySelector("[data-deposit-message]");
  form.addEventListener("submit", async (event) => {
    event.preventDefault();
    const amount = form.amount.value.trim().replace(/[.\s]/g, "");
    if (!AMOUNT.test(amount)) {
      show(message, "Nhập một số đồng nguyên dương, tối đa 18 chữ số, không có số 0 ở đầu.", "error");
      form.amount.focus();
      return;
    }
    const button = form.querySelector("button[type=submit]");
    button.disabled = true;
    const response = await api("POST", "/v1/admin/deposits", {
      body: { walletId: wallet.id, amount, currency: "VND" },
      idempotencyKey: newKey(),
    });
    if (response.status === 201) {
      show(message, `Đã nạp ${formatVnd(amount)}.`, "ok");
      form.reset();
      const root = form.closest("main");
      await refreshBalance(root);
      await loadEntries(root, true);
    } else {
      show(message, problemMessage(response), "error");
    }
    button.disabled = false;
  });
}

/** Loads the newest entries, or the next older page after the ones shown. */
async function loadEntries(root, fromStart) {
  const rows = root.querySelector("[data-entries]");
  const more = root.querySelector("[data-more]");
  if (fromStart) {
    nextCursor = null;
  }
  const after = nextCursor ? `&after=${encodeURIComponent(nextCursor)}` : "";
  const response = await api("GET", `/v1/wallets/${wallet.id}/entries?limit=${PAGE}${after}`);
  if (response.status !== 200) {
    show(root.querySelector("[data-message]"), problemMessage(response), "error");
    return;
  }
  const html = response.body.items.map(entryRow).join("");
  rows.innerHTML = fromStart ? html : rows.innerHTML + html;
  root.querySelector("[data-no-entries]").hidden = rows.children.length > 0;
  nextCursor = response.body.nextCursor;
  more.hidden = !nextCursor;
}

function entryRow(entry) {
  const amount = BigInt(entry.amount);
  const signed = (amount > 0n ? "+" : "") + formatVnd(entry.amount);
  return `
    <tr>
      <td>${when.format(new Date(entry.createdAt))}</td>
      <td>${amount > 0n ? "Tiền vào" : "Tiền ra"}</td>
      <td class="num">${signed}</td>
      <td class="num">${formatVnd(entry.balanceAfter)}</td>
    </tr>`;
}
