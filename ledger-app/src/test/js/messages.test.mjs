// Both languages must say everything: a key missing in one of them would show as a raw key on the page.
import assert from "node:assert/strict";
import { test } from "node:test";

import { MESSAGES } from "../../main/resources/static/js/messages.js";

const params = (text) => [...text.matchAll(/\{(\w+)\}/g)].map((m) => m[1]).sort();

test("Vietnamese and English have the same keys", () => {
  assert.deepEqual(Object.keys(MESSAGES.en).sort(), Object.keys(MESSAGES.vi).sort());
});

test("no message is empty", () => {
  for (const [language, messages] of Object.entries(MESSAGES)) {
    for (const [key, text] of Object.entries(messages)) {
      assert.ok(typeof text === "string" && text.trim(), `${language}.${key} is empty`);
    }
  }
});

test("both languages take the same parameters", () => {
  for (const key of Object.keys(MESSAGES.vi)) {
    assert.deepEqual(params(MESSAGES.en[key]), params(MESSAGES.vi[key]), key);
  }
});
