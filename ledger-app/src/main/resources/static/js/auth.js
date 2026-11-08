// The top bar of every page, and the sign-in and sign-up forms.

import { api, forget, problemMessage, remember, show, signedInAs, token } from "./api.js";
import { lang, setLang, t } from "./i18n.js";
import { theme, toggleTheme } from "./theme.js";

const LINKS = [
  { href: "events.html", label: "nav.events" },
  { href: "tickets.html", label: "nav.tickets", signedIn: true },
  { href: "wallet.html", label: "nav.wallet", signedIn: true },
  { href: "assistant.html", label: "nav.assistant", signedIn: true },
];

const ICONS = {
  // Shown in light mode: switches to dark.
  moon: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8z"/></svg>',
  sun: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" aria-hidden="true"><circle cx="12" cy="12" r="4"/><path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4"/></svg>',
  menu: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" aria-hidden="true"><path d="M4 7h16M4 12h16M4 17h16"/></svg>',
};

/** Fills <header class="topbar">: wordmark, search, the links that fit whether the user is signed in, language and
 * theme switches. */
export function renderTopbar() {
  const header = document.querySelector(".topbar");
  if (!header) {
    return;
  }
  const signedIn = Boolean(token());
  const page = location.pathname.split("/").pop() || "index.html";
  const items = LINKS.filter((link) => signedIn || !link.signedIn).map((link) => {
    const current = link.href === page ? ' aria-current="page"' : "";
    return `<li><a href="${link.href}"${current}>${escapeHtml(t(link.label))}</a></li>`;
  });
  if (signedIn) {
    items.push(`<li class="who">${escapeHtml(signedInAs() || "")}</li>`);
    items.push(`<li><button type="button" data-sign-out>${escapeHtml(t("nav.signOut"))}</button></li>`);
  } else {
    items.push(`<li><a href="login.html">${escapeHtml(t("nav.signIn"))}</a></li>`);
    items.push(`<li><a href="register.html">${escapeHtml(t("nav.register"))}</a></li>`);
  }
  const query = page === "events.html" ? new URLSearchParams(location.search).get("q") || "" : "";
  const current = lang();
  const dark = theme() === "dark";
  header.innerHTML = `
    <div class="topbar-inner">
      <a class="wordmark" href="./">${escapeHtml(t("brand"))}</a>
      <form class="search" action="events.html" role="search">
        <label class="visually-hidden" for="topbar-search">${escapeHtml(t("nav.search"))}</label>
        <input id="topbar-search" name="q" type="search" value="${escapeHtml(query)}"
          placeholder="${escapeHtml(t("nav.searchPlaceholder"))}">
      </form>
      <nav id="topbar-nav" aria-label="${escapeHtml(t("nav.main"))}"><ul class="nav">${items.join("")}</ul></nav>
      <div class="controls">
        <span class="lang" role="group" aria-label="${escapeHtml(t("lang.label"))}">
          <button type="button" data-lang="vi" lang="vi" aria-pressed="${current === "vi"}">VI</button>
          <button type="button" data-lang="en" lang="en" aria-pressed="${current === "en"}">EN</button>
        </span>
        <button class="toggle" type="button" data-theme-toggle
          aria-label="${escapeHtml(t(dark ? "theme.toLight" : "theme.toDark"))}">${dark ? ICONS.sun : ICONS.moon}</button>
        <button class="toggle menu-button" type="button" aria-controls="topbar-nav" aria-expanded="false"
          aria-label="${escapeHtml(t("nav.menu"))}">${ICONS.menu}</button>
      </div>
    </div>`;
  header.querySelector("[data-sign-out]")?.addEventListener("click", () => {
    forget();
    location.assign("./");
  });
  for (const button of header.querySelectorAll("[data-lang]")) {
    button.addEventListener("click", () => {
      if (button.dataset.lang !== lang()) {
        setLang(button.dataset.lang);
      }
    });
  }
  header.querySelector("[data-theme-toggle]").addEventListener("click", (event) => {
    const now = toggleTheme();
    event.currentTarget.innerHTML = now === "dark" ? ICONS.sun : ICONS.moon;
    event.currentTarget.setAttribute("aria-label", t(now === "dark" ? "theme.toLight" : "theme.toDark"));
  });
  const menu = header.querySelector(".menu-button");
  menu.addEventListener("click", () => {
    const open = !header.classList.contains("is-open");
    header.classList.toggle("is-open", open);
    menu.setAttribute("aria-expanded", String(open));
  });
}

export function escapeHtml(text) {
  return String(text).replace(
    /[&<>"']/g,
    (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c],
  );
}

/**
 * Where to go after signing in: the page that sent the user here, if it is on this site. The origin is compared after
 * the browser has parsed the address, since "/\\evil.example" and similar strings lead off the site.
 */
function nextPage() {
  const next = new URLSearchParams(location.search).get("next");
  if (next) {
    const url = new URL(next, location.href);
    if (url.origin === location.origin) {
      return url.pathname + url.search;
    }
  }
  return "events.html";
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
