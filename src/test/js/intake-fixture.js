const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const decode = (text) => String(text || '').replaceAll('&lt;', '<').replaceAll('&gt;', '>')
    .replaceAll('&quot;', '"').replaceAll('&#039;', "'").replaceAll('&amp;', '&');
const attrs = (html) => Object.fromEntries([...html.matchAll(/([\w-]+)="([^"]*)"/g)].map((m) => [m[1], decode(m[2])]));

function fixture(overrides = {}) {
    const calls = [];
    const handlers = {};
    const elements = {};
    const forms = {};
    function element() {
        return { value: '', innerHTML: '', textContent: '', className: '', disabled: false,
            addEventListener(type, fn) { this[type] = fn; }, focus() {}, classList: { toggle() {} } };
    }
    for (const id of ['app', 'toast', 'userIdInput', 'messages', 'personalMealList']) elements[id] = element();
    const store = new Map();
    let sessionNumber = 0;
    const defaults = {
        '/slot-options': { mealTime: ['早餐', '午餐', '晚餐'], mood: [], scene: [], healthGoal: [], cuisine: [], taste: ['微辣'], convenience: [] },
        '/meals/personal': [], '/meals/public': []
    };
    const context = {
        console, Headers, URLSearchParams,
        FormData: class {
            constructor(form) { this.values = form.values; }
            get(key) { return Array.isArray(this.values[key]) ? this.values[key][0] : this.values[key]; }
            getAll(key) { const v = this.values[key]; return Array.isArray(v) ? v : v ? [v] : []; }
        },
        localStorage: { getItem: (k) => store.get(k), setItem: (k, v) => store.set(k, v) },
        location: { hash: '#/diet/meals/personal' },
        document: {
            getElementById(id) {
                if (id === 'personalMealList' && !elements.app.innerHTML.includes('id="personalMealList"')) return null;
                return elements[id] || (forms[id]?.html === elements.app.innerHTML ? forms[id].form : api.form(id));
            },
            querySelectorAll() { return []; },
            querySelector(selector) { return selector.includes('chatForm') ? api.form('chatForm').elements.message : null; }
        },
        setTimeout: () => 1, clearTimeout() {},
        addEventListener(type, handler) { handlers[type] = handler; },
        confirm: () => true,
        async fetch(url, options) {
            const route = url.replace('/api/v1/diet', '');
            const call = { route, method: options.method || 'GET', body: options.body ? JSON.parse(options.body) : undefined,
                userId: options.headers.get('X-User-Id') };
            calls.push(call);
            const handler = overrides[`${call.method} ${route}`] || overrides[route];
            const result = handler ? await handler(call) : route === '/sessions'
                ? { sessionId: `session-${++sessionNumber}` } : defaults[route];
            return { ok: true, status: 200, text: async () => JSON.stringify(result ?? null) };
        },
        DietChatRenderer: require('../../main/resources/static/assets/js/chat-renderer.js')
    };
    context.window = context;
    vm.createContext(context);
    for (const script of ['api.js', 'app.js']) vm.runInContext(fs.readFileSync(path.resolve(__dirname,
        '../../main/resources/static/assets/js', script), 'utf8'), context, { filename: script });
    const api = {
        calls, elements, context,
        async flush() { for (let i = 0; i < 8; i++) await new Promise((resolve) => setImmediate(resolve)); },
        async route(route) { context.location.hash = `#${route}`; handlers.hashchange(); await this.flush(); },
        form(id, changes = {}) {
            const html = elements.app.innerHTML.match(new RegExp(`<form[^>]*id="${id}"[^>]*>([\\s\\S]*?)</form>`))?.[1];
            if (!html) throw new Error(`Missing rendered form ${id}`);
            const values = {};
            for (const m of html.matchAll(/<input\b([^>]*)>/g)) { const a = attrs(m[1]); values[a.name] = a.value || ''; }
            for (const m of html.matchAll(/<textarea\b([^>]*)>([\s\S]*?)<\/textarea>/g)) values[attrs(m[1]).name] = decode(m[2]);
            for (const m of html.matchAll(/<select\b([^>]*)>([\s\S]*?)<\/select>/g)) {
                values[attrs(m[1]).name] = [...m[2].matchAll(/<option\b([^>]*)>/g)].filter((o) => /\bselected\b/.test(o[1])).map((o) => attrs(o[1]).value);
            }
            Object.assign(values, changes);
            const fields = {};
            for (const key of Object.keys(values)) {
                fields[key] = { focus() {} };
                Object.defineProperty(fields[key], 'value', { get: () => values[key], set: (v) => { values[key] = v; } });
            }
            const button = element();
            const form = { id, values, elements: fields, checkValidity: () => true, reportValidity() {},
                querySelector: (selector) => selector.includes('submit') ? button : fields.text || fields.message };
            forms[id] = { html: elements.app.innerHTML, form };
            return form;
        },
        async submit(form) { elements.app.submit({ target: form, preventDefault() {} }); await this.flush(); },
        async input(form, name, value) {
            form.values[name] = value;
            const target = form.elements[name]; target.name = name; target.form = form;
            elements.app.input?.({ target }); await this.flush();
        },
        button(action, extra = {}) {
            const match = [...elements.app.innerHTML.matchAll(/<button\b([^>]*)>/g)]
                .map((m) => attrs(m[1])).find((a) => a['data-action'] === action && Object.entries(extra).every(([k, v]) => a[`data-${k}`] === String(v)));
            if (!match) throw new Error(`Missing rendered button ${action}`);
            return this.target(action, Object.fromEntries(Object.entries(match).filter(([k]) => k.startsWith('data-')).map(([k, v]) => [k.slice(5).replace(/-([a-z])/g, (_, c) => c.toUpperCase()), v])));
        },
        target(action, data = {}) { return { dataset: { action, ...data }, closest() { return this; }, disabled: false }; },
        async click(button) { elements.app.click({ target: typeof button === 'string' ? this.button(button) : button }); await this.flush(); },
        async user(userId) { elements.userIdInput.value = userId; elements.userIdInput.change(); await this.flush(); }
    };
    return api;
}

const draft = (changes = {}) => ({ draftId: 'draft-1', originalText: '番茄面中午吃',
    data: { name: '番茄面', mealTime: ['午餐'], mood: [], scene: [], healthGoal: [], cuisine: [], taste: [], convenience: [],
        knowledge: { ingredients: ['番茄', '面'], description: '家常面', preparation: '煮面', sourceType: 'USER_PROVIDED', sourceReference: '' } },
    missingFields: [], warnings: [], evidence: {}, status: 'READY', savedMealId: null, ...changes });
const deferred = () => { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b; }); return { promise, resolve, reject }; };
module.exports = { fixture, draft, deferred };
