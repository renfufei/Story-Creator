#!/usr/bin/env node
/**
 * /chat 聊天页体验与健壮性：**异步引导数据 + 竞态守卫 + 流式渲染节流 + 移动端抽屉**（2026-10-20）。
 *
 * 上一轮把流式接口从 GET 改成了 POST（见 probe_chat_stream_post.mjs）。这一轮收掉遗留项：
 *
 *   ① 引导数据原来是**同步 XHR**：`open()` 第三个参数传 false，浏览器要停下来等
 *      `/chat/data` 往返，HTML 解析与首屏绘制全被卡住（数据慢就白屏）。改成 fetch + 骨架屏。
 *   ② 流式渲染没有节流：模型每吐一个小包就 `+=` 一次 + `$nextTick` 滚一次底，
 *      几千 token 就是几千次重渲染/滚动计算。改成 rAF 按帧合并（**收尾必须 flush，否则丢尾巴**）。
 *   ③ 滚动条抢人：无条件滚到底，用户往上翻看前文时一有新 token 就被拽回去。
 *   ④ 切会话竞态：先发的 `/messages` 后到会盖掉后点的那个会话。
 *   ⑤ 重命名发两次 PUT：回车保存后输入框隐藏触发 blur，又走一遍保存。
 *   ⑥ 删除用原生 confirm（阻塞主线程、样式不可控）。
 *   ⑦ 窄屏固定 `col-3`：390px 屏只分到 97px，会话标题全被截成一两个字。
 *
 * 本脚本逐条守护：
 *   P0 引导数据不再阻塞解析：**故意把 /chat/data 挂住**，DOM 仍要 ready、骨架要出来
 *   P1 数据到达后骨架退场、会话列表与当前标题正确
 *   P2 【核心】切会话竞态：先发的慢响应不能盖掉后点的会话
 *   P3 重命名只发一次 PUT（空标题/没改则一次都不发）
 *   P4 删除走 SC.confirm 弹层：取消不发请求，确认才发；原生 confirm 不被调用
 *   P5 【核心】300 个 token 只写个位数次 scrollTop，且**最后一行不丢**（缓冲不能吃尾巴）
 *   P6 【核心】用户往上翻时不再被拽回底部（非强制滚动贴着历史不动）
 *   P7 【核心】390px：侧栏是抽屉（不占位/可滑出/选完自动收起）、无横向溢出
 *   P8 运行期无 JS 异常 / console.error
 *
 * 用法：
 *   bash /tmp/settings-verify/run.sh scripts/probe_chat_optimize.mjs   # 1889 临时实例
 *   node scripts/probe_chat_optimize.mjs                              # 默认 1888
 * 环境变量：CHAT_BASE / WM_BASE（默认 http://localhost:1888）、STORY_BROWSER_PATH
 */
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const BASE = process.env.CHAT_BASE || process.env.WM_BASE || 'http://localhost:1888';
const PAGE = BASE + '/chat';
const CHROME = process.env.STORY_BROWSER_PATH
    || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const PORT = 9353;
const SHOT_DIR = '/tmp/chat-optimize-shots';

const results = [];
const check = (name, ok, extra = '') => {
    results.push({ name, ok: !!ok, extra });
    console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${extra ? '  [' + extra + ']' : ''}`);
};

const userDataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'chat-opt-probe-'));
const chrome = spawn(CHROME, [
    '--headless=new', '--no-sandbox', '--disable-gpu', '--hide-scrollbars', '--mute-audio',
    '--remote-debugging-port=' + PORT, '--user-data-dir=' + userDataDir, 'about:blank'
], { stdio: 'ignore' });

const sleep = (ms) => new Promise(r => setTimeout(r, ms));

let ws, msgId = 0;
const pending = new Map();
const runtimeErrors = [];
const consoleErrors = [];

function send(method, params = {}) {
    const id = ++msgId;
    return new Promise((res, rej) => {
        pending.set(id, { res, rej });
        ws.send(JSON.stringify({ id, method, params }));
    });
}

async function evalJs(expression) {
    const r = await send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true });
    if (r.exceptionDetails) {
        throw new Error('页面 JS 异常: ' + (r.exceptionDetails.exception?.description || r.exceptionDetails.text));
    }
    return r.result.value;
}

async function waitFor(label, fn, timeout = 8000, step = 120) {
    const t0 = Date.now();
    while (Date.now() - t0 < timeout) {
        if (await fn()) return true;
        await sleep(step);
    }
    return false;
}

/* ==================== 假数据 ==================== */
const sessions = [
    { id: 1, title: '会话一 · 长篇' },
    { id: 2, title: '会话二 · 短篇' }
];
const configs = {
    textConfigs: [{ id: 11, displayName: '文本模型 A', modelId: 'gpt-test' }],
    ttsConfigs: [],
    imageConfigs: []
};
/* 会话 1 给足够多的消息，让消息区真的能滚（测「不抢滚动条」的前提） */
const LONG_MSG = Array.from({ length: 40 },
    (_, i) => `第${i + 1}条历史消息：雨还在下，檐角的水滴连成线，打在青石板上碎成一片细响。`).join('\n');
const messagesBySession = {
    1: [
        { id: 101, role: 'user', content: '第一段对话', modelType: 'TEXT', createdAt: '2026-10-20T09:00:00' },
        { id: 102, role: 'assistant', content: LONG_MSG, modelType: 'TEXT', createdAt: '2026-10-20T09:00:05' }
    ],
    2: [
        { id: 201, role: 'user', content: '会话二唯一一条消息', modelType: 'TEXT', createdAt: '2026-10-20T09:10:00' }
    ]
};

/* ==================== 网络层拦截 ==================== */
let holdBootstrap = false;         // 挂住 /chat/data
let heldBootstrap = null;          // 被挂住的 requestId
let holdMessagesFor = null;        // 只挂住这个会话的 /messages
const heldMessages = [];           // [{requestId, sessionId}]
let putTitleCount = 0;
let deleteCount = 0;
let createCount = 0;
let sseBody = '';

const b64 = (s) => Buffer.from(s, 'utf8').toString('base64');

function fulfil(requestId, code, contentType, body) {
    return send('Fetch.fulfillRequest', {
        requestId,
        responseCode: code,
        responseHeaders: [{ name: 'Content-Type', value: contentType }],
        body: b64(body)
    });
}
const fulfilJson = (requestId, obj) =>
    fulfil(requestId, 200, 'application/json; charset=utf-8', JSON.stringify(obj));

function chatData() {
    return { sessions: sessions.map(s => ({ id: s.id, title: s.title })), ...configs };
}

async function handlePaused(p) {
    const req = p.request;
    const url = req.url;
    const method = req.method;
    const u = new URL(url);
    const pn = u.pathname;

    // 引导数据
    if (pn === '/chat/data') {
        if (holdBootstrap) { heldBootstrap = p.requestId; return; }
        return fulfilJson(p.requestId, chatData());
    }

    if (pn.indexOf('/api/chat/sessions') === 0) {
        const rest = pn.substring('/api/chat/sessions'.length);
        const seg = rest.split('/').filter(Boolean);

        // 新建会话
        if (method === 'POST' && seg.length === 0) {
            createCount++;
            return fulfilJson(p.requestId, { id: 900 + createCount, title: '新对话' });
        }
        const id = seg[0] ? Number(seg[0]) : null;

        // 消息列表
        if (method === 'GET' && seg[1] === 'messages') {
            if (holdMessagesFor === id) { heldMessages.push({ requestId: p.requestId, sessionId: id }); return; }
            return fulfilJson(p.requestId, messagesBySession[id] || []);
        }
        // 重命名
        if (method === 'PUT' && seg[1] === 'title') {
            putTitleCount++;
            return fulfilJson(p.requestId, { success: true });
        }
        // 流式
        if (method === 'POST' && seg[1] === 'stream') {
            return fulfil(p.requestId, 200, 'text/event-stream; charset=utf-8', sseBody);
        }
        // 删除
        if (method === 'DELETE' && seg.length === 1) {
            deleteCount++;
            return fulfilJson(p.requestId, { success: true });
        }
        // 媒体消息（本探针不触发，兜一层免得挂住）
        if (method === 'POST' && seg[1] === 'send') {
            return fulfilJson(p.requestId, { success: true, mediaUrl: '/api/chat/media/x' });
        }
    }

    // 其它（理论上不该匹配到）直接放过，避免探针把页面自己卡死
    return send('Fetch.continueRequest', { requestId: p.requestId });
}

async function releaseBootstrap() {
    if (!heldBootstrap) return false;
    const id = heldBootstrap;
    heldBootstrap = null;
    holdBootstrap = false;
    await fulfilJson(id, chatData());
    return true;
}

async function releaseMessages() {
    const list = heldMessages.splice(0);
    for (const h of list) {
        await fulfilJson(h.requestId, messagesBySession[h.sessionId] || []);
    }
    return list.length;
}

/* ==================== 快照 ==================== */
const SNAP = `(() => {
    const host = document.querySelector('[x-data]');
    const data = host && window.Alpine ? Alpine.$data(host) : null;
    const box = document.querySelector('[x-ref="messageContainer"]');
    const sidebar = document.querySelector('.chat-sidebar');
    const bar = document.querySelector('.chat-mobile-bar');
    const vis = (el) => !!el && el.getClientRects().length > 0
        && getComputedStyle(el).display !== 'none';
    const rect = (el) => { const r = el.getBoundingClientRect();
        return { l: Math.round(r.left), t: Math.round(r.top), w: Math.round(r.width), h: Math.round(r.height) }; };
    const rows = Array.from(document.querySelectorAll('.chat-sidebar .border-bottom.d-flex.align-items-center'));
    return {
        ready: !!(data && box),
        booting: data ? data.booting : null,
        loadError: data ? data.loadError : null,
        activeId: data ? data.activeSessionId : null,
        sessionCount: data ? data.sessions.length : -1,
        sessionTitles: data ? data.sessions.map(s => s.title) : [],
        msgCount: data ? data.messages.length : -1,
        msgContents: data ? data.messages.map(m => m.content).filter(Boolean) : [],
        streaming: data ? data.streaming : null,
        streamingContent: data ? data.streamingContent : null,
        sidebarOpen: data ? data.sidebarOpen : null,
        selectedConfigId: data ? data.selectedConfigId : null,
        mobileTitle: bar ? bar.innerText.trim() : '',
        sidebar: sidebar ? rect(sidebar) : null,
        sidebarTransform: sidebar ? getComputedStyle(sidebar).transform : null,
        barVisible: vis(bar),
        rowCount: rows.length,
        scrollWrites: window.__scrollWrites || 0,
        docScrollW: document.documentElement.scrollWidth,
        docClientW: document.documentElement.clientWidth,
        box: box ? { top: Math.round(box.scrollTop), h: box.clientHeight,
                     sh: box.scrollHeight, gap: Math.round(box.scrollHeight - box.scrollTop - box.clientHeight) } : null,
        modalCount: document.querySelectorAll('.sc-modal-backdrop').length,
        modalText: (document.querySelector('.sc-modal-backdrop') || {}).innerText || '',
        toastCount: document.querySelectorAll('.sc-toast').length,
        nativeConfirmCalls: window.__confirmCalls || 0
    };
})()`;
const snap = async (ms = 250) => { if (ms) await sleep(ms); return evalJs(SNAP); };

/* ==================== 页面操作 ==================== */
async function shot(name) {
    const r = await send('Page.captureScreenshot', { format: 'png' });
    fs.mkdirSync(SHOT_DIR, { recursive: true });
    fs.writeFileSync(path.join(SHOT_DIR, name + '.png'), Buffer.from(r.data, 'base64'));
}

async function loadPage(width, height) {
    await send('Emulation.setDeviceMetricsOverride', { width, height, deviceScaleFactor: 1, mobile: false });
    await send('Page.navigate', { url: PAGE });
    const ok = await waitFor('页面骨架就绪', async () => {
        try {
            return await evalJs(`(() => !!(window.Alpine && document.querySelector('textarea')
                && Alpine.$data(document.querySelector('[x-data]'))))()`);
        } catch (e) { return false; }
    }, 20000, 150);
    if (!ok) throw new Error('聊天页未就绪');
}

async function waitBooted() {
    return waitFor('booting 结束', async () => {
        const x = await evalJs(SNAP);
        return x.booting === false;
    }, 8000, 150);
}

/* 点侧栏第 n 个会话 */
async function clickSessionRow(n) {
    return evalJs(`(() => {
        const rows = Array.from(document.querySelectorAll('.chat-sidebar .border-bottom.d-flex.align-items-center'));
        const row = rows[${n}];
        if (!row) return false;
        const hit = row.querySelector('div.flex-grow-1') || row;
        hit.click();
        return true;
    })()`);
}

/* ==================== 主流程 ==================== */
async function main() {
    let ver = null;
    for (let i = 0; i < 80; i++) {
        try {
            const r = await fetch(`http://127.0.0.1:${PORT}/json/version`);
            if (r.ok) { ver = await r.json(); break; }
        } catch (e) { /* 还没起来 */ }
        await sleep(250);
    }
    if (!ver) throw new Error('Chrome CDP 端点未就绪');
    console.log('Chrome: ' + ver['Browser']);

    const target = await (await fetch(`http://127.0.0.1:${PORT}/json/new?about:blank`, { method: 'PUT' })).json();
    ws = new WebSocket(target.webSocketDebuggerUrl);
    await new Promise((res, rej) => { ws.onopen = res; ws.onerror = rej; });
    ws.onmessage = (ev) => {
        const m = JSON.parse(ev.data);
        if (m.id && pending.has(m.id)) {
            const p = pending.get(m.id);
            pending.delete(m.id);
            m.error ? p.rej(new Error(JSON.stringify(m.error))) : p.res(m.result);
            return;
        }
        if (m.method === 'Runtime.exceptionThrown') {
            runtimeErrors.push(m.params.exceptionDetails.exception?.description
                || m.params.exceptionDetails.text);
        } else if (m.method === 'Runtime.consoleAPICalled' && m.params.type === 'error') {
            consoleErrors.push((m.params.args || []).map(a => a.value || a.description || '').join(' '));
        } else if (m.method === 'Fetch.requestPaused') {
            handlePaused(m.params).catch(e => consoleErrors.push('Fetch 拦截失败: ' + e.message));
        }
    };

    await send('Runtime.enable');
    await send('Page.enable');
    await send('Fetch.enable', {
        patterns: [
            { urlPattern: '*chat/data*', requestStage: 'Request' },
            { urlPattern: '*api/chat/sessions*', requestStage: 'Request' }
        ]
    });

    /* ---------- P0 引导数据不再阻塞解析 ---------- */
    console.log('\n— P0 引导数据不阻塞解析（挂住 /chat/data） —');
    holdBootstrap = true;
    await loadPage(1280, 900);
    // 关键：/chat/data 还挂着，DOM 必须已经 ready、页面骨架必须已经画出来。
    // 同步 XHR 的页面这里会一直卡在 loading，根本到不了 complete。
    const domState = await evalJs(`document.readyState`);
    let s = await snap(200);
    check('【核心】引导数据挂着时 DOM 仍可解析完成（同步 XHR 会卡住这里）',
        domState === 'complete', 'readyState=' + domState);
    check('【核心】数据没回来时先给骨架，不是空白会话列表',
        s.booting === true && s.sessionCount === 0, `booting=${s.booting} sessions=${s.sessionCount}`);
    check('骨架文案可见（加载会话…）',
        await evalJs(`(() => document.body.innerText.indexOf('加载会话') >= 0)()`));
    check('输入框已可交互（骨架期页面不是死的）',
        await evalJs(`(() => { const t = document.querySelector('textarea'); return !!t && !t.disabled; })()`));
    await shot('01-booting');

    /* ---------- P1 数据到达 ---------- */
    console.log('\n— P1 引导数据到达后 —');
    const released = await releaseBootstrap();
    check('被挂住的 /chat/data 已放行', released === true);
    check('booting 结束', await waitBooted());
    s = await snap(300);
    check('会话列表渲染出 2 个（标题来自接口，不是硬编码）',
        s.sessionCount === 2 && s.rowCount === 2 && s.sessionTitles.join('|') === '会话一 · 长篇|会话二 · 短篇',
        `sessions=${s.sessionCount} rows=${s.rowCount} titles=${s.sessionTitles.join('|')}`);
    check('自动选中最近一个会话并加载了它的消息',
        s.activeId === 1 && s.msgCount === 2, `active=${s.activeId} msgs=${s.msgCount}`);
    check('模型配置也进下拉框了', s.selectedConfigId === 11, 'configId=' + s.selectedConfigId);

    /* ---------- P2 切会话竞态 ---------- */
    console.log('\n— P2 切会话竞态（慢响应不能盖掉后点的会话） —');
    holdMessagesFor = 1;                 // 先把「会话一」的消息请求挂住
    await clickSessionRow(0);
    const arrived = await waitFor('会话一的请求到达', () => heldMessages.length > 0, 5000, 100);
    check('会话一的消息请求已被挂住（模拟慢网络）', arrived === true, 'held=' + heldMessages.length);
    await clickSessionRow(1);            // 立刻点会话二（正常返回）
    await waitFor('切到会话二', async () => (await evalJs(SNAP)).activeId === 2, 5000, 100);
    await releaseMessages();             // 现在把会话一的慢响应放回来
    await sleep(600);
    s = await snap(0);
    check('【核心】慢响应回来后仍停在会话二（没有被过期响应盖回去）',
        s.activeId === 2, 'active=' + s.activeId);
    check('【核心】消息是会话二的内容，不是会话一的长文',
        s.msgCount === 1 && s.msgContents.join('').indexOf('会话二唯一一条消息') >= 0,
        `msgs=${s.msgCount}:${JSON.stringify(s.msgContents.slice(0, 1))}`);
    holdMessagesFor = null;

    /* ---------- P3 重命名只发一次 PUT ---------- */
    console.log('\n— P3 重命名不发重复 PUT —');
    putTitleCount = 0;
    // 模拟「回车保存」：saveTitle 之后编辑态被清掉，输入框隐藏会再触发一次 blur → 又调一次
    const dup = await evalJs(`(() => {
        const d = Alpine.$data(document.querySelector('[x-data]'));
        const s = d.sessions[0];
        d.startEditTitle(s);
        d.editingTitle = '改过的标题';
        d.saveTitle(s);          // 回车
        d.saveTitle(s);          // blur（原来这里会再发一次 PUT）
        return s.title;
    })()`);
    await sleep(400);
    check('【核心】回车 + blur 只发 1 次 PUT', putTitleCount === 1, 'PUT=' + putTitleCount);
    check('标题已就地更新', dup === '改过的标题', 'title=' + dup);

    putTitleCount = 0;
    const noop = await evalJs(`(() => {
        const d = Alpine.$data(document.querySelector('[x-data]'));
        const s = d.sessions[1];
        d.startEditTitle(s);
        d.editingTitle = s.title;     // 没改
        d.saveTitle(s);
        d.startEditTitle(s);
        d.editingTitle = '   ';       // 空白
        d.saveTitle(s);
        return s.title;
    })()`);
    await sleep(300);
    check('标题没改 / 只有空白时一次请求都不发（也保留原标题）',
        putTitleCount === 0 && noop === '会话二 · 短篇', `PUT=${putTitleCount} title=${noop}`);

    /* ---------- P4 删除走 SC.confirm ---------- */
    console.log('\n— P4 删除走统一确认弹层 —');
    // 原生 confirm 在本探针里会被替换成计数器：若页面还在用 confirm，这里会 +1
    await evalJs(`(() => { window.__confirmCalls = 0; window.confirm = function () { window.__confirmCalls++; return true; }; return true; })()`);
    deleteCount = 0;
    await evalJs(`(() => { const d = Alpine.$data(document.querySelector('[x-data]')); d.deleteSession(2); return true; })()`);
    await sleep(350);
    s = await snap(0);
    check('【核心】不调用原生 confirm', s.nativeConfirmCalls === 0, 'confirm=' + s.nativeConfirmCalls);
    check('弹出项目统一的确认弹层（SC.confirm）',
        s.modalCount === 1 && s.modalText.indexOf('删除会话') >= 0, JSON.stringify(s.modalText.slice(0, 40)));
    check('点「取消」不发 DELETE', true);   // 先占位，下面点取消后统一断言
    await evalJs(`(() => { const b = document.querySelector('.sc-modal-backdrop [data-act="cancel"]'); if (b) b.click(); return true; })()`);
    await sleep(300);
    s = await snap(0);
    check('取消后弹层关闭、没有发 DELETE、会话还在',
        s.modalCount === 0 && deleteCount === 0 && s.sessionCount === 2,
        `modal=${s.modalCount} del=${deleteCount} sessions=${s.sessionCount}`);

    await evalJs(`(() => { const d = Alpine.$data(document.querySelector('[x-data]')); d.deleteSession(2); return true; })()`);
    await sleep(300);
    await evalJs(`(() => { const b = document.querySelector('.sc-modal-backdrop [data-act="ok"]'); if (b) b.click(); return true; })()`);
    await sleep(600);
    s = await snap(0);
    check('确认后才发 DELETE，并且列表里真的少了这个会话',
        deleteCount === 1 && s.sessionCount === 1, `del=${deleteCount} sessions=${s.sessionCount}`);

    /* ---------- P5 流式渲染节流 + 尾巴不丢 ---------- */
    console.log('\n— P5 流式渲染节流（300 个 token） —');
    await clickSessionRow(0);                 // 回到会话一
    await waitFor('回到会话一', async () => (await evalJs(SNAP)).activeId === 1, 5000, 100);
    await sleep(400);

    // 在消息容器上装 scrollTop setter 计数器：数「写了多少次滚动位置」
    await evalJs(`(() => {
        const box = document.querySelector('[x-ref="messageContainer"]');
        const desc = Object.getOwnPropertyDescriptor(Element.prototype, 'scrollTop');
        window.__scrollWrites = 0;
        Object.defineProperty(box, 'scrollTop', {
            configurable: true,
            get() { return desc.get.call(this); },
            set(v) { window.__scrollWrites++; desc.set.call(this, v); }
        });
        return true;
    })()`);

    const TOKENS = 300;
    sseBody = Array.from({ length: TOKENS }, (_, i) => `event:token\ndata:第${i + 1}行\ndata:\n\n`).join('')
        + 'event:done\ndata:\n\n';
    const expected = Array.from({ length: TOKENS }, (_, i) => `第${i + 1}行`).join('\n') + '\n';

    // ★ 两步：先填输入框，等一拍让 `:disabled` 的响应式更新跑完，再点。
    //   （Alpine 的效果是异步刷新的；同一条表达式里 dispatch 完立刻 click，
    //     按钮还带着 disabled，点了什么也不会发生。）
    await evalJs(`(() => {
        const ta = document.querySelector('textarea');
        ta.value = '数一下渲染次数';
        ta.dispatchEvent(new Event('input', { bubbles: true }));
        return true;
    })()`);
    await sleep(250);
    const clicked = await evalJs(`(() => {
        const b = Array.from(document.querySelectorAll('button')).find(x => x.querySelector('.bi-send'));
        if (!b || b.disabled) return false;
        b.click();
        return true;
    })()`);
    check('发送按钮已可用并被点下', clicked === true);
    const started = await waitFor('流开始', async () => (await evalJs(SNAP)).streaming === true, 6000, 80);
    check('流式状态已进入「生成中」', started === true);
    await waitFor('流结束', async () => (await evalJs(SNAP)).streaming === false, 15000, 150);
    s = await snap(400);

    const last = s.msgContents.length ? String(s.msgContents[s.msgContents.length - 1]) : '';
    const lines = last.split('\n').filter(x => x !== '');
    // 逐字比对：既查「有没有丢尾巴」，也查「有没有重复/串行」
    check('【核心】300 个 token 全部落地，换行与顺序逐字一致',
        last === expected,
        `lines=${lines.length}/${TOKENS} len=${last.length} vs ${expected.length}` + (last === expected ? '' :
            ` diffAt=${(() => { for (let i = 0; i < Math.max(last.length, expected.length); i++) { if (last[i] !== expected[i]) return i; } return -1; })()}`));
    check('【核心】滚动写入次数远小于 token 数（按帧合并，不是每个 token 一次）',
        s.scrollWrites > 0 && s.scrollWrites <= 20, `writes=${s.scrollWrites} tokens=${TOKENS}`);
    check('收尾后不再有残留缓冲（_pendingTokens 为空）',
        await evalJs(`(() => Alpine.$data(document.querySelector('[x-data]'))._pendingTokens === '')()`));
    await shot('02-throttled');

    /* ---------- P6 不抢滚动条 ---------- */
    console.log('\n— P6 用户翻历史时不被拽回底部 —');
    const scrolled = await evalJs(`(() => {
        const box = document.querySelector('[x-ref="messageContainer"]');
        box.scrollTop = 0;
        return { top: box.scrollTop, gap: box.scrollHeight - box.scrollTop - box.clientHeight };
    })()`);
    check('消息区确实能滚（内容高于容器）', scrolled.gap > 100, 'gap=' + scrolled.gap);

    const polite = await evalJs(`(() => {
        const d = Alpine.$data(document.querySelector('[x-data]'));
        const box = document.querySelector('[x-ref="messageContainer"]');
        d.autoScroll();            // 非强制：用户正在看历史
        const after = box.scrollTop;
        d.autoScroll(true);        // 强制：自己发送 / 切会话 / 收尾
        return { after: Math.round(after), forced: Math.round(box.scrollTop) };
    })()`);
    check('【核心】非强制滚动时停在原位（不把用户拽回底部）', polite.after === 0, 'top=' + polite.after);
    check('强制滚动时才回到底部', polite.forced > 100, 'top=' + polite.forced);

    /* ---------- P7 移动端抽屉 ---------- */
    console.log('\n— P7 390px 窄屏抽屉 —');
    await loadPage(390, 780);
    await waitBooted();
    s = await snap(400);
    check('窄屏工具栏出现（会话入口 + 当前标题）',
        s.barVisible === true && s.mobileTitle.indexOf('会话一') >= 0, JSON.stringify(s.mobileTitle));
    check('【核心】侧栏初始在视口外（抽屉收起，不占 390px 里的 97px）',
        s.sidebar && s.sidebar.l < 0 && s.sidebar.w <= 300,
        `left=${s.sidebar && s.sidebar.l} w=${s.sidebar && s.sidebar.w}`);
    check('【核心】整页没有横向溢出', s.docScrollW <= s.docClientW + 1,
        `scrollW=${s.docScrollW} clientW=${s.docClientW}`);
    await shot('03-mobile-closed');

    await evalJs(`(() => { const b = document.querySelector('.chat-mobile-bar button'); if (b) b.click(); return true; })()`);
    await sleep(400);
    s = await snap(0);
    check('【核心】点入口后抽屉滑进视口（左边缘贴 0）',
        s.sidebar.l === 0 && s.sidebarOpen === true, `left=${s.sidebar.l} open=${s.sidebarOpen}`);
    await shot('04-mobile-open');

    await clickSessionRow(1);
    await sleep(450);
    s = await snap(0);
    check('【核心】选完会话抽屉自动收起', s.sidebarOpen === false && s.sidebar.l < 0,
        `open=${s.sidebarOpen} left=${s.sidebar.l}`);
    check('切到的会话内容正确', s.activeId === 2, 'active=' + s.activeId);

    /* ---------- P8 无异常 ---------- */
    console.log('\n— P8 运行期异常 —');
    check('无 JS 运行时异常', runtimeErrors.length === 0, runtimeErrors.slice(0, 2).join(' | '));
    check('无 console.error', consoleErrors.length === 0, consoleErrors.slice(0, 2).join(' | '));

    const failed = results.filter(r => !r.ok).length;
    console.log(`\n===== ${results.length - failed}/${results.length} 通过 =====`);
    if (failed) console.log('失败项：\n' + results.filter(r => !r.ok).map(r => ' - ' + r.name).join('\n'));

    try { await send('Browser.close'); } catch (e) { /* ignore */ }
    try { chrome.kill(); } catch (e) { /* ignore */ }
    process.exit(failed ? 1 : 0);
}

main().catch(async (e) => {
    console.error('PROBE FAILED: ' + e.message);
    try { chrome.kill(); } catch (err) { /* ignore */ }
    process.exit(2);
});
