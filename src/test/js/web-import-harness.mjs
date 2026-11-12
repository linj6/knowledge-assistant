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
export const tick = async () => { for (let i = 0; i < 15; i++) await Promise.resolve(); };
export async function setup() {
    const elements = Object.fromEntries(['knowledge','chat','retrieval','kb','status','reload'].map(id => [id,new Element()]));
    const calls = [], timers = [], listeners = {};
    const pendingTimers = new Map(); let timerId = 0;
    const document = { hidden: false, addEventListener(type, callback) { listeners[type] = callback; }, createElement: tag => new Element(tag), querySelector: selector => elements[selector.slice(1)], getElementById: id => elements[id], querySelectorAll: () => [] };
    const context = vm.createContext({ document, URL, AbortController, FormData, console: { warn() {} }, performance, setTimeout: (callback, delay) => { const id = ++timerId; pendingTimers.set(id, { callback, delay }); return id; }, clearTimeout: id => pendingTimers.delete(id), location: { href: 'http://localhost/api/index.html', origin: 'http://localhost', hash: '#knowledge', replace(value) { this.hash = value; } }, window: { addEventListener(type, callback) { const previous = listeners[type]; listeners[type] = previous ? (...args) => { previous(...args); callback(...args); } : callback; } }, fetch: (url, options) => new Promise((resolve,reject) => calls.push({ url: String(url), options, resolve, reject })) });
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
    return { elements,calls,timers,listeners,pendingTimers,document,all,button,respond,state,kb,page,tick,context };
}
