/**
 * API 호출 모듈. 화면 코드는 fetch를 직접 부르지 않고 이 모듈만 쓴다(.claude/rules/code-quality.md).
 *
 * - 경로는 상대 경로 `/api/**`. 개발 서버는 Vite 프록시로 같은 출처에서 Spring에 붙는다.
 * - 세션 쿠키를 함께 보낸다. 상태 변경 요청(POST · PUT · PATCH · DELETE)에는 `XSRF-TOKEN` 쿠키 값을
 *   `X-XSRF-TOKEN` 헤더로 붙인다(CONTRACTS.md 「6. 인증 · 세션 · CSRF」).
 * - 실패 응답 `{ error: { code, message } }`는 ApiError로 바꿔 던진다. `401`이면 onUnauthorized 처리기를 부른다.
 */

const CSRF_COOKIE = 'XSRF-TOKEN';
const CSRF_HEADER = 'X-XSRF-TOKEN';
const CSRF_PATH = '/api/v1/csrf';
const STATE_CHANGING_METHODS = new Set(['POST', 'PUT', 'PATCH', 'DELETE']);

/** 서버가 준 에러 코드(CONTRACTS.md 「4. 에러 코드」). 네트워크 오류 · 형식 밖 응답이면 code는 null. */
export class ApiError extends Error {
  constructor({ status, code, message }) {
    super(message);
    this.name = 'ApiError';
    this.status = status; // HTTP 상태. 서버에 닿지 못했으면 0
    this.code = code ?? null;
  }
}

let unauthorizedHandler = null;

/** 로그인이 필요하다는 응답(401)을 받았을 때 부를 함수를 등록한다. 등록을 푸는 함수를 돌려준다. */
export function onUnauthorized(handler) {
  unauthorizedHandler = handler;
  return () => {
    if (unauthorizedHandler === handler) unauthorizedHandler = null;
  };
}

function readCookie(name) {
  const prefix = `${name}=`;
  const found = document.cookie.split('; ').find((part) => part.startsWith(prefix));
  return found ? decodeURIComponent(found.slice(prefix.length)) : null;
}

let pendingCsrf = null;

/**
 * CSRF 토큰을 돌려준다. 쿠키에 없거나 refresh면 `GET /api/v1/csrf`로 새로 받는다.
 * 계약대로 앱 시작 때 한 번 부르고, 상태 변경 요청 직전에도 확인한다.
 */
export async function ensureCsrfToken({ refresh = false } = {}) {
  if (!refresh) {
    const token = readCookie(CSRF_COOKIE);
    if (token) return token;
  }
  pendingCsrf ??= send(CSRF_PATH, { method: 'GET' })
    .then(async (response) => {
      if (!response.ok) throw await toApiError(response);
      const token = readCookie(CSRF_COOKIE);
      if (!token) {
        throw new ApiError({ status: response.status, code: null, message: 'CSRF 토큰 쿠키를 받지 못했어요.' });
      }
      return token;
    })
    .finally(() => {
      pendingCsrf = null;
    });
  return pendingCsrf;
}

/**
 * API를 부르고 JSON 본문을 돌려준다(본문이 없으면 null).
 * @param {string} path `/api/`로 시작하는 경로
 * @param {{ method?: string, body?: unknown, query?: Record<string, unknown>, signal?: AbortSignal }} [options]
 */
export async function apiRequest(path, { method = 'GET', body, query, signal } = {}) {
  if (!path.startsWith('/api/')) {
    throw new Error(`API 경로는 /api/로 시작해야 한다: ${path}`);
  }
  const url = withQuery(path, query);
  const upperMethod = method.toUpperCase();
  const needsCsrf = STATE_CHANGING_METHODS.has(upperMethod);

  let response = await send(url, {
    method: upperMethod,
    body,
    signal,
    csrfToken: needsCsrf ? await ensureCsrfToken() : null,
  });

  // 세션이 바뀌어 토큰이 맞지 않으면 한 번만 새 토큰으로 다시 보낸다.
  if (needsCsrf && response.status === 403) {
    const error = await toApiError(response.clone());
    if (error.code === 'csrf_invalid') {
      response = await send(url, {
        method: upperMethod,
        body,
        signal,
        csrfToken: await ensureCsrfToken({ refresh: true }),
      });
    }
  }

  if (response.ok) {
    return readJson(response);
  }

  const error = await toApiError(response);
  if (error.status === 401) {
    unauthorizedHandler?.(error);
  }
  throw error;
}

export const api = {
  get: (path, options) => apiRequest(path, { ...options, method: 'GET' }),
  post: (path, body, options) => apiRequest(path, { ...options, method: 'POST', body }),
  put: (path, body, options) => apiRequest(path, { ...options, method: 'PUT', body }),
  patch: (path, body, options) => apiRequest(path, { ...options, method: 'PATCH', body }),
  delete: (path, options) => apiRequest(path, { ...options, method: 'DELETE' }),
};

async function send(url, { method, body, signal, csrfToken = null }) {
  const headers = { Accept: 'application/json' };
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  if (csrfToken) headers[CSRF_HEADER] = csrfToken;

  try {
    return await fetch(url, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
      credentials: 'same-origin',
      signal,
    });
  } catch (cause) {
    if (cause?.name === 'AbortError') throw cause;
    const error = new ApiError({ status: 0, code: null, message: '서버에 연결하지 못했어요. 잠시 뒤 다시 시도해 주세요.' });
    error.cause = cause;
    throw error;
  }
}

function withQuery(path, query) {
  if (!query) return path;
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(query)) {
    if (value === undefined || value === null) continue;
    params.append(key, String(value));
  }
  const text = params.toString();
  return text ? `${path}?${text}` : path;
}

async function readJson(response) {
  if (response.status === 204) return null;
  const text = await response.text();
  return text ? JSON.parse(text) : null;
}

async function readErrorBody(response) {
  try {
    return await readJson(response);
  } catch {
    // JSON이 아닌 응답(프록시 오류 페이지 등). 호출한 쪽이 code 없는 ApiError로 바꾼다
    return null;
  }
}

async function toApiError(response) {
  const payload = await readErrorBody(response);
  const error = payload?.error;
  return new ApiError({
    status: response.status,
    code: typeof error?.code === 'string' ? error.code : null,
    message: typeof error?.message === 'string' ? error.message : '요청을 처리하지 못했어요.',
  });
}
