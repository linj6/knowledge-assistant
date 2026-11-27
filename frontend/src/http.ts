type RequestOptions = {
    method?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';
    body?: unknown;
    signal?: AbortSignal;
    notifyUnauthorized?: boolean;
    onSuccessStatus?: (status: number) => void;
};

type Result<T> = { code: number; message: string; data: T; requestId: string };
type Csrf = { headerName: string; token: string };

export class ApiError extends Error {
    constructor(
        message: string,
        readonly kind: 'network' | 'aborted' | 'http' | 'business' | 'protocol',
        readonly status?: number,
        readonly code?: number,
        readonly requestId?: string,
    ) {
        super(message);
    }
}

export const authPath = '/api/v1/auth';
export const sessionEvents = new EventTarget();

export async function request<T>(
    path: string,
    { method = 'GET', body, signal, notifyUnauthorized = true, onSuccessStatus }: RequestOptions = {},
): Promise<T> {
    if (!path.startsWith('/api/')) throw new TypeError('仅允许同源 API 路径');
    const csrf = method === 'GET' ? undefined : await request<Csrf>(authPath + '/csrf', { signal });
    if (
        csrf !== undefined &&
        (typeof csrf?.headerName !== 'string' || !csrf.headerName || typeof csrf.token !== 'string' || !csrf.token)
    ) {
        throw new ApiError('CSRF 响应格式不正确', 'protocol');
    }
    const multipart = body instanceof FormData;
    let response: Response;
    try {
        response = await fetch(path, {
            method,
            credentials: 'same-origin',
            signal,
            headers: {
                Accept: 'application/json',
                ...(csrf ? { [csrf.headerName]: csrf.token } : {}),
                ...(body !== undefined && !multipart ? { 'Content-Type': 'application/json' } : {}),
            },
            body: multipart ? body : JSON.stringify(body),
        });
    } catch {
        if (signal?.aborted) throw new ApiError('请求已取消', 'aborted');
        throw new ApiError('无法连接服务，请稍后重试', 'network');
    }
    let result: Partial<Result<T>> | undefined;
    try {
        result = await response.json();
    } catch {
        // 非 JSON 错误仍保留 HTTP 状态和响应头中的追踪标识。
    }
    if (signal?.aborted) throw new ApiError('请求已取消', 'aborted');
    const code = typeof result?.code === 'number' ? result.code : undefined;
    const requestId =
        typeof result?.requestId === 'string' ? result.requestId : (response.headers.get('X-Request-Id') ?? undefined);
    const message = typeof result?.message === 'string' ? result.message : undefined;
    if (response.status === 401 && notifyUnauthorized) sessionEvents.dispatchEvent(new Event('unauthorized'));
    if (!response.ok) throw new ApiError(message || '服务请求失败', 'http', response.status, code, requestId);
    if (code === undefined || !message || !result || !('data' in result)) {
        throw new ApiError('响应格式不正确', 'protocol', response.status, code, requestId);
    }
    if (code !== 0) throw new ApiError(message, 'business', response.status, code, requestId);
    onSuccessStatus?.(response.status);
    return result.data as T;
}

export function errorMessage(error: unknown) {
    if (!(error instanceof Error)) return '请求失败，请稍后重试';
    return error instanceof ApiError && error.requestId
        ? error.message + '（请求 ID：' + error.requestId + '）'
        : error.message;
}
