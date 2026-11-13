import { initializeImport } from './import.js';
import { request, ApiError } from './api.js';
import { AppState, subscribe } from './state.js';

export function initializeKnowledge(refreshKnowledgeBases, getSelectionRevision = () => 0) {
    const root = document.querySelector('#knowledge');
    const node = (tag, text) => {
        const element = document.createElement(tag);
        if (text !== undefined) element.textContent = text;
        return element;
    };
    const button = (text, action, parent = root) => {
        const element = node('button', text);
        element.type = 'button';
        element.addEventListener('click', action);
        parent.append(element);
        return element;
    };
    root.querySelector('.actions').remove();
    root.querySelector('.empty').remove();
    const description = node('p');
    root.append(description);
    const form = node('form');
    form.hidden = true;
    const name = node('input');
    name.id = 'new-kb-name';
    const nameLabel = node('label', '名称（必填，最多100字）');
    nameLabel.htmlFor = name.id;
    const detail = node('textarea');
    detail.id = 'new-kb-description';
    const detailLabel = node('label', '描述（最多1000字）');
    detailLabel.htmlFor = detail.id;
    const submit = node('button', '创建');
    submit.type = 'submit';
    form.append(nameLabel, name, detailLabel, detail, submit);
    button('取消', () => { form.hidden = true; }, form);
    button('新建知识库', () => { form.hidden = false; name.focus(); });
    root.append(form);
    const notice = node('p');
    notice.setAttribute('role', 'status');
    root.append(notice);
    initializeImport(root, () => load(1));
    const status = node('p');
    status.setAttribute('role', 'status');
    const documents = node('div'); documents.className = 'document-list';
    root.append(status, documents);
    let busy = false;
    let page = 1;
    let last = 1n;
    let sequence = 0;
    let controller;
    let selected;
    const controls = [];
    const maxPage = 2147483647;
    const errorText = error => `${error instanceof ApiError ? error.message : '服务响应异常'}${error.requestId ? ' · requestId: ' + error.requestId : ''}`;
    async function load(target = 1) {
        if (!Number.isInteger(target) || target < 1 || target > maxPage) return;
        const kb = AppState.selectedKnowledgeBaseId;
        const current = ++sequence;
        controller?.abort();
        controller = new AbortController();
        documents.replaceChildren();
        if (!kb) {
            busy = false;
            status.textContent = '请选择或新建知识库；列表失败时可使用顶部重新加载。';
            controls.forEach(control => { control.disabled = true; });
            return;
        }
        busy = true;
        controls.forEach(control => { control.disabled = true; });
        status.textContent = '正在加载文档…';
        const activeController = controller;
        const timer = setTimeout(() => activeController.abort(), 20000);
        try {
            const { data } = await request(`../api/v1/knowledge-bases/${encodeURIComponent(kb)}/documents?page=${target}&pageSize=20`, { signal: controller.signal });
            // 取消等待之外再检查代次，保证旧知识库响应不能覆盖新上下文。
            if (current !== sequence || kb !== AppState.selectedKnowledgeBaseId) return;
            if (!data || !Array.isArray(data.items) || !Number.isInteger(data.page) || data.page !== target || data.page < 1 || data.page > 2147483647 || data.pageSize !== 20 || typeof data.total !== 'string' || !/^(0|[1-9]\d*)$/.test(data.total)) throw new ApiError('protocol');
            if (BigInt(data.total) > 9223372036854775807n) throw new ApiError('protocol');
            last = (BigInt(data.total) + 19n) / 20n || 1n;
            if (data.items.length > 20 || BigInt(data.items.length) > BigInt(data.total)) throw new ApiError('protocol');
            const publication = { PUBLISHED: '已发布', UNPUBLISHED: '未发布' };
            const tasks = { RECEIVED: '已接收', PROCESSING: '处理中', READY: '导入完成', FAILED: '导入失败' };
            for (const item of data.items) {
                if (!item || typeof item.documentId !== 'string' || typeof item.filename !== 'string' || typeof item.createdAt !== 'string' || !Object.hasOwn(publication, item.publicationStatus) || (item.importTask !== null && (!item.importTask || !Object.hasOwn(tasks, item.importTask.status)))) throw new ApiError('protocol');
            }
            page = data.page;
            status.textContent = `第 ${page} 页 / ${last} 页 · 共 ${data.total} 个文档${BigInt(page) > last ? ' · 当前页超出范围，请返回首页' : ''}${last > BigInt(maxPage) ? ' · 总页数超过接口页码上限，末页按钮仅到达最大可查询页' : ''}`;
            if (!data.items.length) documents.append(node('p', '暂无文档，可选择文件上传。'));
            for (const item of data.items) {
                const row = node('article'); row.className = 'document-row';
                row.append(node('h3', item.filename), node('p', `创建时间：${item.createdAt} · 发布状态：${publication[item.publicationStatus]}`));
                row.append(node('p', `最新导入任务：${item.importTask ? tasks[item.importTask.status] : '暂无任务'}`));
                if (item.importTask?.failureCode) row.append(node('p', '失败分类：' + item.importTask.failureCode));
                const metadata = node('details'); metadata.append(node('summary', '文档详情'));
                for (const key of ['documentId','activeVersionId','publishedAt']) metadata.append(node('p', key + '：' + (item[key] ?? '未提供')));
                if (item.publishedAt) metadata.append(node('p', '发布时间：' + item.publishedAt));
                if (item.importTask) for (const key of ['importTaskId','createdAt','updatedAt']) metadata.append(node('p', key + '：' + (item.importTask[key] ?? '未提供')));
                row.append(metadata);
                documents.append(row);
            }
        } catch (error) {
            // 当前代次的取消来自等待超时；切库导致的旧请求取消仍保持静默。
            if (current === sequence) status.textContent = (error.kind === 'aborted' ? '文档加载等待超时' : errorText(error)) + '，请刷新文档重试。';
        } finally {
            clearTimeout(timer);
            if (current === sequence) {
                busy = false;
                controls.forEach((control, index) => { control.disabled = index < 2 ? page <= 1 : BigInt(page) >= last || page >= maxPage;  });
            }
        }
    }
    button('刷新文档', () => { if (!busy) load(page); });
    const pagination = node('div');
    pagination.className = 'actions';
    root.append(pagination);
    [['首页', () => 1], ['上一页', () => page - 1], ['下一页', () => Math.min(page + 1, 2147483647)], ['最后一页', () => Number(last > 2147483647n ? 2147483647n : last)]].forEach(([label, target]) => controls.push(button(label, () => { if (!busy) load(target()); }, pagination)));
    let creating = false;
    form.addEventListener('submit', async event => {
        event.preventDefault();
        if (creating) return;
        const value = name.value.trim();
        const description = detail.value.trim();
        if (!value || value.length > 100 || description.length > 1000) {
            notice.textContent = '名称必填且最多100字，描述最多1000字。';
            return;
        }
        creating = true;
        submit.disabled = true;
        // 创建期间用户的新选择优先于旧创建请求的自动选中。
        const selectionRevision = getSelectionRevision();
        const abort = new AbortController();
        const timer = setTimeout(() => abort.abort(), 20000);
        try {
            const { data } = await request('../api/v1/knowledge-bases', { method: 'POST', body: { name: value, description }, signal: abort.signal });
            if (!data || typeof data.id !== 'string' || !/^[1-9]\d*$/.test(data.id)) throw new ApiError('protocol');
            form.hidden = true;
            name.value = '';
            detail.value = '';
            const refreshed = await refreshKnowledgeBases(data.id, selectionRevision);
            notice.textContent = refreshed ? '知识库创建成功。' : '创建已成功，列表刷新失败，请使用顶部重新加载。';
        } catch (error) {
            notice.textContent = errorText(error) + '。创建结果可能未知，请先使用顶部重新加载确认，勿重复提交。';
        } finally {
            clearTimeout(timer);
            creating = false;
            submit.disabled = false;
        }
    });
    subscribe(state => {
        description.textContent = state.knowledgeBases.find(kb => kb.id === state.selectedKnowledgeBaseId)?.description ?? '';
        if (selected !== state.selectedKnowledgeBaseId) {
            selected = state.selectedKnowledgeBaseId;
            page = 1;
            load(1);
        }
    });
}
