import { test } from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import fs from 'node:fs';
const base = 'src/main/resources/static/js/';
class Element {
    constructor(tag = 'div') { this.tag = tag; this.children = []; this.events = {}; this.value = ''; this.disabled = false; this.hidden = false; this.text = ''; }
    set textContent(value) { this.text = String(value); this.children = []; }
    get textContent() { return this.text + this.children.map(child => child.textContent).join(''); }
    append(...children) { this.children.push(...children); }
    replaceChildren(...children) { this.text = ''; this.children = children; }
    addEventListener(type, callback) { (this.events[type] ??= []).push(callback); }
    async fire(type) { if (this.disabled) return; await Promise.all((this.events[type] ?? []).map(callback => callback({ preventDefault() {} }))); }
    setAttribute() {}
    removeAttribute() {}
    remove() {}
    focus() {}
    querySelector() { return new Element(); }
}
const tick = async () => { for (let i = 0; i < 15; i++) await Promise.resolve(); };
async function setup() {
    const elements = Object.fromEntries(['knowledge','chat','retrieval','kb','status','reload'].map(id => [id,new Element()]));
    const calls = [], timers = [], listeners = {};
    const document = { createElement: tag => new Element(tag), querySelector: selector => elements[selector.slice(1)], getElementById: id => elements[id], querySelectorAll: () => [] };
    const context = vm.createContext({ document, URL, AbortController, FormData, console: { warn() {} }, performance, setTimeout: callback => (timers.push(callback), timers.length), clearTimeout() {}, location: { href: 'http://localhost/api/index.html', origin: 'http://localhost', hash: '#knowledge', replace(value) { this.hash = value; } }, window: { addEventListener(type, callback) { listeners[type] = callback; } }, fetch: (url, options) => new Promise((resolve,reject) => calls.push({ url: String(url), options, resolve, reject })) });
    const modules = {};
    async function module(name) { if (!modules[name]) { const mod = new vm.SourceTextModule(fs.readFileSync(base + name,'utf8'), { context }); modules[name] = mod; await mod.link(specifier => module(specifier.replace('./',''))); } return modules[name]; }
    await (await module('app.js')).evaluate();
    const all = () => { const result = []; function walk(el) { result.push(el); el.children.forEach(walk); } walk(elements.knowledge); return result; };
    const button = label => all().find(el => el.tag === 'button' && el.textContent === label);
    const respond = async (index,data,status=200) => { calls[index].resolve({ ok: status < 400, status, headers: { get: () => 'test-id' }, json: async () => ({ code: status < 400 ? 0 : 50000, data, requestId: 'test-id' }) }); await tick(); };
    const state = modules['state.js'].namespace;
    const kb = id => ({id,name:'库'+id,description:null});
    await respond(0,[kb('1'),kb('2')]);
    const page = (number=1,total='0',items=[]) => ({page:number,pageSize:20,total,items});
    return { elements,calls,timers,listeners,all,button,respond,state,kb,page,tick,context };
}
const item = (filename='a.md',publicationStatus='UNPUBLISHED',status='READY') => ({documentId:'9007199254740993',filename,createdAt:'2026-11-12T00:00:00Z',publicationStatus,activeVersionId:null,publishedAt:null,importTask:{status,failureCode:null}});
for (const [label,name,description] of [['空名称','',''],['超长名称','x'.repeat(101),''],['超长描述','ok','x'.repeat(1001)]]) test(label,async()=>{ const h=await setup();const form=h.all().find(x=>x.tag==='form');h.all().find(x=>x.id==='new-kb-name').value=name;h.all().find(x=>x.id==='new-kb-description').value=description;await form.fire('submit');assert.equal(h.calls.length,2); });
test('创建、重复提交、字符串ID及刷新成功',async()=>{const h=await setup();await h.respond(1,h.page());h.all().find(x=>x.id==='new-kb-name').value=' Java ';const form=h.all().find(x=>x.tag==='form');const pending=form.fire('submit');await tick();await form.fire('submit');assert.equal(h.calls.length,3);assert.equal(JSON.parse(h.calls[2].options.body).name,'Java');await h.respond(2,{id:'9007199254740993'});await h.respond(3,[h.kb('9007199254740993')]);await pending;assert.equal(h.state.AppState.selectedKnowledgeBaseId,'9007199254740993');assert.match(h.calls[4].url,/9007199254740993\/documents/);assert.equal(form.hidden,true);});
for(const status of [400,500,'network','timeout']) test('创建错误不自动重发 '+status,async()=>{const h=await setup();h.all().find(x=>x.id==='new-kb-name').value='ok';const pending=h.all().find(x=>x.tag==='form').fire('submit');await tick();if(status==='network')h.calls[2].reject(new Error());else if(status==='timeout')h.calls[2].reject(Object.assign(new Error(),{name:'AbortError'}));else await h.respond(2,null,status);await pending;assert.equal(h.calls.filter(c=>c.options.method==='POST').length,1);assert.match(h.elements.knowledge.textContent,/结果可能未知/);});
test('创建成功列表刷新失败保留成功事实',async()=>{const h=await setup();h.all().find(x=>x.id==='new-kb-name').value='ok';const p=h.all().find(x=>x.tag==='form').fire('submit');await tick();await h.respond(2,{id:'3'});await h.respond(3,null,500);await p;assert.match(h.elements.knowledge.textContent,/创建已成功/);});
test('分页导航、越界、切库重置',async()=>{const h=await setup();await h.respond(1,h.page(1,'41',[item()]));await h.button('下一页').fire('click');await h.respond(2,h.page(2,'41',[item()]));await h.button('最后一页').fire('click');await h.respond(3,h.page(3,'41',[item()]));assert.equal(h.button('下一页').disabled,true);await h.button('上一页').fire('click');await h.respond(4,h.page(2,'1'));assert.match(h.elements.knowledge.textContent,/超出范围/);await h.button('首页').fire('click');await h.respond(5,h.page());h.state.updateState({selectedKnowledgeBaseId:'2'});await tick();assert.match(h.calls[6].url,/2\/documents\?page=1/);});
test('巨大total精确显示且末页请求不溢出Java int',async()=>{const h=await setup();await h.respond(1,h.page(1,'9007199254740993'));assert.match(h.elements.knowledge.textContent,/450359962737050/);await h.button('最后一页').fire('click');assert.match(h.calls[2].url,/page=2147483647&/);await h.respond(2,h.page(2147483647,'9007199254740993'));assert.equal(h.button('下一页').disabled,true);});
for(const patch of [{total:1},{total:'-1'},{total:'x'},{total:'9223372036854775808'},{page:0},{page:'1'},{pageSize:0},{pageSize:'20'},{items:null},{items:[{}]}]) test('非法分页 '+JSON.stringify(patch),async()=>{const h=await setup();await h.respond(1,{...h.page(),...patch});assert.match(h.elements.knowledge.textContent,/响应格式/);});
test('A晚于B且忽略取消仍不覆盖',async()=>{const h=await setup();h.state.updateState({selectedKnowledgeBaseId:'2'});await tick();assert.equal(h.calls[1].options.signal.aborted,true);await h.respond(2,h.page(1,'1',[item('B.md')]));await h.respond(1,h.page(1,'1',[item('A.md')]));assert.match(h.elements.knowledge.textContent,/B.md/);assert.doesNotMatch(h.elements.knowledge.textContent,/A.md/);});
test('空列表、刷新防并发、Hash不重复绑定',async()=>{const h=await setup();await h.respond(1,h.page());assert.match(h.elements.knowledge.textContent,/暂无文档/);await h.button('刷新文档').fire('click');await h.button('刷新文档').fire('click');assert.equal(h.calls.length,3);for(const hash of ['#chat','#retrieval','#knowledge']) {h.context.location.hash=hash;h.listeners.hashchange();}assert.equal(h.state.AppState.selectedKnowledgeBaseId,'1');assert.equal(h.all().filter(x=>x.tag==='form').length,1);assert.equal(h.button('上传文件').disabled,true);});
for(const status of ['RECEIVED','PROCESSING','READY','FAILED']) test('发布与任务独立 '+status,async()=>{const h=await setup();await h.respond(1,h.page(1,'2',[item('a','UNPUBLISHED',status),item('b','PUBLISHED',status)]));assert.match(h.elements.knowledge.textContent,/未发布/);assert.match(h.elements.knowledge.textContent,/已发布/);});
test('恶意文件名与描述只创建文本节点',async()=>{const h=await setup();const attack='<img src=x onerror=alert(1)><script>alert(1)</script>&';h.state.updateState({knowledgeBases:[{id:'1',name:attack,description:attack}]});await h.respond(1,h.page(1,'1',[item(attack)]));assert.match(h.elements.knowledge.textContent,/<script>/);assert.equal(h.all().some(x=>['script','img'].includes(x.tag)),false);});
test('旧创建完成不覆盖用户新选择',async()=>{const h=await setup();h.all().find(x=>x.id==='new-kb-name').value='ok';const pending=h.all().find(x=>x.tag==='form').fire('submit');await tick();h.elements.kb.value='2';await h.elements.kb.fire('change');await h.respond(2,{id:'3'});await h.respond(4,[h.kb('1'),h.kb('2'),h.kb('3')]);await pending;assert.equal(h.state.AppState.selectedKnowledgeBaseId,'2');});
test('取消旧请求不显示错误且API不可用可恢复',async()=>{const h=await setup();h.state.updateState({selectedKnowledgeBaseId:'2'});await tick();h.calls[1].reject(Object.assign(new Error(),{name:'AbortError'}));await tick();assert.doesNotMatch(h.elements.knowledge.textContent,/请求已取消|服务请求失败/);await h.respond(2,null,500);assert.match(h.elements.knowledge.textContent,/服务请求失败/);await h.button('刷新文档').fire('click');await h.respond(3,h.page());assert.match(h.elements.knowledge.textContent,/暂无文档/);});
test('较旧知识库列表响应不会覆盖新列表',async()=>{const h=await setup();h.elements.reload.fire('click');await tick();assert.equal(h.elements.reload.disabled,true);await h.elements.reload.fire('click');assert.equal(h.calls.length,3);h.all().find(x=>x.id==='new-kb-name').value='ok';const pending=h.all().find(x=>x.tag==='form').fire('submit');await tick();await h.respond(3,{id:'3'});await h.respond(4,[h.kb('3')]);await pending;await h.respond(2,[h.kb('1')]);assert.equal(h.state.AppState.selectedKnowledgeBaseId,'3');});

for (const [name,size,valid] of [['a.md',1,true],['A.TXT',2097152,true],['a.MD',1,true],['a.txt',0,false],['a.md',2097153,false],['a.pdf',1,false],['a/b.md',1,false],['a\\b.txt',1,false],['x'.repeat(256)+'.md',1,false]]) test('上传文件校验 '+name,async()=>{ const h=await setup(); const input=h.all().find(x=>x.id==='import-file');input.files=[{name,size}];await input.fire('change');assert.equal(h.button('上传文件').disabled,!valid);});
for(const status of [202,200]) test('上传 FormData 与终态 '+status,async()=>{const h=await setup();await h.respond(1,h.page());const input=h.all().find(x=>x.id==='import-file');input.files=[new Blob(['hello'])];Object.defineProperty(input.files[0],'name',{value:'a.md'});await input.fire('change');const pending=h.button('上传文件').fire('click');await tick();await h.button('上传文件').fire('click');assert.equal(h.calls.length,3);assert.ok(h.calls[2].options.body instanceof FormData);assert.equal(h.calls[2].options.body.getAll('file').length,1);assert.equal(h.calls[2].options.headers['Content-Type'],undefined);await h.respond(2,{importTaskId:'9007199254740993',documentId:'2',status:status===202?'READY':'FAILED',createdAt:'now',updatedAt:'now',failureCode:null},status);await pending;assert.equal(h.calls.length,4);assert.match(h.elements.knowledge.textContent,/9007199254740993/);if(status===200)assert.match(h.elements.knowledge.textContent,/内容已存在/);});

test('紧凑文档行保留长文件名、发布及任务状态和完整详情',async()=>{
 const {setup}=await import('./web-import-harness.mjs');const h=await setup();const filename='<img onerror=x>'+ '长文件名'.repeat(100);
 await h.respond(1,h.page(1,'1',[{documentId:'9007199254740993',filename,createdAt:'created',publicationStatus:'UNPUBLISHED',activeVersionId:null,publishedAt:null,importTask:{importTaskId:'7',status:'FAILED',failureCode:'IMPORT_FAILED',createdAt:'task-created',updatedAt:'task-updated'}}]));
 const row=h.all().find(e=>e.className==='document-row');assert.ok(row);assert.equal(row.children.find(e=>e.tag==='h3').textContent,filename);
 for(const text of ['未发布','导入失败','IMPORT_FAILED','创建时间：created'])assert.ok(row.textContent.includes(text));
 const detail=row.children.find(e=>e.tag==='details');assert.ok(!detail.open);detail.open=true;await detail.fire('toggle');for(const text of ['9007199254740993','importTaskId：7','task-updated'])assert.ok(detail.textContent.includes(text));assert.equal(h.all().some(e=>e.tag==='img'),false);
});
