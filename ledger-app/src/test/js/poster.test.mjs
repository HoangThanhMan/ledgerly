import assert from "node:assert/strict";
import { test } from "node:test";

import { hueFor, spotFor } from "../../main/resources/static/js/poster.js";

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

test("the light spot sits somewhere on the poster, the same for an event every time", () => {
  const spots = Array.from({ length: 20 }, (_, i) => spotFor(`event-${i}`));
  for (const { x, y } of spots) {
    assert.ok(x >= 10 && x <= 90 && y >= 0 && y <= 40, `${x},${y}`);
  }
  assert.deepEqual(spotFor("a"), spotFor("a"));
  assert.ok(new Set(spots.map((s) => s.x)).size >= 5);
});
