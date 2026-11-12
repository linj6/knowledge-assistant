import { request, ApiError } from './api.js';
import { AppState, subscribe } from './state.js';

const strategies = ['HYBRID_RRF', 'VECTOR', 'BM25', 'HYBRID_RERANK'];
export function validateAnswer(data) {
    const id = value => typeof value === 'string' && /^[1-9]\d*$/.test(value);
    if (!data || !strategies.includes(data.requestedStrategy) || !Array.isArray(data.citations)) throw new ApiError('protocol');
    if (data.citations.some(c => !c || typeof c.evidenceId !== 'string' || !/^E[1-9]\d*$/.test(c.evidenceId) || !id(c.chunkId) || !id(c.documentId) || !id(c.indexVersionId) || !Number.isInteger(c.sourceStart) || !Number.isInteger(c.sourceEnd) || c.sourceStart < 0 || c.sourceEnd <= c.sourceStart)) throw new ApiError('protocol');
    const valid = data.status === 'ANSWERED' ? typeof data.answer === 'string' && data.answer.trim() && data.citations.length && data.error == null
        : data.status === 'INSUFFICIENT_EVIDENCE' ? !data.citations.length && data.error == null && (data.answer == null || typeof data.answer === 'string' && data.answer.trim())
        : data.status === 'FAILED' && data.answer == null && !data.citations.length && typeof data.error === 'string' && /^[A-Z_]+$/.test(data.error);
    if (!valid) throw new ApiError('protocol');
    return data;
}
let initialized = false;
export function initializeChat() {
    if (initialized) return;
    initialized = true;
    const root = document.querySelector('#chat');
    const node = (tag, text) => { const el = document.createElement(tag); if (text !== undefined) el.textContent = text; return el; };
    root.replaceChildren(node('h1', 'AI 问答'));
    const context = node('p'); context.className = 'context';
    const history = node('div');
    const form = node('form');
    const label = node('label', '你的问题'); label.setAttribute('for', 'question');
    const question = node('textarea'); question.id = 'question'; question.maxLength = 4000; question.required = true;
    const send = node('button', '发送'); send.type = 'submit';
    const cancel = node('button', '取消等待'); cancel.type = 'button'; cancel.hidden = true;
    const status = node('p'); status.setAttribute('role', 'status'); status.setAttribute('aria-live', 'polite');
    form.append(label, question, send, cancel);
    root.append(context, node('p', '每次只发送当前问题；历史仅供本页阅读，刷新后清空。'), history, form, status);
    let generation = 0, pending = null, kb = AppState.selectedKnowledgeBaseId, page = AppState.activePage;
    function controls() { send.disabled = !!pending || !kb || AppState.activePage !== 'chat'; question.disabled = !!pending; cancel.hidden = !pending; }
    function stop(message) {
        generation++;
        if (pending) { clearTimeout(pending.timer); pending.controller.abort(); pending = null; status.textContent = message; }
        controls();
    }
    cancel.addEventListener('click', () => stop('已取消客户端等待，服务端可能继续执行；再次发送可能重复执行。'));
    subscribe(state => {
        if (kb !== state.selectedKnowledgeBaseId) { stop(''); kb = state.selectedKnowledgeBaseId; history.replaceChildren(); question.value = ''; status.textContent = ''; }
        if (page !== state.activePage) { stop('已离开问答页，旧请求已失效；服务端可能继续执行。'); page = state.activePage; }
        controls();
    });
    window.addEventListener('pagehide', () => stop('等待已结束。'));
    let composing = false;
    question.addEventListener('compositionstart', () => { composing = true; });
    question.addEventListener('compositionend', () => { composing = false; });
    question.addEventListener('keydown', event => {
        if (event.key === 'Enter' && !event.shiftKey && !event.isComposing && !composing && event.keyCode !== 229) {
            event.preventDefault();
            form.requestSubmit();
        }
    });
    form.addEventListener('submit', async event => {
        event.preventDefault();
        const text = question.value.trim();
        if (pending || !kb || AppState.activePage !== 'chat') return;
        if (!text || question.value.length > 4000) { status.textContent = '请输入 1～4000 字符的问题。'; return; }
        const current = ++generation, selectedKb = kb;
        const controller = new AbortController();
        pending = { controller, timer: setTimeout(() => stop('等待超时，服务端可能仍在执行；再次发送可能重复执行。'), 60000) };
        controls(); status.textContent = '正在等待答案…';
        const item = node('article'); item.append(node('h2', '问题'), node('p', text)); history.append(item);
        try {
            // 不传历史；回调写入前校验代次和知识库，取消也不能让旧响应覆盖新页面。
            const response = await request('../api/v1/chat/answers', { method: 'POST', body: { knowledgeBaseId: selectedKb, question: text, strategy: 'HYBRID_RRF' }, signal: controller.signal });
            if (current !== generation || selectedKb !== kb) return;
            let data;
            try { data = validateAnswer(response.data); }
            catch { throw new ApiError('protocol', { requestId: response.requestId }); }
            item.append(node('h3', { ANSWERED: '已回答', INSUFFICIENT_EVIDENCE: '证据不足', FAILED: '问答失败' }[data.status]));
            if (data.answer) item.append(node('p', data.answer));
            if (data.status === 'FAILED') item.append(node('p', '问答处理失败，请稍后自行重试。错误码：' + data.error));
            data.citations.forEach(c => item.append(node('p', '引用：' + c.evidenceId)));
            if (response.requestId) item.append(node('p', 'requestId：' + response.requestId));
            status.textContent = '本轮请求已完成。';
        } catch (error) {
            if (current !== generation || selectedKb !== kb) return;
            item.append(node('p', '请求未完成：' + ({ network: '网络连接失败', http: 'HTTP 请求失败', business: '业务请求失败', protocol: '响应格式异常', aborted: '等待已取消' }[error.kind] ?? '请求异常') + (error.requestId ? '；requestId：' + error.requestId : '')));
            status.textContent = '可自行再次发送；服务端可能已执行，重复发送可能重复调用模型。';
        } finally {
            if (current === generation) { clearTimeout(pending.timer); pending = null; controls(); }
        }
    });
    controls();
}
