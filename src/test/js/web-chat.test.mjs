import { test } from 'node:test';
import assert from 'node:assert/strict';
import { setup } from './web-import-harness.mjs';
for (const result of ['ANSWERED', 'INSUFFICIENT_EVIDENCE', 'FAILED']) {
    test('单轮业务状态 ' + result, async () => {
        const h = await setup();
        h.state.updateState({ activePage: 'chat' });
        const nodes = [];
        const walk = (el) => {
            nodes.push(el);
            el.children.forEach(walk);
        };
        walk(h.elements.chat);
        const form = nodes.find((el) => el.tag === 'form');
        nodes.find((el) => el.tag === 'textarea').value = '<script>问题</script>';
        void form.fire('submit');
        await h.tick();
        const index = h.calls.length - 1;
        const body = JSON.parse(h.calls[index].options.body);
        assert.deepEqual(Object.keys(body).sort(), ['knowledgeBaseId', 'question', 'strategy']);
        void form.fire('submit');
        await h.tick();
        assert.equal(h.calls.length, index + 1);
        await h.respond(index, {
            status: result,
            requestedStrategy: 'HYBRID_RRF',
            answer: result === 'ANSWERED' ? '<img onerror=alert(1)>' : null,
            citations:
                result === 'ANSWERED'
                    ? [
                          {
                              evidenceId: 'E1',
                              chunkId: '9007199254740993',
                              documentId: '2',
                              indexVersionId: '3',
                              sourceStart: 0,
                              sourceEnd: 1,
                          },
                      ]
                    : [],
            error: result === 'FAILED' ? 'GENERATION_FAILED' : null,
        });
        assert.match(
            h.elements.chat.textContent,
            new RegExp({ ANSWERED: '已回答', INSUFFICIENT_EVIDENCE: '证据不足', FAILED: '问答失败' }[result]),
        );
    });
}
test('切库后的迟到响应无效且历史清空', async () => {
    const h = await setup();
    h.state.updateState({ activePage: 'chat' });
    const nodes = [];
    const walk = (e) => {
        nodes.push(e);
        e.children.forEach(walk);
    };
    walk(h.elements.chat);
    nodes.find((e) => e.tag === 'textarea').value = '旧问题';
    void nodes.find((e) => e.tag === 'form').fire('submit');
    await h.tick();
    const index = h.calls.length - 1;
    h.state.updateState({ selectedKnowledgeBaseId: '2' });
    await h.respond(index, {
        status: 'INSUFFICIENT_EVIDENCE',
        requestedStrategy: 'HYBRID_RRF',
        answer: '旧答案',
        citations: [],
        error: null,
    });
    assert.doesNotMatch(h.elements.chat.textContent, /旧问题|旧答案/);
});

async function chat() {
    const h = await setup();
    h.state.updateState({ activePage: 'chat' });
    h.nodes = () => {
        const a = [];
        const walk = (e) => {
            a.push(e);
            e.children.forEach(walk);
        };
        walk(h.elements.chat);
        return a;
    };
    h.form = h.nodes().find((e) => e.tag === 'form');
    h.input = h.nodes().find((e) => e.tag === 'textarea');
    h.send = h.nodes().find((e) => e.textContent === '发送');
    h.submit = async (text = '问题') => {
        h.input.value = text;
        void h.form.fire('submit');
        await h.tick();
        return h.calls.length - 1;
    };
    return h;
}
const answer = () => ({
    status: 'ANSWERED',
    requestedStrategy: 'HYBRID_RRF',
    answer: '答案 [E99] <script>alert(1)</script>',
    citations: [
        {
            evidenceId: 'E1',
            chunkId: '9007199254740993',
            documentId: '2',
            indexVersionId: '3',
            sourceStart: 0,
            sourceEnd: 1,
        },
    ],
    error: null,
});
for (const result of ['ANSWERED', 'INSUFFICIENT_EVIDENCE', 'FAILED', 'network', 'timeout']) {
    test('立即清空且完成不改写后续输入 ' + result, async () => {
        const h = await chat();
        h.input.value = '  本轮问题  ';
        void h.form.fire('submit');
        assert.equal(h.input.value, '');
        assert.equal(h.input.disabled, true);
        assert.match(h.elements.chat.textContent, /本轮问题/);
        await h.tick();
        const i = h.calls.length - 1;
        assert.equal(JSON.parse(h.calls[i].options.body).question, '本轮问题');
        assert.equal(h.input.value, '');
        // 模拟后续草稿，验证任何完成回调均不会改写输入值。
        h.input.value = '下一轮草稿';
        if (result === 'network') {
            h.calls[i].reject(new Error('network'));
            await h.tick();
        } else if (result === 'timeout') {
            [...h.pendingTimers.values()].find((t) => t.delay === 60000).callback();
            await h.respond(i, answer());
        } else {
            const data = answer();
            if (result !== 'ANSWERED')
                Object.assign(data, {
                    status: result,
                    answer: null,
                    citations: [],
                    error: result === 'FAILED' ? 'GENERATION_FAILED' : null,
                });
            await h.respond(i, data);
        }
        assert.equal(h.input.value, '下一轮草稿');
        assert.equal(h.input.disabled, false);
        assert.match(h.elements.chat.textContent, /本轮问题/);
        if (result === 'network') assert.match(h.elements.chat.textContent, /网络连接失败/);
    });
}
for (const [name, text, kb] of [
    ['空白', '  ', '1'],
    ['超长', '问'.repeat(4001), '1'],
    ['未选库', '保留问题', ''],
]) {
    test('非法提交保留输入 ' + name, async () => {
        const h = await chat();
        h.state.updateState({ selectedKnowledgeBaseId: kb });
        const count = h.calls.length;
        await h.submit(text);
        assert.equal(h.input.value, text);
        assert.equal(h.calls.length, count);
    });
}
for (const [name, text, kb] of [
    ['空白', '  ', '1'],
    ['UTF16超长', '😀'.repeat(2001), '1'],
    ['无知识库', '问题', ''],
])
    test(name, async () => {
        const h = await chat();
        h.state.updateState({ selectedKnowledgeBaseId: kb });
        const n = h.calls.length;
        await h.submit(text);
        assert.equal(h.calls.length, n);
    });
test('固定策略、字符串ID、重复提交锁、完成恢复、单轮隔离', async () => {
    const h = await chat();
    h.state.updateState({ selectedKnowledgeBaseId: '9007199254740993' });
    const i = await h.submit();
    assert.equal(h.send.disabled, true);
    await h.submit();
    assert.equal(h.calls.length, i + 1);
    assert.equal(
        h.nodes().some((e) => e.tag === 'select'),
        false,
    );
    assert.deepEqual(JSON.parse(h.calls[i].options.body), {
        knowledgeBaseId: '9007199254740993',
        question: '问题',
        strategy: 'HYBRID_RRF',
    });
    await h.respond(i, answer());
    assert.equal(h.send.disabled, false);
    const j = await h.submit('第二问');
    assert.equal(Object.keys(JSON.parse(h.calls[j].options.body)).length, 3);
    await h.respond(j, answer());
});
for (const [name, mutate] of [
    ['未知状态', (d) => (d.status = 'UNKNOWN')],
    ['非法响应', (d) => (d.citations = null)],
    ['空引用', (d) => (d.citations = [])],
    ['非法标识', (d) => (d.citations[0].evidenceId = '<img>')],
    ['数值ID', (d) => (d.citations[0].chunkId = 123)],
])
    test(name, async () => {
        const h = await chat();
        const i = await h.submit();
        const d = answer();
        mutate(d);
        await h.respond(i, d);
        assert.match(h.elements.chat.textContent, /响应格式异常/);
        assert.equal(h.send.disabled, false);
    });
test('多引用、虚构引用、HTML和requestId安全文本', async () => {
    const h = await chat();
    const i = await h.submit();
    const d = answer();
    d.citations.push({ ...d.citations[0], evidenceId: 'E2' });
    h.calls[i].resolve({
        ok: true,
        status: 200,
        headers: { get: () => null },
        json: async () => ({ code: 0, data: d, requestId: '<img onerror=alert(1)>' }),
    });
    await h.tick();
    const text = h.elements.chat.textContent;
    assert.match(text, /引用：E1/);
    assert.match(text, /引用：E2/);
    assert.doesNotMatch(text, /引用：E99/);
    assert.match(text, /<script>/);
    assert.match(text, /requestId：<img/);
    assert.equal(
        h.nodes().some((e) => ['img', 'script'].includes(e.tag)),
        false,
    );
});
for (const kind of ['http', 'envelope', 'network'])
    test('请求层失败 ' + kind, async () => {
        const h = await chat();
        const i = await h.submit();
        if (kind === 'network') h.calls[i].reject(new Error('secret'));
        else
            h.calls[i].resolve({
                ok: kind !== 'http',
                status: kind === 'http' ? 500 : 200,
                headers: { get: () => null },
                json: async () => ({ code: 50000, data: answer(), requestId: 'id' }),
            });
        await h.tick();
        assert.match(h.elements.chat.textContent, /请求未完成/);
        assert.doesNotMatch(h.elements.chat.textContent, /已回答|secret/);
        assert.equal(h.calls.length, i + 1);
    });
for (const action of ['cancel', 'timeout', 'page', 'kb'])
    test('旧请求失效与新请求锁 ' + action, async () => {
        const h = await chat();
        const i = await h.submit('旧问题');
        if (action === 'cancel')
            await h
                .nodes()
                .find((e) => e.textContent === '取消等待')
                .fire('click');
        if (action === 'timeout') [...h.pendingTimers.values()].find((t) => t.delay === 60000).callback();
        if (action === 'page') {
            h.state.updateState({ activePage: 'knowledge' });
            h.state.updateState({ activePage: 'chat' });
        }
        if (action === 'kb') h.state.updateState({ selectedKnowledgeBaseId: '2' });
        assert.equal(h.calls[i].options.signal.aborted, true);
        assert.equal(h.calls.filter((c) => c.options.method === 'POST').length, 1);
        const j = await h.submit('新问题');
        await h.respond(i, answer());
        assert.equal(h.send.disabled, true);
        assert.doesNotMatch(h.elements.chat.textContent, /已回答/);
        await h.respond(j, answer());
        assert.equal(h.send.disabled, false);
        if (action === 'kb') assert.doesNotMatch(h.elements.chat.textContent, /旧问题/);
    });
test('旧响应晚到不覆盖新答案', async () => {
    const h = await chat();
    const i = await h.submit();
    h.state.updateState({ selectedKnowledgeBaseId: '2' });
    const j = await h.submit();
    await h.respond(j, answer());
    const before = h.elements.chat.textContent;
    await h.respond(i, answer());
    assert.equal(h.elements.chat.textContent, before);
});
test('刷新清空历史', async () => {
    const h = await chat();
    await h.respond(await h.submit(), answer());
    const fresh = await chat();
    assert.doesNotMatch(fresh.elements.chat.textContent, /已回答/);
});
test('Enter、Shift和输入法组合', async () => {
    const h = await chat();
    let n = 0;
    h.form.requestSubmit = () => n++;
    const key = h.input.events.keydown[0];
    const e = { key: 'Enter', preventDefault() {} };
    key({ ...e, shiftKey: true });
    key({ ...e, isComposing: true });
    key({ ...e, keyCode: 229 });
    assert.equal(n, 0);
    await h.input.fire('compositionstart');
    key(e);
    assert.equal(n, 0);
    await h.input.fire('compositionend');
    key(e);
    assert.equal(n, 1);
});
test('Hash往返不重发、不重复绑定', async () => {
    const h = await chat();
    const i = await h.submit();
    await h.respond(i, answer());
    const n = h.calls.filter((c) => c.options.method === 'POST').length;
    for (let k = 0; k < 3; k++) {
        h.context.location.hash = '#retrieval';
        h.listeners.hashchange();
        h.context.location.hash = '#chat';
        h.listeners.hashchange();
    }
    assert.equal(h.calls.filter((c) => c.options.method === 'POST').length, n);
    assert.equal(h.form.events.submit.length, 1);
    await h.respond(await h.submit(), answer());
    assert.equal(h.calls.filter((c) => c.options.method === 'POST').length, n + 1);
});
test('原始长度包含首尾空白', async () => {
    const h = await chat();
    const n = h.calls.length;
    await h.submit(' '.repeat(4000) + '问');
    assert.equal(h.calls.length, n);
});
