import { mountAnswerReview } from './evaluation-review.js';
import { initializeAnswerEvaluation, renderAnswerRun } from './evaluation-answer.js';
const select = document.querySelector('#eval-run');
const content = document.querySelector('#eval-content');
let runs = [],
    revision = 0;
const metrics = [
    'precision3',
    'precision5',
    'ndcg3',
    'ndcg5',
    'recall3',
    'recall5',
    'cec3',
    'cec5',
    'hit3',
    'hit5',
    'mrr5',
];
// 口径依据 EvidenceMetrics、RankingMetrics 和 M4-B04；这里只解释，不计算指标。
const names = {
    FIXED_CODE_POINT: '固定长度切分',
    MARKDOWN_STRUCTURE_AWARE: '结构感知切分',
    VECTOR: '向量语义检索',
    BM25: 'BM25 关键词检索',
    HYBRID_RRF: '混合检索（RRF 融合）',
    HYBRID_RERANK: '混合检索 + 重排序',
};
const definitions = {
    precision: [
        '相关结果精确率',
        '前 K 个位置中有多少是人工确认的相关结果。',
        '单题 = 前 K 名中人工相关等级 ≥2 的 Chunk 数 / K。分母固定为 K，结果不足 K 不缩小分母；汇总为有效题的宏平均。',
        '越高表示前排相关结果占比越高。例如前3名等级为 [3,1,2]，Precision@3=2/3。',
        '不衡量必要事实是否齐全；多个相关块可能重复支持同一事实。',
    ],
    ndcg: [
        '池内归一化排序质量',
        '衡量高等级相关结果是否排在前面。',
        'DCG@K = Σ(2^等级−1)/log₂(排名+1)；等级0/1/2/3的收益为0/1/3/7。IDCG 将同题、同切分、同快照冻结候选池的全部等级降序后取前K名计算；nDCG=DCG/IDCG，IDCG=0时无定义。汇总取有效题宏平均。',
        '越高表示越接近池内理想排序。例如只有等级 [0,3] 的池，@1将3级排第一得1，排0级第一得0。',
        '这是三策略 Top-5 并集池内的相对分数，不代表全库质量。不同切分策略的候选池及 IDCG 可能不同，不直接比较绝对值；1级背景也有收益。',
    ],
    recall: [
        '证据召回率',
        '衡量必要 Gold 证据区间覆盖的比例。',
        '单题 = 前K名连续完整覆盖的 Gold Evidence 数 / 该题必要 Evidence 总数。只联合同一来源、哈希及文档版本的 SourceSpan；区间可相接或重叠，不能跨越空洞。汇总为逐题比例的宏平均，不是所有证据合并计数。',
        '越高表示覆盖的必要证据越多。例如2段Gold只完整覆盖1段，单题得1/2。',
        '来源区间完整覆盖不等于语义完整，宽 Gold 区间可能降低得分；不是全库相关 Chunk 召回率。',
    ],
    cec: [
        '全部证据覆盖率',
        '衡量有多少题覆盖了全部必要 Gold 证据。',
        '单题：前K名以 SourceSpan 连续并集完整覆盖全部必要 Evidence 得1，否则0。汇总对有效题取平均。',
        '越高表示全部证据区间齐全的题越多。例如2段Gold只覆盖1段得0，全部覆盖得1。',
        '不区分差一小段还是完全未覆盖。来源区间完整覆盖不等于语义完整；Q11 区间覆盖通过仍有语义条件缺失。',
    ],
    hit: [
        '证据命中率',
        '衡量有多少题至少完整覆盖一段 Gold 证据。',
        '单题：前K名的 SourceSpan 连续并集完整覆盖至少1段必要 Evidence 得1，否则0；汇总为有效题宏平均。',
        '越高表示至少找到一段证据的题越多。例如2段Gold中完整覆盖1段，Hit=1，但CEC=0。',
        '片段重叠不算完整命中；命中一段不保证其他必要证据齐全，更不保证答案正确。',
    ],
    mrr: [
        '首段证据倒数排名',
        '衡量第一段完整覆盖的 Gold 证据出现得多早。',
        '逐步检查前1至前5名的区间并集，首次完整覆盖任意一段 Evidence 的前缀排名为 firstRank；单题=1/firstRank，前5名未达到则0。汇总为有效题宏平均。',
        '越高表示更早完整覆盖第一段证据。例如前2名合起来才覆盖完整一段，得1/2；不要求由单个Chunk覆盖。',
        '只关注第一段，不代表覆盖全部必要证据；SourceSpan 完整仍不等于语义完整。',
    ],
};
function metricTitle(key, parent) {
    const type = key.replace(/\d$/, ''),
        depth = key.at(-1),
        definition = definitions[type];
    el(
        'span',
        `${definition[0]} ${type === 'ndcg' ? 'nDCG' : type === 'cec' || type === 'mrr' ? type.toUpperCase() : type[0].toUpperCase() + type.slice(1)}@${depth}`,
        parent,
    );
    const help = el('details', null, parent);
    help.className = 'eval-help';
    const summary = el('summary', 'ⓘ', help);
    summary.setAttribute('aria-label', `${definition[0]}@${depth}的计算说明`);
    el('p', definition[1], help);
    for (const [i, label] of ['计算口径', '理解与例子', '局限性'].entries())
        el('p', label + '：' + definition[i + 2], help);
    el(
        'p',
        '汇总值旁的有效分母是参与该项平均的题数。失败、待审、快照不匹配或无有效分母不等于0%；SUCCESS/EMPTY_RESULT与不可评分状态应分别阅读。页面仅展示Runner结果。',
        help,
    );
}
function el(tag, text, parent) {
    const node = document.createElement(tag);
    if (text != null) node.textContent = text;
    parent?.append(node);
    return node;
}
function picker(label, values, parent) {
    const wrap = el('label', label + ' ', parent);
    const node = el('select', null, wrap);
    for (const value of values) {
        const option = el('option', names[value] ?? value, node);
        option.value = value;
    }
    return node;
}
function fields(data, parent) {
    const dl = el('dl', null, parent);
    for (const [key, value] of Object.entries(data)) {
        el('dt', key, dl);
        el('dd', value ?? '未记录', dl);
    }
}
function score(metric, status) {
    if (!metric || !metric.denominator || metric.value == null)
        return `UNDEFINED · 分母 ${metric?.denominator ?? 0} · ${status || 'NOT_READY'}`;
    return `${(metric.value * 100).toFixed(2)}% · 分母 ${metric.denominator} · ${status || 'SCORED'}`;
}
async function render() {
    const current = ++revision;
    content.textContent = '正在加载…';
    try {
        const response = await fetch('/api/v1/evaluation/runs/' + encodeURIComponent(select.value));
        if (!response.ok) throw new Error();
        const run = await response.json();
        if (current !== revision) return;
        content.replaceChildren();
        const m = run.manifest;
        if (m.runnerVersion === 'm4-d03-answer-v1') {
            renderAnswerRun(run, content);
            mountAnswerReview(run.runId, content, () => current === revision);
            return;
        }
        fields(
            {
                Run: run.runId,
                模式: m.mode,
                状态: m.status,
                数据集: m.dataset,
                创建时间: m.createdAt ?? '未记录',
                冻结身份: (m.frozenPool ?? m.frozenInputs)?.fingerprint,
                Gold来源: run.gold.status,
            },
            content,
        );
        el(
            'p',
            '仅比较当前 Run 内同一切分方式。不同快照、Gold、参数或知识库版本不可默认比较；逐条覆盖未记录时显示未知。',
            content,
        );
        const chunking = picker('切分方式', [...new Set(run.results.map((r) => r.chunking))], content);
        const tableHost = el('div', null, content);
        tableHost.className = 'eval-scroll';
        const question = picker('题目', [...new Set(run.results.map((r) => r.questionId))], content);
        const strategies = [
            'VECTOR',
            'BM25',
            'HYBRID_RRF',
            ...(run.results.some((r) => r.strategy === 'HYBRID_RERANK') ? ['HYBRID_RERANK'] : []),
        ];
        const strategy = picker('检索策略', strategies, content);
        const details = el('div', null, content);
        function compare() {
            tableHost.replaceChildren();
            const table = el('table', null, tableHost);
            const head = el('tr', null, el('thead', null, table));
            el('th', '检索策略', head);
            for (const key of metrics) metricTitle(key, el('th', null, head));
            const body = el('tbody', null, table);
            for (const name of strategies) {
                const group =
                    run.metrics.groups?.find((g) => g.chunking === chunking.value && g.strategy === name) ??
                    run.metrics.summaries?.[name];
                const row = el('tr', null, body);
                el('th', names[name] ?? name, row);
                const statuses = run.metrics.runs.filter(
                    (r) => r.strategy === name && (!r.chunking || r.chunking === chunking.value),
                );
                for (const key of metrics) {
                    const status = [
                        ...new Set(
                            statuses
                                .map((r) =>
                                    key.startsWith('precision') || key.startsWith('ndcg')
                                        ? (r.rankingStatus ?? r['at' + key.at(-1)]?.status)
                                        : (r.evidenceStatus ?? r['evidence' + key.at(-1)]?.qualityStatus),
                                )
                                .filter(Boolean),
                        ),
                    ].join(' / ');
                    el('td', score(group?.[key] ?? group?.evidence?.[key], status), row);
                }
            }
            detail();
        }
        function detail() {
            details.replaceChildren();
            const result = run.results.find(
                (r) =>
                    r.questionId === question.value && r.chunking === chunking.value && r.strategy === strategy.value,
            );
            const metric = run.metrics.runs.find(
                (r) =>
                    r.questionId === question.value &&
                    (!r.chunking || r.chunking === chunking.value) &&
                    r.strategy === strategy.value,
            );
            const gold = run.gold.questions.find((q) => q.questionId === question.value);
            el('h2', question.value + ' · ' + (gold?.question ?? '问题原文不可用：来源缺失或校验失败'), details);
            fields(
                {
                    切分方式: names[chunking.value] ?? chunking.value,
                    检索策略: names[strategy.value] ?? strategy.value,
                    检索状态: result?.status ?? '未记录',
                    错误原因: result?.error ?? result?.errorMessage ?? '未记录',
                    Ranking: metric?.rankingStatus,
                    Gold适用性: metric?.evidenceStatus,
                },
                details,
            );
            el('h3', '逐题正式评分与覆盖', details);
            const guide = el('div', null, details);
            guide.className = 'eval-metric-guide';
            for (const key of metrics) metricTitle(key, el('div', null, guide));
            for (const depth of [3, 5]) {
                const rank = metric?.['at' + depth],
                    evidence = metric?.['evidence' + depth];
                fields(
                    {
                        深度: depth,
                        Ranking状态: rank?.status ?? metric?.rankingStatus ?? 'NOT_READY',
                        相关结果精确率Precision: rank?.status === 'SCORED' ? rank.precision : 'UNDEFINED',
                        池内排序质量nDCG: rank?.status === 'SCORED' ? rank.ndcg : 'UNDEFINED',
                        有效题数: rank?.status === 'SCORED' ? 1 : (metric?.rankingDenominator ?? 0),
                        证据状态: evidence?.qualityStatus ?? metric?.evidenceStatus,
                        覆盖证据数: evidence?.covered,
                        证据分母: evidence?.evidenceCount,
                        首个命中排名: evidence?.firstRank,
                        完整覆盖排名: evidence?.completeRank,
                    },
                    details,
                );
            }
            el('h3', 'Gold Evidence', details);
            if (!gold) el('p', '不可用：' + run.gold.status, details);
            for (const evidence of gold?.goldEvidence ?? []) {
                fields(
                    {
                        来源: gold.documentPath,
                        区间: `[${evidence.span.start}, ${evidence.span.end}) Unicode code point`,
                        逐条覆盖: '未知：Runner 未记录逐条覆盖；不推断未覆盖证据',
                    },
                    details,
                );
                el('blockquote', evidence.text, details);
            }
            el('h3', 'Top-K · 原始顺序', details);
            if (!result) el('p', '该组合未记录', details);
            const pool = (m.frozenPool ?? m.frozenInputs)?.pools?.find(
                (p) =>
                    p.questionId === question.value &&
                    p.chunking === chunking.value &&
                    p.snapshotHash === result?.snapshotHash,
            );
            for (const chunk of result?.chunks ?? []) {
                const card = el('article', null, details);
                card.className = 'eval-chunk';
                const label = pool?.judgments?.find(
                    (j) =>
                        String(j.chunkId) === String(chunk.chunkId) &&
                        String(j.documentId) === String(chunk.documentId) &&
                        String(j.indexVersionId) === String(chunk.indexVersionId),
                );
                el('h4', `排名 ${chunk.finalRank ?? '未记录'} · Chunk ${chunk.chunkId}`, card);
                fields(
                    {
                        来源: label?.documentPath ?? chunk.documentPath ?? '未记录',
                        Document: chunk.documentId,
                        IndexVersion: chunk.indexVersionId,
                        位置: `${chunk.sourceStart ?? '未记录'}–${chunk.sourceEnd ?? '未记录'}`,
                        相关性: label?.reviewStatus === 'CONFIRMED' ? label.relevance : '未记录 / 不适用',
                        人工确认: label?.reviewStatus ?? '未记录',
                        依据: label?.rationale,
                    },
                    card,
                );
                el('pre', chunk.content ?? '未记录', card);
                fields(
                    {
                        Vector排名: chunk.vectorRank,
                        BM25排名: chunk.bm25Rank,
                        RRF: chunk.rrfScore,
                        Vector距离: chunk.vectorDistance,
                        BM25得分: chunk.bm25Score,
                    },
                    card,
                );
            }
            if (result?.chunks?.length === 0)
                el('p', result.status === 'FAILED' ? 'FAILED：检索失败' : '空结果；执行状态 ' + result.status, details);
        }
        chunking.onchange = compare;
        question.onchange = detail;
        strategy.onchange = detail;
        compare();
    } catch {
        if (current === revision) content.textContent = '记录加载失败：缺失文件、协议错误或 SHA-256 校验失败。';
    }
}
select.onchange = render;
document.querySelector('#eval-load').onclick = async () => {
    ++revision;
    content.textContent = '正在读取…';
    select.disabled = true;
    try {
        const response = await fetch('/api/v1/evaluation/runs');
        if (!response.ok) throw new Error();
        runs = await response.json();
        select.replaceChildren();
        for (const run of runs) {
            const option = el(
                'option',
                `${run.runId} · ${run.manifest.mode ?? 'ANSWER'} · ${run.manifest.status} · ${run.manifest.dataset}`,
                select,
            );
            option.value = run.runId;
        }
        if (runs.length) await render();
        else content.textContent = '暂无有效 Run；损坏或不完整记录不会列出。';
    } catch {
        content.textContent = '读取失败，请检查本地服务和评估产物。';
    } finally {
        select.disabled = false;
    }
};

initializeAnswerEvaluation(async (runId) => {
    let option = [...select.options].find((item) => item.value === runId);
    if (!option) {
        option = el('option', runId, select);
        option.value = runId;
    }
    select.value = runId;
    await render();
});
