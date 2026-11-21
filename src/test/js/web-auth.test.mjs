import { test } from 'node:test';
import assert from 'node:assert/strict';
import { setup, tick } from './web-import-harness.mjs';

const userA = { id: '9007199254740993', username: '<b>alice</b>' };
const userB = { id: '2', username: 'bob' };
const path = (call) => new URL(call.url).pathname;
const paths = (h) => h.calls.map(path);
const csrf = (token) => ({ headerName: 'X-CSRF-TOKEN', token });
const header = 'X-CSRF-TOKEN';

function submit(h) {
    h.elements['auth-username'].value = 'alice';
    h.elements['auth-password'].value = 'password';
    return h.elements['auth-form'].fire('submit');
}

async function fixture() {
    const h = await setup({ manualAuth: true });
    await h.respond(0, null, 401);
    return h;
}

test('匿名启动只请求 me，401 后显示登录表单', async () => {
    const h = await setup({ manualAuth: true });
    assert.deepEqual(paths(h), ['/api/v1/auth/me']);
    assert.equal(h.elements.workspace.hidden, true);
    assert.equal(h.elements['auth-form'].hidden, true);
    assert.equal(h.calls[0].options.headers[header], undefined);
    await h.respond(0, null, 401);
    assert.equal(h.elements['auth-form'].hidden, false);
    assert.equal(h.elements['auth-status'].textContent, '');
    assert.equal(h.elements['workspace-nav'].hidden, true);
    assert.equal(h.calls.length, 1);
    assert.equal(h.reloads(), 0);
});

test('有效 Session 刷新恢复工作区，用户名按文本显示', async () => {
    const h = await setup({ manualAuth: true });
    await h.respond(0, userA);
    assert.equal(h.elements.auth.hidden, true);
    assert.equal(h.elements.workspace.hidden, false);
    assert.equal(h.elements['current-user'].textContent, userA.username);
    assert.deepEqual(paths(h), ['/api/v1/auth/me', '/api/v1/knowledge-bases']);
    assert.equal(h.calls[0].options.credentials, 'same-origin');
});

for (const issue of ['network', 500, 'numeric-id']) {
    test('启动异常可重试 ' + issue, async () => {
        const h = await setup({ manualAuth: true });
        if (issue === 'network') h.calls[0].reject(new Error());
        else if (issue === 500) await h.respond(0, null, 500);
        else await h.respond(0, { ...userA, id: 1 });
        await tick();
        assert.equal(h.elements['auth-title'].textContent, '连接异常');
        assert.equal(h.elements['auth-form'].hidden, true);
        assert.equal(h.elements.workspace.hidden, true);
        const retry = h.elements['auth-retry'].fire('click');
        await h.respond(1, userA);
        await retry;
        assert.equal(h.elements.workspace.hidden, false);
    });
}

test('注册请求携带 Token，成功后提示登录且不加载业务', async () => {
    const h = await fixture();
    await h.elements['auth-toggle'].fire('click');
    const pending = submit(h);
    await submit(h);
    assert.deepEqual(paths(h), ['/api/v1/auth/me', '/api/v1/auth/csrf']);
    await h.respond(1, csrf('anon'));
    assert.equal(path(h.calls[2]), '/api/v1/auth/register');
    assert.equal(h.calls[2].options.headers[header], 'anon');
    assert.deepEqual(JSON.parse(h.calls[2].options.body), { username: 'alice', password: 'password' });
    await h.respond(2, userA);
    await pending;
    assert.equal(h.state.AppState.user, null);
    assert.equal(h.elements['auth-title'].textContent, '登录');
    assert.match(h.elements['auth-status'].textContent, /注册成功，请登录/);
    assert.equal(h.elements['auth-password'].value, '');
    assert.equal(h.calls.length, 3);
});

for (const [register, status, message] of [
    [false, 401, /用户名或密码错误/],
    [true, 409, /用户名已存在/],
]) {
    test('认证表单反馈 ' + status, async () => {
        const h = await fixture();
        if (register) await h.elements['auth-toggle'].fire('click');
        const pending = submit(h);
        await h.respond(1, csrf('anon'));
        await h.respond(2, null, status);
        await pending;
        assert.match(h.elements['auth-status'].textContent, message);
        assert.equal(h.elements['auth-submit'].disabled, false);
        assert.equal(h.reloads(), 0);
    });
}

test('登录后轮换 Token，并发写请求复用获取操作，FormData 无手工 Content-Type', async () => {
    const h = await fixture();
    const login = submit(h);
    await h.respond(1, csrf('anon'));
    assert.equal(path(h.calls[2]), '/api/v1/auth/login');
    assert.equal(h.calls[2].options.headers[header], 'anon');
    await h.respond(2, userA);
    await login;
    assert.equal(h.elements.workspace.hidden, false);
    assert.equal(path(h.calls[3]), '/api/v1/knowledge-bases');

    const file = new FormData();
    file.append('file', new Blob(['text']), 'test.md');
    const writes = [
        h.request('/api/v1/upload', { method: 'POST', body: file }),
        ...['PUT', 'PATCH', 'DELETE'].map((method) => h.request('/api/v1/example', { method })),
    ];
    assert.deepEqual(paths(h).slice(4), ['/api/v1/auth/csrf']);
    await h.respond(4, csrf('authenticated'));
    for (const call of h.calls.slice(5)) assert.equal(call.options.headers[header], 'authenticated');
    assert.equal(h.calls[5].options.body, file);
    assert.equal(h.calls[5].options.headers['Content-Type'], undefined);
    for (let i = 5; i < 9; i++) await h.respond(i, null);
    await Promise.all(writes);
});

test('403 保留登录状态，只在手动重试时重新获取 Token', async () => {
    const h = await setup({ manualAuth: true });
    await h.respond(0, userA);
    const write = () => h.request('/api/v1/knowledge-bases', { method: 'POST', body: {} });
    const rejected = assert.rejects(write(), { status: 403 });
    await h.respond(2, csrf('stale'));
    await h.respond(3, null, 403);
    await rejected;
    assert.equal(h.calls.length, 4);
    assert.equal(h.state.AppState.user.username, userA.username);
    const retried = write();
    assert.equal(path(h.calls[4]), '/api/v1/auth/csrf');
    await h.respond(4, csrf('fresh'));
    assert.equal(h.calls[5].options.headers[header], 'fresh');
    await h.respond(5, null);
    await retried;
});

test('退出网络失败可重试，成功后清空 A 的数据，B 登录后只显示 B 的知识库', async () => {
    const session = new Map();
    const a = await setup({ manualAuth: true, session });
    await a.respond(0, userA);
    await a.respond(1, [a.kb('1')]);
    const logout = a.elements.logout.fire('click');
    await a.respond(3, csrf('a-token'));
    a.calls[4].reject(new Error());
    await logout;
    assert.match(a.elements['logout-status'].textContent, /退出状态未确认/);
    assert.equal(a.reloads(), 0);
    const retry = a.elements.logout.fire('click');
    await tick();
    assert.equal(path(a.calls[5]), '/api/v1/auth/logout');
    await a.respond(5, null);
    await retry;
    assert.equal(a.reloads(), 1);
    assert.equal(a.elements.workspace.hidden, true);
    assert.equal(a.state.AppState.user, null);
    assert.equal(a.state.AppState.knowledgeBases.length, 0);
    assert.equal(a.state.AppState.selectedKnowledgeBaseId, '');
    assert.equal(a.elements.kb.children.length, 0);

    const b = await setup({ manualAuth: true, session });
    await b.respond(0, null, 401);
    assert.equal(b.elements['auth-form'].hidden, false);
    const login = submit(b);
    await b.respond(1, csrf('b-token'));
    await b.respond(2, userB);
    await login;
    await b.respond(3, [b.kb('2')]);
    assert.deepEqual(
        Array.from(b.state.AppState.knowledgeBases, (kb) => kb.id),
        ['2'],
    );
});

test('Session 过期只重载一次，旧请求失效，新页面提示重新登录', async () => {
    const session = new Map();
    const h = await setup({ manualAuth: true, session });
    await h.respond(0, userA);
    const rejected = assert.rejects(h.request('/api/v1/example'), { status: 401 });
    const stale = assert.rejects(h.request('/api/v1/other'), { kind: 'aborted' });
    await h.respond(2, null, 401);
    await h.respond(3, null, 401);
    await Promise.all([rejected, stale]);
    await h.respond(1, [h.kb('1')]);
    assert.equal(h.state.AppState.knowledgeBases.length, 0);
    assert.equal(h.reloads(), 1);
    const restored = await setup({ manualAuth: true, session });
    await restored.respond(0, null, 401);
    assert.equal(restored.elements['auth-form'].hidden, false);
    assert.match(restored.elements['auth-status'].textContent, /登录已过期/);
    assert.equal(restored.reloads(), 0);
});
