// The page's language: Vietnamese unless the visitor chose English. A ?lang= parameter overrides the choice for one
// page, without saving it (used for screenshots).

import { MESSAGES } from "./messages.js";

const KEY = "lang";

function stored() {
  try {
    return localStorage.getItem(KEY);
  } catch {
    return null;
  }
}

export function lang() {
  const chosen = new URLSearchParams(location.search).get("lang") || stored();
  return chosen === "en" ? "en" : "vi";
}

/** Saves the choice and reloads the page in that language. */
export function setLang(language) {
  try {
    localStorage.setItem(KEY, language);
  } catch {
    // Without storage the choice lasts for this page only.
  }
  const url = new URL(location.href);
  url.searchParams.delete("lang");
  location.replace(url);
}

/** The locale dates and amounts are written in. */
export function locale() {
  return lang() === "en" ? "en-GB" : "vi-VN";
}

/** The text of `key` in the page's language, with {name} filled in from `params`. A missing key shows as itself. */
export function t(key, params = {}) {
  const text = MESSAGES[lang()][key] ?? key;
  return text.replace(/\{(\w+)\}/g, (match, name) => (name in params ? String(params[name]) : match));
}

/** Fills the text of every element marked with data-i18n, data-i18n-placeholder or data-i18n-aria-label. */
export function translatePage(root = document) {
  for (const element of root.querySelectorAll("[data-i18n]")) {
    element.textContent = t(element.dataset.i18n);
  }
  for (const element of root.querySelectorAll("[data-i18n-placeholder]")) {
    element.placeholder = t(element.dataset.i18nPlaceholder);
  }
  for (const element of root.querySelectorAll("[data-i18n-aria-label]")) {
    element.setAttribute("aria-label", t(element.dataset.i18nAriaLabel));
  }
  document.documentElement.lang = lang();
}
