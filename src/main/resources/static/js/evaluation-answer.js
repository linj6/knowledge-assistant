import { request } from './api.js';
import { AppState, subscribe } from './state.js';

const explanations = {
    GOLD_FINGERPRINT_MISMATCH: '冻结 Gold 指纹不匹配，请检查数据集。',
    PUBLISHED_SOURCE_MISMATCH: '当前知识库发布来源与 Gold 不匹配。',
    MODEL_COST_NOT_AUTHORIZED: '费用授权或服务端模型授权未通过。',
    EXECUTION_SNAPSHOT_MISMATCH: '实际执行快照与预检查快照不一致。'
};
function node(tag, text, parent) {
    const element = document.createElement(tag);
    if (text != null) element.textContent = text;
    parent.append(element);
    return element;
}
function value(data) { return data == null ? '未产生' : typeof data === 'object' ? JSON.stringify(data, (key, item) => item == null ? '未产生' : item, 2) : String(data); }
function section(title, data, parent, collapsed = false) {
    const host = node(collapsed ? 'details' : 'article', null, parent);
    node(collapsed ? 'summary' : 'h3', title, host);
    node('pre', value(data), host);
}
export function renderAnswerRun(run, parent) {
    const m = run.manifest, result = run.results, execution = result?.execution;
    section('Answer Run', {runId: run.runId, createdAt: m.createdAt, caseId: result?.case?.caseId,
        question: result?.case?.question, requestedStrategy: m.request?.retrievalStrategy,
        actualStrategy: execution?.retrieval?.diagnostics?.actualStrategy, status: m.status,
        errorCode: m.errorCode, stage: m.stage}, parent);
    node('p', 'COMPLETED 表示执行完成，不代表回答质量通过；NOT_READY 表示预检查未通过；FAILED 表示执行失败。', parent);
    if (m.errorCode) node('p', explanations[m.errorCode] ?? m.errorCode, parent);
    section('模型回答', execution?.answer?.answer, parent);
    section('Citation · 模型最终引用', execution?.answer?.citations, parent);
    section('SelectedEvidence · ContextBuilder 实际选择', execution?.context?.selectedEvidence, parent, true);
    section('检索 Top-K 候选 · 不等于实际证据', execution?.retrieval?.chunks, parent, true);
    section('实际模型上下文', execution?.context?.contextText, parent, true);
    section('执行诊断', {failureType: execution?.failureType ?? null, retrieval: execution?.retrieval?.diagnostics ?? null,
        answer: execution?.answer?.diagnostics ?? null, answerStatus: execution?.answer?.status ?? null,
        answerError: execution?.answer?.error ?? null, contextUsage: execution?.context?.usage ?? null}, parent, true);
    section('Snapshot / 模型标识', {snapshot: m.snapshot ?? null, chatModel: m.configuredChatModel ?? null,
        embeddingModel: m.configuredEmbeddingModel ?? null, datasetSha256: m.datasetSha256}, parent, true);
}
// Evaluation 控制器返回裸 JSON，与普通业务 API 的 envelope 不同。
async function evaluationRequest(path, options) {
    return (await request('/api/v1/evaluation/' + path, { ...options, raw: true })).data;
}
export function initializeAnswerEvaluation(openRun) {
    const host = document.querySelector('#evaluation');
    const form = document.createElement('form');
    host.insertBefore(form, document.querySelector('#eval-load'));
    node('h2', '单题 Answer Gold V1 真实评估', form);
    const load = node('button', '加载冻结 Answer Gold 数据集', form); load.type = 'button';
    const questionLabel = node('label', '冻结问题', form);
    const question = node('select', null, questionLabel);
    const preview = node('p', '尚未加载数据集', form);
    const strategyLabel = node('label', '检索策略', form);
    const strategy = node('select', null, strategyLabel);
    for (const name of ['BM25', 'VECTOR', 'HYBRID_RRF']) node('option', name, strategy).value = name;
    const kb = node('p', null, form);
    node('p', '本次执行会调用真实模型，可能产生费用。检索参数使用 V1 默认值。', form);
    const authLabel = node('label', null, form);
    const auth = node('input', null, authLabel); auth.type = 'checkbox';
    authLabel.append(document.createTextNode(' 我明确授权本次模型费用'));
    const submit = node('button', '运行一次 Answer Evaluation', form); submit.type = 'submit';
    const status = node('p', null, form); status.setAttribute('role', 'status');
    let cases = [], busy = false, kbId = AppState.selectedKnowledgeBaseId;
    function update() {
        kb.textContent = '当前知识库：' + (AppState.knowledgeBases.find(item => item.id === AppState.selectedKnowledgeBaseId)?.name ?? '尚未选择');
        submit.disabled = busy || !auth.checked || !question.value || !AppState.selectedKnowledgeBaseId;
        question.disabled = strategy.disabled = auth.disabled = load.disabled = busy;
    }
    function reset() { auth.checked = false; update(); }
    subscribe(() => { if (kbId !== AppState.selectedKnowledgeBaseId) { kbId = AppState.selectedKnowledgeBaseId; reset(); } update(); });
    question.onchange = () => { preview.textContent = cases.find(item => item.caseId === question.value)?.question ?? '未产生'; reset(); };
    strategy.onchange = reset; auth.onchange = update;
    document.querySelector('#eval-run').addEventListener('change', reset);
    document.querySelector('#eval-load').addEventListener('click', reset);
    load.onclick = async () => {
        reset(); load.disabled = true;
        try {
            const description = await evaluationRequest('datasets');
            if (description.dataset?.protocol !== 'answer-evaluation-v1' || description.dataset.freezeStatus !== 'FROZEN') throw new Error('数据集协议不匹配');
            cases = description.dataset.cases;
            question.replaceChildren();
            for (const item of cases) node('option', item.caseId, question).value = item.caseId;
            question.onchange(); status.textContent = '冻结数据集已加载';
        } catch (error) { cases = []; question.replaceChildren(); preview.textContent = '数据集不可用'; status.textContent = '加载失败，请确认 app.evaluation.enabled=true。' + error.message; }
        finally { update(); }
    };
    form.onsubmit = async event => {
        event.preventDefault();
        if (busy || submit.disabled || !auth.checked) return;
        busy = true; update(); status.textContent = '正在执行，请勿重复提交…';
        try {
            const outcome = await evaluationRequest('runs', {method: 'POST', body: {
                dataset: 'answer-evaluation-v1', caseId: question.value, knowledgeBaseId: AppState.selectedKnowledgeBaseId,
                retrievalStrategy: strategy.value, parameters: {vectorTopK:20,bm25TopK:20,rrfK:60,fusionTopK:20,finalTopK:5}, authorizeModelCost:true
            }});
            status.textContent = `${outcome.runId ?? '未产生 RunId'} · ${outcome.status} · ${outcome.errorCode ?? '无错误码'} ${explanations[outcome.errorCode] ?? ''}`;
            if (outcome.runId) await openRun(outcome.runId);
        } catch (error) { status.textContent = 'HTTP / 网络或响应异常：' + error.message + '。服务器可能已经产生结果，请加载历史 Run 核查；不会自动重试。'; }
        finally { busy = false; reset(); }
    };
    update();
}
