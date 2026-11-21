export class ApiError extends Error {
    constructor(kind, { status = null, code = null, detail = null, requestId = null, elapsedMillis = 0 } = {}) {
        super(
            {
                network: '网络连接失败',
                aborted: '请求已取消',
                http: status === 403 ? '请求被拒绝，请手动重试或确认访问权限' : '服务请求失败',
                business: '业务请求失败',
                protocol: '响应格式不正确',
            }[kind],
        );
        Object.assign(this, {
            kind,
            status,
            code,
            detail,
            requestId,
            elapsedMillis,
        });
    }
}
const csrfPath = '/api/v1/auth/csrf';
let csrfRequest;
let sessionRevision = 0;
let onUnauthorized = () => {};

export function setUnauthorizedHandler(handler) {
    onUnauthorized = handler;
}

export function resetSessionRequests() {
    sessionRevision++;
    csrfRequest = undefined;
}

function getCsrfToken() {
    if (!csrfRequest) {
        const pending = request(csrfPath)
            .then(({ data }) => {
                if (
                    typeof data?.headerName !== 'string' ||
                    !data.headerName ||
                    typeof data.token !== 'string' ||
                    !data.token
                )
                    throw new ApiError('protocol');
                return data;
            })
            .catch((error) => {
                if (csrfRequest === pending) csrfRequest = undefined;
                throw error;
            });
        csrfRequest = pending;
    }
    return csrfRequest;
}

export async function request(path, { method = 'GET', body, signal, raw = false } = {}) {
    const url = new URL(path, location.href);
    if (url.origin !== location.origin) throw new TypeError('仅允许同源请求');
    const start = performance.now();
    const revision = sessionRevision;
    method = method.toUpperCase();
    const writes = ['POST', 'PUT', 'PATCH', 'DELETE'].includes(method);
    const tokenRequest = writes ? getCsrfToken() : undefined;
    const csrf = writes ? await tokenRequest : undefined;
    if (revision !== sessionRevision || signal?.aborted) throw new ApiError('aborted');
    let response;
    try {
        response = await fetch(url, {
            method,
            signal,
            credentials: 'same-origin',
            headers: {
                Accept: 'application/json',
                ...(csrf ? { [csrf.headerName]: csrf.token } : {}),
                ...(body === undefined || (typeof FormData !== 'undefined' && body instanceof FormData)
                    ? {}
                    : {
                          'Content-Type': 'application/json',
                      }),
            },
            ...(body === undefined
                ? {}
                : {
                      body: typeof FormData !== 'undefined' && body instanceof FormData ? body : JSON.stringify(body),
                  }),
        });
    } catch (error) {
        throw new ApiError(error.name === 'AbortError' ? 'aborted' : 'network', {
            elapsedMillis: performance.now() - start,
        });
    }
    let envelope;
    try {
        envelope = await response.json();
    } catch {
        /* 非 JSON 响应也保留 HTTP 状态。 */
    }
    const meta = {
        status: response.status,
        code: envelope?.code,
        detail: envelope?.detail ?? envelope?.reason,
        requestId: envelope?.requestId ?? response.headers.get('X-Request-Id'),
        elapsedMillis: performance.now() - start,
    };
    if (revision !== sessionRevision) throw new ApiError('aborted');
    if (response.status === 401) onUnauthorized(url.pathname);
    // 仅失效本次写请求使用的 Token，下次手动提交时重新获取。
    if (response.status === 403 && writes && csrfRequest === tokenRequest) csrfRequest = undefined;
    if (!response.ok) throw new ApiError('http', meta);
    if (raw) {
        if (envelope == null) throw new ApiError('protocol', meta);
        return { data: envelope, ...meta };
    }
    if (!envelope || typeof envelope.code !== 'number') throw new ApiError('protocol', meta);
    if (envelope.code !== 0) throw new ApiError('business', meta);
    return {
        data: envelope.data,
        ...meta,
    };
}
