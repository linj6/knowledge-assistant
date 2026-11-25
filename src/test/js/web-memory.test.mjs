import { test } from 'node:test';
import assert from 'node:assert/strict';
import { setup } from './web-import-harness.mjs';

const id = '900719925474099399';
const memory = { id, type: 'BACKGROUND', content: '<b>Java</b>', keywords: '<关键词>', enabled: true };

async function fixture() {
    const h = await setup();
    h.context.window.confirm = () => true;
    const get = (name) => h.elements['memory-' + name];
    const index = (path, method = 'GET') =>
        h.calls.findLastIndex((call) => call.url.endsWith(path) && call.options.method === method);
    const reply = (path, data, method) => h.respond(index(path, method), data);
    const body = (path, method) => JSON.parse(h.calls[index(path, method)].options.body);
    const click = async (name, event = 'click') => {
        void get(name).fire(event);
        await h.tick();
    };
    const action = async (label) => {
        const button = get('list')
            .children[0].children.at(-1)
            .children.find((button) => button.textContent === label);
        void button.fire('click');
        await h.tick();
    };
    const fail = async (code) => {
        h.calls.at(-1).resolve({ ok: false, status: 409, headers: new Headers(), json: async () => ({ code }) });
        await h.tick();
    };
    assert.equal(index('/memories'), -1);
    h.context.location.hash = '#memory';
    h.listeners.hashchange();
    assert.equal(h.elements.memory.hidden, false);
    assert.match(get('status').textContent, /正在加载/);
    return { ...h, get, index, reply, body, click, action, fail };
}

test('记忆设置保存失败后恢复状态，关闭开关仍可管理记忆', async () => {
    const h = await fixture();
    await h.reply('/memory/settings', { memoryEnabled: true });
    await h.reply('/memories', []);
    assert.match(h.get('list').textContent, /暂无记忆/);
    h.get('enabled').checked = false;
    await h.click('enabled', 'change');
    assert.deepEqual(h.body('/memory/settings', 'PUT'), { memoryEnabled: false });
    assert.equal(h.get('enabled').disabled, true);
    await h.respond(h.calls.length - 1, null, 500);
    assert.equal(h.get('enabled').checked, true);
    assert.match(h.get('settings-status').textContent, /保存失败/);
    h.get('enabled').checked = false;
    await h.click('enabled', 'change');
    await h.reply('/memory/settings', { memoryEnabled: false }, 'PUT');
    assert.equal(h.get('enabled').checked, false);
    assert.equal(h.get('add').disabled, false);
});

test('创建、编辑、启停和删除使用完整请求契约，长度和冲突错误保留输入', async () => {
    const h = await fixture();
    await h.reply('/memory/settings', { memoryEnabled: false });
    await h.reply('/memories', [memory]);
    assert.match(h.get('list').textContent, /<b>Java<\/b>.*<关键词>/);
    await h.click('add');
    h.get('content').value = '😀'.repeat(501);
    await h.click('form', 'submit');
    assert.match(h.get('form-status').textContent, /500/);
    h.get('content').value = '　' + '😀'.repeat(500) + '　';
    h.get('keywords').value = '😀'.repeat(201);
    await h.click('form', 'submit');
    assert.match(h.get('form-status').textContent, /200/);
    h.get('keywords').value = '😀'.repeat(200);
    const draft = { type: 'BACKGROUND', content: h.get('content').value, keywords: h.get('keywords').value };
    await h.click('form', 'submit');
    assert.deepEqual(h.body('/memories', 'POST'), draft);
    const total = h.calls.length;
    await h.click('form', 'submit');
    assert.equal(h.calls.length, total);
    await h.fail(40903);
    assert.match(h.get('form-status').textContent, /相同记忆/);
    assert.equal(h.get('content').value, draft.content);
    await h.click('form', 'submit');
    await h.fail(40904);
    assert.match(h.get('form-status').textContent, /50 条/);
    assert.equal(h.get('keywords').value, draft.keywords);
    await h.click('form', 'submit');
    await h.reply('/memories', memory, 'POST');
    await h.reply('/memories', [memory]);
    assert.equal(h.get('form').hidden, true);

    await h.action('编辑');
    h.get('content').value = '更新正文';
    await h.click('form', 'submit');
    const path = '/memories/' + id;
    const edited = { type: memory.type, content: '更新正文', keywords: memory.keywords, enabled: true };
    assert.deepEqual(h.body(path, 'PUT'), edited);
    await h.reply(path, { ...edited, id }, 'PUT');
    await h.reply('/memories', [{ ...edited, id }]);
    await h.action('停用');
    assert.deepEqual(h.body(path, 'PUT'), { ...edited, enabled: false });
    await h.reply(path, null, 'PUT');
    await h.reply('/memories', [{ ...edited, id, enabled: false }]);
    assert.match(h.get('list').textContent, /已停用/);
    await h.action('删除');
    assert.ok(h.index(path, 'DELETE') >= 0);
    await h.reply(path, null, 'DELETE');
    await h.reply('/memories', []);
    assert.match(h.get('count').textContent, /0 \/ 50/);
});

test('账号切换及退出清除记忆和表单，迟到响应无法恢复旧数据', async () => {
    const h = await fixture();
    const previous = h.index('/memories');
    const previousSettings = h.index('/memory/settings');
    h.state.updateState({ user: { id: '2', username: 'other' } });
    assert.equal(h.calls[previous].options.signal.aborted, true);
    await h.reply('/memory/settings', { memoryEnabled: false });
    await h.reply('/memories', []);
    await h.respond(previousSettings, { memoryEnabled: true });
    await h.respond(previous, [memory]);
    assert.match(h.get('list').textContent, /暂无记忆/);
    assert.equal(h.get('enabled').checked, false);
    await h.click('add');
    h.get('content').value = '另一个账号的草稿';
    h.state.updateState({ user: null });
    assert.equal(h.get('content').value, '');
    assert.equal(h.get('list').textContent, '');
    assert.equal(h.get('form').hidden, true);
});
