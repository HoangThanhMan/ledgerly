// Calls the Ledgerly API from the browser. The access token lives in sessionStorage: it survives a reload, and a new
// tab signs in again.

import { locale, t } from "./i18n.js";

const TOKEN = "token";
const EMAIL = "email";

export function token() {
  return sessionStorage.getItem(TOKEN);
}

export function signedInAs() {
  return sessionStorage.getItem(EMAIL);
}

export function remember(accessToken, email) {
  sessionStorage.setItem(TOKEN, accessToken);
  sessionStorage.setItem(EMAIL, email);
}

export function forget() {
  sessionStorage.removeItem(TOKEN);
  sessionStorage.removeItem(EMAIL);
}

/** Sends the user to the sign-in page, and back here once signed in. */
export function goToLogin() {
  const here = location.pathname + location.search;
  location.assign("login.html?next=" + encodeURIComponent(here));
}

/** For pages that only make sense signed in. */
export function requireSignIn() {
  if (!token()) {
    goToLogin();
    throw new Error("not signed in");
  }
}

/**
 * Calls the API and resolves with { status, headers, body }, whatever the status. A 401 means the token is missing
 * or has expired: it is dropped and the user is sent to sign in.
 */
export async function api(method, path, { body, idempotencyKey } = {}) {
  const headers = { Accept: "application/json, application/problem+json" };
  if (body !== undefined) {
    headers["Content-Type"] = "application/json";
  }
  if (idempotencyKey) {
    headers["Idempotency-Key"] = idempotencyKey;
  }
  const accessToken = token();
  if (accessToken) {
    headers.Authorization = "Bearer " + accessToken;
  }
  const response = await fetch(path, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await response.text();
  const result = {
    status: response.status,
    headers: response.headers,
    body: text ? JSON.parse(text) : null,
  };
  if (response.status === 401 && accessToken) {
    forget();
    goToLogin();
  }
  return result;
}

/** What went wrong, in words for the user, from an API response that is a Problem Details document. */
export function problemMessage(response) {
  const problem = response.body || {};
  const key = "problem." + (problem.type || "").replace("/problems/", "");
  const seconds = response.headers?.get?.("Retry-After") || "60";
  const message = t(key, { seconds });
  if (message !== key) {
    return message;
  }
  return problem.title || t("problem.generic", { status: response.status });
}

/** "500000" → "500.000 ₫" (or "₫500,000" in English). Amounts arrive as strings and may exceed a double. */
export function formatVnd(amount) {
  return new Intl.NumberFormat(locale(), { style: "currency", currency: "VND" }).format(BigInt(amount));
}

/** "Thứ Bảy, 28 tháng 11, 2026, lúc 16:08", in the browser's time zone. */
export function formatDateTime(instant) {
  const date = new Date(instant);
  const day = new Intl.DateTimeFormat(locale(), { weekday: "long", day: "numeric", month: "long", year: "numeric" });
  const time = new Intl.DateTimeFormat(locale(), { hour: "2-digit", minute: "2-digit" });
  return t("format.dateTime", { date: day.format(date), time: time.format(date) });
}

/** "28 tháng 11, 2026": the day only. */
export function formatDate(instant) {
  return new Intl.DateTimeFormat(locale(), { day: "numeric", month: "long", year: "numeric" }).format(new Date(instant));
}

/** "28 thg 11": short, for cards and lists. */
export function formatShortDate(instant) {
  return new Intl.DateTimeFormat(locale(), { day: "numeric", month: "short" }).format(new Date(instant));
}

/** Writes a message into an element with role=status or role=alert; an empty message hides it. */
export function show(element, message, kind = "") {
  element.className = "notice" + (kind ? " " + kind : "");
  element.textContent = message;
}

/** A new Idempotency-Key. crypto.randomUUID needs a secure context, which plain http on a LAN address is not. */
export function newKey() {
  if (crypto.randomUUID) {
    return crypto.randomUUID();
  }
  const bytes = crypto.getRandomValues(new Uint8Array(16));
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = [...bytes].map((b) => b.toString(16).padStart(2, "0")).join("");
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}
