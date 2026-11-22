import { ApiError, request } from './api.js';
import { AppState, subscribe } from './state.js';

const path = '../api/v1/conversations';
const longId = (value) => typeof value === 'string' && /^[1-9]\d*$/.test(value);
const statuses = {
    PROCESSING: '正在生成',
    CANCEL_REQUESTED: '正在取消',
    ANSWERED: '已回答',
    INSUFFICIENT_EVIDENCE: '证据不足',
    FAILED: '问答失败',
    CANCELLED: '已取消',
};

const active = (status) => status === 'PROCESSING' || status === 'CANCEL_REQUESTED';
const uuid = (value) => typeof value === 'string' && /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(value);

function validCitations(citations) {
    return (
        Array.isArray(citations) &&
        citations.every(
            (citation) =>
                citation &&
                /^E[1-9]\d*$/.test(citation.evidenceId) &&
                longId(citation.chunkId) &&
                longId(citation.documentId) &&
                longId(citation.indexVersionId) &&
                Number.isSafeInteger(citation.sourceStart) &&
                Number.isSafeInteger(citation.sourceEnd) &&
                citation.sourceStart >= 0 &&
                citation.sourceEnd > citation.sourceStart,
        ) &&
        new Set(citations.map((citation) => citation.evidenceId)).size === citations.length
    );
}

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
    const draftNotice = node('p');
    draftNotice.setAttribute('role', 'status');
    const history = node('div');
    history.className = 'chat-history';
    history.setAttribute('aria-label', '会话历史');
    status.setAttribute('role', 'status');
    create.type = retry.type = 'button';
    panel.append(node('h2', '会话'), create, list, status, draftNotice, retry, history);
    const form = node('form');
    form.className = 'chat-composer';
    const label = node('label', '你的问题');
    label.setAttribute('for', 'question');
    const question = node('textarea');
    question.id = 'question';
    question.required = true;
    question.maxLength = 4000;
    const send = node('button', '发送');
    send.type = 'submit';
    const cancel = node('button', '取消生成');
    cancel.type = 'button';
    form.append(label, question, send, cancel);
    panel.append(form);
    root.append(panel);

    let kb = '';
    let user = null;
    let pending;
    let selected = '';
    let conversations = [];
    let retryAction;

    let ready = false;
    let turn;
    let pollTimer;
    let items = new Map();
    const drafts = new Map();

    function storage(key, value) {
        try {
            if (value === undefined) return JSON.parse(sessionStorage.getItem(key) ?? 'null');
            if (value === null) sessionStorage.removeItem(key);
            else sessionStorage.setItem(key, JSON.stringify(value));
        } catch {
            // 浏览器禁用会话存储时，当前页面仍可以按原 ID 重试。
        }
    }

    const selectionKey = () => `ka.conversation:${user?.id}:${kb}`;
    const draftKey = () => `ka.turn:${user?.id}:${selected}`;

    function getDraft() {
        const key = draftKey();
        const draft = drafts.get(key) ?? storage(key);
        if (uuid(draft?.turnId) && typeof draft.question === 'string') {
            drafts.set(key, draft);
            return draft;
        }
    }

    function saveDraft(value) {
        const key = draftKey();
        if (value) drafts.set(key, value);
        else drafts.delete(key);
        storage(key, value);
    }

    function controls() {
        const draft = selected ? getDraft() : undefined;
        draftNotice.textContent = draft?.blockedByActiveTurn
            ? '提交时此会话已有一轮正在执行。原问题已保留，可在当前轮结束后手动重试提交。'
            : '';
        draftNotice.hidden = !draftNotice.textContent;
        send.disabled = question.disabled = !ready || !!turn;
        cancel.hidden = turn?.status !== 'PROCESSING';
        cancel.disabled = cancel.hidden || !!pending;
    }

    function resetHistory() {
        clearTimeout(pollTimer);
        ready = false;
        turn = undefined;
        items = new Map();
        question.value = '';
        history.replaceChildren();
        controls();
    }

    function renderMessage(message) {
        const key = message.turnId + ':' + message.role;
        let article = items.get(key);
        if (!article) {
            article = node('article');
            article.className = 'chat-turn';
            items.set(key, article);
            history.append(article);
        }
        article.replaceChildren(node('h3', message.role === 'USER' ? '你' : '助手'));
        if (message.content) article.append(node('p', message.content));
        if (message.status) article.append(node('p', statuses[message.status]));
        if (message.errorCode) article.append(node('p', '错误码：' + message.errorCode));
        appendCitations(article, kb, message.citations);
    }

    function renderTurn(data) {
        renderMessage({ turnId: data.turnId, role: 'USER', content: data.question, citations: [] });
        renderMessage({
            turnId: data.turnId,
            role: 'ASSISTANT',
            content: data.answer,
            status: data.status,
            errorCode: data.errorCode,
            citations: data.citations,
        });
    }

    function schedulePoll() {
        pollTimer = setTimeout(readTurn, 1500);
    }

    function readTurn() {
        const current = turn;
        void run('查询本轮状态', readTurn, async (signal, isCurrent) => {
            let data;
            try {
                ({ data } = await request(`${path}/${selected}/turns/${current.turnId}`, { signal }));
            } catch (error) {
                if (!isCurrent()) return;
                // 仅在服务端明确表示该轮不存在时开放原样重提。
                if (error.code !== 40400 || current.status || !current.question) throw error;
                status.textContent = '服务端尚未查到本轮，可使用原问题重试提交。';
                retry.textContent = '重试提交';
                retryAction = submitTurn;
                retry.hidden = false;
                return;
            }
            if (!isCurrent()) return;
            if (
                data?.turnId !== current.turnId ||
                !Object.hasOwn(statuses, data.status) ||
                typeof data.question !== 'string' ||
                (data.answer != null && typeof data.answer !== 'string') ||
                !longId(data.userMessageId) ||
                !longId(data.assistantMessageId) ||
                !validCitations(data.citations)
            )
                throw new ApiError('protocol');
            renderTurn(data);
            if (getDraft()?.turnId === data.turnId) saveDraft(null);
            status.textContent = '';
            if (active(data.status)) {
                current.status = data.status;
                schedulePoll();
            } else if (getDraft()) {
                loadHistory(selected);
            } else {
                turn = undefined;
            }
        });
    }

    function submitTurn() {
        const current = turn;
        void run('提交问题', readTurn, async (signal, isCurrent) => {
            try {
                const { data } = await request(`${path}/${selected}/turns`, {
                    method: 'POST',
                    body: { turnId: current.turnId, question: current.question },
                    signal,
                });
                if (!isCurrent()) return;
                if (data?.turnId !== current.turnId || !Object.hasOwn(statuses, data.status))
                    throw new ApiError('protocol');
                current.status = data.status;
                if (active(data.status)) renderMessage({ ...current, role: 'ASSISTANT', citations: [] });
            } catch (error) {
                if (!isCurrent()) return;
                if (error.code === 40902) {
                    saveDraft({
                        turnId: current.turnId,
                        question: current.question,
                        blockedByActiveTurn: true,
                    });
                    loadHistory(selected);
                    return;
                }
                if ([40000, 40901].includes(error.code)) {
                    saveDraft(null);
                    items.delete(current.turnId + ':USER');
                    history.replaceChildren(...items.values());
                    turn = undefined;
                    question.value = current.question;
                    status.textContent =
                        error.code === 40000 ? '问题不符合要求，请修改后发送。' : '本轮标识冲突，请重新发送。';
                    return;
                }
                // 提交结果未知时先查原轮次，避免重复投递。
            }
            readTurn();
        });
    }

    form.addEventListener('submit', (event) => {
        event.preventDefault();
        const text = question.value;
        if (!ready || turn || !text.trim() || text.length > 4000) return;
        turn = { turnId: crypto.randomUUID(), question: text };
        saveDraft(turn);
        question.value = '';
        renderMessage({ turnId: turn.turnId, role: 'USER', content: text, citations: [] });
        submitTurn();
    });

    let composing = false;
    question.addEventListener('compositionstart', () => (composing = true));
    question.addEventListener('compositionend', () => (composing = false));
    question.addEventListener('keydown', (event) => {
        if (event.key === 'Enter' && !event.shiftKey && !event.isComposing && !composing && event.keyCode !== 229) {
            event.preventDefault();
            form.requestSubmit();
        }
    });

    cancel.addEventListener('click', () => {
        if (turn?.status !== 'PROCESSING' || pending) return;
        clearTimeout(pollTimer);
        const current = turn;
        void run('请求取消', readTurn, async (signal, isCurrent) => {
            try {
                const { data } = await request(`${path}/${selected}/turns/${current.turnId}/cancel`, {
                    method: 'POST',
                    signal,
                });
                if (!isCurrent()) return;
                if (data?.turnId === current.turnId && Object.hasOwn(statuses, data.status)) {
                    current.status = data.status;
                    if (data.status === 'CANCEL_REQUESTED')
                        renderMessage({ ...current, role: 'ASSISTANT', citations: [] });
                }
            } catch {
                // 取消响应丢失时，通过查询确认服务端真实状态。
            }
            if (isCurrent()) readTurn();
        });
    });

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
            if (!isCurrent()) return;
            invalidate();
            status.textContent = label + '失败。' + message;
            retry.textContent = '重试';
            retryAction = onError;
            retry.hidden = false;
            create.disabled = !kb;
            controls();
        };
        operation.timer = setTimeout(() => fail('请求超时。'), 20000);
        status.textContent = '正在' + label + '…';
        retry.hidden = true;
        create.disabled = true;
        controls();
        try {
            await work(controller.signal, isCurrent);
        } catch (error) {
            if (isCurrent()) fail({ network: '网络连接失败。', protocol: '响应格式异常。' }[error.kind] ?? '');
        } finally {
            if (isCurrent()) {
                clearTimeout(operation.timer);
                pending = undefined;
                create.disabled = !kb;
                controls();
            }
        }
    }

    function loadHistory(id) {
        evidence.close();
        selected = id;
        renderList();
        resetHistory();
        storage(selectionKey(), id);
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
                for (const message of messages) {
                    if (
                        !longId(message?.id) ||
                        message.conversationId !== id ||
                        !uuid(message.turnId) ||
                        !['USER', 'ASSISTANT'].includes(message.role) ||
                        (message.role === 'ASSISTANT'
                            ? !Object.hasOwn(statuses, message.status)
                            : message.status != null) ||
                        (message.content != null && typeof message.content !== 'string') ||
                        !validCitations(message.citations)
                    )
                        throw new ApiError('protocol');
                }
                messages.forEach(renderMessage);
                const draft = getDraft();
                const assistant = messages.find((message) => message.role === 'ASSISTANT' && active(message.status));
                const alreadyStored =
                    draft &&
                    messages.some((message) => message.role === 'ASSISTANT' && message.turnId === draft.turnId);
                if (alreadyStored) saveDraft(null);
                if (assistant) turn = { turnId: assistant.turnId, status: assistant.status };
                else if (draft && !alreadyStored) {
                    turn = draft;
                    renderMessage({ ...draft, role: 'USER', content: draft.question, citations: [] });
                }
                ready = true;
                status.textContent = items.size ? '' : '此会话暂无消息。';
                if (turn) readTurn();
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
            if (data.length) loadHistory((data.find((item) => item.id === storage(selectionKey())) ?? data[0]).id);
            else status.textContent = '当前知识库暂无会话。';
        });
    }

    retry.addEventListener('click', () => retryAction?.());
    create.addEventListener('click', () => {
        if (!kb || pending) return;
        evidence.close();
        resetHistory();
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
        if (user && !state.user) {
            // 退出登录时清除本页保存的待确认问题。
            for (const key of drafts.keys()) storage(key, null);
            drafts.clear();
        }
        kb = nextKb;
        user = state.user;
        selected = '';
        conversations = [];
        renderList();
        resetHistory();
        retry.hidden = true;
        create.disabled = !kb;
        status.textContent = kb ? '' : '请先选择知识库。';
        if (kb) loadList();
    }

    retry.hidden = true;
    create.disabled = true;
    controls();
    window.addEventListener('pagehide', () => {
        invalidate();
        clearTimeout(pollTimer);
    });
    window.addEventListener('pageshow', (event) => {
        if (event.persisted && kb) {
            if (selected) loadHistory(selected);
            else loadList();
        }
    });
    subscribe(sync);
    sync(AppState);
}
