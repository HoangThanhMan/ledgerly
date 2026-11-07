// Guards for the page markup. The top bar holds a search form, so a page that takes "the first form" gets that one,
// and its own form then submits natively — as GET, putting a password in the address.
import assert from "node:assert/strict";
import { readdirSync, readFileSync } from "node:fs";
import { test } from "node:test";

const dir = new URL("../../main/resources/static/", import.meta.url);
const pages = readdirSync(dir).filter((name) => name.endsWith(".html"));

test("no page takes the first form of the document", () => {
  for (const page of pages) {
    assert.ok(!readFileSync(new URL(page, dir), "utf8").includes('document.querySelector("form")'), page);
  }
});

test("forms with a password field are posted", () => {
  for (const page of pages) {
    const html = readFileSync(new URL(page, dir), "utf8");
    for (const form of html.match(/<form\b[^>]*>[\s\S]*?<\/form>/g) ?? []) {
      if (/type="password"/.test(form)) {
        assert.match(form, /^<form\b[^>]*method="post"/, page);
      }
    }
  }
});
