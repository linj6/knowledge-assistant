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
