// 仅供人工浏览器验收：零持久化、无代理、无模型和基础设施依赖。
import http from 'node:http';
import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const root = fileURLToPath(new URL('../../main/resources/static/', import.meta.url));
const time = '2026-11-12T00:00:00Z';
const bases = [{ id: '1001', name: 'Mock 多页知识库', description: '仅内存测试', createdAt: time }, { id: '1002', name: 'Mock 空知识库', description: '', createdAt: time }];
const tasks = new Map();
let next = 3000;
const parameters = { vectorTopK: 20, bm25TopK: 20, rrfK: 60, fusionTopK: 20, finalTopK: 5 };
const citation = n => ({ evidenceId: `E${n}`, chunkId: String(5000 + n), documentId: '2001', indexVersionId: '4001', sourceStart: 0, sourceEnd: 80 });
const server = http.createServer(async (req, res) => {
    const url = new URL(req.url, 'http://localhost');
    const send = (data, status = 200, code = 0) => {
        if (res.destroyed) return;
        res.writeHead(status, { 'Content-Type': 'application/json', 'X-Request-Id': 'mock-browser' });
        res.end(JSON.stringify({ code, message: code ? 'Mock 错误' : '成功', data, requestId: 'mock-browser' }));
    };
    try {
        if (!url.pathname.startsWith('/api/v1/')) {
            const relative = url.pathname === '/api/' ? 'index.html' : url.pathname.slice('/api/'.length);
            if (!url.pathname.startsWith('/api/') || relative.includes('..')) return send(null, 404, 40400);
            const file = path.resolve(root, relative);
            if (!file.startsWith(root)) return send(null, 404, 40400);
            const content = await readFile(file);
            res.writeHead(200, { 'Content-Type': ({ '.html': 'text/html; charset=utf-8', '.css': 'text/css', '.js': 'text/javascript' })[path.extname(file)] ?? 'application/octet-stream' });
            return res.end(content);
        }
        const chunks = [];
        for await (const chunk of req) {
            chunks.push(chunk);
            if (chunks.reduce((n, c) => n + c.length, 0) > 4 * 1024 * 1024) return send(null, 413, 40000);
        }
        const raw = Buffer.concat(chunks).toString();
        const body = req.headers['content-type']?.includes('application/json') ? JSON.parse(raw || '{}') : {};
        const text = body.question ?? body.query ?? body.name ?? '';
        // 显式场景词只改变 Mock 响应，不修改生产协议。
        if (text.includes('network')) return req.socket.destroy();
        if (text.includes('delay')) await new Promise(resolve => setTimeout(resolve, 65000));
        for (const status of [400, 404, 500]) if (text.includes(`http${status}`)) return send(null, status, status * 100);
        if (url.pathname === '/api/v1/knowledge-bases') {
            if (req.method === 'GET') return send(bases);
            if (req.method === 'POST') {
                if (!body.name?.trim() || body.name.length > 100 || (body.description?.length ?? 0) > 1000) return send(null, 400, 40000);
                const kb = { id: String(++next), name: body.name, description: body.description ?? '', createdAt: time };
                bases.push(kb); return send(kb, 201);
            }
        }
        if (/\/documents$/.test(url.pathname) && req.method === 'GET') {
            const page = Number(url.searchParams.get('page') ?? 1);
            const total = url.pathname.includes('/1001/') ? 21 : 0;
            const items = Array.from({ length: Math.max(0, Math.min(20, total - (page - 1) * 20)) }, (_, i) => ({ documentId: String(2001 + (page - 1) * 20 + i), filename: `Mock-${'长文件名'.repeat(20)}-${i}.md`, createdAt: time, publicationStatus: 'PUBLISHED', activeVersionId: '4001', publishedAt: time, importTask: null }));
            return send({ items, page, pageSize: 20, total: String(total) });
        }
        if (url.pathname.endsWith('/documents:import') && req.method === 'POST') {
            const duplicate = raw.includes('duplicate.');
            const id = String(++next);
            tasks.set(id, { polls: 0, failed: raw.includes('failed.') });
            return send({ importTaskId: id, documentId: '2001', status: 'RECEIVED', createdAt: time, updatedAt: time, failureCode: null }, duplicate ? 200 : 202);
        }
        if (/\/import-tasks\/\d+$/.test(url.pathname) && req.method === 'GET') {
            const id = url.pathname.split('/').at(-1), task = tasks.get(id);
            if (!task) return send(null, 404, 40400);
            const status = ++task.polls < 2 ? 'PROCESSING' : task.failed ? 'FAILED' : 'READY';
            return send({ importTaskId: id, documentId: '2001', status, createdAt: time, updatedAt: time, failureCode: status === 'FAILED' ? 'IMPORT_FAILED' : null });
        }
        if (url.pathname === '/api/v1/chat/answers' && req.method === 'POST') {
            const status = text.includes('empty') ? 'INSUFFICIENT_EVIDENCE' : text.includes('failed') ? 'FAILED' : 'ANSWERED';
            return send({ status, answer: status === 'ANSWERED' ? `Mock 回答\n${'<script>仅文本</script>长内容'.repeat(60)} [E1] [E2]` : null, citations: status === 'ANSWERED' ? [citation(1), citation(2)] : [], requestedStrategy: body.strategy, actualStrategy: body.strategy, degraded: false, error: status === 'FAILED' ? 'GENERATION_FAILED' : null, diagnostics: { retrievalStatus: 'SUCCESS', retrievalError: null, snapshotHash: 'mock', usage: null } });
        }
        if (/\/chunks\/\d+$/.test(url.pathname) && req.method === 'GET') {
            const id = url.pathname.split('/').at(-1);
            // E2 固定 404，便于验证恢复、切换与重开。
            if (id === '5002') return send(null, 404, 40400);
            return send({ chunkId: id, documentId: '2001', indexVersionId: '4001', filename: 'Mock-' + '长文件名'.repeat(40), headingPath: '长标题'.repeat(80), content: 'Mock 证据\n'.repeat(200), sourceStart: 0, sourceEnd: 80 });
        }
        if (url.pathname === '/api/v1/retrieval/search' && req.method === 'POST') return send({ results: body.strategies.map(strategy => {
            const failed = text.includes('failed') || text.includes('partial') && strategy === 'HYBRID_RERANK';
            const empty = text.includes('empty');
            return { chunks: failed || empty ? [] : [{ ...citation(1), knowledgeBaseId: body.knowledgeBaseId, content: 'Mock 检索内容\n'.repeat(50), headingPath: 'Mock 标题', vectorRank: 1, vectorDistance: 0.2, bm25Rank: null, bm25Score: null, rrfScore: null, rerankScore: null, finalRank: 1 }], diagnostics: { requestedStrategy: strategy, actualStrategy: failed ? null : strategy, degraded: false, status: failed ? 'FAILED' : empty ? 'EMPTY_RESULT' : 'SUCCESS', snapshotHash: 'mock', parameters: body.parameters ?? parameters, stages: [], error: failed ? 'INTERNAL_ERROR' : null } };
        }) });
        send(null, 404, 40400);
    } catch { send(null, 500, 50000); }
});
server.listen(Number(process.env.KA_MOCK_PORT ?? 18080), '127.0.0.1', () => console.log(`Mock ONLY http://127.0.0.1:${server.address().port}/api/index.html — Ctrl+C 停止并清空内存`));
