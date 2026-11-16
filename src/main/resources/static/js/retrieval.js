import { request, ApiError } from './api.js';
import { AppState, subscribe } from './state.js';
export const strategies = ['VECTOR', 'BM25', 'HYBRID_RRF', 'HYBRID_RERANK'];
export const defaults = { vectorTopK: 20, bm25TopK: 20, rrfK: 60, fusionTopK: 20, finalTopK: 5 };
export function validateParameters(p, selected) {
    if (!selected.length || new Set(selected).size !== selected.length || selected.some((s) => !strategies.includes(s)))
        throw new Error('请选择一至四种策略。');
    for (const key of Object.keys(defaults))
        if (!Number.isInteger(p[key]) || p[key] < 1 || p[key] > (key === 'rrfK' ? 1000 : 100))
            throw new Error(key + ' 必须为允许范围内的整数。');
    for (const s of selected) {
        const pool = s === 'VECTOR' ? p.vectorTopK : s === 'BM25' ? p.bm25TopK : p.fusionTopK;
        if (p.finalTopK > pool || (s.startsWith('HYBRID') && p.fusionTopK > p.vectorTopK + p.bm25TopK))
            throw new Error('候选池参数冲突。');
    }
}
const id = (v) => typeof v === 'string' && /^[1-9]\d*$/.test(v) && BigInt(v) <= 9223372036854775807n;
const integer = (v) => Number.isInteger(v) && v >= 0 && v <= 2147483647;
const rank = (v) => v == null || (integer(v) && v > 0);
const score = (v) => v == null || (typeof v === 'number' && Number.isFinite(v));
export function validateResults(data, selected, kb) {
    const fail = () => {
        throw new ApiError('protocol');
    };
    if (!data || !Array.isArray(data.results) || data.results.length !== selected.length) fail();
    const seen = new Set();
    for (const result of data.results) {
        const d = result?.diagnostics;
        if (
            !d ||
            !selected.includes(d.requestedStrategy) ||
            seen.has(d.requestedStrategy) ||
            typeof d.degraded !== 'boolean' ||
            !['SUCCESS', 'EMPTY_RESULT', 'FAILED'].includes(d.status) ||
            !Array.isArray(result.chunks) ||
            !Array.isArray(d.stages)
        )
            fail();
        seen.add(d.requestedStrategy);
        // 失败也可能携带快照；只接受字符串，避免把畸形对象显示成有效诊断。
        if (d.snapshotHash != null && typeof d.snapshotHash !== 'string') fail();
        try {
            validateParameters(d.parameters, [d.requestedStrategy]);
        } catch {
            fail();
        }
        if (
            d.degraded ||
            (d.status === 'FAILED'
                ? d.actualStrategy !== null ||
                  ![
                      'SNAPSHOT_READ_FAILED',
                      'SNAPSHOT_INVALID',
                      'TERMS_LIMIT_EXCEEDED',
                      'EMBEDDING_FAILED',
                      'VECTOR_FAILED',
                      'BM25_FAILED',
                      'FUSION_FAILED',
                      'RERANK_FAILED',
                      'INTERNAL_ERROR',
                  ].includes(d.error)
                : d.actualStrategy !== d.requestedStrategy || d.error != null || typeof d.snapshotHash !== 'string')
        )
            fail();
        if ((d.status === 'SUCCESS') !== result.chunks.length > 0) fail();
        const phases = new Set();
        for (const stage of d.stages) {
            if (
                !stage ||
                !['EMBEDDING', 'VECTOR', 'BM25', 'FUSION', 'RERANK'].includes(stage.phase) ||
                phases.has(stage.phase) ||
                !Array.isArray(stage.candidates) ||
                stage.candidateCount !== stage.candidates.length ||
                !integer(stage.candidateCount)
            )
                fail();
            phases.add(stage.phase);
            const t = stage.elapsedMillis;
            if (
                !(
                    (typeof t === 'string' && /^(0|[1-9]\d*)$/.test(t) && BigInt(t) <= 9223372036854775807n) ||
                    (Number.isSafeInteger(t) && t >= 0)
                )
            )
                fail();
            for (const c of stage.candidates)
                if (!c || !id(c.chunkId) || !integer(c.rank) || c.rank < 1 || !score(c.score)) fail();
        }
        for (const c of result.chunks) {
            if (
                !c ||
                ['chunkId', 'documentId', 'indexVersionId', 'knowledgeBaseId'].some((k) => !id(c[k])) ||
                c.knowledgeBaseId !== kb ||
                typeof c.content !== 'string' ||
                (c.headingPath != null && typeof c.headingPath !== 'string') ||
                !integer(c.sourceStart) ||
                !integer(c.sourceEnd) ||
                c.sourceEnd <= c.sourceStart ||
                ['finalRank', 'vectorRank', 'bm25Rank'].some((k) => !rank(c[k])) ||
                ['vectorDistance', 'bm25Score', 'rrfScore', 'rerankScore'].some((k) => !score(c[k])) ||
                (c.vectorRank == null) !== (c.vectorDistance == null) ||
                (c.bm25Rank == null) !== (c.bm25Score == null)
            )
                fail();
        }
    }
    return data;
}
export function initializeRetrieval() {
    const root = document.querySelector('#retrieval');
    const node = (tag, text) => {
        const e = document.createElement(tag);
        if (text !== undefined) e.textContent = text;
        return e;
    };
    root.replaceChildren(node('h1', '检索实验室'));
    const context = node('p');
    context.className = 'context';
    const form = node('form');
    const label = node('label', '检索问题');
    label.setAttribute('for', 'retrieval-query');
    const query = node('textarea');
    query.id = 'retrieval-query';
    const choices = node('fieldset');
    choices.append(node('legend', '检索策略'));
    const checks = strategies.map((s) => {
        const l = node('label', s);
        const c = node('input');
        c.type = 'checkbox';
        c.value = s;
        c.checked = s === 'HYBRID_RRF';
        l.append(c);
        choices.append(l);
        return c;
    });
    const advanced = node('details');
    const summary = node('summary');
    advanced.append(summary);
    const inputs = {};
    function summarize() {
        summary.textContent =
            '高级参数 · ' +
            Object.keys(defaults)
                .map((k) => k + ' ' + inputs[k].value)
                .join(' / ');
    }
    for (const key of Object.keys(defaults)) {
        const l = node('label', key);
        const input = node('input');
        input.type = 'number';
        input.min = '1';
        input.max = key === 'rrfK' ? '1000' : '100';
        input.step = '1';
        input.value = String(defaults[key]);
        inputs[key] = input;
        l.append(input);
        advanced.append(l);
        input.addEventListener('input', summarize);
    }
    const reset = node('button', '恢复默认参数');
    reset.type = 'button';
    reset.addEventListener('click', () => {
        for (const k of Object.keys(defaults)) inputs[k].value = String(defaults[k]);
        summarize();
    });
    advanced.append(reset);
    summarize();
    const send = node('button', '开始检索');
    send.type = 'submit';
    const cancel = node('button', '取消等待');
    cancel.type = 'button';
    const status = node('p');
    status.setAttribute('role', 'status');
    status.setAttribute('aria-live', 'polite');
    const output = node('div');
    form.append(
        label,
        query,
        choices,
        advanced,
        node(
            'p',
            'VECTOR 及混合检索可能调用在线 Embedding；HYBRID_RERANK 还可能调用付费重排模型。仅主动提交时执行，无自动重试。',
        ),
        send,
        cancel,
    );
    root.append(context, form, status, output);
    let generation = 0,
        pending = null,
        kb = AppState.selectedKnowledgeBaseId,
        page = AppState.activePage;
    function controls() {
        send.disabled = !!pending || !kb || AppState.activePage !== 'retrieval';
        cancel.hidden = !pending;
    }
    function stop(message) {
        generation++;
        if (pending) {
            clearTimeout(pending.timer);
            pending.controller.abort();
            pending = null;
        }
        status.textContent = message;
        controls();
    }
    cancel.addEventListener('click', () => stop('已取消客户端等待，服务端可能继续执行。'));
    subscribe((s) => {
        if (kb !== s.selectedKnowledgeBaseId || page !== s.activePage) {
            stop('');
            output.replaceChildren();
            if (kb !== s.selectedKnowledgeBaseId) query.value = '';
            kb = s.selectedKnowledgeBaseId;
            page = s.activePage;
        }
        controls();
    });
    window.addEventListener('pagehide', () => stop('等待已结束。'));
    form.addEventListener('submit', async (e) => {
        e.preventDefault();
        if (pending || AppState.activePage !== 'retrieval') return;
        const selected = checks.filter((c) => c.checked).map((c) => c.value);
        const parameters = Object.fromEntries(
            Object.keys(defaults).map((k) => [k, inputs[k].value.trim() === '' ? NaN : Number(inputs[k].value)]),
        );
        try {
            if (!id(kb)) throw new Error('请选择知识库。');
            if (!query.value.trim()) throw new Error('请输入检索问题。');
            validateParameters(parameters, selected);
        } catch (error) {
            status.textContent = error.message;
            return;
        }
        const current = ++generation,
            selectedKb = kb;
        const controller = new AbortController();
        pending = {
            controller,
            timer: setTimeout(() => stop('等待超时，服务端可能继续执行；再次提交可能重复调用模型。'), 60000),
        };
        controls();
        output.replaceChildren();
        status.textContent = '正在检索…';
        try {
            const response = await request('../api/v1/retrieval/search', {
                method: 'POST',
                body: { knowledgeBaseId: selectedKb, query: query.value.trim(), strategies: selected, parameters },
                signal: controller.signal,
            });
            // 代次校验独立于 abort，阻止忽略取消信号的迟到响应写入。
            if (current !== generation || kb !== selectedKb) return;
            let data;
            try {
                data = validateResults(response.data, selected, selectedKb);
            } catch {
                throw new ApiError('protocol', { requestId: response.requestId });
            }
            for (const r of data.results) {
                const d = r.diagnostics;
                const card = node('article');
                card.append(
                    node('h2', d.requestedStrategy),
                    node('p', `实际策略：${d.actualStrategy ?? '不适用'} · ${d.status} · ${r.chunks.length} Chunks`),
                );
                if (d.status !== 'SUCCESS')
                    card.append(
                        node('p', d.status === 'EMPTY_RESULT' ? '没有找到匹配内容' : '该策略执行失败：' + d.error),
                    );
                const detail = node('details');
                detail.append(node('summary', 'Diagnostics'));
                for (const k of ['requestedStrategy', 'actualStrategy', 'degraded', 'status', 'snapshotHash', 'error'])
                    detail.append(node('p', k + '：' + (d[k] ?? '未提供')));
                detail.append(node('pre', JSON.stringify(d.parameters, null, 2)));
                if (!d.stages.length) detail.append(node('p', '未提供阶段数据'));
                for (const s of d.stages) {
                    detail.append(node('h3', `${s.phase} · ${s.elapsedMillis} ms · ${s.candidateCount} 候选`));
                    for (const c of s.candidates)
                        detail.append(node('p', `${c.chunkId} · rank ${c.rank} · score ${c.score ?? '不适用'}`));
                }
                // Diagnostics 紧邻策略标题；正文折叠只控制展示，不修改原始数据。
                card.append(detail);
                for (const c of r.chunks) {
                    const chunk = node('section');
                    chunk.className = 'retrieval-chunk';
                    chunk.append(
                        node('h3', '排名：' + (c.finalRank ?? '不适用')),
                        node('p', c.headingPath ?? '无标题路径'),
                    );
                    const content = node('pre', c.content);
                    content.className = 'chunk-preview';
                    const toggle = node('button', '展开全文');
                    toggle.type = 'button';
                    toggle.setAttribute('aria-expanded', 'false');
                    let expanded = false;
                    toggle.addEventListener('click', () => {
                        expanded = !expanded;
                        content.className = expanded ? 'chunk-full' : 'chunk-preview';
                        toggle.textContent = expanded ? '收起正文' : '展开全文';
                        toggle.setAttribute('aria-expanded', String(expanded));
                    });
                    chunk.append(content, toggle);
                    const scores = node('div');
                    scores.className = 'chunk-scores';
                    for (const k of [
                        'vectorRank',
                        'vectorDistance',
                        'bm25Rank',
                        'bm25Score',
                        'rrfScore',
                        'rerankScore',
                    ])
                        scores.append(node('p', k + '：' + (c[k] ?? '不适用')));
                    const metadata = node('details');
                    metadata.append(node('summary', '技术元数据'));
                    for (const k of [
                        'chunkId',
                        'documentId',
                        'indexVersionId',
                        'knowledgeBaseId',
                        'sourceStart',
                        'sourceEnd',
                    ])
                        metadata.append(node('p', k + '：' + c[k]));
                    chunk.append(scores, metadata);
                    card.append(chunk);
                }
                output.append(card);
            }
            status.textContent = '检索完成。requestId：' + (response.requestId ?? '未提供');
        } catch (error) {
            if (current === generation)
                status.textContent =
                    '请求失败：' +
                    ({
                        protocol: '响应格式异常',
                        network: '网络连接失败',
                        http: 'HTTP 错误',
                        business: '业务错误',
                        aborted: '等待已取消',
                    }[error.kind] ?? '未知错误') +
                    '；requestId：' +
                    (error.requestId ?? '未提供');
        } finally {
            if (current === generation) {
                clearTimeout(pending.timer);
                pending = null;
                controls();
            }
        }
    });
    root.append(
        node(
            'p',
            'Vector Distance 为距离；BM25 Score 为关键词评分；RRF Score 为融合评分；Rerank Score 为重排评分。不同分数不能直接比较或当作百分比。阶段可能共享，耗时不能直接相加作为请求总耗时。',
        ),
    );
    controls();
}
