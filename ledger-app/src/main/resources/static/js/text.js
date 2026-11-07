// Text helpers that need no browser, so that they can be tested with node --test.

/** "Đêm Nhạc Jazz" → "dem nhac jazz": what a search compares, ignoring case, accents and extra spaces. */
export function fold(text) {
  return String(text)
    .normalize("NFD")
    .replace(/[̀-ͯ]/g, "")
    .replace(/đ/gi, "d")
    .toLowerCase()
    .replace(/\s+/g, " ")
    .trim();
}
