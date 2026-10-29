// The top bar of every page, and the sign-in and sign-up forms.

import { api, forget, problemMessage, remember, show, signedInAs, token } from "./api.js";

const LINKS = [
  { href: "events.html", label: "Sự kiện" },
  { href: "tickets.html", label: "Vé của tôi", signedIn: true },
  { href: "wallet.html", label: "Ví", signedIn: true },
];

/** Fills <header class="topbar"> with the wordmark and the links that fit whether the user is signed in. */
export function renderTopbar() {
  const header = document.querySelector(".topbar");
  if (!header) {
    return;
  }
  const signedIn = Boolean(token());
  const page = location.pathname.split("/").pop() || "index.html";
  const items = LINKS.filter((link) => signedIn || !link.signedIn).map((link) => {
    const current = link.href === page ? ' aria-current="page"' : "";
    return `<li><a href="${link.href}"${current}>${link.label}</a></li>`;
  });
  if (signedIn) {
    items.push(`<li class="who">${escapeHtml(signedInAs() || "")}</li>`);
    items.push('<li><button type="button" data-sign-out>Đăng xuất</button></li>');
  } else {
    items.push('<li><a href="login.html">Đăng nhập</a></li>');
    items.push('<li><a href="register.html">Đăng ký</a></li>');
  }
  header.innerHTML = `
    <div class="topbar-inner">
      <a class="wordmark" href="./">Ticket<span>Box</span></a>
      <nav aria-label="Chính"><ul class="nav">${items.join("")}</ul></nav>
    </div>`;
  header.querySelector("[data-sign-out]")?.addEventListener("click", () => {
    forget();
    location.assign("./");
  });
}

export function escapeHtml(text) {
  return String(text).replace(
    /[&<>"']/g,
    (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c],
  );
}

/** Where to go after signing in: the page that sent the user here, if it is one of ours. */
function nextPage() {
  const next = new URLSearchParams(location.search).get("next");
  return next && next.startsWith("/") && !next.startsWith("//") ? next : "events.html";
}

async function signIn(email, password) {
  const response = await api("POST", "/v1/auth/login", { body: { email, password } });
  if (response.status === 200) {
    remember(response.body.accessToken, email);
  }
  return response;
}

export function setUpLoginForm(form) {
  const message = form.querySelector("[data-message]");
  form.addEventListener("submit", async (event) => {
    event.preventDefault();
    const button = form.querySelector("button[type=submit]");
    button.disabled = true;
    show(message, "");
    const email = form.email.value.trim();
    const response = await signIn(email, form.password.value);
    if (response.status === 200) {
      location.assign(nextPage());
      return;
    }
    show(message, problemMessage(response), "error");
    button.disabled = false;
  });
}

export function setUpRegisterForm(form) {
  const message = form.querySelector("[data-message]");
  form.addEventListener("submit", async (event) => {
    event.preventDefault();
    const button = form.querySelector("button[type=submit]");
    button.disabled = true;
    show(message, "");
    const email = form.email.value.trim();
    const password = form.password.value;
    const created = await api("POST", "/v1/auth/register", {
      body: { email, password, role: form.role.value },
    });
    const response = created.status === 201 ? await signIn(email, password) : created;
    if (response.status === 200) {
      location.assign("wallet.html");
      return;
    }
    show(message, problemMessage(response), "error");
    button.disabled = false;
  });
}
