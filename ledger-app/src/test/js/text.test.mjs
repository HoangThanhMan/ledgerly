import assert from "node:assert/strict";
import { test } from "node:test";

import { fold, matches } from "../../main/resources/static/js/text.js";

test("fold ignores case and Vietnamese accents", () => {
  assert.equal(fold("Đêm Nhạc Jazz"), "dem nhac jazz");
  assert.equal(fold("  Hội chợ SÁCH "), "hoi cho sach");
});

test("a search matches words of the name in any case or accent", () => {
  const event = { name: "Đêm nhạc Jazz mùa thu" };
  assert.ok(matches(event, "dem nhac"));
  assert.ok(matches(event, "JAZZ"));
  assert.ok(matches(event, "  "));
  assert.ok(!matches(event, "rock"));
});
