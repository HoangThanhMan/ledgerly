import assert from "node:assert/strict";
import { test } from "node:test";

import { hueFor } from "../../main/resources/static/js/poster.js";

test("an event always gets the same violet", () => {
  const id = "01a1b6a3-298a-7497-a93b-7c511856143e";
  assert.equal(hueFor(id), hueFor(id));
});

test("hues stay violet and vary between events", () => {
  const hues = Array.from({ length: 20 }, (_, i) => hueFor(`event-${i}`));
  for (const hue of hues) {
    assert.ok(hue >= 255 && hue <= 300, `${hue} is not violet`);
  }
  assert.ok(new Set(hues).size >= 5);
});
