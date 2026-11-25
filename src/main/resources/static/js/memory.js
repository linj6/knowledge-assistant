import { ApiError, request } from './api.js';
import { subscribe } from './state.js';

const types = {
    BACKGROUND: '技术背景',
    LEARNING_GOAL: '学习目标',
    ANSWER_PREFERENCE: '回答偏好',
    TECH_PREFERENCE: '技术偏好',
    LEARNING_STATUS: '学习情况',
    OTHER: '其他',
};
const settingsPath = '/api/v1/memory/settings';
const memoriesPath = '/api/v1/memories';
const capacity = 50;

function node(tag, text) {
    const element = document.createElement(tag);
    element.textContent = text;
    return element;
}

export function appendMemoryTrace(article, trace) {
    if (!Number.isInteger(trace?.injectedCount) || trace.injectedCount <= 0) return;
    const details = node('details', '');
    details.className = 'memory-trace';
    details.append(node('summary', '本轮已提供 ' + trace.injectedCount + ' 条长期记忆'));
    const counts = new Map();
    for (const item of Array.isArray(trace.memories) ? trace.memories : []) {
        const type = item?.type;
        const label = Object.hasOwn(types, type) ? types[type] : types.OTHER;
        counts.set(label, (counts.get(label) ?? 0) + 1);
    }
    if (counts.size) {
        const list = node('ul', '');
        for (const [label, count] of counts) list.append(node('li', label + ' · ' + count + ' 条'));
        details.append(list);
    }
    details.append(node('p', '这里统计提交给模型的记忆，模型可能只参考其中一部分。长期记忆不属于知识库引用证据。'));
    article.append(details);
}

// 对齐 Java String.strip() 的空白范围，并按 Unicode 码点计数。
const javaEdgeWhitespace =
    /^[\u0009-\u000d\u001c-\u0020\u1680\u2000-\u2006\u2008-\u200a\u2028\u2029\u205f\u3000]+|[\u0009-\u000d\u001c-\u0020\u1680\u2000-\u2006\u2008-\u200a\u2028\u2029\u205f\u3000]+$/g;

function validate(content, keywords) {
    const normalized = content.replace(javaEdgeWhitespace, '');
    if (!normalized) return '请输入记忆正文。';
    if ([...normalized].length > 500) return '正文最多 500 字符。';
    if ([...keywords].length > 200) return '关键词最多 200 字符。';
    if (normalized.includes('\0') || keywords.includes('\0')) return '正文和关键词不能包含空字符。';
    return '';
}

function errorMessage(error) {
    if (error.code === 40903) return '相同记忆已存在。';
    if (error.code === 40904) return '记忆数量已达 50 条上限。';
    if (error.code === 40403) return '记忆已不存在，请重新加载列表。';
    if (error.status === 400) return '请检查记忆类型、正文及关键词。';
    return error.message;
}

export function initializeMemory() {
    const get = (name) => document.getElementById('memory-' + name);
    const toggle = get('enabled');
    const settingsStatus = get('settings-status');
    const list = get('list');
    const status = get('status');
    const count = get('count');
    const reload = get('reload');
    const add = get('add');
    const form = get('form');
    const title = get('form-title');
    const type = get('type');
    const hint = get('type-hint');
    const content = get('content');
    const keywords = get('keywords');
    const save = get('save');
    const cancel = get('cancel');
    const formStatus = get('form-status');
    for (const [value, label] of Object.entries(types)) {
        const option = node('option', label);
        option.value = value;
        type.append(option);
    }

    let session;
    let userId;
    let enabled = null;
    let memories = null;
    let editing = null;
    let busy = false;

    function describeType() {
        hint.textContent = ['BACKGROUND', 'ANSWER_PREFERENCE'].includes(type.value)
            ? '全局型记忆，无需关键词即可参与召回。'
            : '主题型记忆，没有有效关键词时不会被规则召回。';
    }

    function closeForm() {
        editing = null;
        form.hidden = true;
        content.value = keywords.value = formStatus.textContent = '';
        type.value = 'BACKGROUND';
        describeType();
    }

    function openForm(memory) {
        if (busy || !form.hidden) return;
        editing = memory;
        type.value = memory?.type ?? 'BACKGROUND';
        content.value = memory?.content ?? '';
        keywords.value = memory?.keywords ?? '';
        title.textContent = memory ? '编辑记忆' : '新增记忆';
        describeType();
        form.hidden = false;
        render();
        content.focus();
    }

    function render() {
        toggle.checked = enabled === true;
        toggle.disabled = busy || enabled === null;
        reload.disabled = busy;
        add.disabled = busy || !form.hidden || memories === null || memories.length >= capacity;
        save.disabled = cancel.disabled = type.disabled = content.disabled = keywords.disabled = busy;
        count.textContent = memories === null ? '' : '已保存 ' + memories.length + ' / ' + capacity + ' 条';
        list.replaceChildren();
        if (memories?.length === 0) list.append(node('p', '暂无记忆。'));
        function action(label, callback) {
            const button = node('button', label);
            button.type = 'button';
            button.disabled = busy || !form.hidden;
            button.addEventListener('click', callback);
            return button;
        }
        for (const memory of memories ?? []) {
            const card = node('article', '');
            const body = node('p', memory.content);
            body.className = 'memory-content';
            const actions = node('div', '');
            actions.className = 'actions';
            actions.append(
                action('编辑', () => openForm(memory)),
                action(memory.enabled ? '停用' : '启用', () =>
                    mutate(memory.id, 'PUT', payload(memory, !memory.enabled), status),
                ),
                action('删除', () => {
                    if (!busy && form.hidden && window.confirm('确定删除这条记忆？')) {
                        void mutate(memory.id, 'DELETE', undefined, status);
                    }
                }),
            );
            card.append(
                node('h3', types[memory.type] + ' · ' + (memory.enabled ? '已启用' : '已停用')),
                body,
                node('p', '主题关键词 · ' + (memory.keywords || '无')),
                actions,
            );
            list.append(card);
        }
    }

    async function run(work) {
        if (!session || busy) return;
        const current = session;
        const signal = current.signal;
        busy = true;
        render();
        try {
            await work(signal);
        } finally {
            if (current === session) {
                busy = false;
                render();
            }
        }
    }

    async function loadList(signal) {
        status.textContent = '正在加载记忆…';
        try {
            const { data } = await request(memoriesPath, { signal });
            if (
                !Array.isArray(data) ||
                data.some((memory) => typeof memory?.id !== 'string' || !/^[1-9]\d*$/.test(memory.id))
            ) {
                throw new ApiError('protocol');
            }
            if (signal.aborted) return;
            memories = data;
            status.textContent = data.length >= capacity ? '记忆数量已达 50 条上限。' : '';
        } catch (error) {
            if (signal.aborted) return;
            memories = null;
            status.textContent = '记忆列表加载失败。' + errorMessage(error);
        }
    }

    async function loadSettings(signal) {
        settingsStatus.textContent = '正在加载设置…';
        try {
            const { data } = await request(settingsPath, { signal });
            if (typeof data?.memoryEnabled !== 'boolean') throw new ApiError('protocol');
            if (signal.aborted) return;
            enabled = data.memoryEnabled;
            settingsStatus.textContent = '';
        } catch (error) {
            if (signal.aborted) return;
            enabled = null;
            settingsStatus.textContent = '设置加载失败。' + errorMessage(error);
        }
    }

    const load = () => run((signal) => Promise.all([loadSettings(signal), loadList(signal)]));
    function payload({ type, content, keywords }, enabled) {
        return { type, content, keywords, ...(enabled === undefined ? {} : { enabled }) };
    }

    function mutate(id, method, body, notice) {
        return run(async (signal) => {
            notice.textContent = '正在提交…';
            try {
                await request(id ? memoriesPath + '/' + id : memoriesPath, { method, body, signal });
                if (signal.aborted) return;
                closeForm();
                memories = null;
                await loadList(signal);
            } catch (error) {
                if (!signal.aborted) notice.textContent = '操作失败。' + errorMessage(error);
            }
        });
    }

    toggle.addEventListener('change', () => {
        const memoryEnabled = toggle.checked;
        return run(async (signal) => {
            toggle.checked = memoryEnabled;
            settingsStatus.textContent = '正在保存设置…';
            try {
                const { data } = await request(settingsPath, { method: 'PUT', body: { memoryEnabled }, signal });
                if (typeof data?.memoryEnabled !== 'boolean') throw new ApiError('protocol');
                if (signal.aborted) return;
                enabled = data.memoryEnabled;
                settingsStatus.textContent = '设置已保存。';
            } catch (error) {
                if (!signal.aborted) settingsStatus.textContent = '设置保存失败。' + errorMessage(error);
            }
        });
    });
    reload.addEventListener('click', load);
    add.addEventListener('click', () => openForm(null));
    type.addEventListener('change', describeType);
    cancel.addEventListener('click', () => {
        closeForm();
        render();
        add.focus();
    });
    form.addEventListener('submit', (event) => {
        event.preventDefault();
        if (busy || form.hidden || !session) return;
        formStatus.textContent = validate(content.value, keywords.value);
        if (formStatus.textContent) return;
        const body = payload({ type: type.value, content: content.value, keywords: keywords.value }, editing?.enabled);
        return mutate(editing?.id, editing ? 'PUT' : 'POST', body, formStatus);
    });

    subscribe((state) => {
        const nextUserId = state.user?.id;
        if (nextUserId !== userId) {
            session?.abort();
            session = nextUserId ? new AbortController() : null;
            userId = nextUserId;
            busy = false;
            enabled = memories = null;
            status.textContent = settingsStatus.textContent = '';
            closeForm();
            render();
        }
        if (session && state.activePage === 'memory' && enabled === null && memories === null && !status.textContent) {
            void load();
        }
    });
    closeForm();
    render();
}
