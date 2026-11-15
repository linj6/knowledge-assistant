import { request } from './api.js';

const judgments = ['PENDING', 'PASS', 'FAIL'];
const dimensions = {appropriateness:'Response Appropriateness', correctness:'Factual Correctness', completeness:'Answer Completeness', validity:'Citation Validity', support:'Citation Semantic Support', wholeCase:'Whole-case'};
function el(tag, text, parent) { const n = document.createElement(tag); if (text != null) n.textContent = String(text); parent.append(n); return n; }
function field(label, tag, parent, value = '') { const n = el(tag, null, el('label', label, parent)); n.value = value; return n; }
function picker(label, values, parent, value = 'PENDING') { const n = field(label, 'select', parent, value); for (const v of values) el('option', v, n).value = v; return n; }
function fold(title, text, parent) { const box = el('details', null, parent); el('summary', title, box); el('pre', text ?? '未记录', box); return box; }
function facts(fact, parent) {
    el('h4', fact.factId ?? '未记录 Fact ID', parent); el('p', fact.description ?? '未记录描述', parent);
    el('p', '限定：' + (fact.qualifications ?? '未记录'), parent);
    for (const ref of fact.sourceRefs ?? []) el('p', `Gold 来源：${ref.documentPath ?? '未记录'} · ${ref.sourceSha256 ?? '未记录'} · Evidence indexes ${(ref.evidenceIndexes ?? []).join(', ')}`, parent);
}
const errors = {REVIEW_INCOMPLETE:'必要人工判断尚未完成', INVALID_CLAIM_OR_CITATION:'Claim 或引用关联无效', REVIEW_IDENTITY_MISMATCH:'评审身份与当前 Run 不匹配', INVALID_FACT_ID_OR_JUDGMENT:'Fact ID 或判断无效'};

// 每次选择 Run 创建独立闭包；异步响应必须仍属于当前选择才可修改页面。
export function mountAnswerReview(runId, parent, isCurrent) {
    const host = el('section', null, parent); host.className = 'evaluation-review';
    let generation = 0, busy = false, uncertain = false;
    const active = () => isCurrent();
    const api = async (suffix, options) => (await request(`/api/v1/evaluation/runs/${encodeURIComponent(runId)}/${suffix}`, {...options, raw:true})).data;
    async function load(message = '') {
        const revision = ++generation;
        host.replaceChildren(); el('p', '正在加载人工评审…', host);
        try {
            const material = await api('review');
            if (!active() || revision !== generation) return;
            if (material.run?.runId !== runId) throw new Error('评审 Run 身份不匹配');
            draw(material, message);
            const score = await api('score');
            if (!active() || revision !== generation) return;
            if (score.caseId && score.caseId !== material.gold?.caseId) throw new Error('评分题目不匹配');
            showScore(score);
        } catch (error) {
            if (!active() || revision !== generation) return;
            const status = el('p', `${message} 读取失败：${error.message}。请重新读取服务器状态。`, host); status.setAttribute('role','status');
            const retry = el('button','重新读取评审',host); retry.type='button'; retry.onclick=()=>load();
        }
    }
    let scoreHost;
    function showScore(score = {}) {
        scoreHost.replaceChildren(); el('h3','五维评分 · 后端计算',scoreHost);
        const list=el('dl',null,scoreHost);
        for (const [key,label] of Object.entries(dimensions)) { el('dt',label,list); el('dd',score[key] === 'NA' ? 'N/A' : score[key] ?? 'PENDING',list); }
        for (const key of ['reviewComplete','requiredCount','coveredCount']) { el('dt',key,list); el('dd',score[key] ?? '未记录',list); }
        el('p','Citation Validity PASS 不等于 Citation Semantic Support PASS。未审为 PENDING；不计算通过率。',scoreHost);
    }
    function draw(material, message) {
        host.replaceChildren(); el('h2','人工评审',host);
        const run=material.run, m=run.manifest ?? {}, gold=material.gold ?? {}, execution=run.results?.execution ?? {}, answer=execution.answer ?? {};
        const saved=material.review?.judgments ?? {}, confirmed=material.reviewStatus==='CONFIRMED', failed=m.status==='FAILED', notReady=m.status==='NOT_READY';
        const info=el('dl',null,host);
        for (const [key,value] of Object.entries({RunId:runId,caseId:gold.caseId,问题:gold.question,requestedStrategy:m.request?.retrievalStrategy,actualStrategy:execution.retrieval?.diagnostics?.actualStrategy,执行状态:m.status,RAG状态:answer.status,Gold指纹:m.datasetSha256,发布快照:m.snapshot?.snapshotHash,评审状态:material.reviewStatus ?? 'PENDING',reviewer:material.review?.judgments?.reviewer,reviewedAt:material.review?.reviewedAt})) { el('dt',key,info); el('dd',value ?? '未记录',info); }
        el('p','COMPLETED 仅表示执行完成，不代表质量 PASS。',host);
        if (failed || notReady) el('p',`${failed?'执行失败，无可评回答':'预检查未通过，不允许确认'}：${m.errorCode ?? answer.error ?? execution.failureType ?? '未记录原因'}`,host);
        el('h3','模型回答',host); el('pre',answer.answer ?? '未产生回答',host);
        el('h3','Citation · 模型最终引用',host);
        const citations=answer.citations ?? [], selected=execution.context?.selectedEvidence ?? [];
        for (const citation of citations) {
            const card=el('article',null,host); card.className='eval-chunk';
            const evidence=selected.find(item=>item.evidenceId===citation.evidenceId);
            el('h4',citation.evidenceId ?? '未记录 ID',card);
            el('p',`ChunkId ${citation.chunkId ?? '未记录'} · [${citation.sourceStart ?? '未知'}, ${citation.sourceEnd ?? '未知'})`,card);
            el('pre',evidence?.content ?? '未找到对应 SelectedEvidence 正文，不能据此判定支持',card);
        }
        if (!citations.length) el('p','无实际 Citation；拒答不强制创建引用关系。',host);
        el('p','SelectedEvidence 是送入 Prompt 的证据；未被引用的证据不能补证 Claim。Retrieval Candidates 仅为检索候选。',host);
        const evidenceBox=fold('SelectedEvidence · 实际 Prompt 证据','',host);
        for (const e of selected) { el('h4',e.evidenceId ?? '未记录',evidenceBox); el('pre',e.content ?? '未记录正文',evidenceBox); }
        const candidates=fold('Retrieval Candidates · 原始检索候选','',host);
        for (const c of execution.retrieval?.chunks ?? []) { el('h4',`Chunk ${c.chunkId ?? '未记录'} · [${c.sourceStart ?? '未知'}, ${c.sourceEnd ?? '未知'})`,candidates); el('pre',c.content ?? '未记录正文',candidates); }
        fold('实际上下文',execution.context?.contextText,host);
        el('p',`Knowledge Answerable（Gold，只读）：${gold.knowledgeAnswerable == null ? 'PENDING' : String(gold.knowledgeAnswerable).toUpperCase()}`,host);
        el('p',`Citation Validity（后端校验，只读）：${material.citationValidity ?? 'PENDING'}`,host);
        if (gold.allowedAnswerBoundary) el('p','Gold 回答边界：'+gold.allowedAnswerBoundary,host);
        const form=el('form',null,host), controls=[];
        const register=n=>{controls.push(n);return n;};
        const coverage={}, claims=[];
        let context, appropriate, complete, draft, confirm;
        if (!failed) {
            context=register(picker('Context Answerable · 仅判断本次实际上下文是否足够', ['PENDING','TRUE','FALSE'], form, saved.contextAnswerable ?? 'PENDING'));
            el('p','TRUE：上下文足够；FALSE：上下文不足；PENDING：尚未判断。不能根据检索命中自动判 TRUE。',form);
            el('h3','必要事实覆盖',form);
            for (const fact of gold.requiredFacts ?? []) { const card=el('article',null,form); card.className='eval-chunk'; facts(fact,card); coverage[fact.factId]=register(picker('覆盖判断',judgments,card,saved.factCoverage?.[fact.factId] ?? 'PENDING')); }
            const optional=fold('可选事实 · 只读参考，不计必要覆盖','',form); for (const fact of gold.optionalFacts ?? []) facts(fact,optional);
            appropriate=register(picker('Response Appropriateness',judgments,form,saved.appropriateness ?? 'PENDING'));
            el('p','人工核验是否切题、充分上下文是否正确回答、上下文不足时是否明确限制并合理拒答。',form);
            el('h3','Claim Review',form);
            el('p','正确性核验事实、条件、单位与范围；支持性只核验实际关联引用正文的联合语义。缺失必要事实不自动等于事实错误。拒答的支持维度由后端记 N/A。',form);
            const claimHost=el('div',null,form); let sequence=0;
            const ids=new Set((saved.claims ?? []).map(c=>c.claimId));
            function addClaim(data) {
                let id=data?.claimId;
                if (!id) { do { id='C'+(++sequence); } while(ids.has(id)); ids.add(id); }
                const card=el('article',null,claimHost); card.className='eval-chunk'; el('h4',id,card);
                const text=register(field('断言正文','textarea',card,data?.text ?? ''));
                const correctness=register(picker('Factual Correctness',judgments,card,data?.correctness ?? 'PENDING'));
                const support=register(picker('Citation Semantic Support',judgments,card,data?.support ?? 'PENDING'));
                const refs=citations.map(c=>{ const box=register(field(c.evidenceId,'input',card)); box.type='checkbox'; box.checked=(data?.citationIds ?? []).includes(c.evidenceId); return {id:c.evidenceId,box}; });
                const item={id,text,correctness,support,refs}; claims.push(item);
                const remove=register(el('button','删除 Claim',card)); remove.type='button'; remove.onclick=()=>{claims.splice(claims.indexOf(item),1); card.remove();};
                update();
            }
            const add=register(el('button','添加 Claim',form)); add.type='button'; add.onclick=()=>addClaim();
            complete=register(field('我已完成全部实质断言清单','input',form)); complete.type='checkbox'; complete.checked=saved.claimsComplete===true;
            for (const claim of saved.claims ?? []) addClaim(claim);
        }
        const reviewer=register(field('评审人 reviewer','input',form,saved.reviewer ?? ''));
        const basis=register(field('评审依据 basis','textarea',form,saved.basis ?? ''));
        const status=el('p',message,form); status.setAttribute('role','status');
        const actions=el('div',null,form);
        if (!confirmed) {
            draft=el('button','保存草稿',actions); draft.type='button'; draft.onclick=()=>submit(false);
            if (!notReady) { confirm=el('button','确认评审',actions); confirm.type='button'; confirm.onclick=()=>submit(true); }
        } else el('p','CONFIRMED · V1 已确认评审只读，无法再修改。',form);
        scoreHost=el('div',null,host); showScore();
        function update() { for(const n of controls) n.disabled=confirmed || busy || uncertain; if(draft) draft.disabled=busy || uncertain; if(confirm) confirm.disabled=busy || uncertain; }
        async function submit(final) {
            if (!active() || busy || uncertain || confirmed) return;
            const claimValues=claims.map(c=>({claimId:c.id,text:c.text.value,correctness:c.correctness.value,support:c.support.value,citationIds:c.refs.filter(r=>r.box.checked).map(r=>r.id)}));
            if (claimValues.some(c=>!c.text.trim() || (c.support==='PASS' && !c.citationIds.length))) { status.textContent='Claim 必须填写正文；support=PASS 必须关联至少一个实际 Citation。拒答无引用的 Claim 请使用 FAIL，后端支持维度仍为 N/A。'; return; }
            if (final && (!reviewer.value.trim() || !basis.value.trim() || (!failed && (context.value==='PENDING' || appropriate.value==='PENDING' || Object.values(coverage).some(n=>n.value==='PENDING') || !complete.checked || claimValues.some(c=>c.correctness==='PENDING' || c.support==='PENDING'))))) { status.textContent='确认前请完成评审人、依据及全部必要人工判断。'; return; }
            if (final && !window.confirm('确认后 V1 无法再修改本次评审，是否确认？')) return;
            const input={runId,caseId:gold.caseId,datasetVersion:saved.datasetVersion ?? 1,freezeFingerprint:m.datasetSha256,snapshotHash:m.snapshot?.snapshotHash ?? null,reviewer:reviewer.value,basis:basis.value,reviewStatus:final?'CONFIRMED':'PENDING',...(failed?{}:{contextAnswerable:context.value,appropriateness:appropriate.value,factCoverage:Object.fromEntries(Object.entries(coverage).map(([id,n])=>[id,n.value])),claims:claimValues,claimsComplete:complete.checked})};
            busy=true; update(); status.textContent='正在保存，请勿重复提交…';
            try { await api('review',{method:'PUT',body:input}); if(active()) { uncertain=false; await load(final?'评审已确认，只读。':'草稿已保存并重新读取。'); } }
            catch(error) {
                if (!active()) return;
                const message=error.status===409?'REVIEW_ALREADY_CONFIRMED：服务器评审已确认，正在重新读取。':`${error.message}${error.status ? ' · HTTP '+error.status : ''} ${errors[error.code] ?? error.detail ?? error.code ?? ''}。不会自动重复提交；正在重新读取服务器状态。`;
                uncertain=true; await load(message);
            } finally { busy=false; if(active()) update(); }
        }
        form.onsubmit=event=>event.preventDefault();
        busy=false; uncertain=false; update();
    }
    void load();
}
