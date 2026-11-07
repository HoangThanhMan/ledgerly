// Light or dark. The small script in each page's <head> sets data-theme before anything is drawn, from the saved
// choice or else the system's; this module reads and changes it.

export function theme() {
  return document.documentElement.dataset.theme === "dark" ? "dark" : "light";
}

export function toggleTheme() {
  const next = theme() === "dark" ? "light" : "dark";
  document.documentElement.dataset.theme = next;
  try {
    localStorage.setItem("theme", next);
  } catch {
    // Without storage the choice lasts until the page is left.
  }
  return next;
}
