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

const dateTime = new Intl.DateTimeFormat("vi-VN", { dateStyle: "full", timeStyle: "short" });

export function formatDateTime(instant) {
  return dateTime.format(new Date(instant));
}

/** Writes a message into an element with role=status or role=alert; an empty message hides it. */
export function show(element, message, kind = "") {
  element.className = "notice" + (kind ? " " + kind : "");
  element.textContent = message;
}
