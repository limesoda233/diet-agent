const assert = require('node:assert/strict');
const test = require('node:test');
const { fixture, draft, deferred } = require('./intake-fixture');

test('initial meal list load waits for the list node without showing an error', async () => {
    const f = fixture();
    await f.flush();
    assert.match(f.elements.app.innerHTML, /id="personalMealList"/);
    assert.doesNotMatch(f.elements.toast.textContent, /Cannot set properties/);
});

test('AI 整理 only fills editable fields and save carries edited knowledge', async () => {
    const f = fixture({ 'POST /meals/personal/draft': () => draft(), 'POST /meals/personal': () => ({ id: 8 }) });
    await f.flush();
    const form = f.form('mealForm');
    assert.equal(form.values.text, '', 'new meal exposes colloquial input');
    await f.input(form, 'text', '番茄面中午吃');
    await f.click('generate-meal-draft');
    assert.equal(f.calls.filter((c) => c.method === 'POST' && c.route === '/meals/personal').length, 0);
    assert.equal(f.calls.find((c) => c.route.endsWith('/draft')).body.text, '番茄面中午吃');
    const editable = f.form('mealForm', { preparation: '先炒番茄，再煮面' });
    assert.equal(editable.values.name, '番茄面');
    assert.deepEqual(editable.values.mealTime, ['午餐']);
    assert.equal(editable.values.ingredients, '番茄\n面');
    await f.submit(editable);
    assert.deepEqual(f.calls.find((c) => c.method === 'POST' && c.route === '/meals/personal').body.knowledge,
        { ingredients: ['番茄', '面'], description: '家常面', preparation: '先炒番茄，再煮面', sourceType: 'USER_PROVIDED', sourceReference: '' });
    assert.ok(f.calls.filter((c) => c.route === '/meals/personal' && c.method === 'GET').length >= 2);
});

test('missing name or meal time blocks save until user supplies both', async () => {
    const f = fixture({ 'POST /meals/personal/draft': () => draft({ data: { name: '', mealTime: [] }, missingFields: ['name', 'mealTime'], status: 'NEEDS_INPUT' }) });
    await f.flush();
    await f.submit(f.form('mealForm', { name: '', mealTime: ['午餐'] }));
    await f.submit(f.form('mealForm', { name: '面', mealTime: [] }));
    assert.equal(f.calls.filter((c) => c.method === 'POST').length, 0);
});

test('editing an existing meal retains its knowledge and cannot AI overwrite the edit', async () => {
    const f = fixture({ 'GET /meals/personal': () => [{ id: 12, ...draft().data }], 'PUT /meals/personal/12': () => ({ id: 12 }) });
    await f.flush();
    await f.click(f.button('edit-meal', { id: 12 }));
    const form = f.form('mealForm');
    assert.equal(form.values.preparation, '煮面');
    await f.click(f.target('generate-meal-draft'));
    assert.equal(f.calls.filter((c) => c.route.endsWith('/draft')).length, 0);
    await f.submit(form);
    assert.equal(f.calls.find((c) => c.method === 'PUT').body.knowledge, undefined,
        'unchanged knowledge stays in the database without rewriting provenance');
});

test('seeded knowledge is preserved on ordinary edit and becomes user provided only after explicit knowledge edits', async () => {
    const seeded = { id: 12, ...draft().data, knowledge: { ...draft().data.knowledge,
        sourceType: 'SYNTHETIC_EVALUATION', sourceReference: 'seed-case' } };
    const f = fixture({ 'GET /meals/personal': () => [seeded], 'PUT /meals/personal/12': () => ({ id: 12 }) });
    await f.flush();
    await f.click(f.button('edit-meal', { id: 12 }));
    assert.equal(f.form('mealForm').values.preparation, '煮面');
    await f.submit(f.form('mealForm', { name: '番茄面改名' }));
    assert.equal(f.calls.find((c) => c.method === 'PUT').body.knowledge, undefined);
    await f.click(f.button('edit-meal', { id: 12 }));
    await f.submit(f.form('mealForm', { description: '我改过的描述' }));
    const edited = f.calls.filter((c) => c.method === 'PUT')[1].body.knowledge;
    assert.deepEqual(edited, { ingredients: ['番茄', '面'], description: '我改过的描述',
        preparation: '煮面', sourceType: 'USER_PROVIDED', sourceReference: '' });
});

test('chat READY card saves the owning session and draft, refreshes list and becomes a receipt', async () => {
    const f = fixture({ 'POST /chat': (c) => ({ sessionId: 'session-1', speechText: '请确认',
        mealDraft: c.body.context.mealDraftAction ? draft({ status: 'SAVED', savedMealId: 23 }) : draft() }) });
    await f.flush(); await f.route('/diet/chat');
    await f.submit(f.form('chatForm', { message: '帮我录入番茄面，午餐' }));
    assert.match(f.elements.app.innerHTML, /data-action="save-meal-draft"/, 'READY response exposes explicit save');
    const save = f.button('save-meal-draft');
    await f.click(save);
    const request = f.calls.filter((c) => c.route === '/chat')[1].body;
    assert.deepEqual(request, { sessionId: 'session-1', message: '确认保存', sourceMode: 'PERSONAL', context: { mealDraftAction: { draftId: 'draft-1', action: 'SAVE' } } });
    assert.match(f.elements.app.innerHTML, /已保存[\s\S]*23/);
    const count = f.calls.length;
    await f.click(save);
    assert.equal(f.calls.length, count, 'saved card cannot submit again');
    assert.equal(f.calls.filter((c) => c.route === '/meals/personal' && c.method === 'GET').length, 2);
});

test('chat missing draft cannot save, cancel sends explicit CANCEL and retires all buttons', async () => {
    const f = fixture({ 'POST /chat': (c) => ({ sessionId: 'session-1',
        mealDraft: c.body.context.mealDraftAction ? draft({ status: 'CANCELLED' }) : draft({ status: 'NEEDS_INPUT', data: { name: '面', mealTime: [] }, missingFields: ['mealTime'], warnings: ['请补餐次'] }) }) });
    await f.flush(); await f.route('/diet/chat');
    await f.submit(f.form('chatForm', { message: '帮我录入面' }));
    assert.match(f.elements.app.innerHTML, /待补充[\s\S]*用餐时间/);
    assert.match(f.elements.app.innerHTML, /请补餐次/);
    await f.click(f.target('save-meal-draft', { draftId: 'draft-1', sessionId: 'session-1', revision: '1', userId: '1' }));
    assert.equal(f.calls.filter((c) => c.route === '/chat').length, 1);
    await f.click('cancel-meal-draft');
    assert.deepEqual(f.calls.filter((c) => c.route === '/chat')[1].body.context, { mealDraftAction: { draftId: 'draft-1', action: 'CANCEL' } });
    assert.match(f.elements.app.innerHTML, /已取消/);
});

test('same draft ID with newer revision invalidates old card, and reset or user switch prevents stale actions', async () => {
    const f = fixture({ 'POST /chat': () => ({ sessionId: 'session-1', mealDraft: draft() }) });
    await f.flush(); await f.route('/diet/chat');
    await f.submit(f.form('chatForm', { message: '帮我录入番茄面午餐' }));
    assert.match(f.elements.app.innerHTML, /data-action="save-meal-draft"/);
    const old = f.button('save-meal-draft');
    await f.submit(f.form('chatForm', { message: '改为晚餐' }));
    const before = f.calls.length;
    await f.click(old);
    assert.equal(f.calls.length, before);
    const latest = f.button('save-meal-draft', { revision: 2 });
    await f.click('new-session'); await f.click(latest);
    assert.equal(f.calls.length, before);
    await f.submit(f.form('chatForm', { message: '帮我录入面午餐' }));
    const owner = f.button('save-meal-draft');
    await f.user('2');
    const afterSwitch = f.calls.length;
    await f.click(owner);
    assert.equal(f.calls.length, afterSwitch);
});

test('chat SAVE failure restores latest action and simultaneous clicks send only once', async () => {
    const pending = deferred();
    let attempts = 0;
    const f = fixture({ 'POST /chat': (c) => {
        if (!c.body.context.mealDraftAction) return { sessionId: 'session-1', mealDraft: draft() };
        attempts++;
        return attempts === 1 ? pending.promise : { sessionId: 'session-1', mealDraft: draft({ status: 'SAVED', savedMealId: 25 }) };
    } });
    await f.flush(); await f.route('/diet/chat');
    await f.submit(f.form('chatForm', { message: '帮我录入面午餐' }));
    assert.match(f.elements.app.innerHTML, /data-action="save-meal-draft"/);
    const button = f.button('save-meal-draft');
    await f.click(button); await f.click(button);
    assert.equal(attempts, 1);
    pending.reject(new Error('临时失败')); await f.flush();
    assert.doesNotMatch(f.elements.app.innerHTML, /已保存/);
    await f.click(f.button('save-meal-draft'));
    assert.equal(attempts, 2);
    assert.match(f.elements.app.innerHTML, /已保存/);
});

test('chat response after reset is discarded and cannot restore previous session', async () => {
    const pending = deferred();
    const f = fixture({ 'POST /chat': () => pending.promise });
    await f.flush(); await f.route('/diet/chat');
    await f.submit(f.form('chatForm', { message: '帮我录入面午餐' }));
    await f.click('new-session');
    pending.resolve({ sessionId: 'session-1', mealDraft: draft({ data: { name: '旧草稿', mealTime: ['午餐'] } }) });
    await f.flush();
    assert.doesNotMatch(f.elements.app.innerHTML, /旧草稿|当前会话：session-1/);
    await f.submit(f.form('chatForm', { message: '新问题' }));
    assert.equal(f.calls.filter((c) => c.route === '/sessions').length, 2);
});

test('management discards a delayed draft after clearing or switching user', async () => {
    const first = deferred();
    const second = deferred();
    let count = 0;
    const f = fixture({ 'POST /meals/personal/draft': () => ++count === 1 ? first.promise : second.promise });
    await f.flush();
    const form = f.form('mealForm', { text: '第一道菜午餐' });
    await f.click('generate-meal-draft');
    await f.click('new-meal');
    first.resolve(draft({ data: { ...draft().data, name: '旧菜' } }));
    await f.flush();
    assert.doesNotMatch(f.elements.app.innerHTML, /旧菜/);
    await f.input(f.form('mealForm'), 'text', '第二道菜晚餐');
    await f.click('generate-meal-draft');
    await f.user('2');
    second.resolve(draft({ data: { ...draft().data, name: '跨用户菜' } }));
    await f.flush();
    assert.doesNotMatch(f.elements.app.innerHTML, /跨用户菜/);
});

test('management serializes save while a create request is pending', async () => {
    const pending = deferred();
    const f = fixture({ 'POST /meals/personal': () => pending.promise });
    await f.flush();
    const form = f.form('mealForm', { name: '番茄面', mealTime: ['午餐'] });
    await f.submit(form);
    await f.submit(form);
    assert.equal(f.calls.filter((c) => c.method === 'POST' && c.route === '/meals/personal').length, 1);
    pending.resolve({ id: 31 });
    await f.flush();
});

for (const action of ['cancel-edit', 'new-meal', 'edit-meal', 'user-switch']) {
    for (const outcome of ['success', 'failure']) {
        test(`pending save ${outcome} after ${action} leaves the new context usable`, async () => {
            const pending = deferred();
            const row = { id: 12, ...draft().data };
            const f = fixture({ 'GET /meals/personal': () => [row], 'POST /meals/personal': () => pending.promise });
            await f.flush();
            await f.submit(f.form('mealForm', { name: '待存的面', mealTime: ['午餐'] }));
            if (action === 'user-switch') await f.user('2');
            else await f.click(action === 'edit-meal' ? f.button('edit-meal', { id: 12 }) : action);
            const html = f.elements.app.innerHTML.match(/<form id="mealForm"[\s\S]*?<\/form>/)?.[0] || '';
            assert.doesNotMatch(html, /type="submit" disabled/, 'current editor must not inherit old pending state');
            if (outcome === 'success') pending.resolve({ id: 31 });
            else pending.reject(new Error('旧请求失败'));
            await f.flush();
            const currentHtml = f.elements.app.innerHTML.match(/<form id="mealForm"[\s\S]*?<\/form>/)?.[0] || '';
            assert.doesNotMatch(currentHtml, /type="submit" disabled/);
            if (action !== 'user-switch' && outcome === 'success') {
                assert.ok(f.calls.filter((c) => c.method === 'GET' && c.route === '/meals/personal').length >= 2,
                    'successful old write still refreshes this user list');
            }
        });
    }
}

test('a failed second extraction retires the first preview while keeping new text and editable fields', async () => {
    let attempts = 0;
    const f = fixture({ 'POST /meals/personal/draft': () => ++attempts === 1 ? draft() : Promise.reject(new Error('模型失败')) });
    await f.flush();
    await f.input(f.form('mealForm'), 'text', '番茄面中午吃');
    await f.click('generate-meal-draft');
    assert.match(f.elements.app.innerHTML, /整理预览/);
    await f.input(f.form('mealForm'), 'text', '新描述晚餐吃');
    await f.click('generate-meal-draft');
    assert.doesNotMatch(f.elements.app.innerHTML, /整理预览[\s\S]*番茄面中午吃/);
    const form = f.form('mealForm');
    assert.equal(form.values.text, '新描述晚餐吃');
    assert.equal(form.values.name, '番茄面');
    assert.match(f.elements.app.innerHTML, /整理失败，已保留表单内容/);
    assert.match(f.elements.toast.textContent, /模型失败/);
});

test('draft preview names every generated detail and escapes model text', async () => {
    const f = fixture({ 'POST /meals/personal/draft': () => draft({ data: { ...draft().data,
        name: '<img src=x onerror=1>', knowledge: { ...draft().data.knowledge, description: '<script>alert(1)</script>' } },
        warnings: ['<svg onload=1>'] }) });
    await f.flush();
    await f.input(f.form('mealForm'), 'text', '一道菜');
    await f.click('generate-meal-draft');
    const html = f.elements.app.innerHTML;
    const preview = html.match(/<section class="draft-preview full"[\s\S]*?<\/section>/)?.[0] || '';
    assert.match(preview, /整理预览[\s\S]*食材[\s\S]*番茄[\s\S]*描述[\s\S]*我的做法/);
    assert.doesNotMatch(html, /<img src=x|<script>|<svg onload/);
    assert.match(html, /&lt;svg onload=1&gt;/);
});

test('a pending chat response from the former user is discarded', async () => {
    const pending = deferred();
    const f = fixture({ 'POST /chat': () => pending.promise });
    await f.flush(); await f.route('/diet/chat');
    await f.submit(f.form('chatForm', { message: '帮我录入面午餐' }));
    await f.user('2');
    pending.resolve({ sessionId: 'session-1', mealDraft: draft({ data: { ...draft().data, name: '前用户的面' } }) });
    await f.flush();
    assert.doesNotMatch(f.elements.app.innerHTML, /前用户的面/);
});
