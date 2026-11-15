import {test} from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import {setup} from './web-import-harness.mjs';
const dataset=JSON.parse(fs.readFileSync('src/main/resources/evaluation/answer-evaluation-v1.json','utf8'));
const tick=async()=>{for(let i=0;i<40;i++)await Promise.resolve();};
async function fixture(){
 const h=await setup(); const nodes=()=>{const out=[];const walk=e=>{out.push(e);e.children.forEach(walk);};walk(h.elements.evaluation);return out;};
 const form=nodes().find(e=>e.tag==='form'), [question,strategy]=nodes().filter(e=>e.tag==='select'), auth=nodes().find(e=>e.tag==='input');
 const submit=nodes().find(e=>e.type==='submit'),load=nodes().find(e=>e.textContent==='加载冻结 Answer Gold 数据集');
 const respond=async(i,data,status=200)=>{h.calls[i].resolve({ok:status<400,status,headers:{get:()=>null},json:async()=>data});await tick();};
 const loadData=async()=>{void load.fire('click');await tick();await respond(h.calls.length-1,{dataset,sha256:'stub'});};
 const authorize=async()=>{auth.checked=true;await auth.fire('change');};
 const send=async()=>{void form.fire('submit');await tick();return h.calls.length-1;};
 return {...h,nodes,form,question,strategy,auth,submit,load,respond,loadData,authorize,send};
}
function answer(status='COMPLETED',errorCode=null){return {runId:'stub-answer',manifest:{runnerVersion:'m4-d03-answer-v1',createdAt:'2026-11-15T00:00:00Z',dataset:'answer-evaluation-v1',status,errorCode,stage:'ANSWER_RESULT',request:{retrievalStrategy:'BM25'}},results:{case:dataset.cases[0],execution:status==='COMPLETED'?{retrieval:{chunks:[{chunkId:'candidate-only',content:'<img>候选'}],diagnostics:{actualStrategy:'BM25'}},context:{selectedEvidence:[{evidenceId:'selected-only'}],contextText:'<script>实际上下文'},answer:{status:'ANSWERED',answer:'<img onerror=alert(1)>回答',citations:[{evidenceId:'citation-only'}]}}:null}};}

test('冻结16题原文、当前知识库、大整数ID与授权重置',async()=>{
 const h=await fixture();assert.equal(h.auth.checked,false);await h.loadData();assert.equal(h.question.options.length,16);
 for(const item of dataset.cases){h.question.value=item.caseId;await h.question.fire('change');assert.ok(h.elements.evaluation.textContent.includes(item.question));}
 await h.authorize();h.question.value='Q01';await h.question.fire('change');assert.equal(h.auth.checked,false);
 await h.authorize();h.strategy.value='VECTOR';await h.strategy.fire('change');assert.equal(h.auth.checked,false);
 await h.authorize();h.state.updateState({selectedKnowledgeBaseId:'2120893400476848130',knowledgeBases:[h.kb('2120893400476848130')]});assert.equal(h.auth.checked,false);assert.match(h.elements.evaluation.textContent,/库2120893400476848130/);
 const before=h.calls.length;await h.send();assert.equal(h.calls.length,before);
 await h.authorize();const i=await h.send();assert.equal(JSON.parse(h.calls[i].options.body).knowledgeBaseId,'2120893400476848130');
 await h.respond(i,{runId:null,status:'NOT_READY',errorCode:'GOLD_FINGERPRINT_MISMATCH'});assert.equal(h.auth.checked,false);
});
for(const status of ['COMPLETED','NOT_READY','FAILED'])test(status+' 自动读取持久化记录与安全详情',async()=>{
 const h=await fixture();await h.loadData();await h.authorize();const i=await h.send();await h.send();assert.equal(h.calls.length,i+1);
 await h.respond(i,{runId:'stub-answer',status,errorCode:status==='NOT_READY'?'PUBLISHED_SOURCE_MISMATCH':null});assert.match(h.calls[i+1].url,/runs\/stub-answer$/);assert.equal(h.calls[i+1].options,undefined);
 await h.respond(i+1,answer(status,status==='NOT_READY'?'PUBLISHED_SOURCE_MISMATCH':null));
 const text=h.elements['eval-content'].textContent;assert.ok(text.includes(status));assert.equal(h.auth.checked,false);
 if(status==='COMPLETED'){for(const marker of ['candidate-only','selected-only','citation-only','<script>实际上下文','<img onerror=alert(1)>回答'])assert.ok(text.includes(marker));assert.equal(h.nodes().some(e=>['script','img'].includes(e.tag)),false);}else assert.match(text,/未产生/);
});
test('网络失败不重试，提醒历史核查；关闭开关提示',async()=>{
 const h=await fixture();void h.load.fire('click');await tick();await h.respond(h.calls.length-1,{},404);assert.match(h.elements.evaluation.textContent,/app.evaluation.enabled=true/);
 await h.loadData();await h.authorize();const i=await h.send();h.calls[i].reject(new Error('network'));await tick();assert.equal(h.calls.length,i+1);assert.match(h.elements.evaluation.textContent,/可能已经产生结果.*历史/);assert.equal(h.auth.checked,false);
});
for(const protocol of ['m4-c01-offline-v1','m4-c02-live-v1'])test(protocol+' 指标视图与刷新重开',async()=>{
 const h=await fixture();const run={runId:'history',manifest:{runnerVersion:protocol,mode:protocol.includes('c01')?'OFFLINE':'LIVE',status:'COMPLETED'},gold:{status:'SOURCE_VERIFIED',questions:[]},results:[{questionId:'Q01',chunking:'FIXED_CODE_POINT',strategy:'BM25',status:'SUCCESS',chunks:[]}],metrics:{runs:[],groups:[]}};
 void h.elements['eval-load'].fire('click');await tick();await h.respond(h.calls.length-1,[run]);await h.respond(h.calls.length-1,run);
 assert.match(h.elements['eval-content'].textContent,/相关结果精确率/);assert.doesNotMatch(h.elements['eval-content'].textContent,/记录加载失败/);
});
test('新页面通过历史重新打开无metrics的Answer Run',async()=>{const h=await fixture(),run=answer();void h.elements['eval-load'].fire('click');await tick();await h.respond(h.calls.length-1,[run]);await h.respond(h.calls.length-1,run);assert.match(h.elements['eval-content'].textContent,/citation-only/);});

test('重载历史清除费用授权，缺失嵌套字段显示未产生',async()=>{
 const h=await fixture();await h.loadData();await h.authorize();void h.elements['eval-load'].fire('click');await tick();assert.equal(h.auth.checked,false);
 const run=answer();run.results.execution={};await h.respond(h.calls.length-1,[run]);await h.respond(h.calls.length-1,run);
 assert.match(h.elements['eval-content'].textContent,/actualStrategy.*未产生/s);assert.doesNotMatch(h.elements['eval-content'].textContent,/记录加载失败/);
});
test('裸JSON模式拒绝非JSON响应且不自动重试',async()=>{
 const h=await fixture();await h.loadData();await h.authorize();const i=await h.send();
 h.calls[i].resolve({ok:true,status:200,headers:{get:()=>null},json:async()=>{throw new Error('invalid json');}});await tick();
 assert.equal(h.calls.length,i+1);assert.match(h.elements.evaluation.textContent,/响应异常.*历史/s);assert.equal(h.auth.checked,false);
});

async function reviewFixture(status='COMPLETED',rag='ANSWERED',id='review-q01') {
 const h=await fixture(),run=answer(status);run.runId=id;run.manifest.datasetSha256=dataset.freezeFingerprint;run.manifest.snapshot={snapshotHash:'snapshot'};
 if(run.results.execution){run.results.execution.answer.status=rag;run.results.execution.answer.citations=rag==='ANSWERED'?[{evidenceId:'E1',chunkId:'2120893400476848130',sourceStart:0,sourceEnd:8},{evidenceId:'E2',chunkId:'2120893400476848131',sourceStart:8,sourceEnd:16}]:[];run.results.execution.context.selectedEvidence=[{evidenceId:'E1',content:'<img>实际引用正文'},{evidenceId:'E2',content:'另一引用'}];}
 const material={run,gold:dataset.cases[0],review:null,reviewStatus:'PENDING',citationValidity:'PASS'};
 const pending={caseId:'Q01',wholeCase:'PENDING',appropriateness:'PENDING',correctness:'PENDING',completeness:'PENDING',validity:'PENDING',support:'PENDING',reviewComplete:false,requiredCount:0,coveredCount:0};
 void h.elements['eval-load'].fire('click');await tick();await h.respond(h.calls.length-1,[run]);await h.respond(h.calls.length-1,run);
 const reviewIndex=h.calls.length-1;await h.respond(reviewIndex,material);await h.respond(h.calls.length-1,pending);
 const nodes=()=>{const out=[];const walk=e=>{out.push(e);e.children.forEach(walk);};walk(h.elements['eval-content']);return out;};
 const button=text=>nodes().find(e=>e.tag==='button'&&e.textContent===text);
 const field=text=>nodes().find(e=>e.tag==='label'&&e.text.startsWith(text))?.children[0];
 h.context.window.confirm=()=>true;
 return {...h,run,material,pending,reviewIndex,reviewNodes:nodes,reviewButton:button,reviewField:field};
}
test('人工评审默认PENDING、安全证据、Fact编辑、Claim稳定ID及Citation多选草稿重读',async()=>{
 const h=await reviewFixture();assert.equal(h.reviewField('Context Answerable').value,'PENDING');
 assert.ok(h.elements['eval-content'].textContent.includes(dataset.cases[0].requiredFacts[0].description));
 assert.ok(h.elements['eval-content'].textContent.includes('<img>实际引用正文'));
 assert.equal(h.reviewNodes().some(e=>e.tag==='img'),false);
 h.reviewField('覆盖判断').value='FAIL';await h.reviewButton('添加 Claim').fire('click');h.reviewField('断言正文').value='<script>断言';
 h.reviewField('Citation Semantic Support').value='PASS';await h.reviewButton('保存草稿').fire('click');assert.match(h.elements['eval-content'].textContent,/至少一个实际 Citation/);
 h.reviewField('E1').checked=true;h.reviewField('E2').checked=true;
 await h.reviewButton('添加 Claim').fire('click');const deletes=h.reviewNodes().filter(e=>e.textContent==='删除 Claim');await deletes[1].fire('click');
 void h.reviewButton('保存草稿').fire('click');await tick();const i=h.calls.length-1;assert.equal(h.calls[i].options.method,'PUT');
 const input=JSON.parse(h.calls[i].options.body);assert.equal(input.reviewStatus,'PENDING');assert.equal(input.claims.length,1);assert.equal(input.claims[0].claimId,'C1');assert.deepEqual(input.claims[0].citationIds,['E1','E2']);assert.equal(input.claimsComplete,false);assert.equal(input.factCoverage['Q01-F01'],'FAIL');
 void h.reviewButton('保存草稿').fire('click');await tick();assert.equal(h.calls.length,i+1);
 await h.respond(i,{});await h.respond(h.calls.length-1,{...h.material,review:{judgments:input}});await h.respond(h.calls.length-1,h.pending);
 assert.equal(h.reviewField('断言正文').value,'<script>断言');assert.equal(h.reviewField('E2').checked,true);
});
test('FAILED只填评审人及依据、二次确认、保存后只读与N/A',async()=>{
 const h=await reviewFixture('FAILED');assert.equal(h.reviewField('Context Answerable'),undefined);
 h.reviewField('评审人').value='USER';h.reviewField('评审依据').value='执行失败';let confirmations=0;h.context.window.confirm=()=>{confirmations++;return true;};
 void h.reviewButton('确认评审').fire('click');await tick();const i=h.calls.length-1,input=JSON.parse(h.calls[i].options.body);assert.equal(confirmations,1);assert.equal(input.contextAnswerable,undefined);
 await h.respond(i,{});const confirmed={...h.material,reviewStatus:'CONFIRMED',review:{judgments:input,reviewedAt:'now'}};await h.respond(h.calls.length-1,confirmed);await h.respond(h.calls.length-1,{...h.pending,wholeCase:'FAIL',appropriateness:'NA'});
 assert.equal(h.reviewButton('保存草稿'),undefined);assert.equal(h.reviewButton('确认评审'),undefined);assert.equal(h.reviewField('评审人').disabled,true);assert.match(h.elements['eval-content'].textContent,/N\/A/);
});
test('确认完整性、409冲突重读与历史只读',async()=>{
 const h=await reviewFixture();await h.reviewButton('确认评审').fire('click');assert.match(h.elements['eval-content'].textContent,/全部必要人工判断/);
 void h.reviewButton('保存草稿').fire('click');await tick();const i=h.calls.length-1;await h.respond(i,{detail:'REVIEW_ALREADY_CONFIRMED'},409);
 await h.respond(h.calls.length-1,{...h.material,reviewStatus:'CONFIRMED',review:{judgments:{reviewer:'other',claims:[]},reviewedAt:'then'}});await h.respond(h.calls.length-1,h.pending);
 assert.match(h.elements['eval-content'].textContent,/REVIEW_ALREADY_CONFIRMED/);assert.equal(h.reviewField('评审人').disabled,true);
});
test('NOT_READY禁止确认；拒答允许空Claim清单；网络异常先GET而不重复PUT',async()=>{
 const n=await reviewFixture('NOT_READY');assert.equal(n.reviewButton('确认评审'),undefined);
 const h=await reviewFixture('COMPLETED','INSUFFICIENT_EVIDENCE');assert.equal(h.reviewField('E1'),undefined);
 h.reviewField('Context Answerable').value='FALSE';h.reviewField('Response Appropriateness').value='PASS';for(const l of h.reviewNodes().filter(e=>e.tag==='label'&&e.text==='覆盖判断'))l.children[0].value='FAIL';
 h.reviewField('我已完成').checked=true;h.reviewField('评审人').value='USER';h.reviewField('评审依据').value='拒答说明';
 void h.reviewButton('确认评审').fire('click');await tick();const i=h.calls.length-1;assert.deepEqual(JSON.parse(h.calls[i].options.body).claims,[]);
 h.calls[i].reject(new Error('network'));await tick();assert.match(h.calls.at(-1).url,/\/review$/);assert.equal(h.calls.at(-1).options.method,'GET');assert.equal(h.calls.filter(c=>c.options?.method==='PUT').length,1);
});
test('快速切换Run时旧评审响应不得覆盖新Run',async()=>{
 const h=await reviewFixture();void h.elements['eval-run'].fire('change');await tick();await h.respond(h.calls.length-1,h.run);const old=h.calls.length-1;
 h.elements['eval-run'].value='second';void h.elements['eval-run'].fire('change');await tick();const second={...answer('NOT_READY'),runId:'second'};await h.respond(h.calls.length-1,second);const newer=h.calls.length-1;
 await h.respond(old,{...h.material,gold:{...h.material.gold,question:'旧请求不能覆盖'}});assert.doesNotMatch(h.elements['eval-content'].textContent,/旧请求不能覆盖/);
 await h.respond(newer,{...h.material,run:second,gold:{...h.material.gold,question:'第二个Run'}});await h.respond(h.calls.length-1,h.pending);assert.match(h.elements['eval-content'].textContent,/第二个Run/);
});
