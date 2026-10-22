#!/usr/bin/env node
/**
 * /chat 聊天记录：每条消息的「⋯」操作菜单（全选 / 复制），2026-10-20
 *
 * 需求：用户输入和大模型返回的**每一条**消息，旁边都有一个菜单小图标，
 *       点开有【全选】【复制】—— 全选 = 选中这条消息的正文（随后 Ctrl/⌘+C），
 *       复制 = 整条正文进剪贴板。
 *
 * 实现落点（pages/chat.html）：
 *   · 气泡与图标放进 flex 行 .msg-line：用户消息的气泡靠右、图标在它**左侧**，
 *     助手消息反之 —— 图标永远在气泡外侧，正文宽度不变；
 *   · 全局只渲染一个 #msg-menu（fixed 定位，坐标按图标 getBoundingClientRect 现算），
 *     x-for 里逐条挂浮层会多出几十个节点和定位状态；
 *   · @click.stop 保住「点另一个图标能直接换目标」（否则 Alpine 的 .outside 会立刻把它关掉）；
 *   · 全选走 Range.selectNodeContents，复制优先 navigator.clipboard、失败退回 execCommand。
 *
 * 本脚本逐条守护：
 *   T1 每条消息都有图标；用户/助手图标分居气泡左右（不压正文）
 *   T2 点图标弹出菜单：两个按钮、贴着图标、在视口内
 *   T3 【全选】选中这条消息的正文（含换行），菜单关闭
 *   T4 【复制】把整条正文写进剪贴板（拦截 writeText 比对原文）
 *   T5 点外部 / Esc / 滚消息区 都能关掉菜单
 *   T6 最后一条消息的菜单向上翻转，不超出视口
 *   T7 换一条消息：菜单跟着换目标（不会复制到上一条）
 *   T8 320px：无横向溢出、图标仍可点、菜单不出界
 *   T9 全程无 JS 运行时异常
 *
 * ★ 自带静态服务器 + mock 接口，不依赖 1888（其静态资源处理已坏，见记忆 dev-workflow）。
 *
 * 用法：node scripts/probe_chat_msg_menu.mjs
 */
import { spawn } from 'node:child_process';
import http from 'node:http';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const CHROME = process.env.STORY_BROWSER_PATH
    || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const CDP_PORT = 9391;
const HTTP_PORT = 18912;
const SHOT_DIR = '/tmp/chat-menu-shots';
const ROOT = path.resolve(import.meta.dirname, '..');
const STATIC = path.join(ROOT, 'src/main/resources/static');
const STATIC_ROOTS = [STATIC, path.join(ROOT, 'data/static')];

const results = [];
const check = (name, ok, extra = '') => {
    results.push({ name, ok: !!ok, extra });
    console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${extra ? '  [' + extra + ']' : ''}`);
};
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

/* ==================== mock 数据 ==================== */
/* 多行 + 中文 + 空格，用来验证「全选/复制」拿到的是完整原文而不是首行 */
const USER_TEXT = '帮我改一下第二章的开头，重点是让对话更自然一点。\n第二行：主角应该在雨里等了三分钟。';
/* 故意写长：① 验证「全选/复制」拿到的是整段而不是首行；
   ② 让消息区真的出现滚动（4 条短消息在 1280×900 里全装得下，
      「滚一下菜单要关掉」就没法验，会变成假绿）。 */
const AI_PARA = '雨水顺着伞骨滑下来，他数到第一百八十下的时候，街角终于出现了那个身影。\n'
    + '「你迟到了。」他说，声音比想象中平静。\n\n';
const AI_TEXT = '好的，这里是改写后的版本：\n\n' + AI_PARA.repeat(12)
    + '主要改动：把「等了三分钟」换成数数，让等待有重量。';
const AI_IMG_TEXT = '一幅雨夜街角的插画：暖黄路灯、湿漉漉的柏油路。';

const SESSIONS = [{ id: 1, title: '测试会话' }];
const MESSAGES = [
    { id: 101, role: 'user', content: USER_TEXT, createdAt: '2026-10-20T10:00:00', modelType: 'TEXT' },
    { id: 102, role: 'assistant', content: AI_TEXT, createdAt: '2026-10-20T10:00:05', modelType: 'TEXT' },
    { id: 103, role: 'user', content: '再画一张配图吧', createdAt: '2026-10-20T10:01:00', modelType: 'TEXT' },
    { id: 104, role: 'assistant', content: AI_IMG_TEXT, createdAt: '2026-10-20T10:01:09',
      modelType: 'IMAGE', mediaFilePath: 'probe-fake.png' }
];

/* ==================== 静态服务器 ==================== */
const MIME = {
    '.html': 'text/html; charset=utf-8', '.css': 'text/css; charset=utf-8',
    '.js': 'application/javascript; charset=utf-8', '.json': 'application/json; charset=utf-8',
    '.svg': 'image/svg+xml', '.png': 'image/png',
    '.woff': 'font/woff', '.woff2': 'font/woff2', '.ttf': 'font/ttf'
};

function serveStatic(res, relPath) {
    let buf = null, hit = null;
    for (const root of STATIC_ROOTS) {
        const file = path.join(root, relPath);
        if (!file.startsWith(root)) continue;
        try { buf = fs.readFileSync(file); hit = file; break; } catch (e) { /* 换下一个根目录 */ }
    }
    if (!buf) { res.writeHead(404); return res.end(''); }
    res.writeHead(200, { 'Content-Type': MIME[path.extname(hit)] || 'application/octet-stream' });
    res.end(buf);
}

const json = (res, obj) => {
    res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
    res.end(JSON.stringify(obj));
};

const server = http.createServer((req, res) => {
    const pn = decodeURIComponent(new URL(req.url, 'http://127.0.0.1').pathname);
    if (pn === '/chat') return serveStatic(res, 'pages/chat.html');
    if (pn === '/chat/data') {
        return json(res, {
            sessions: SESSIONS,
            textConfigs: [{ id: 1, displayName: '本地模型', modelId: 'qwen' }],
            ttsConfigs: [], imageConfigs: []
        });
    }
    if (pn === '/api/chat/sessions/1/messages') return json(res, MESSAGES);
    /* 图片消息的 <img> 会 404，浏览器控制台有噪音但页面不报错 —— 换一个 1x1 的 png */
    if (pn === '/api/chat/media/probe-fake.png') {
        const png = Buffer.from(
            'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8AAAwAB/AH2H0ZQAAAAAElFTkSuQmCC', 'base64');
        res.writeHead(200, { 'Content-Type': 'image/png' });
        return res.end(png);
    }
    if (pn.indexOf('/api/') === 0) return json(res, {});
    return serveStatic(res, pn.replace(/^\/+/, ''));
});

/* ==================== Chrome / CDP ==================== */
const userDataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'chat-menu-probe-'));
const chrome = spawn(CHROME, [
    '--headless=new', '--no-sandbox', '--disable-gpu', '--hide-scrollbars', '--mute-audio',
    '--remote-debugging-port=' + CDP_PORT, '--user-data-dir=' + userDataDir, 'about:blank'
], { stdio: 'ignore' });

let ws, msgId = 0;
const pending = new Map();
const runtimeErrors = [];

function send(method, params = {}) {
    const id = ++msgId;
    return new Promise((res, rej) => { pending.set(id, { res, rej }); ws.send(JSON.stringify({ id, method, params })); });
}
async function evalJs(expression) {
    const r = await send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true });
    if (r.exceptionDetails) throw new Error('页面 JS 异常: ' + (r.exceptionDetails.exception?.description || r.exceptionDetails.text));
    return r.result.value;
}
async function waitFor(fn, timeout = 12000, step = 120) {
    const t0 = Date.now();
    while (Date.now() - t0 < timeout) { if (await fn()) return true; await sleep(step); }
    return false;
}
async function shot(name) {
    const r = await send('Page.captureScreenshot', { format: 'png' });
    fs.mkdirSync(SHOT_DIR, { recursive: true });
    fs.writeFileSync(path.join(SHOT_DIR, name + '.png'), Buffer.from(r.data, 'base64'));
}

let navSeq = 0;
async function goto(width = 1280, height = 900) {
    await send('Emulation.setDeviceMetricsOverride', { width, height, deviceScaleFactor: 1, mobile: false });
    await send('Page.navigate', { url: `http://127.0.0.1:${HTTP_PORT}/chat?_probe=` + (++navSeq) });
}

const waitSettled = () => waitFor(async () => {
    try {
        return (await evalJs(`(() => { const r = document.querySelector('.chat-shell');
            const d = r && window.Alpine ? window.Alpine.$data(r) : null;
            return !!d && d.booting === false && d.messages.length > 0; })()`)) === true;
    } catch (e) { return false; }
});

/** 真实鼠标点击（Alpine @click / @click.stop 需要真实事件流） */
async function clickAt(x, y) {
    await send('Input.dispatchMouseEvent', { type: 'mouseMoved', x, y, button: 'none', clickCount: 0 });
    await send('Input.dispatchMouseEvent', { type: 'mousePressed', x, y, button: 'left', clickCount: 1 });
    await send('Input.dispatchMouseEvent', { type: 'mouseReleased', x, y, button: 'left', clickCount: 1 });
}
async function clickSel(selector) {
    const box = await evalJs(`(() => {
        const el = document.querySelector(${JSON.stringify(selector)});
        if (!el) return null;
        el.scrollIntoView({ block: 'center' });
        const r = el.getBoundingClientRect();
        if (!r.width || !r.height) return null;
        return { x: r.left + r.width / 2, y: r.top + r.height / 2 };
    })()`);
    if (!box) throw new Error('点击目标不存在或不可见: ' + selector);
    await sleep(120);
    const pt = await evalJs(`(() => {
        const el = document.querySelector(${JSON.stringify(selector)});
        const r = el.getBoundingClientRect();
        return { x: r.left + r.width / 2, y: r.top + r.height / 2 };
    })()`);
    await clickAt(pt.x, pt.y);
}

/* 读页面现状：每条消息的气泡/图标位置、菜单状态、选区 */
const STATE = `(() => {
    const vis = (el) => !!el && el.getClientRects().length > 0 && getComputedStyle(el).display !== 'none';
    const rows = Array.from(document.querySelectorAll('.msg-line')).map((line, i) => {
        const bubble = line.querySelector('.msg-bubble');
        const btns = Array.from(line.querySelectorAll('.msg-more')).filter(vis);
        const b = bubble ? bubble.getBoundingClientRect() : null;
        const btn = btns[0] ? btns[0].getBoundingClientRect() : null;
        return {
            i,
            key: bubble ? bubble.getAttribute('data-msg-key') : null,
            btnCount: btns.length,
            isUser: !!bubble && bubble.classList.contains('bg-primary'),
            btnSide: (btn && b) ? (btn.left < b.left ? 'left' : 'right') : null,
            btnInBubble: (btn && b) ? !(btn.right <= b.left + 1 || btn.left >= b.right - 1) : null,
            text: bubble ? (bubble.querySelector('.msg-text') || {}).innerText || '' : ''
        };
    });
    const menu = document.querySelector('#msg-menu');
    const mr = menu ? menu.getBoundingClientRect() : null;
    const root = document.querySelector('.chat-shell');
    const st = (root && window.Alpine) ? window.Alpine.$data(root) : null;
    return {
        rows,
        menuVisible: vis(menu),
        menu: mr ? { left: Math.round(mr.left), top: Math.round(mr.top),
                     right: Math.round(mr.right), bottom: Math.round(mr.bottom) } : null,
        menuItems: Array.from(menu ? menu.querySelectorAll('button') : [])
            .map(b => (b.innerText || '').replace(/\\s+/g, ' ').trim()),
        menuState: st ? { open: st.msgMenu.open, key: st.msgMenu.key, text: st.msgMenu.text } : null,
        selection: (window.getSelection() || '').toString(),
        copied: window.__copied || null,
        vw: window.innerWidth, vh: window.innerHeight,
        docScrollW: document.documentElement.scrollWidth,
        docClientW: document.documentElement.clientWidth,
        containerScrollTop: (() => { const c = document.querySelector('[x-ref="messageContainer"]');
            return c ? Math.round(c.scrollTop) : -1; })()
    };
})()`;
const state = () => evalJs(STATE);

/* 菜单按钮：全选 = 第 1 个，复制 = 第 2 个 */
const MENU_SELECT = '#msg-menu button:nth-of-type(1)';
const MENU_COPY = '#msg-menu button:nth-of-type(2)';

/* 第 i 条消息（从 0 数）的「⋯」：.msg-line 之间没有可靠的 nth-of-type，
   统一走 querySelectorAll 的索引，避免「选择器命中另一个 div」这种假红。 */
async function clickMore(i) {
    const box = await evalJs(`(() => {
        const line = document.querySelectorAll('.msg-line')[${i}];
        if (!line) return null;
        const el = Array.from(line.querySelectorAll('.msg-more'))
            .filter(x => x.getClientRects().length > 0 && getComputedStyle(x).display !== 'none')[0];
        if (!el) return null;
        el.scrollIntoView({ block: 'center' });
        const r = el.getBoundingClientRect();
        if (!r.width || !r.height) return null;
        return { x: r.left + r.width / 2, y: r.top + r.height / 2 };
    })()`);
    if (!box) throw new Error('第 ' + i + ' 条消息的「⋯」不存在或不可见');
    await sleep(140);
    const pt = await evalJs(`(() => {
        const line = document.querySelectorAll('.msg-line')[${i}];
        const el = Array.from(line.querySelectorAll('.msg-more'))
            .filter(x => x.getClientRects().length > 0 && getComputedStyle(x).display !== 'none')[0];
        const r = el.getBoundingClientRect();
        return { x: r.left + r.width / 2, y: r.top + r.height / 2 };
    })()`);
    await clickAt(pt.x, pt.y);
}

async function main() {
    await new Promise((res) => server.listen(HTTP_PORT, '127.0.0.1', res));

    const resp = await fetch(`http://127.0.0.1:${CDP_PORT}/json/list`).then(r => r.json()).catch(() => null);
    let page = resp && resp.find(t => t.type === 'page');
    if (!page) {
        await waitFor(async () => {
            const l = await fetch(`http://127.0.0.1:${CDP_PORT}/json/list`).then(r => r.json()).catch(() => null);
            page = l && l.find(t => t.type === 'page');
            return !!page;
        }, 15000);
    }
    ws = new WebSocket(page.webSocketDebuggerUrl);
    await new Promise((res, rej) => { ws.onopen = res; ws.onerror = rej; });
    ws.onmessage = (ev) => {
        const m = JSON.parse(ev.data);
        if (m.id && pending.has(m.id)) {
            const p = pending.get(m.id);
            pending.delete(m.id);
            m.error ? p.rej(new Error(m.error.message)) : p.res(m.result);
            return;
        }
        if (m.method === 'Runtime.exceptionThrown') {
            runtimeErrors.push(m.params.exceptionDetails?.exception?.description
                || m.params.exceptionDetails?.text || 'unknown');
        }
    };

    await send('Page.enable');
    await send('Runtime.enable');
    /* 剪贴板：headless 下 writeText 需要权限/焦点，直接换成一个记录参数的桩，
       这样能比对「复制出去的到底是不是这条消息的原文」。 */
    await send('Page.addScriptToEvaluateOnNewDocument', {
        source: `
            window.__copied = null;
            try {
                Object.defineProperty(navigator, 'clipboard', {
                    configurable: true,
                    value: {
                        writeText: function (t) { window.__copied = String(t); return Promise.resolve(); },
                        readText: function () { return Promise.resolve(window.__copied || ''); }
                    }
                });
            } catch (e) { /* 覆写失败下面的用例会暴露 */ }
        `
    });

    /* ---------- T1 每条消息都有图标 ---------- */
    console.log('\n— T1 每条消息都有「⋯」 —');
    await goto();
    check('页面 boot 完成', (await waitSettled()) === true);
    await sleep(300);
    let s = await state();
    check('4 条消息都渲染出来（3 文本 + 1 图片）', s.rows.length === 4, 'rows=' + s.rows.length);
    check('【核心】每条消息都有且仅有一个可见的「⋯」', s.rows.every(r => r.btnCount === 1),
        s.rows.map(r => r.btnCount).join(','));
    check('用户消息的图标在气泡左侧、助手在右侧',
        s.rows[0].isUser && s.rows[0].btnSide === 'left' && s.rows[1].btnSide === 'right',
        s.rows.map(r => (r.isUser ? 'U' : 'A') + ':' + r.btnSide).join(' '));
    check('图标不压在气泡上（正文宽度不受影响）', s.rows.every(r => r.btnInBubble === false));
    /* 入口得让人找得到：靠 hover 才出现的图标在触屏上等于不存在（浏览器里也量不到） */
    const moreStyle = await evalJs(`(() => { const el = Array.from(document.querySelectorAll('.msg-more'))
        .filter(x => x.getClientRects().length > 0)[0];
        const cs = getComputedStyle(el); const r = el.getBoundingClientRect();
        return { opacity: +cs.opacity, w: Math.round(r.width), h: Math.round(r.height),
                 glyph: getComputedStyle(el.querySelector('i'), '::before').content }; })()`);
    check('图标常显且够点（不透明度 ≥ .6、点击区 ≥ 24px）',
        moreStyle.opacity >= 0.6 && moreStyle.w >= 24 && moreStyle.h >= 24, JSON.stringify(moreStyle));
    check('「⋯」字形真的渲染出来了（不是空格子）',
        !!moreStyle.glyph && moreStyle.glyph !== 'none' && moreStyle.glyph !== '""', moreStyle.glyph);
    await shot('menu-01-idle');

    /* ---------- T2 点图标弹菜单 ---------- */
    console.log('\n— T2 弹出菜单 —');
    await clickMore(1);
    await sleep(220);
    s = await state();
    check('【核心】点助手消息的「⋯」弹出菜单', s.menuVisible === true);
    check('菜单里有【全选】【复制】两项',
        s.menuItems.length === 2 && /全选/.test(s.menuItems[0]) && /复制/.test(s.menuItems[1]),
        s.menuItems.join(' | '));
    /* 图标名写错（bi-textarea-select 这种不存在的）只会渲染成一格空白，肉眼很难发现 */
    const menuGlyphs = await evalJs(`(() => Array.from(document.querySelectorAll('#msg-menu button > i'))
        .map(i => getComputedStyle(i, '::before').content))()`);
    check('菜单两项都有图标字形（没有空格子）',
        menuGlyphs.length === 2 && menuGlyphs.every(c => c && c !== 'none' && c !== '""'),
        JSON.stringify(menuGlyphs));
    check('菜单对准的是这一条消息', s.menuState.key === s.rows[1].key, s.menuState.key + ' vs ' + s.rows[1].key);
    check('菜单完整落在视口内',
        s.menu.left >= 0 && s.menu.top >= 0 && s.menu.right <= s.vw && s.menu.bottom <= s.vh,
        JSON.stringify(s.menu));
    /* 贴着图标：菜单左边缘在图标中点左侧、顶边在图标下沿附近 */
    /* 助手消息的图标在气泡右侧 ⇒ 菜单**右边缘**对齐图标右边缘（往左展开，不压气泡）；
       用户消息反之，左边缘对齐。贴错方向的话菜单会跑到视口边上或压住正文。 */
    const gap = await evalJs(`(() => {
        const line = document.querySelectorAll('.msg-line')[1];
        const b = Array.from(line.querySelectorAll('.msg-more'))
            .filter(x => x.getClientRects().length > 0 && getComputedStyle(x).display !== 'none')[0]
            .getBoundingClientRect();
        const m = document.querySelector('#msg-menu').getBoundingClientRect();
        return { leftGap: Math.round(m.left - b.left), rightGap: Math.round(m.right - b.right),
                 dy: Math.round(m.top - b.bottom) }; })()`);
    check('菜单贴着图标（右边缘对齐、纵向间距 0~16px）',
        Math.abs(gap.rightGap) <= 2 && gap.dy >= 0 && gap.dy <= 16, JSON.stringify(gap));
    await shot('menu-02-open');

    /* ---------- T3 全选 ---------- */
    console.log('\n— T3 全选 —');
    await clickSel(MENU_SELECT);
    await sleep(260);
    s = await state();
    check('点【全选】后菜单关闭', s.menuVisible === false);
    check('【核心】选中的就是这条消息的全文（含换行、不是首行截断）',
        s.selection === AI_TEXT.replace(/\n+$/, '') || s.selection.replace(/\s+/g, ' ') === AI_TEXT.replace(/\s+/g, ' '),
        'selLen=' + s.selection.length + ' expected=' + AI_TEXT.length);
    check('选区包含换行（说明是多段整体选中）', s.selection.indexOf('\n') > 0);
    await shot('menu-03-selected');

    /* ---------- T4 复制 ---------- */
    console.log('\n— T4 复制 —');
    await evalJs(`(() => { window.__copied = null; return true; })()`);
    await clickMore(0);
    await sleep(200);
    s = await state();
    check('用户消息也能弹菜单', s.menuVisible === true && s.menuState.key === s.rows[0].key);
    await clickSel(MENU_COPY);
    await sleep(260);
    s = await state();
    check('点【复制】后菜单关闭', s.menuVisible === false);
    check('【核心】剪贴板拿到的是这条用户消息的完整原文',
        s.copied === USER_TEXT, 'copiedLen=' + (s.copied ? s.copied.length : -1) + ' expected=' + USER_TEXT.length);
    /* 全选留下的 info toast 可能还没退场 ⇒ 不能只取第一个，把所有 toast 文案并起来找 */
    const toasts = await evalJs(`(() => Array.from(document.querySelectorAll('.sc-toast'))
        .map(t => (t.innerText || '').trim()).join(' | '))()`);
    check('复制后给了成功提示', toasts.indexOf('已复制') >= 0, toasts);

    /* ---------- T5 三种关闭方式 ---------- */
    console.log('\n— T5 关闭方式 —');
    await clickMore(2);
    await sleep(180);
    check('菜单已打开', (await state()).menuVisible === true);
    await clickAt(640, 40);   // 点消息区上方的空白
    await sleep(200);
    check('点外部关闭', (await state()).menuVisible === false);

    await clickMore(2);
    await sleep(180);
    await send('Input.dispatchKeyEvent', { type: 'keyDown', key: 'Escape', code: 'Escape', windowsVirtualKeyCode: 27 });
    await send('Input.dispatchKeyEvent', { type: 'keyUp', key: 'Escape', code: 'Escape', windowsVirtualKeyCode: 27 });
    await sleep(200);
    check('Esc 关闭', (await state()).menuVisible === false);

    await clickMore(2);
    await sleep(180);
    /* 内容不够长时 scrollTop 可能本来就是 0（设成 0 不触发 scroll 事件）⇒ 往有空间的方向滚 */
    const scrolled = await evalJs(`(() => {
        const c = document.querySelector('[x-ref="messageContainer"]');
        const before = Math.round(c.scrollTop);
        const room = c.scrollHeight - c.clientHeight;
        c.scrollTop = before > 0 ? 0 : Math.min(160, room);
        return { before, after: Math.round(c.scrollTop), room: Math.round(room) };
    })()`);
    await sleep(320);
    check('滚动确实发生了（不然这条是假绿）', scrolled.before !== scrolled.after, JSON.stringify(scrolled));
    check('滚消息区关闭（浮层不会停在半空对着错的位置）', (await state()).menuVisible === false);

    /* ---------- T6 最后一条（贴底）向上翻转 ---------- */
    console.log('\n— T6 贴底消息 —');
    await evalJs(`(() => { const c = document.querySelector('[x-ref="messageContainer"]'); c.scrollTop = c.scrollHeight; return true; })()`);
    await sleep(240);
    await clickMore(3);
    await sleep(260);
    s = await state();
    check('最后一条消息的菜单也打开了', s.menuVisible === true, 'key=' + (s.menuState && s.menuState.key));
    check('【核心】菜单没有超出视口下边（贴底时向上翻转）',
        s.menu.bottom <= s.vh - 6, `bottom=${s.menu.bottom} vh=${s.vh}`);
    check('图片消息也能弹菜单（内容是提示词）', s.menuState.text === AI_IMG_TEXT, 'text=' + s.menuState.text);
    await shot('menu-04-bottom');

    /* ---------- T7 换一条：目标跟着换 ---------- */
    console.log('\n— T7 切换目标 —');
    await clickMore(0);
    await sleep(240);
    s = await state();
    check('【核心】直接点另一条的图标：菜单换目标而不是被关掉',
        s.menuVisible === true && s.menuState.key === s.rows[0].key,
        'key=' + (s.menuState && s.menuState.key) + ' visible=' + s.menuVisible);
    await evalJs(`(() => { window.__copied = null; return true; })()`);
    await clickSel(MENU_COPY);
    await sleep(240);
    s = await state();
    check('复制到的是新目标（不是上一条残留）', s.copied === USER_TEXT,
        'copiedLen=' + (s.copied ? s.copied.length : -1));

    /* ---------- T8 320px ---------- */
    console.log('\n— T8 320px 窄屏 —');
    await goto(320, 568);
    await waitSettled();
    await sleep(320);
    s = await state();
    check('窄屏无横向溢出', s.docScrollW <= s.docClientW + 1, `scrollW=${s.docScrollW} clientW=${s.docClientW}`);
    check('窄屏每条消息仍有图标、气泡仍不被压', s.rows.every(r => r.btnCount === 1 && r.btnInBubble === false),
        s.rows.map(r => r.btnCount + '/' + r.btnInBubble).join(' '));
    await clickMore(1);
    await sleep(260);
    s = await state();
    check('窄屏菜单可见且不出界',
        s.menuVisible === true && s.menu.left >= 0 && s.menu.right <= s.vw && s.menu.bottom <= s.vh,
        JSON.stringify(s.menu) + ' vw=' + s.vw + ' vh=' + s.vh);
    await shot('menu-05-mobile');
    await clickSel(MENU_COPY);
    await sleep(220);
    check('窄屏复制同样拿到完整原文', (await state()).copied === AI_TEXT);

    /* ---------- T9 全程无异常 ---------- */
    console.log('\n— T9 全程 —');
    check('无 JS 运行时异常', runtimeErrors.length === 0, runtimeErrors.slice(0, 2).join(' | '));

    const failed = results.filter(x => !x.ok).length;
    console.log(`\n===== ${results.length - failed}/${results.length} 通过 =====`);
    if (failed) console.log('失败项：\n' + results.filter(x => !x.ok).map(x => ' - ' + x.name).join('\n'));

    try { await send('Browser.close'); } catch (e) {}
    try { chrome.kill(); } catch (e) {}
    try { server.close(); } catch (e) {}
    process.exit(failed ? 1 : 0);
}

main().catch(async (e) => {
    console.error('PROBE FAILED: ' + e.message);
    try { chrome.kill(); } catch (err) {}
    try { server.close(); } catch (err) {}
    process.exit(2);
});
