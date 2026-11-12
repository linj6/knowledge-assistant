import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { once } from 'node:events';

async function start(t) {
    const child = spawn(process.execPath, [new URL('./web-browser-mock.mjs', import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1')], { env: { ...process.env, KA_MOCK_PORT: '0' }, stdio: ['ignore', 'pipe', 'pipe'] });
    t.after(async () => { child.kill(); await once(child, 'exit'); });
    const origin = await new Promise((resolve, reject) => {
        const timeout = setTimeout(() => reject(new Error('Mock 启动超时')), 10000);
        child.stdout.on('data', data => {
            const match = String(data).match(/http:\/\/127\.0\.0\.1:\d+/);
            if (match) { clearTimeout(timeout); resolve(match[0]); }
        });
        child.once('error', error => { clearTimeout(timeout); reject(error); });
    });
    const get = async path => (await (await fetch(origin + '/api/v1/' + path)).json()).data;
    const upload = async (kb, name, content) => {
        const body = new FormData(); body.append('file', new Blob([content]), name);
        const response = await fetch(`${origin}/api/v1/knowledge-bases/${kb}/documents:import`, { method: 'POST', body });
        return { status: response.status, task: (await response.json()).data };
    };
    const list = (kb, page = 1) => get(`knowledge-bases/${kb}/documents?page=${page}&pageSize=20`);
    return { origin, get, upload, list };
}

test('Mock 上传发布闭环、分页和同进程页面重载保留', async t => {
    const { origin, get, upload, list } = await start(t);
    assert.equal((await list('1001')).total, '21');
    const { status, task } = await upload('1001', '新增.md', 'Mock 新内容');
    assert.equal(status, 202); assert.equal(task.status, 'RECEIVED');
    let first = await list('1001');
    assert.equal(first.total, '22'); assert.equal(first.items.length, 20);
    assert.equal(first.items[0].documentId, task.documentId);
    assert.equal(first.items[0].filename, '新增.md');
    assert.equal(first.items[0].publicationStatus, 'UNPUBLISHED');
    assert.equal(first.items[0].activeVersionId, null);
    assert.equal((await get(`import-tasks/${task.importTaskId}`)).status, 'PROCESSING');
    assert.equal((await get(`import-tasks/${task.importTaskId}`)).status, 'READY');
    first = await list('1001');
    const document = first.items[0];
    assert.equal(document.documentId, task.documentId);
    assert.equal(document.publicationStatus, 'PUBLISHED');
    assert.equal(document.activeVersionId, document.importTask.indexVersionId);
    assert.ok(document.publishedAt); assert.equal(document.importTask.status, 'READY');
    const second = await list('1001', 2);
    assert.equal(second.total, '22'); assert.equal(second.items.length, 2);
    assert.equal(new Set([...first.items, ...second.items].map(d => d.documentId)).size, 22);
    // 新的 HTTP 客户端重新加载入口及列表，模拟刷新后的服务器数据读取；不是浏览器测试。
    assert.equal((await fetch(origin + '/api/index.html')).status, 200);
    assert.deepEqual(await list('1001'), first);
    assert.equal((await list('1002')).total, '0');
});

test('同库内容去重返回同一任务，跨库内容独立', async t => {
    const { upload, get, list } = await start(t);
    const first = await upload('1002', 'one.txt', 'Mock 相同内容');
    const duplicate = await upload('1002', 'renamed.md', 'Mock 相同内容');
    assert.equal(duplicate.status, 200); assert.deepEqual(duplicate.task, first.task);
    await get(`import-tasks/${first.task.importTaskId}`); await get(`import-tasks/${first.task.importTaskId}`);
    const readyDuplicate = await upload('1002', 'one.txt', 'Mock 相同内容');
    assert.equal(readyDuplicate.task.status, 'READY');
    assert.equal(readyDuplicate.task.documentId, first.task.documentId);
    assert.equal((await list('1002')).total, '1');
    const other = await upload('1001', 'one.txt', 'Mock 相同内容');
    assert.equal(other.status, 202); assert.notEqual(other.task.documentId, first.task.documentId);
    assert.equal((await list('1001')).total, '22');
});

test('FAILED 不发布，终态保持且不同内容可生成不同文档', async t => {
    const { upload, get, list } = await start(t);
    const { task } = await upload('1002', 'failed.md', 'Mock 失败');
    await get(`import-tasks/${task.importTaskId}`);
    const failed = await get(`import-tasks/${task.importTaskId}`);
    assert.equal(failed.status, 'FAILED'); assert.equal(failed.failureCode, 'IMPORT_FAILED');
    assert.deepEqual(await get(`import-tasks/${task.importTaskId}`), failed);
    const document = (await list('1002')).items[0];
    assert.equal(document.publicationStatus, 'UNPUBLISHED');
    assert.equal(document.activeVersionId, null); assert.equal(document.publishedAt, null);
    assert.equal(document.importTask.status, 'FAILED');
    await upload('1002', 'failed.md', 'Mock 不同内容');
    assert.equal((await list('1002')).total, '2');
});

test('新服务器进程重置内存，创建知识库可独立上传', async t => {
    const first = await start(t);
    await first.upload('1002', 'one.md', 'Mock reset');
    const second = await start(t);
    assert.equal((await second.list('1002')).total, '0');
    const response = await fetch(second.origin + '/api/v1/knowledge-bases', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ name: 'Mock 新库', description: '' }) });
    const kb = (await response.json()).data;
    const { task } = await second.upload(kb.id, 'new.md', 'Mock new');
    assert.equal((await second.list(kb.id)).items[0].documentId, task.documentId);
    assert.equal((await second.list('1002')).total, '0');
});
