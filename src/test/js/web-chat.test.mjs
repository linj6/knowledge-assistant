import { test } from 'node:test';
import assert from 'node:assert/strict';
import { setup } from './web-import-harness.mjs';

const id = '9007199254740993';
const other = '9007199254740994';
const conversation = (id, knowledgeBaseId = '1') => ({ id, knowledgeBaseId, title: id });
const page = (items = []) => ({ items, page: 1, pageSize: 100, hasNext: false });

async function fixture(session) {
    const h = await setup({ session });
    h.state.updateState({ activePage: 'chat' });
    const nodes = (node = h.elements.chat) => [node, ...node.children.flatMap(nodes)];
    h.find = (label) => nodes().find((node) => node.tag === 'button' && node.textContent === label);
    h.last = () => h.calls.at(-1);
    h.reply = (data, status) => h.respond(h.calls.length - 1, data, status);
    h.load = async (items) => {
        await h.reply([conversation(id), conversation(other)]);
        await h.reply(conversation(id));
        await h.reply(page(items));
    };
    h.click = async (label) => {
        await h.find(label).fire('click');
        await h.tick();
    };
    h.submit = async (text) => {
        nodes().find((node) => node.tag === 'textarea').value = text;
        await nodes()
            .find((node) => node.tag === 'form')
            .fire('submit');
        await h.tick();
        return JSON.parse(h.last().options.body);
    };
    h.fireTimer = async (delay) => {
        const [key, timer] = [...h.pendingTimers].findLast(([, timer]) => timer.delay === delay);
        h.pendingTimers.delete(key);
        timer.callback();
        await h.tick();
    };
    h.text = () => h.elements.chat.textContent;
    return h;
}

const result = (body, status, extra = {}) => ({
    ...body,
    userMessageId: id,
    assistantMessageId: other,
    status,
    citations: [],
    ...extra,
});

test('同一会话连续发送并展示成功、证据不足和失败终态', async () => {
    const h = await fixture();
    assert.equal(h.find('发送').disabled, true);
    await h.load();
    const ids = [];
    for (const [status, extra, visible] of [
        ['ANSWERED', { answer: '<答案>' }, /<答案>已回答/],
        ['INSUFFICIENT_EVIDENCE', {}, /证据不足/],
        ['FAILED', { errorCode: 'MODEL_FAILED' }, /问答失败错误码：MODEL_FAILED/],
    ]) {
        const body = await h.submit('  <问题> ' + status);
        ids.push(body.turnId);
        assert.equal(body.question, '  <问题> ' + status);
        assert.equal(h.find('发送').disabled, true);
        const count = h.calls.length;
        await h.submit('重复发送');
        assert.equal(h.calls.length, count);
        await h.reply({ turnId: body.turnId, status: 'PROCESSING' }, 202);
        assert.match(h.last().url, new RegExp('/turns/' + body.turnId + '$'));
        await h.reply(result(body, 'PROCESSING'));
        assert.match(h.text(), /正在生成/);
        await h.fireTimer(1500);
        await h.reply(result(body, status, extra));
        assert.match(h.text(), visible);
        assert.equal(h.find('发送').disabled, false);
    }
    assert.equal(new Set(ids).size, 3);
});

test('服务端取消后持续轮询到 CANCELLED', async () => {
    const h = await fixture();
    await h.load();
    const body = await h.submit('取消测试');
    await h.reply({ turnId: body.turnId, status: 'PROCESSING' });
    await h.reply(result(body, 'PROCESSING'));
    await h.click('取消生成');
    assert.match(h.last().url, /\/cancel$/);
    assert.equal(h.last().options.method, 'POST');
    await h.reply({ turnId: body.turnId, status: 'CANCEL_REQUESTED' });
    assert.match(h.text(), /正在取消/);
    await h.reply(result(body, 'CANCEL_REQUESTED'));
    assert.equal(h.find('发送').disabled, true);
    await h.fireTimer(1500);
    await h.reply(result(body, 'CANCELLED'));
    assert.match(h.text(), /已取消/);
    assert.equal(h.find('发送').disabled, false);
});

test('查询超时仅中止浏览器请求，并可恢复原轮查询', async () => {
    const h = await fixture();
    await h.load();
    const body = await h.submit('超时测试');
    await h.reply({ turnId: body.turnId, status: 'PROCESSING' });
    const poll = h.last();
    await h.fireTimer(20000);
    assert.equal(poll.options.signal.aborted, true);
    assert.match(h.text(), /超时/);
    assert.doesNotMatch(h.text(), /已取消/);
    assert.equal(h.find('发送').disabled, true);
    await h.click('重试');
    assert.equal(h.last().url, poll.url);
    await h.reply(result(body, 'ANSWERED', { answer: '恢复完成' }));
    assert.match(h.text(), /恢复完成/);
    assert.equal(h.calls.filter((call) => call.options.method === 'POST').length, 1);
});

test('POST 响应丢失时查原 UUID，手动重提沿用原问题和 UUID', async () => {
    const session = new Map();
    const h = await fixture(session);
    await h.load();
    const body = await h.submit('原问题');
    h.last().reject(new Error('offline'));
    await h.tick();
    assert.match(h.last().url, new RegExp(body.turnId + '$'));
    assert.equal(h.calls.filter((call) => call.options.method === 'POST').length, 1);

    const refreshed = await fixture(session);
    await refreshed.load();
    assert.match(refreshed.last().url, new RegExp(body.turnId + '$'));
    refreshed.last().resolve({
        status: 404,
        ok: false,
        headers: { get: () => null },
        json: async () => ({ code: 40400 }),
    });
    await refreshed.tick();
    await refreshed.click('重试提交');
    assert.deepEqual(JSON.parse(refreshed.last().options.body), body);
    await refreshed.reply({ turnId: body.turnId, status: 'PROCESSING' });
    await refreshed.reply(result(body, 'ANSWERED', { answer: '原轮回答' }));
    assert.match(refreshed.text(), /原轮回答/);
    assert.equal(refreshed.text().split('原问题').length, 2);
});

test('刷新从消息历史恢复轮询，切会话、切库、登出隔离迟到结果', async () => {
    for (const action of ['会话', '知识库', '退出']) {
        const h = await fixture();
        const body = { turnId: '5eebed1b-bdc4-4184-a8ef-945ce65d06e9', question: '历史原问题' };
        await h.load([
            { id, conversationId: id, turnId: body.turnId, role: 'USER', content: body.question, citations: [] },
            {
                id: other,
                conversationId: id,
                turnId: body.turnId,
                role: 'ASSISTANT',
                status: 'PROCESSING',
                citations: [],
            },
        ]);
        const old = h.calls.length - 1;
        assert.match(h.last().url, new RegExp(body.turnId + '$'));
        assert.equal(h.find('发送').disabled, true);
        if (action === '会话') await h.click(other);
        if (action === '知识库') h.state.updateState({ selectedKnowledgeBaseId: '2' });
        if (action === '退出') h.state.updateState({ user: null });
        assert.equal(h.calls[old].options.signal.aborted, true);
        await h.respond(old, result(body, 'ANSWERED', { answer: '迟到回答' }));
        assert.doesNotMatch(h.text(), /历史原问题|迟到回答/);
        assert.equal(
            h.calls.some((call) => call.options.method === 'POST'),
            false,
        );
    }
});

test('POST 已落库但响应丢失时直接完成原轮，离开页面只停止查询', async () => {
    const h = await fixture();
    await h.load();
    const body = await h.submit('已落库');
    h.last().reject(new Error('offline'));
    await h.tick();
    const firstRead = h.calls.length - 1;
    h.listeners.pagehide();
    assert.equal(h.calls[firstRead].options.signal.aborted, true);
    await h.respond(firstRead, result(body, 'ANSWERED', { answer: '已失效的等待' }));
    assert.doesNotMatch(h.text(), /已失效的等待/);
    h.listeners.pageshow({ persisted: true });
    await h.reply(conversation(id));
    await h.reply(page());
    assert.match(h.last().url, new RegExp(body.turnId + '$'));
    await h.reply(result(body, 'ANSWERED', { answer: '原轮已完成' }));
    assert.match(h.text(), /原轮已完成/);
    assert.equal(h.calls.filter((call) => call.options.method === 'POST').length, 1);
});

test('刷新保留非默认知识库和已选会话', async () => {
    const session = new Map();
    const h = await fixture(session);
    await h.load();
    h.elements.kb.value = '2';
    await h.elements.kb.fire('change');
    await h.reply([conversation(id, '2'), conversation(other, '2')]);
    await h.click(other);
    await h.reply(conversation(other, '2'));
    await h.reply(page());

    const refreshed = await fixture(session);
    assert.match(refreshed.last().url, /knowledgeBaseId=2$/);
    await refreshed.reply([conversation(id, '2'), conversation(other, '2')]);
    assert.match(refreshed.last().url, new RegExp(other + '$'));
});

test('切换后旧轮查询的 401 不触发新页面退出登录', async () => {
    const h = await fixture();
    await h.load();
    const body = await h.submit('旧轮');
    await h.reply({ turnId: body.turnId, status: 'PROCESSING' });
    const old = h.calls.length - 1;
    await h.click(other);
    await h.respond(old, null, 401);
    assert.equal(h.reloads(), 0);
    assert.equal(h.state.AppState.user.id, '1');
});
