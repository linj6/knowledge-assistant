export class ApiError extends Error {
  constructor(kind, {status = null, code = null, requestId = null, elapsedMillis = 0} = {}) {
    super({network: '网络连接失败', aborted: '请求已取消', http: '服务请求失败', business: '业务请求失败', protocol: '响应格式不正确'}[kind]);
    Object.assign(this, {kind, status, code, requestId, elapsedMillis});
  }
}
export async function request(path, {method = 'GET', body, signal} = {}) {
  const url = new URL(path, location.href);
  if (url.origin !== location.origin) throw new TypeError('仅允许同源请求');
  const start = performance.now();
  let response;
  try {
    response = await fetch(url, {method, signal, headers: {Accept: 'application/json', ...(body === undefined ? {} : {'Content-Type': 'application/json'})}, ...(body === undefined ? {} : {body: JSON.stringify(body)})});
  } catch (error) {
    throw new ApiError(error.name === 'AbortError' ? 'aborted' : 'network', {elapsedMillis: performance.now() - start});
  }
  let envelope;
  try { envelope = await response.json(); } catch { /* 非 JSON 响应也保留 HTTP 状态。 */ }
  const meta = {status: response.status, code: envelope?.code, requestId: envelope?.requestId ?? response.headers.get('X-Request-Id'), elapsedMillis: performance.now() - start};
  if (!response.ok) throw new ApiError('http', meta);
  if (!envelope || typeof envelope.code !== 'number') throw new ApiError('protocol', meta);
  if (envelope.code !== 0) throw new ApiError('business', meta);
  return {data: envelope.data, ...meta};
}
