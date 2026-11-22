import { ApiError, request } from './api.js';
import { AppState, subscribe } from './state.js';

const path = '../api/v1/conversations';
const longId = (value) => typeof value === 'string' && /^[1-9]\d*$/.test(value);
const statuses = {
    PROCESSING: '处理中',
    CANCEL_REQUESTED: '正在取消',
    ANSWERED: '已回答',
    INSUFFICIENT_EVIDENCE: '证据不足',
    FAILED: '问答失败',
    CANCELLED: '已取消',
};

function validConversation(data, kb) {
    return longId(data?.id) && data.knowledgeBaseId === kb && typeof data.title === 'string';
}

export function initializeConversations(root, node, evidence, appendCitations) {
    const panel = node('div');
    panel.className = 'conversation-panel';
    const create = node('button', '新建会话');
    const list = node('ul');
    const status = node('p');
    const retry = node('button', '重试');
    const history = node('div');
    history.className = 'chat-history';
    history.setAttribute('aria-label', '会话历史');
    status.setAttribute('role', 'status');
    create.type = retry.type = 'button';
    panel.append(node('h2', '会话'), create, list, status, retry, history);
    root.append(panel);

    let kb = '';
    let user = null;
    let pending;
    let selected = '';
    let conversations = [];
    let retryAction;

    function renderList() {
        list.replaceChildren();
        for (const conversation of conversations) {
            const item = node('li');
            const button = node('button', conversation.title || '未命名会话');
            button.type = 'button';
            if (conversation.id === selected) button.setAttribute('aria-current', 'true');
            button.addEventListener('click', () => loadHistory(conversation.id));
            item.append(button);
            list.append(item);
        }
    }

    function invalidate() {
        if (pending) {
            clearTimeout(pending.timer);
            pending.controller.abort();
            pending = undefined;
        }
    }

    async function run(label, onError, work) {
        invalidate();
        const controller = new AbortController();
        const operation = { controller };
        pending = operation;
        const isCurrent = () => pending === operation;
        const fail = (message) => {
            invalidate();
            status.textContent = label + '失败。' + message;
            retryAction = onError;
            retry.hidden = false;
            create.disabled = false;
        };
        operation.timer = setTimeout(() => fail('请求超时。'), 20000);
        status.textContent = '正在' + label + '…';
        retry.hidden = true;
        create.disabled = true;
        try {
            await work(controller.signal, isCurrent);
        } catch (error) {
            if (isCurrent()) fail(error.kind === 'network' ? '网络连接失败。' : '');
        } finally {
            if (isCurrent()) {
                clearTimeout(operation.timer);
                pending = undefined;
                create.disabled = false;
            }
        }
    }

    function loadHistory(id) {
        evidence.close();
        selected = id;
        renderList();
        history.replaceChildren();
        const currentKb = kb;
        void run(
            '读取会话历史',
            () => loadHistory(id),
            async (signal, isCurrent) => {
                const { data: detail } = await request(path + '/' + id, { signal });
                if (!isCurrent()) return;
                if (!validConversation(detail, currentKb) || detail.id !== id) throw new ApiError('protocol');

                const messages = [];
                const pageSize = 100;
                // 按服务端顺序合并各页，旧会话失效后即停止请求后续页。
                for (let page = 1; ; page++) {
                    const { data } = await request(`${path}/${id}/messages?page=${page}&pageSize=${pageSize}`, {
                        signal,
                    });
                    if (!isCurrent()) return;
                    if (
                        data?.page !== page ||
                        data.pageSize !== pageSize ||
                        typeof data.hasNext !== 'boolean' ||
                        !Array.isArray(data.items) ||
                        (data.hasNext && data.items.length !== pageSize)
                    )
                        throw new ApiError('protocol');
                    messages.push(...data.items);
                    if (!data.hasNext) break;
                }
                const items = messages.map((message) => {
                    if (
                        !longId(message?.id) ||
                        message.conversationId !== id ||
                        typeof message.turnId !== 'string' ||
                        !['USER', 'ASSISTANT'].includes(message.role) ||
                        (message.role === 'ASSISTANT'
                            ? !Object.hasOwn(statuses, message.status)
                            : message.status != null) ||
                        (message.content != null && typeof message.content !== 'string') ||
                        !Array.isArray(message.citations)
                    )
                        throw new ApiError('protocol');
                    const article = node('article');
                    article.className = 'chat-turn';
                    article.append(node('h3', message.role === 'USER' ? '你' : '助手'));
                    if (message.content) article.append(node('p', message.content));
                    if (message.status) article.append(node('p', statuses[message.status]));
                    if (message.errorCode) article.append(node('p', '错误码：' + message.errorCode));
                    appendCitations(article, currentKb, message.citations);
                    return article;
                });
                history.replaceChildren(...items);
                status.textContent = items.length ? '' : '此会话暂无消息。';
            },
        );
    }

    function loadList() {
        const currentKb = kb;
        void run('加载会话列表', loadList, async (signal, isCurrent) => {
            const { data } = await request(path + '?knowledgeBaseId=' + encodeURIComponent(currentKb), { signal });
            if (!isCurrent()) return;
            if (!Array.isArray(data) || data.some((item) => !validConversation(item, currentKb)))
                throw new ApiError('protocol');
            conversations = data;
            renderList();
            if (data.length) loadHistory(data[0].id);
            else status.textContent = '当前知识库暂无会话。';
        });
    }

    retry.addEventListener('click', () => retryAction?.());
    create.addEventListener('click', () => {
        if (!kb || pending) return;
        const currentKb = kb;
        void run('创建会话', loadList, async (signal, isCurrent) => {
            const { data } = await request(path, {
                method: 'POST',
                body: { knowledgeBaseId: currentKb },
                signal,
            });
            if (!isCurrent()) return;
            if (!validConversation(data, currentKb)) throw new ApiError('protocol');
            conversations = [data, ...conversations.filter((item) => item.id !== data.id)];
            loadHistory(data.id);
        });
    });

    function sync(state) {
        const nextKb = state.user && state.activePage === 'chat' ? state.selectedKnowledgeBaseId : '';
        if (nextKb === kb && state.user === user) return;
        invalidate();
        evidence.close();
        kb = nextKb;
        user = state.user;
        selected = '';
        conversations = [];
        renderList();
        history.replaceChildren();
        retry.hidden = true;
        create.disabled = !kb;
        status.textContent = kb ? '' : '请先选择知识库。';
        if (kb) loadList();
    }

    retry.hidden = true;
    create.disabled = true;
    subscribe(sync);
    sync(AppState);
}
