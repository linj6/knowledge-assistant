import { request, ApiError } from './api.js';
import { AppState, subscribe } from './state.js';
const id = value => typeof value === 'string' && /^[1-9]\d*$/.test(value);
export function validateEvidence(data, citation) {
    if (!data || ['chunkId', 'documentId', 'indexVersionId'].some(key => !id(data[key]) || data[key] !== citation[key]) || typeof data.filename !== 'string' || (data.headingPath !== null && typeof data.headingPath !== 'string') || typeof data.content !== 'string' || !Number.isSafeInteger(data.sourceStart) || !Number.isSafeInteger(data.sourceEnd) || data.sourceStart < 0 || data.sourceEnd <= data.sourceStart) throw new ApiError('protocol');
    return data;
}
export function createEvidence(root) {
    const node = (tag, text) => { const el = document.createElement(tag); if (text !== undefined) el.textContent = text; return el; };
    const dialog = node('dialog'); dialog.className = 'evidence-drawer'; dialog.setAttribute('aria-labelledby', 'evidence-title');
    const title = node('h2', '引用证据'); title.id = 'evidence-title';
    const closeButton = node('button', '关闭证据'); closeButton.type = 'button';
    const status = node('p'); status.setAttribute('role', 'status'); status.setAttribute('aria-live', 'polite');
    const body = node('div'); const retry = node('button', '重试读取'); retry.type = 'button'; retry.hidden = true;
    dialog.append(closeButton, title, status, body, retry); root.append(dialog);
    let generation = 0, pending = null, context = null, trigger = null;
    function invalidate() { generation++; if (pending) { clearTimeout(pending.timer); pending.controller.abort(); pending = null; } }
    function close() { invalidate(); context = null; body.replaceChildren(); if (dialog.open) dialog.close(); if (trigger?.isConnected) trigger.focus(); trigger = null; }
    closeButton.addEventListener('click', close);
    dialog.addEventListener('cancel', event => { event.preventDefault(); close(); });
    dialog.addEventListener('close', () => { if (!dialog.open && context) close(); });
    subscribe(state => { if (context && (state.activePage !== 'chat' || state.selectedKnowledgeBaseId !== context.kb)) close(); });
    window.addEventListener('pagehide', close);
    async function load() {
        if (!context || pending) return;
        const { kb, citation } = context;
        invalidate(); const current = generation; const controller = new AbortController();
        body.replaceChildren(); status.textContent = '正在读取证据…'; retry.hidden = true;
        // 超时也递增代次，即使底层忽略 abort，迟到正文仍不能写入。
        pending = { controller, timer: setTimeout(() => { if (current !== generation) return; invalidate(); status.textContent = '证据读取超时，请重试。'; retry.hidden = false; }, 20000) };
        try {
            const response = await request(`../api/v1/knowledge-bases/${kb}/documents/${citation.documentId}/versions/${citation.indexVersionId}/chunks/${citation.chunkId}`, { signal: controller.signal });
            if (current !== generation || !context) return;
            const data = validateEvidence(response.data, citation);
            body.append(node('h3', data.filename), node('p', data.headingPath ?? '无标题路径'), node('pre', data.content), node('p', `来源区间：[${data.sourceStart}, ${data.sourceEnd})（Unicode 码点）`));
            status.textContent = '证据已加载。';
        } catch (error) {
            if (current !== generation || !context) return;
            status.textContent = error.status === 404 ? '该引用证据当前不可用，可能已失效或不属于当前发布版本。' : error.kind === 'protocol' ? '证据响应不一致或格式异常（协议错误）。' : error.kind === 'network' ? '网络连接失败，请检查网络后重试。' : '证据读取失败，请稍后重试。';
            if (typeof error.requestId === 'string' && /^[A-Za-z0-9_-]{1,64}$/.test(error.requestId)) status.textContent += ' requestId：' + error.requestId;
            retry.hidden = false;
        } finally { if (current === generation && pending) { clearTimeout(pending.timer); pending = null; } }
    }
    retry.addEventListener('click', load);
    return { close, open(kb, citation, button) {
        if (!id(kb) || !citation || !/^E[1-9]\d*$/.test(citation.evidenceId) || !['chunkId', 'documentId', 'indexVersionId'].every(key => id(citation[key])) || AppState.activePage !== 'chat' || AppState.selectedKnowledgeBaseId !== kb) return;
        invalidate(); context = { kb, citation: { ...citation } }; trigger = button;
        title.textContent = citation.evidenceId + ' · 引用证据';
        if (!dialog.open) dialog.showModal(); closeButton.focus(); void load();
    } };
}
