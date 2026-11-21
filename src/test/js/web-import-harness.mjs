import vm from 'node:vm';
import fs from 'node:fs';
const base = 'src/main/resources/static/js/';
class Element {
    constructor(tag = 'div') {
        this.tag = tag;
        this.children = [];
        this.events = {};
        this._value = '';
        this.checked = false;
        this.disabled = false;
        this.hidden = false;
        this.text = '';
    }
    set textContent(value) {
        this.text = String(value);
        this.children = [];
    }
    get textContent() {
        return this.text + this.children.map((child) => child.textContent).join('');
    }
    get value() {
        return this._value || (this.tag === 'select' ? (this.children[0]?.value ?? '') : '');
    }
    set value(value) {
        this._value = this.tag === 'select' ? (this.children.some((c) => c.value === value) ? value : '') : value;
    }
    get options() {
        return this.children;
    }
    insertBefore(child, reference) {
        const index = this.children.indexOf(reference);
        this.children.splice(index < 0 ? 0 : index, 0, child);
    }
    append(...children) {
        this.children.push(...children);
    }
    replaceChildren(...children) {
        this.text = '';
        this.children = children;
    }
    addEventListener(type, callback) {
        (this.events[type] ??= []).push(callback);
    }
    async fire(type) {
        if (this.disabled) return;
        const result = this['on' + type]?.({ preventDefault() {} });
        const pending = Promise.all((this.events[type] ?? []).map((callback) => callback({ preventDefault() {} })));
        await result;
        await pending;
    }
    setAttribute() {}
    removeAttribute() {}
    remove() {}
    focus() {}
    querySelector() {
        return new Element();
    }
}
export const tick = async () => {
    for (let i = 0; i < 15; i++) await Promise.resolve();
};
export async function setup({ manualAuth = false, session = new Map() } = {}) {
    const html = fs.readFileSync('src/main/resources/static/index.html', 'utf8');
    const elements = Object.fromEntries(
        [...html.matchAll(/<([\w-]+)\b([^<>]*\bid="([^"]+)"[^<>]*)>/g)].map(([, , attributes, id]) => {
            const element = new Element();
            element.hidden = /\bhidden\b/.test(attributes);
            return [id, element];
        }),
    );
    const authCalls = [];
    let reloads = 0;
    const calls = [],
        timers = [],
        listeners = {};
    const pendingTimers = new Map();
    let timerId = 0;
    const document = {
        hidden: false,
        addEventListener(type, callback) {
            listeners[type] = callback;
        },
        createTextNode: (text) => {
            const el = new Element('#text');
            el.textContent = text;
            return el;
        },
        createElement: (tag) => new Element(tag),
        querySelector: (selector) => elements[selector.slice(1)],
        getElementById: (id) => elements[id],
        querySelectorAll: () => [],
    };
    const context = vm.createContext({
        document,
        URL,
        AbortController,
        FormData,
        console: { warn() {} },
        performance,
        setTimeout: (callback, delay) => {
            const id = ++timerId;
            pendingTimers.set(id, { callback, delay });
            return id;
        },
        clearTimeout: (id) => pendingTimers.delete(id),
        sessionStorage: {
            getItem: (key) => session.get(key),
            setItem: (key, value) => session.set(key, value),
            removeItem: (key) => session.delete(key),
        },
        location: {
            reload() {
                reloads++;
            },
            href: 'http://localhost/api/index.html',
            origin: 'http://localhost',
            hash: '#knowledge',
            replace(value) {
                this.hash = value;
            },
        },
        window: {
            addEventListener(type, callback) {
                const previous = listeners[type];
                listeners[type] = previous
                    ? (...args) => {
                          previous(...args);
                          callback(...args);
                      }
                    : callback;
            },
        },
        fetch: (url, options) => {
            if (!manualAuth && url.pathname?.startsWith('/api/v1/auth/')) {
                authCalls.push({ url: String(url), options });
                const data = url.pathname.endsWith('/me')
                    ? { id: '1', username: 'test_user' }
                    : { headerName: 'X-CSRF-TOKEN', token: 'test-token' };
                return Promise.resolve(response(data));
            }
            return new Promise((resolve, reject) => calls.push({ url: String(url), options, resolve, reject }));
        },
    });
    const modules = {};
    async function module(name) {
        if (!modules[name]) {
            const mod = new vm.SourceTextModule(fs.readFileSync(base + name, 'utf8'), { context });
            modules[name] = mod;
            await mod.link((specifier) => module(specifier.replace('./', '')));
        }
        return modules[name];
    }
    await (await module('app.js')).evaluate();
    const all = () => {
        const result = [];
        function walk(el) {
            result.push(el);
            el.children.forEach(walk);
        }
        walk(elements.knowledge);
        return result;
    };
    const button = (label) => all().find((el) => el.tag === 'button' && el.textContent === label);
    function response(data, status = 200) {
        return {
            ok: status < 400,
            status,
            headers: { get: () => 'test-id' },
            json: async () => ({ code: status < 400 ? 0 : 50000, data, requestId: 'test-id' }),
        };
    }
    const respond = async (index, data, status) => {
        calls[index].resolve(response(data, status));
        await tick();
    };
    const state = modules['state.js'].namespace;
    const kb = (id) => ({ id, name: '库' + id, description: null });
    await tick();
    if (!manualAuth) await respond(0, [kb('1'), kb('2')]);
    const page = (number = 1, total = '0', items = []) => ({ page: number, pageSize: 20, total, items });
    return {
        elements,
        calls,
        authCalls,
        reloads: () => reloads,
        request: modules['api.js'].namespace.request,
        timers,
        listeners,
        pendingTimers,
        document,
        all,
        button,
        respond,
        state,
        kb,
        page,
        tick,
        context,
    };
}
