import { test } from 'node:test';
import assert from 'node:assert/strict';
import { setup } from './web-import-harness.mjs';
const citation = (n) => ({
    evidenceId: 'E1',
    chunkId: String(n),
    documentId: '20',
    indexVersionId: '30',
    sourceStart: 0,
    sourceEnd: 2,
});
const answer = (n) => ({
    status: 'ANSWERED',
    userMessageId: '9007199254740993',
    assistantMessageId: '9007199254740994',
    answer: '回答 [E99]',
    citations: [citation(n)],
    errorCode: null,
});
const evidence = (n) => ({ ...citation(n), filename: '文件', headingPath: '标题', content: '正文' + n });
async function fixture() {
    const h = await setup();
    h.state.updateState({ activePage: 'chat' });
    h.load = async (knowledgeBaseId = '1') => {
        const conversation = { id: knowledgeBaseId, knowledgeBaseId, title: '会话' };
        await h.respond(h.calls.length - 1, [conversation]);
        await h.respond(h.calls.length - 1, conversation);
        await h.respond(h.calls.length - 1, { items: [], page: 1, pageSize: 100, hasNext: false });
    };
    await h.load();
    h.complete = async (request, chunk) => {
        const body = JSON.parse(h.calls[request].options.body);
        await h.respond(request, { turnId: body.turnId, status: 'PROCESSING' });
        await h.respond(h.calls.length - 1, { ...body, ...answer(chunk) });
    };
    h.nodes = () => {
        const result = [];
        const walk = (e) => {
            result.push(e);
            e.children.forEach(walk);
        };
        walk(h.elements.chat);
        return result;
    };
    h.dialog = h.nodes().find((e) => e.tag === 'dialog');
    h.dialog.showModal = () => {
        h.dialog.open = true;
    };
    h.dialog.close = () => {
        h.dialog.open = false;
    };
    h.submit = async (text) => {
        h.nodes().find((e) => e.tag === 'textarea').value = text;
        void h
            .nodes()
            .find((e) => e.tag === 'form')
            .fire('submit');
        await h.tick();
        return h.calls.length - 1;
    };
    h.buttons = () => h.nodes().filter((e) => e.tag === 'button' && e.textContent.includes('查看证据'));
    h.click = async (button) => {
        await button.fire('click');
        await h.tick();
        return h.calls.length - 1;
    };
    return h;
}
test('完整流程：多条历史引用保留各自身份且 GET 不触发模型', async () => {
    const h = await fixture();
    await h.complete(await h.submit('第一问'), 11);
    await h.complete(await h.submit('第二问'), 12);
    assert.equal(h.buttons().length, 2);
    for (const [index, chunk] of [
        [0, 11],
        [1, 12],
        [0, 11],
    ]) {
        const i = await h.click(h.buttons()[index]);
        assert.match(h.calls[i].url, new RegExp('/knowledge-bases/1/documents/20/versions/30/chunks/' + chunk + '$'));
        await h.respond(i, evidence(chunk));
        assert.match(h.dialog.textContent, new RegExp('正文' + chunk));
    }
    const posts = h.calls.filter((c) => c.options.method === 'POST');
    assert.equal(posts.length, 2);
    assert.deepEqual(
        posts.map((c) => Object.keys(JSON.parse(c.options.body)).sort()),
        Array(2).fill(['question', 'turnId']),
    );
});
test('跨库交叉竞态：A 证据迟到不能写入 B 或释放 B 问答锁', async () => {
    const h = await fixture();
    await h.complete(await h.submit('A问题'), 11);
    const old = await h.click(h.buttons()[0]);
    h.state.updateState({ selectedKnowledgeBaseId: '2' });
    assert.equal(h.dialog.open, false);
    assert.equal(h.buttons().length, 0);
    await h.load('2');
    const current = await h.submit('B问题');
    await h.respond(old, evidence(11));
    assert.equal(h.nodes().find((e) => e.textContent === '发送').disabled, true);
    assert.doesNotMatch(h.elements.chat.textContent, /A问题|正文11/);
    await h.complete(current, 12);
    const next = await h.click(h.buttons()[0]);
    assert.match(h.calls[next].url, /knowledge-bases\/2\/documents\/20\/versions\/30\/chunks\/12$/);
    await h.respond(next, evidence(12));
    assert.match(h.dialog.textContent, /正文12/);
    assert.equal(h.calls.filter((c) => c.options.method === 'POST').length, 2);
});
