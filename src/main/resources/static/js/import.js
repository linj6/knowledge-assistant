import { request, ApiError } from './api.js';
import { AppState, subscribe } from './state.js';

export function validateFile(file) {
    return !!file && typeof file.name === 'string' && file.name.length <= 255 &&
        !/[\\/\x00-\x1f\x7f-\x9f]/.test(file.name) && /\.(md|txt)$/i.test(file.name) &&
        file.size > 0 && file.size <= 2 * 1024 * 1024;
}
export function validateTask(task) {
    const id = value => typeof value === 'string' && /^[1-9]\d*$/.test(value) && BigInt(value) <= 9223372036854775807n;
    if (!task || !id(task.importTaskId) || !id(task.documentId) ||
        !['RECEIVED', 'PROCESSING', 'READY', 'FAILED'].includes(task.status) ||
        typeof task.createdAt !== 'string' || typeof task.updatedAt !== 'string' ||
        (task.failureCode !== null && typeof task.failureCode !== 'string')) throw new ApiError('protocol');
    return task;
}
export function initializeImport(root, refreshDocuments) {
    const node = (tag, text) => { const el = document.createElement(tag); el.textContent = text ?? ''; return el; };
    const area = node('article');
    area.append(node('h2', '上传文档'), node('p', '支持 UTF-8 .md / .txt，非空，最大 2 MiB。刷新页面后通过文档列表确认状态；当前任务不持久保存。'));
    const input = node('input'); input.type = 'file'; input.accept = '.md,.txt'; input.id = 'import-file';
    const label = node('label', '选择一个文件'); label.htmlFor = input.id;
    const info = node('p', 'IDLE · 尚未选择文件');
    const message = node('p'); message.setAttribute('role', 'status');
    const taskText = node('p');
    const upload = node('button', '上传文件'); upload.type = 'button';
    const manual = node('button', '刷新任务状态'); manual.type = 'button';
    const chat = node('a', '进入 AI 问答'); chat.href = '#chat'; chat.hidden = true;
    area.append(label, input, info, upload, message, taskText, manual, chat); root.append(area);
    let kb = AppState.selectedKnowledgeBaseId, task = null, file = null, uploading = false;
    let epoch = 0, timer, active, failures = 0;
    const visible = () => AppState.activePage === 'knowledge' && !document.hidden;
    // 每次暂停都使旧响应失效，旧请求不能重新建立轮询链。
    function stop() { epoch++; clearTimeout(timer); timer = null; active?.abort(); active = null; }
    function render() { input.disabled = uploading || !kb; upload.disabled = uploading || !kb || !validateFile(file); manual.disabled = uploading || !task || !visible(); }
    function accept(value) {
        task = validateTask(value);
        taskText.textContent = `任务 ${task.importTaskId} · ${task.status}${task.failureCode ? ' · ' + task.failureCode : ''}`;
        chat.hidden = task.status !== 'READY';
        if (['READY', 'FAILED'].includes(task.status)) refreshDocuments();
    }
    async function query() {
        if (!task || !visible() || active || uploading) return;
        clearTimeout(timer); timer = null;
        const current = epoch, expected = task.importTaskId;
        const abort = new AbortController(); active = abort;
        const timeout = setTimeout(() => abort.abort(), 15000);
        let again = false;
        try {
            const { data } = await request(`../api/v1/import-tasks/${encodeURIComponent(expected)}`, { signal: abort.signal });
            if (current !== epoch) return;
            validateTask(data);
            if (data.importTaskId !== expected || data.documentId !== task.documentId) throw new ApiError('protocol');
            accept(data); failures = 0; message.textContent = '';
            again = ['RECEIVED', 'PROCESSING'].includes(task.status);
        } catch (error) {
            if (current !== epoch) return;
            failures++;
            again = ['network', 'aborted'].includes(error.kind) && failures < 3;
            message.textContent = `${error.kind === 'aborted' ? '任务查询等待超时' : error.message}。${again ? '稍后再次查询。' : '自动查询已停止，可手动刷新。'}任务未被标记失败。`;
        } finally {
            clearTimeout(timeout);
            if (current === epoch) {
                active = null; render();
                if (again && visible()) timer = setTimeout(() => { timer = null; if (current === epoch) query(); }, 3000 * Math.max(1, failures));
            }
        }
    }
    input.addEventListener('change', () => {
        file = input.files?.length === 1 ? input.files[0] : null;
        info.textContent = file ? `${file.name} · ${file.size} 字节 · ${validateFile(file) ? 'SELECTED' : '文件不符合规则'}` : 'IDLE · 尚未选择文件'; render();
    });
    upload.addEventListener('click', async () => {
        if (uploading || !kb || !validateFile(file)) return;
        stop(); failures = 0; task = null; taskText.textContent = ''; chat.hidden = true; uploading = true; render();
        const current = epoch, target = kb, abort = new AbortController(); active = abort;
        info.textContent = 'UPLOADING · 正在等待上传结果';
        const timeout = setTimeout(() => abort.abort(), 60000);
        try {
            const formData = new FormData(); formData.append('file', file);
            const result = await request(`../api/v1/knowledge-bases/${encodeURIComponent(target)}/documents:import`, { method: 'POST', body: formData, signal: abort.signal });
            if (current !== epoch) return;
            if (![200, 202].includes(result.status)) throw new ApiError('protocol');
            accept(result.data); info.textContent = 'ACCEPTED · 已确认服务端任务';
            message.textContent = result.status === 200 ? '该文件内容已存在，已返回现有导入任务' : '新导入任务已创建';
        } catch (error) {
            if (current !== epoch) return;
            info.textContent = 'UNKNOWN · 上传结果尚未确认';
            message.textContent = `${error.kind === 'aborted' ? '上传等待超时' : error.message}。本次上传结果尚未确认。文件可能已经被服务器接收，请先刷新文档列表或任务状态，确认后再决定是否重新上传。`;
        } finally {
            clearTimeout(timeout);
            if (current === epoch) { uploading = false; active = null; render(); if (task && visible() && ['RECEIVED', 'PROCESSING'].includes(task.status)) query(); }
        }
    });
    manual.addEventListener('click', () => { if (!active) { failures = 0; query(); } });
    function lifecycle() {
        if (kb !== AppState.selectedKnowledgeBaseId) {
            stop(); kb = AppState.selectedKnowledgeBaseId; task = null; file = null; uploading = false;
            input.value = ''; info.textContent = 'IDLE · 尚未选择文件'; message.textContent = ''; taskText.textContent = ''; chat.hidden = true;
        } else if (!visible() && !uploading) stop();
        render();
        if (visible() && task && !active && !timer && ['RECEIVED', 'PROCESSING'].includes(task.status)) query();
    }
    subscribe(lifecycle);
    document.addEventListener?.('visibilitychange', lifecycle);
    render();
}
