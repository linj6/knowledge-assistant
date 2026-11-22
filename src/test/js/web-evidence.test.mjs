import { test } from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import fs from 'node:fs';
const context = vm.createContext({});
const api = new vm.SourceTextModule('export class ApiError extends Error {} export function request() {}', { context });
const state = new vm.SourceTextModule('export const AppState={}; export function subscribe() {}', { context });
const mod = new vm.SourceTextModule(fs.readFileSync('src/main/resources/static/js/evidence.js', 'utf8'), { context });
await mod.link((s) => (s.includes('api') ? api : state));
await mod.evaluate();
const citation = { chunkId: '9007199254740993', documentId: '2', indexVersionId: '3' };
const data = () => ({
    ...citation,
    filename: '<img>',
    headingPath: null,
    content: '<script>',
    sourceStart: 0,
    sourceEnd: 2,
});
test('有效证据保留字符串ID及不可信原文', () => {
    assert.equal(mod.namespace.validateEvidence(data(), citation).content, '<script>');
});
for (const [key, value] of [
    ['chunkId', '4'],
    ['documentId', '9'],
    ['indexVersionId', '8'],
    ['filename', null],
    ['headingPath', 3],
    ['content', {}],
    ['sourceStart', -1],
    ['sourceEnd', 0],
    ['sourceEnd', 1.2],
])
    test('拒绝非法证据 ' + key + String(value), () =>
        assert.throws(() => mod.namespace.validateEvidence({ ...data(), [key]: value }, citation)),
    );

import { setup } from './web-import-harness.mjs';
async function fixture(citations) {
    const h = await setup();
    h.state.updateState({ activePage: 'chat' });
    const nodes = () => {
        const a = [];
        const walk = (e) => {
            a.push(e);
            e.children.forEach(walk);
        };
        walk(h.elements.chat);
        return a;
    };
    const dialog = nodes().find((e) => e.tag === 'dialog');
    // 模拟原生 dialog 的 open 状态；close 事件单独派发，以覆盖浏览器排队事件。
    dialog.open = false;
    dialog.showModal = () => {
        dialog.open = true;
    };
    dialog.close = () => {
        dialog.open = false;
    };
    const closeButton = nodes().find((e) => e.textContent === '关闭证据');
    let focused = null;
    closeButton.focus = () => {
        focused = closeButton;
    };
    const conversation = { id: '1', knowledgeBaseId: '1', title: '会话' };
    await h.respond(h.calls.length - 1, [conversation]);
    await h.respond(h.calls.length - 1, conversation);
    const c = { ...citation, evidenceId: 'E1', sourceStart: 0, sourceEnd: 2 };
    await h.respond(h.calls.length - 1, {
        page: 1,
        pageSize: 100,
        hasNext: false,
        items: [
            {
                id: '2',
                conversationId: '1',
                turnId: '5eebed1b-bdc4-4184-a8ef-945ce65d06e9',
                role: 'ASSISTANT',
                status: 'ANSWERED',
                content: '答案 [E99]',
                citations: citations ?? [c, { ...c, evidenceId: 'E2', chunkId: '4' }],
            },
        ],
    });
    const buttons = nodes().filter((e) => e.tag === 'button' && e.textContent.includes('查看证据'));
    buttons.forEach((b) => {
        b.isConnected = true;
        b.focus = () => {
            focused = b;
        };
    });
    const click = async (i) => {
        await buttons[i].fire('click');
        await h.tick();
        return h.calls.length - 1;
    };
    const retry = nodes().find((e) => e.textContent === '重试读取');
    return { ...h, nodes, dialog, buttons, closeButton, retry, click, focused: () => focused };
}
test('打开、关闭、焦点恢复、重新打开清除错误', async () => {
    const h = await fixture();
    let i = await h.click(0);
    assert.equal(h.dialog.open, true);
    assert.equal(h.focused(), h.closeButton);
    assert.match(h.calls[i].url, /knowledge-bases\/1\/documents\/2\/versions\/3\/chunks\/9007199254740993$/);
    await h.respond(i, null, 500);
    await h.closeButton.fire('click');
    assert.equal(h.dialog.open, false);
    assert.equal(h.focused(), h.buttons[0]);
    i = await h.click(0);
    assert.match(h.dialog.textContent, /正在读取/);
    assert.doesNotMatch(h.dialog.textContent, /读取失败/);
    await h.respond(i, data());
    assert.match(h.dialog.textContent, /<script>/);
    assert.equal(
        h.nodes().some((e) => ['script', 'img'].includes(e.tag)),
        false,
    );
});
for (const action of ['button', 'cancel', 'native', 'kb', 'hash', 'pagehide'])
    test('关闭后迟到响应及定时器无效 ' + action, async () => {
        const h = await fixture();
        const i = await h.click(0);
        const timer = [...h.pendingTimers.values()].filter((t) => t.delay === 20000).at(-1);
        if (action === 'button') await h.closeButton.fire('click');
        if (action === 'cancel') await h.dialog.fire('cancel');
        if (action === 'native') {
            h.dialog.open = false;
            await h.dialog.fire('close');
        }
        if (action === 'kb') h.state.updateState({ selectedKnowledgeBaseId: '2' });
        if (action === 'hash') {
            h.context.location.hash = '#knowledge';
            h.listeners.hashchange();
        }
        if (action === 'pagehide') h.listeners.pagehide();
        assert.equal(h.dialog.open, false);
        assert.equal(h.calls[i].options.signal.aborted, true);
        const before = h.dialog.textContent;
        timer.callback();
        await h.respond(i, data());
        assert.equal(h.dialog.textContent, before);
        assert.equal(h.dialog.open, false);
    });
test('E2 先返回，E1 晚到不能覆盖', async () => {
    const h = await fixture();
    const a = await h.click(0),
        b = await h.click(1);
    assert.equal(h.calls[a].options.signal.aborted, true);
    await h.respond(b, { ...data(), chunkId: '4', content: '第二证据' });
    await h.respond(a, data());
    assert.match(h.dialog.textContent, /第二证据/);
    assert.doesNotMatch(h.dialog.textContent, /<script>/);
});
test('关闭后重开不受排队原生 close 事件影响', async () => {
    const h = await fixture();
    const a = await h.click(0);
    await h.closeButton.fire('click');
    const b = await h.click(0);
    await h.dialog.fire('close');
    assert.equal(h.dialog.open, true);
    await h.respond(a, data());
    assert.doesNotMatch(h.dialog.textContent, /<script>/);
    await h.respond(b, data());
    assert.match(h.dialog.textContent, /证据已加载/);
});
test('Hash 往返不重发且不重复监听', async () => {
    const h = await fixture();
    await h.click(0);
    for (let k = 0; k < 3; k++) {
        h.context.location.hash = '#knowledge';
        h.listeners.hashchange();
        h.context.location.hash = '#chat';
        h.listeners.hashchange();
    }
    const n = h.calls.length;
    assert.equal(h.dialog.open, false);
    assert.equal(h.closeButton.events.click.length, 1);
    assert.equal(h.retry.events.click.length, 1);
    assert.equal(h.buttons[0].events.click.length, 1);
    await h.tick();
    assert.equal(h.calls.length, n);
});
for (const kind of ['404', '500', 'network', 'timeout', 'envelope'])
    test('错误与手动重试 ' + kind, async () => {
        const h = await fixture();
        const i = await h.click(0);
        if (kind === 'network') h.calls[i].reject(new Error('<img>secret'));
        else if (kind === 'timeout')
            [...h.pendingTimers.values()]
                .filter((t) => t.delay === 20000)
                .at(-1)
                .callback();
        else if (kind === 'envelope')
            h.calls[i].resolve({
                ok: true,
                status: 200,
                headers: { get: () => null },
                json: async () => ({ message: '<img>secret' }),
            });
        else await h.respond(i, null, Number(kind));
        await h.tick();
        assert.match(
            h.dialog.textContent,
            { 404: /当前不可用/, 500: /读取失败/, network: /网络连接失败/, timeout: /超时/, envelope: /协议错误/ }[
                kind
            ],
        );
        assert.doesNotMatch(h.dialog.textContent, /secret/);
        assert.equal(h.retry.hidden, false);
        void h.retry.fire('click');
        void h.retry.fire('click');
        await h.tick();
        const j = h.calls.length - 1;
        assert.equal(j, i + 1);
        assert.equal(h.calls[j].url, h.calls[i].url);
        await h.respond(j, data());
        if (kind === 'timeout') await h.respond(i, { ...data(), content: '旧正文' });
        assert.match(h.dialog.textContent, /证据已加载/);
        assert.doesNotMatch(h.dialog.textContent, /旧正文/);
    });
for (const citations of [
    [{ ...citation, evidenceId: '<img>', sourceStart: 0, sourceEnd: 2 }],
    [
        { ...citation, evidenceId: 'E1', sourceStart: 0, sourceEnd: 2 },
        { ...citation, evidenceId: 'E1', sourceStart: 0, sourceEnd: 2 },
    ],
])
    test('非法或重复 evidenceId 拒绝整份回答', async () => {
        const h = await fixture(citations);
        assert.equal(h.buttons.length, 0);
        assert.match(h.elements.chat.textContent, /响应格式异常/);
    });
test('服务器错误 HTML 与非法 requestId 不展示', async () => {
    const h = await fixture();
    const i = await h.click(0);
    h.calls[i].resolve({
        ok: false,
        status: 500,
        headers: { get: () => null },
        json: async () => ({ code: 50000, message: '<img onerror=alert(1)>secret', requestId: '<script>secret' }),
    });
    await h.tick();
    assert.match(h.dialog.textContent, /读取失败/);
    assert.doesNotMatch(h.dialog.textContent, /secret|<img|<script/);
});
test('成功后清除超时定时器，旧定时器不能污染重开', async () => {
    const h = await fixture();
    const i = await h.click(0);
    const entry = [...h.pendingTimers.entries()].filter(([, t]) => t.delay === 20000).at(-1);
    await h.respond(i, data());
    assert.equal(h.pendingTimers.has(entry[0]), false);
    await h.closeButton.fire('click');
    await h.click(1);
    entry[1].callback();
    assert.match(h.dialog.textContent, /正在读取/);
    assert.doesNotMatch(h.dialog.textContent, /超时/);
});

test('证据紧凑来源保持完整安全文本，正文独立区域不影响关闭', async () => {
    const f = await fixture();
    const i = await f.click(0);
    await f.respond(i, { ...data(), filename: '<img>' + '长文件名'.repeat(100), headingPath: '标题'.repeat(100) });
    assert.ok(f.dialog.textContent.includes('<img>'));
    assert.equal(
        f.nodes().some((e) => e.tag === 'img'),
        false,
    );
    const body = f.nodes().find((e) => e.className === 'evidence-body');
    assert.ok(body);
    assert.ok(body.textContent.includes('来源区间'));
    assert.equal(body.children.at(-1).tag, 'pre');
});
