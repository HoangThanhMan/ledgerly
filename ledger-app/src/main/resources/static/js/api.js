// Calls the Ledgerly API from the browser. The access token lives in sessionStorage: it survives a reload, and a new
// tab signs in again.

const TOKEN = "token";
const EMAIL = "email";

export function token() {
  return sessionStorage.getItem(TOKEN);
}

export function signedInAs() {
  return sessionStorage.getItem(EMAIL);
}

export function remember(accessToken, email) {
  sessionStorage.setItem(TOKEN, accessToken);
  sessionStorage.setItem(EMAIL, email);
}

export function forget() {
  sessionStorage.removeItem(TOKEN);
  sessionStorage.removeItem(EMAIL);
}

/** Sends the user to the sign-in page, and back here once signed in. */
export function goToLogin() {
  const here = location.pathname + location.search;
  location.assign("login.html?next=" + encodeURIComponent(here));
}

/** For pages that only make sense signed in. */
export function requireSignIn() {
  if (!token()) {
    goToLogin();
    throw new Error("not signed in");
  }
}

/**
 * Calls the API and resolves with { status, headers, body }, whatever the status. A 401 means the token is missing
 * or has expired: it is dropped and the user is sent to sign in.
 */
export async function api(method, path, { body, idempotencyKey } = {}) {
  const headers = { Accept: "application/json, application/problem+json" };
  if (body !== undefined) {
    headers["Content-Type"] = "application/json";
  }
  if (idempotencyKey) {
    headers["Idempotency-Key"] = idempotencyKey;
  }
  const accessToken = token();
  if (accessToken) {
    headers.Authorization = "Bearer " + accessToken;
  }
  const response = await fetch(path, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await response.text();
  const result = {
    status: response.status,
    headers: response.headers,
    body: text ? JSON.parse(text) : null,
  };
  if (response.status === 401 && accessToken) {
    forget();
    goToLogin();
  }
  return result;
}

/** What went wrong, in words for the user, from an API response that is a Problem Details document. */
export function problemMessage(response) {
  const problem = response.body || {};
  const slug = (problem.type || "").replace("/problems/", "");
  switch (slug) {
    case "sold-out":
      return "Hết vé: hạng này không còn đủ vé cho đơn của bạn.";
    case "insufficient-funds":
      return "Không đủ tiền: số dư của ví nhỏ hơn số tiền cần trả. Nạp thêm ở trang Ví.";
    case "quantity-out-of-range":
      return "Mỗi đơn mua từ 1 đến 4 vé.";
    case "rate-limited": {
      const seconds = response.headers.get("Retry-After");
      return `Bạn đã đặt quá nhiều đơn trong một phút. Thử lại sau ${seconds || "ít"} giây.`;
    }
    case "sales-closed":
      return "Sự kiện đã bắt đầu nên không bán vé nữa.";
    case "idempotency-in-progress":
      return "Lần gửi trước vẫn đang được xử lý. Chờ vài giây rồi gửi lại.";
    case "invalid-credentials":
      return "Email hoặc mật khẩu không đúng.";
    case "email-taken":
      return "Email này đã có tài khoản. Hãy đăng nhập.";
    case "validation-error":
      return "Thông tin chưa hợp lệ. Kiểm tra lại các ô đã nhập.";
    case "wallet-not-found":
      return "Không tìm thấy ví này trong tài khoản của bạn.";
    default:
      return problem.title || `Có lỗi (HTTP ${response.status}).`;
  }
}

const vnd = new Intl.NumberFormat("vi-VN", { style: "currency", currency: "VND" });

/** "500000" → "500.000 ₫". Amounts arrive as strings and may be larger than a double holds exactly. */
export function formatVnd(amount) {
  return vnd.format(BigInt(amount));
}

const longDate = new Intl.DateTimeFormat("vi-VN", { weekday: "long", day: "numeric", month: "long", year: "numeric" });
const time = new Intl.DateTimeFormat("vi-VN", { hour: "2-digit", minute: "2-digit" });

/** "Thứ Bảy, 28 tháng 11, 2026, lúc 16:08", in the browser's time zone. */
export function formatDateTime(instant) {
  const date = new Date(instant);
  return `${longDate.format(date)}, lúc ${time.format(date)}`;
}

/** Writes a message into an element with role=status or role=alert; an empty message hides it. */
export function show(element, message, kind = "") {
  element.className = "notice" + (kind ? " " + kind : "");
  element.textContent = message;
}

/** A new Idempotency-Key. crypto.randomUUID needs a secure context, which plain http on a LAN address is not. */
export function newKey() {
  if (crypto.randomUUID) {
    return crypto.randomUUID();
  }
  const bytes = crypto.getRandomValues(new Uint8Array(16));
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = [...bytes].map((b) => b.toString(16).padStart(2, "0")).join("");
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}
