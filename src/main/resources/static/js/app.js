import {
    ApiError,
    request
} from './api.js';
import {
    AppState,
    subscribe,
    updateState
} from './state.js';
const pages = ['chat', 'knowledge', 'retrieval'];
const selector = document.querySelector('#kb');
const status = document.querySelector('#status');
const reload = document.querySelector('#reload');
// Hash 是唯一导航来源，刷新和浏览器历史使用同一条更新路径。
function navigate() {
    const page = location.hash.slice(1);
    if (!pages.includes(page)) {
        location.replace('#chat');
        return;
    }
    updateState({
        activePage: page
    });
}
subscribe(state => {
    pages.forEach(page => {
        document.getElementById(page).hidden = page !== state.activePage;
    });
    document.querySelectorAll('nav a').forEach(link => {
        if (link.hash === '#' + state.activePage) link.setAttribute('aria-current', 'page');
        else link.removeAttribute('aria-current');
    });
    const selected = state.knowledgeBases.find(kb => kb.id === state.selectedKnowledgeBaseId);
    document.querySelectorAll('.context').forEach(el => {
        el.textContent = '当前知识库 · ' + (selected?.name ?? '尚未选择');
    });
});
selector.addEventListener('change', () => updateState({
    selectedKnowledgeBaseId: selector.value
}));
async function loadKnowledgeBases() {
    reload.disabled = true;
    selector.disabled = true;
    status.textContent = '正在加载知识库…';
    try {
        const {
            data
        } = await request('../api/v1/knowledge-bases');
        // 拒绝数值 ID，避免精度损失后悄悄绑定错误知识库。
        if (!Array.isArray(data) || data.some(kb => !kb || typeof kb.id !== 'string' || typeof kb.name !== 'string')) throw new ApiError('protocol');
        const selected = data.some(kb => kb.id === AppState.selectedKnowledgeBaseId) ? AppState.selectedKnowledgeBaseId : (data[0]?.id ?? '');
        selector.replaceChildren();
        for (const kb of data) {
            const option = document.createElement('option');
            option.value = kb.id;
            option.textContent = kb.name;
            selector.append(option);
        }
        if (!data.length) {
            const option = document.createElement('option');
            option.textContent = '暂无知识库';
            selector.append(option);
        }
        selector.value = selected;
        updateState({
            knowledgeBases: data,
            selectedKnowledgeBaseId: selected,
            initialized: true
        });
        selector.disabled = !data.length;
        status.textContent = data.length ? '知识库已加载' : '暂无知识库，创建功能将在 WEB-03 接入。';
    } catch (error) {
        selector.replaceChildren();
        const option = document.createElement('option');
        option.textContent = '知识库不可用';
        selector.append(option);
        updateState({
            knowledgeBases: [],
            selectedKnowledgeBaseId: '',
            initialized: true
        });
        const kind = error instanceof ApiError ? error.kind : 'protocol';
        const messages = {
            network: '网络连接失败，请检查网络后重试。',
            http: '服务暂时不可用，请稍后重试。',
            business: '知识库加载失败，请稍后重试。',
            protocol: '服务响应异常，请稍后重试。',
            aborted: '加载已取消，请重试。'
        };
        status.textContent = messages[kind];
        // 仅输出诊断元数据，不输出响应正文、异常对象或堆栈。
        console.warn('知识库加载失败', {
            kind,
            status: error.status ?? null,
            requestId: error.requestId ?? null
        });
    } finally {
        reload.disabled = false;
    }
}
window.addEventListener('hashchange', navigate);
reload.addEventListener('click', loadKnowledgeBases);
navigate();
loadKnowledgeBases();
