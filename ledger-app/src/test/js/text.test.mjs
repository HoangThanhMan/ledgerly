import assert from "node:assert/strict";
import { test } from "node:test";

import { fold } from "../../main/resources/static/js/text.js";

test("fold ignores case and Vietnamese accents", () => {
  assert.equal(fold("Đêm Nhạc Jazz"), "dem nhac jazz");
  assert.equal(fold("  Hội chợ SÁCH "), "hoi cho sach");
});
