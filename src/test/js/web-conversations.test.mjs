import { test } from 'node:test';
import assert from 'node:assert/strict';
import { setup } from './web-import-harness.mjs';

const a = '9007199254740993';
const b = '9007199254740994';
const conversation = (id, knowledgeBaseId = '1') => ({ id, knowledgeBaseId, title: '会话 ' + id });
const page = (items = [], number = 1, hasNext = false) => ({ items, page: number, pageSize: 100, hasNext });
const citation = { evidenceId: 'E1', chunkId: a, documentId: '20', indexVersionId: '30', sourceStart: 0, sourceEnd: 2 };
const message = (id, role, content, citations = []) => ({
    id: String(id),
    conversationId: a,
    turnId: '5eebed1b-bdc4-4184-a8ef-945ce65d06e9',
    role,
    content,
    status: role === 'ASSISTANT' ? 'ANSWERED' : null,
    citations,
});

async function fixture() {
    const h = await setup();
    h.state.updateState({ activePage: 'chat' });
    const nodes = (node = h.elements.chat) => [node, ...node.children.flatMap(nodes)];
    h.find = (label) => nodes().find((node) => node.tag === 'button' && node.textContent === label);
    h.click = async (label) => {
        await h.find(label).fire('click');
        await h.tick();
    };
    h.last = () => h.calls.at(-1);
    h.reply = (data, status) => h.respond(h.calls.length - 1, data, status);
    h.chat = h.elements.chat;
    h.dialog = nodes().find((node) => node.tag === 'dialog');
    h.dialog.showModal = () => (h.dialog.open = true);
    h.dialog.close = () => (h.dialog.open = false);
    return h;
}

test('空列表创建会话，刷新后从服务端读取完整分页并打开引用', async () => {
    const h = await fixture();
    assert.match(h.last().url, /conversations\?knowledgeBaseId=1$/);
    await h.reply([]);
    assert.match(h.chat.textContent, /暂无会话/);
    await h.click('新建会话');
    assert.equal(h.last().options.method, 'POST');
    assert.deepEqual(JSON.parse(h.last().options.body), { knowledgeBaseId: '1' });
    await h.reply(conversation(a), 201);
    assert.match(h.last().url, new RegExp('/conversations/' + a + '$'));
    await h.reply(conversation(a));
    await h.reply(page());
    assert.match(h.chat.textContent, /暂无消息/);

    const refreshed = await fixture();
    await refreshed.reply([conversation(a)]);
    await refreshed.reply(conversation(a));
    assert.match(refreshed.last().url, /messages\?page=1&pageSize=100$/);
    const questions = Array.from({ length: 100 }, (_, i) => ({
        ...message(i + 1, 'USER', '历史问题 ' + i),
        turnId: '5eebed1b-bdc4-4184-a8ef-' + String(i).padStart(12, '0'),
    }));
    await refreshed.reply(page(questions, 1, true));
    assert.match(refreshed.last().url, /messages\?page=2&pageSize=100$/);
    const answer = {
        ...message(101, 'ASSISTANT', '历史答案', [citation]),
        memoryTrace: {
            injectedCount: 2,
            memories: [
                { id: a, type: 'LEARNING_GOAL', content: '不应展示的正文' },
                { id: b, type: 'LEARNING_GOAL' },
            ],
        },
    };
    await refreshed.reply(page([answer], 2));
    assert.match(refreshed.chat.textContent, /历史问题 99.*历史答案.*已回答/s);
    assert.match(refreshed.chat.textContent, /本轮已提供 2 条长期记忆.*学习目标 · 2 条/);
    assert.doesNotMatch(refreshed.chat.textContent, /不应展示的正文/);
    await refreshed.click('引用：E1 · 查看证据');
    assert.match(refreshed.last().url, new RegExp('/knowledge-bases/1/documents/20/versions/30/chunks/' + a + '$'));
    await refreshed.reply({ ...citation, filename: '文件', headingPath: null, content: '证据正文' });
    assert.match(refreshed.dialog.textContent, /证据正文/);
});

test('同库切换和跨库切换丢弃迟到响应，网络失败后重试当前历史', async () => {
    const h = await fixture();
    await h.reply([conversation(a), conversation(b)]);
    await h.reply(conversation(a));
    const oldHistory = h.calls.length - 1;
    await h.click('会话 ' + b);
    const oldDetail = h.calls.length - 1;
    await h.respond(oldHistory, page([message(1, 'USER', '旧历史')]));
    assert.doesNotMatch(h.chat.textContent, /旧历史/);
    h.state.updateState({ selectedKnowledgeBaseId: '2' });
    assert.match(h.last().url, /knowledgeBaseId=2$/);
    assert.equal(h.calls[oldDetail].options.signal.aborted, true);
    await h.respond(oldDetail, conversation(b));
    assert.doesNotMatch(h.chat.textContent, new RegExp(a + '|' + b));
    await h.reply([conversation(b, '2')]);
    h.last().reject(new Error('offline'));
    await h.tick();
    assert.match(h.chat.textContent, /网络连接失败/);
    await h.click('重试');
    await h.reply(conversation(b, '2'));
    await h.reply(page());
    assert.match(h.chat.textContent, /暂无消息/);
    assert.equal(h.find('重试').hidden, true);
    h.state.updateState({ user: null, selectedKnowledgeBaseId: '' });
    assert.equal(h.find('会话 ' + b), undefined);
    assert.equal(h.find('新建会话').disabled, true);
});
