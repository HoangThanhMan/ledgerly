// Every colour comes from a token, so that the dark theme changes all of them. Only the token blocks and the posters
// (which look the same in both themes) may name colours.
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";

const css = readFileSync(new URL("../../main/resources/static/css/app.css", import.meta.url), "utf8");

test("colours are only named in tokens and posters", () => {
  const offenders = [];
  for (const block of css.replace(/\/\*[\s\S]*?\*\//g, "").matchAll(/([^{}]+)\{([^{}]*)\}/g)) {
    const selector = block[1].trim();
    if (/^(:root|\[data-theme="dark"\])/.test(selector) || /\.poster/.test(selector)) {
      continue;
    }
    if (/#[0-9a-fA-F]{3,8}\b|rgba?\(|hsla?\(/.test(block[2])) {
      offenders.push(selector);
    }
  }
  assert.deepEqual(offenders, []);
});
