#!/usr/bin/env node
/**
 * 探针：俄罗斯方块（从 ../BlockBlast/index.html 迁入 /learn/block-blast）。
 *
 * 验什么：
 *   1. /learn 卡片墙上出现了「俄罗斯方块」这张卡，卡面图标是**内联多色方块 SVG**
 *      （6 组渐变 / 21 个格子 / 零外链 / 96×80 真渲染出来），点卡片进得去；
 *   2. 游戏页真的跑起来了：8×8 棋盘铺满 64 格、托盘发到 3 个方块
 *      （HTML 骨架里 #board 与三个 slot 都是空的 ⇒ 数得出来就等于脚本跑完了）；
 *   3. 整页自包含：**零外部请求**、零资源加载失败、零未捕获 JS 错误 —— 游戏页没有
 *      Bootstrap / 站内导航，全部是内联样式与脚本，迁进来不该带回任何 CDN；
 *   4. 设置菜单里那一项是【返回】，点它跳回 /learn（原版是「退出游戏」：
 *      electronAPI.quit() / Capacitor 退后台 / window.close()+alert，浏览器里全不成立）；
 *   5. 桌面 1280 与手机 390 两种视口下棋盘都不溢出。
 *
 * 为什么要拦导航：静态页与样式表都打进 jar，改完 HTML/CSS 不重启服务就看不到新东西
 * （/learn/block-blast 这条路由本身也是新加的，旧 jar 直接 404）。这里把
 * /learn、/learn/block-blast 两个导航响应与 /css/app.css 换成本地文件，
 * 其余请求照旧走真实后端。**重启打包之后**可以 WM_NO_INTERCEPT=1 直接验线上页。
 *
 * 用法：node scripts/probe_block_blast.mjs              （需 managed Node 22：有全局 WebSocket）
 *       WM_NO_INTERCEPT=1 node scripts/probe_block_blast.mjs
 *       WM_OUT=/tmp/xxx node scripts/probe_block_blast.mjs   自定义截图目录
 */
import { spawn } from 'node:child_process';
// 静音守门：--mute-audio + 注入 src/test/resources/silent-audio.js（管住平台 TTS）
import { SILENT_AUDIO_FLAGS, installSilentAudio } from './lib/silent-audio.mjs';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const LOCAL = {
    [new URL('http://x/learn/block-blast').pathname]: path.join(ROOT, 'src/main/resources/static/pages/learn-block-blast.html'),
    [new URL('http://x/learn').pathname]: path.join(ROOT, 'src/main/resources/static/pages/learn.html'),
    // 卡片墙的规格都在 app.css 的第 9 节，样式表不一起换成本地文件就永远量到"没样式"的裸 DOM
    [new URL('http://x/css/app.css').pathname]: path.join(ROOT, 'src/main/resources/static/css/app.css'),
};

/** 被拦截文件的 Content-Type：CSS 要按 text/css 发，否则浏览器按 HTML 解析、样式照样不生效 */
const MIME = (file) => file.endsWith('.css') ? 'text/css; charset=utf-8' : 'text/html; charset=utf-8';
const BASE = process.env.WM_BASE || 'http://localhost:1888';
const PAGE = BASE + '/learn/block-blast';
const LEARN = BASE + '/learn';
const CHROME = process.env.STORY_BROWSER_PATH
    || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const PORT = 9347;
const OUT = process.env.WM_OUT || '/tmp/bb-probe';
const NO_INTERCEPT = process.env.WM_NO_INTERCEPT === '1';

fs.mkdirSync(OUT, { recursive: true });
const userDataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'bb-'));

const results = [];
const check = (name, ok, extra = '') => {
    results.push({ name, ok: !!ok });
    console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${extra ? '  [' + extra + ']' : ''}`);
};

const chrome = spawn(CHROME, [
    '--headless=new', '--no-sandbox', '--disable-gpu', '--hide-scrollbars',
    ...SILENT_AUDIO_FLAGS,            // ① 浏览器音频管线静音（媒体元素 + Web Audio）
    '--remote-debugging-port=' + PORT, '--user-data-dir=' + userDataDir, 'about:blank'
], { stdio: 'ignore' });

const sleep = (ms) => new Promise(r => setTimeout(r, ms));
let ws, msgId = 0;
const pending = new Map();
const pageErrors = [];
const external = [];
const failures = [];

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
async function shot(name) {
    const r = await send('Page.captureScreenshot', { format: 'png' });
    const file = path.join(OUT, name + '.png');
    fs.writeFileSync(file, Buffer.from(r.data, 'base64'));
    console.log('  截图 ' + file);
    return file;
}
/** 轮询直到页面里的条件成立（导航过程中求值会抛错，一律当「还没好」处理） */
async function waitFor(predicate, tries = 80, gap = 150) {
    for (let i = 0; i < tries; i++) {
        try { if (await evalJs(predicate)) return true; } catch (e) { /* 导航中 */ }
        await sleep(gap);
    }
    return false;
}
/** 导航并等条件成立 */
async function nav(url, predicate, tries = 80, gap = 150) {
    await send('Page.navigate', { url });
    return waitFor(predicate, tries, gap);
}

/* ---------- 页面读数 ---------- */
const READ_GAME = `(() => {
    const board = document.getElementById('board');
    const slots = [0,1,2].map(i => document.getElementById('slot-' + i));
    const cells = board ? board.querySelectorAll('.cell').length : 0;
    const pieces = slots.map(s => s ? s.querySelectorAll('.piece-hitarea').length : 0);
    const r = board ? board.getBoundingClientRect() : null;
    const btn = document.getElementById('settings-btn');
    const br = btn ? btn.getBoundingClientRect() : null;
    return {
        cells,
        filled: board ? board.querySelectorAll('.cell.filled').length : 0,
        pieces,
        score: (document.getElementById('score') || {}).textContent,
        hs: (document.getElementById('highscore-val') || {}).textContent,
        board: r ? { x: Math.round(r.x), y: Math.round(r.y),
                     w: Math.round(r.width), h: Math.round(r.height) } : null,
        settings: br ? { x: Math.round(br.x + br.width / 2), y: Math.round(br.y + br.height / 2) } : null,
        vw: window.innerWidth, vh: window.innerHeight,
        overflowX: document.documentElement.scrollWidth > window.innerWidth + 1,
        title: document.title,
        keys: Object.keys(localStorage)
    };
})()`;

const READ_MENU = `(() => {
    const menu = document.getElementById('settings-menu');
    const items = Array.from(menu.querySelectorAll('.menu-item')).map(el => ({
        id: el.id,
        label: el.querySelector('.menu-item-label').textContent.trim(),
        icon: el.querySelector('.menu-item-icon').textContent.trim()
    }));
    const r = menu.getBoundingClientRect();
    return { open: menu.classList.contains('open'), items,
             box: { x: Math.round(r.x), y: Math.round(r.y),
                    w: Math.round(r.width), h: Math.round(r.height) } };
})()`;

async function main() {
    for (let i = 0; i < 80; i++) {
        try {
            const ver = await (await fetch(`http://127.0.0.1:${PORT}/json/version`)).json();
            if (ver && ver['Browser']) break;
        } catch (e) { /* 还没起来 */ }
        await sleep(250);
    }
    const target = await (await fetch(`http://127.0.0.1:${PORT}/json/new?about:blank`,
        { method: 'PUT' })).json();
    ws = new WebSocket(target.webSocketDebuggerUrl);
    await new Promise((res, rej) => { ws.onopen = res; ws.onerror = rej; });
    ws.onmessage = (ev) => {
        const m = JSON.parse(ev.data);
        if (m.method === 'Fetch.requestPaused') {
            const { requestId, request } = m.params;
            const local = LOCAL[new URL(request.url).pathname];
            if (!NO_INTERCEPT && local) {
                send('Fetch.fulfillRequest', {
                    requestId, responseCode: 200,
                    responseHeaders: [{ name: 'Content-Type', value: MIME(local) }],
                    body: Buffer.from(fs.readFileSync(local, 'utf8'), 'utf8').toString('base64')
                });
            } else {
                send('Fetch.continueRequest', { requestId });
            }
            return;
        }
        if (m.method === 'Runtime.exceptionThrown') {
            pageErrors.push(m.params.exceptionDetails.exception?.description
                || m.params.exceptionDetails.text);
            return;
        }
        if (m.method === 'Network.requestWillBeSent') {
            const u = m.params.request.url;
            if (!u.startsWith(BASE) && !u.startsWith('data:') && !u.startsWith('blob:')) external.push(u);
            return;
        }
        if (m.method === 'Network.loadingFailed') {
            failures.push(m.params.errorText || 'unknown');
            return;
        }
        if (m.id && pending.has(m.id)) {
            const p = pending.get(m.id);
            pending.delete(m.id);
            m.error ? p.rej(new Error(JSON.stringify(m.error))) : p.res(m.result);
        }
    };

    // ② 平台 TTS 管不到就去注入守门脚本（addScriptToEvaluateOnNewDocument，对每次导航都生效）
    await installSilentAudio(send);
    await send('Runtime.enable');
    await send('Network.enable');
    if (!NO_INTERCEPT) {
        await send('Fetch.enable', { patterns: Object.keys(LOCAL).map(p => ({ urlPattern: BASE + p, requestStage: 'Request' })) });
    }
    await send('Emulation.setDeviceMetricsOverride',
        { width: 1280, height: 900, deviceScaleFactor: 1, mobile: false });

    console.log(NO_INTERCEPT
        ? '— 直接验线上页（未拦截）—'
        : `— 已把 ${Object.keys(LOCAL).join(' 与 ')} 的导航响应替换为本地文件 —`);

    /* 卡片墙图标规格（app.css .sc-learn-icon）：桌面字形 5rem / 色块 7rem，
       手机档字形 3.75rem / 色块 5.5rem。「样式表已生效」的前置阈值全部由这几个常量推出来 ——
       改尺寸只改这里，别再写 30 / 60~120 那种魔数（尺寸一改，这些守门值就静默失真）。
       ⚠️ waitFor/evalJs 的入参是在**页面里**求值的字符串，Node 侧常量只能靠模板插值注入
       （直接写 rem2px(...) 会在页面里 ReferenceError，静默返回 false 变成假红）。 */
    const ICON_REM = 5, CHIP_REM = 7;
    const M_ICON_REM = 3.75, M_CHIP_REM = 5.5;
    const rem2px = (rem) => rem * 16;

        /* ---------- 1. /learn 卡片墙：三张卡共用一套规格 ---------- */
    const learnOk = await nav(LEARN, `document.querySelectorAll('main .card').length >= 3`);
    check('/learn 卡片墙渲染出 3 张卡', learnOk,
        `${await evalJs(`document.querySelectorAll('main .card').length`)} 张`);
    // 必须等样式表生效再量尺寸：裸 DOM 下 .sc-learn-icon 是块级 div，宽度会等于容器宽（假绿），
    // 所以判据取「字体号 2.5rem + 有圆角 + 是个 84px 左右的正方形」。
    const styled = await waitFor(`(() => {
        const el = document.querySelector('main .card .sc-learn-icon');
        if (!el) return false;
        const cs = getComputedStyle(el);
        const r = el.getBoundingClientRect();
        return parseFloat(cs.fontSize) >= ${rem2px(ICON_REM) - 4}
            && parseFloat(cs.borderTopLeftRadius) > 0
            && r.width >= ${rem2px(CHIP_REM) - 6} && r.width <= ${rem2px(CHIP_REM) + 6}
            && Math.abs(r.width - r.height) < 1;
    })()`, 60, 100);
    check('/learn 样式表已生效（图标色块量得到尺寸，后面的读数才有意义）', styled);

    const READ_WALL = `(() => {
        return Array.from(document.querySelectorAll('main .card')).map(c => {
            const icon = c.querySelector('.sc-learn-icon');
            const svg = icon ? icon.querySelector('svg.wm-bb-icon') : null;
            const glyphs = icon ? Array.from(icon.querySelectorAll('i')) : [];
            const ics = icon ? getComputedStyle(icon) : null;
            const ir = icon ? icon.getBoundingClientRect() : null;
            const t = c.querySelector('.card-title');
            const tr = t ? t.getBoundingClientRect() : null;
            const desc = c.querySelector('.card-text');
            const dr = desc ? desc.getBoundingClientRect() : null;
            // 数真实 line box（别拿容器高度除行高）：按 top 分组，把同一行的 rect 宽度加起来
            const dlines = (() => {
                if (!desc) return null;
                const rg = document.createRange();
                rg.selectNodeContents(desc);
                const byLine = new Map();
                Array.from(rg.getClientRects())
                    .filter(q => q.width > 0.5 && q.height > 0.5)
                    .forEach(q => {
                        const k = Math.round(q.top);
                        byLine.set(k, (byLine.get(k) || 0) + q.width);
                    });
                return Array.from(byLine.values()).map(w => +w.toFixed(1));
            })();
            const btn = c.querySelector('.sc-learn-actions .btn');
            const bcs = btn ? getComputedStyle(btn) : null;
            const br = btn ? btn.getBoundingClientRect() : null;
            const gears = Array.from(c.querySelectorAll('.sc-learn-extra'));
            const cr = c.getBoundingClientRect();
            const gr0 = gears.length ? gears[0].getBoundingClientRect() : null;
            const gcs = gears.length ? getComputedStyle(gears[0]) : null;
            return {
                cardX: Math.round(cr.x),
                cardCx: Math.round(cr.x + cr.width / 2),
                accent: c.getAttribute('data-accent'),
                cls: c.className,
                title: t ? t.textContent.trim() : '',
                tag: c.tagName.toLowerCase(),
                onclick: c.getAttribute('onclick') || '',
                iconBox: ir ? { w: +ir.width.toFixed(1), h: +ir.height.toFixed(1),
                                x: Math.round(ir.x), cx: Math.round(ir.x + ir.width / 2),
                                radius: ics.borderRadius,
                                fs: ics.fontSize, color: ics.color, bg: ics.backgroundColor,
                                border: ics.borderTopColor } : null,
                glyph: glyphs.length === 1 ? glyphs[0].className : (glyphs.length ? 'multiple' : null),
                hasSvg: !!svg,
                titleY: tr ? Math.round(tr.top) : -1,
                descH: dr ? +dr.height.toFixed(1) : -1,
                descLines: dlines,
                btnCls: btn ? btn.className : null,
                btn: br ? { w: +br.width.toFixed(1), h: +br.height.toFixed(1),
                            x: Math.round(br.x), bottom: Math.round(br.bottom),
                            bg: bcs.backgroundColor, fg: bcs.color } : null,
                extras: gears.length,
                extraTitle: gears.length ? (gears[0].getAttribute('title') || '') : '',
                extra: gr0 ? { pos: gcs.position, z: gcs.zIndex,
                               w: +gr0.width.toFixed(1), h: +gr0.height.toFixed(1),
                               inCard: gr0.left >= cr.left - 0.5 && gr0.right <= cr.right + 0.5
                                       && gr0.top >= cr.top - 0.5 && gr0.bottom <= cr.bottom + 0.5,
                               topRight: (gr0.left + gr0.width / 2) > (cr.left + cr.width / 2)
                                         && (gr0.top + gr0.height / 2) < (cr.top + cr.height / 2),
                               inActions: !!gears[0].closest('.sc-learn-actions'),
                               insetTop: Math.round(gr0.top - cr.top),
                               insetRight: Math.round(cr.right - gr0.right) } : null,
            };
        });
    })()`;

    const wall = await evalJs(READ_WALL);
    const flat = (s) => (s || '').replace(/\s/g, '');
    const ACCENT = { blue: 'rgb(13,110,253)', green: 'rgb(25,135,84)', violet: 'rgb(111,66,193)' };

    check('/learn 三张卡都挂了站内卡片样式（hover 上浮）与教学卡类、整卡可点',
        wall.length === 3 && wall.every(c => /(^|\s)sc-card(\s|$)/.test(c.cls)
            && /\bsc-learn-card\b/.test(c.cls) && c.tag === 'div'
            && /window\.location\.href='\/learn\//.test(c.onclick)),
        wall.map(c => c.accent + ':' + c.title).join(' | '));

    const ib = wall.map(c => c.iconBox);
    check('三张卡的图标色块规格一模一样（同宽同高同圆角同字号、都是正方形、在各自卡里居中）',
        ib.every(b => b && Math.abs(b.w - ib[0].w) < 0.5 && Math.abs(b.h - ib[0].h) < 0.5
            && b.radius === ib[0].radius && b.fs === ib[0].fs
            && Math.abs(b.w - b.h) < 0.5 && b.w > 60)
        && wall.every(c => Math.abs(c.iconBox.cx - c.cardCx) <= 1),
        ib.map((b, i) => b && `${b.w}×${b.h} r=${b.radius} fs=${b.fs}/偏心${b.cx - wall[i].cardCx}`).join(' | '));

    check('每张卡恰好一个图标，且都装进同一个色块容器里（不再三种画风各写各的）',
        wall.every(c => c.iconBox && ((c.glyph ? 1 : 0) + (c.hasSvg ? 1 : 0) === 1)),
        wall.map(c => c.title + ':' + (c.hasSvg ? 'svg' : c.glyph)).join(' | '));

    const bgRgb = (s) => (s.match(/\d+/g) || []).map(Number);
    check('色块是各卡主题色的浅底 + 同色描边 + 同色图标（实心字形，视觉重量对齐）',
        wall.every(c => {
            const b = c.iconBox;
            const bg = bgRgb(b.bg);
            return b && flat(b.color) === ACCENT[c.accent]
                && bg.length === 3 && bg.every(v => v >= 225)
                && flat(b.border) !== flat(b.bg);
        }),
        wall.map(c => `${c.accent}:图标${flat(c.iconBox.color)} 底${flat(c.iconBox.bg)}`).join(' | '));

    // 三张卡在三个列里，"左边线对齐"要按各自卡框算内缩，不能跨列直接比 x
    const insets = wall.map(c => c.btn.x - c.cardX);
    check('三张卡的按钮同宽同高、距卡左边界的内缩一致、底边严格对齐（齿轮脱流后按钮不再一张宽一张窄）',
        wall.every(c => c.btn) && wall[0].btn && wall[1].btn && wall[2].btn
        && Math.max(...wall.map(c => c.btn.h)) - Math.min(...wall.map(c => c.btn.h)) < 0.5
        && Math.max(...wall.map(c => c.btn.w)) - Math.min(...wall.map(c => c.btn.w)) < 0.5
        && Math.max(...insets) - Math.min(...insets) <= 1
        && Math.max(...wall.map(c => c.btn.bottom)) - Math.min(...wall.map(c => c.btn.bottom)) <= 1,
        wall.map((c, i) => `${c.btn.w}×${c.btn.h}/内缩${insets[i]}/底${c.btn.bottom}`).join(' | '));

    check('按钮不再混用 btn-primary/success/warning，而是同一款式 + 各卡主题色（白字）',
        wall.every(c => c.btnCls && /(^|\s)btn(\s|$)/.test(c.btnCls)
            && !/btn-(primary|success|warning|danger|info|secondary|light|dark)/.test(c.btnCls)
            && flat(c.btn.bg) === ACCENT[c.accent] && flat(c.btn.fg) === 'rgb(255,255,255)'),
        wall.map(c => `[${c.btnCls} ${flat(c.btn.bg)}/${flat(c.btn.fg)}]`).join(' | '));

    check('三张卡的说明文字同字号同高度（都控制在两行内，按钮基线才不飘）',
        wall.every(c => c.descH > 0)
        && Math.max(...wall.map(c => c.descH)) - Math.min(...wall.map(c => c.descH)) < 1,
        wall.map(c => c.descH + 'px').join(' | '));

    const dlines = wall.map(c => c.descLines || []);
    check('三张卡的说明文字都恰好排成两行（数的是真实 line box，不是拿容器高度除行高）',
        dlines.every(l => l.length === 2),
        dlines.map(l => `${l.length} 行 [${l.join(' + ')}]`).join(' | '));
    // 对照读数：把 text-wrap 打回 auto 再量一遍，证明"均衡"是这条 CSS 挣来的、不是本来就齐
    const autoLines = await evalJs(`(() => {
        const read = () => Array.from(document.querySelectorAll('main .card .card-text')).map(el => {
            const rg = document.createRange();
            rg.selectNodeContents(el);
            const byLine = new Map();
            Array.from(rg.getClientRects())
                .filter(q => q.width > 0.5 && q.height > 0.5)
                .forEach(q => {
                    const k = Math.round(q.top);
                    byLine.set(k, (byLine.get(k) || 0) + q.width);
                });
            return Array.from(byLine.values()).map(w => +w.toFixed(1));
        });
        const st = document.createElement('style');
        st.textContent = 'main .card .card-text{text-wrap:auto !important}';
        document.head.appendChild(st);
        const off = read();
        st.remove();
        return off;
    })()`);
    const pct = (l) => l.length > 1 ? (Math.min(...l) / Math.max(...l) * 100).toFixed(0) + '%' : '—';
    check('两行长度均衡：短行 ≥ 长行的 70%（默认断行会把第二行挤成孤字）',
        dlines.every(l => l.length === 2 && Math.min(...l) >= Math.max(...l) * 0.7),
        dlines.map((l, i) => (l.length === 2
            ? `${l[0]}/${l[1]} → ${pct(l)}（text-wrap:auto 时 ${(autoLines[i] || []).join('+')} → ${pct(autoLines[i] || [])}）`
            : l.length + ' 行')).join(' | '));

    check('三张卡的标题落在同一水平线（色块等高是硬保证）',
        Math.max(...wall.map(c => c.titleY)) - Math.min(...wall.map(c => c.titleY)) <= 1,
        wall.map(c => c.titleY).join(' | '));

    const head = await evalJs(`(() => {
        const h = document.querySelector('main .sc-page-title');
        return h ? { tag: h.tagName.toLowerCase(), text: h.textContent.trim() } : null;
    })()`);
    check('页头与站内其它页统一（h2.sc-page-title + 图标）',
        !!head && head.tag === 'h2' && /教学模块/.test(head.text),
        head ? head.tag + ' ' + head.text : 'null');

    check('只有卡1 带音频设置齿轮（在卡片右上角），卡2/卡3 不带',
        wall[0].extras === 1 && wall[0].extraTitle === '音频设置'
        && wall[1].extras === 0 && wall[2].extras === 0,
        wall.map(c => c.title + ':' + c.extras).join(' | '));

    check('齿轮是绝对定位 + z-index 的浮层（脱离文档流），浮在卡片右上角且在卡内不溢出',
        !!wall[0].extra && wall[0].extra.pos === 'absolute'
        && /^\d+$/.test(wall[0].extra.z) && Number(wall[0].extra.z) > 0
        && wall[0].extra.topRight && wall[0].extra.inCard
        && wall[0].extra.insetTop >= 4 && wall[0].extra.insetTop <= 24
        && wall[0].extra.insetRight >= 4 && wall[0].extra.insetRight <= 24,
        wall[0].extra ? JSON.stringify(wall[0].extra) : 'null');

    check('齿轮不在动作区里（脱流 ⇒ 不占位，三卡排版长度才一致）',
        wall.every(c => !c.extra || !c.extra.inActions),
        wall.map(c => `${c.title}:${c.extra ? c.extra.inActions : '-'}`).join(' | '));

    const card = await evalJs(`(() => {
        const el = Array.from(document.querySelectorAll('main .card')).find(c =>
            c.querySelector('.card-title') && c.querySelector('.card-title').textContent.includes('俄罗斯方块'));
        if (!el) return null;
        const a = el.querySelector('.sc-learn-actions .btn');
        // 图标容器是 .sc-learn-icon；注意别用 el.querySelector('i') ——
        // 那会抓到按钮里的 ▶（bi-play-circle），上一版就是这么假红的。
        const art = el.querySelector('.sc-learn-icon');
        const svg = art ? art.querySelector('svg.wm-bb-icon') : null;
        const strayIcon = art ? art.querySelector('i') : null;
        let badHref = 0, uses = 0, internalUses = 0;
        if (svg) {
            svg.querySelectorAll('*').forEach(e => {
                const h = e.getAttribute('href') || e.getAttribute('xlink:href');
                if (h && !h.startsWith('#')) badHref++;
            });
            svg.querySelectorAll('use').forEach(u => {
                uses++;
                if ((u.getAttribute('href') || '').startsWith('#')) internalUses++;
            });
        }
        const r = svg ? svg.getBoundingClientRect() : null;
        return { title: el.querySelector('.card-title').textContent.trim(),
                 desc: el.querySelector('.card-text').textContent.trim(),
                 href: a ? a.getAttribute('href') : null,
                 btn: a ? a.textContent.trim() : null,
                 icon: strayIcon ? strayIcon.className : null,
                 onclick: el.getAttribute('onclick'),
                 svg: svg ? {
                     w: +r.width.toFixed(1), h: +r.height.toFixed(1),
                     grads: svg.querySelectorAll('linearGradient').length,
                     blocks: uses, internalUses, badHref,
                     aria: svg.getAttribute('aria-label') || '',
                     viewBox: svg.getAttribute('viewBox') || '',
                 } : null };
    })()`);
    check('/learn 上有「俄罗斯方块」卡片，按钮与卡片都指向 /learn/block-blast',
        !!card && card.href === '/learn/block-blast'
        && /\/learn\/block-blast/.test(card.onclick || ''),
        card ? `${card.title} · ${card.btn} → ${card.href}` : 'null');
    check('卡片图标改用内联 SVG（图标区里不再有 bootstrap-icons 的三宫格）',
        !!card && !!card.svg && !card.icon,
        card ? `svg=${!!card.svg} 残留 i=${card.icon}` : 'null');
    check('图标是多色宝石方块：≥5 组渐变、≥12 个方块格子，且格子全引用内部图形',
        !!card && !!card.svg && card.svg.grads >= 5 && card.svg.blocks >= 12
        && card.svg.internalUses === card.svg.blocks,
        card && card.svg ? `${card.svg.grads} 组渐变 / ${card.svg.blocks} 格` : 'null');
    check('图标零外部引用（无 http 资源、无外链 href），自带 aria-label 与 viewBox',
        !!card && !!card.svg && card.svg.badHref === 0
        && card.svg.aria.length > 0 && /^0 0 [\d.]+ [\d.]+$/.test(card.svg.viewBox),
        card && card.svg ? `${card.svg.viewBox} · ${card.svg.aria} · 外链 ${card.svg.badHref}` : 'null');
    check('SVG 图标与隔壁两张卡的 bootstrap-icons 同号（都是 5rem=80px 字形，正方形）',
        !!card && !!card.svg
        && Math.abs(card.svg.w - rem2px(ICON_REM)) <= 1 && Math.abs(card.svg.h - card.svg.w) < 0.5,
        card && card.svg ? `${card.svg.w}×${card.svg.h}（期望 ${rem2px(ICON_REM)}）` : 'null');
    await shot('40-learn-card-wall');

    /* ---------- 2. 点卡片进游戏 ---------- */
    const entered = await nav(PAGE, `!!document.getElementById('board') && document.querySelectorAll('#board .cell').length === 64`);
    check('点卡片进入游戏页，8×8 棋盘铺满 64 格（骨架里 #board 是空的）', entered);
    let g = await evalJs(READ_GAME);
    check('托盘发满 3 个方块（#slot-0/1/2 里的 .piece-hitarea 由脚本生成）',
        g.pieces.length === 3 && g.pieces.every(n => n === 1), 'pieces=' + JSON.stringify(g.pieces));
    check('新局棋盘是空的（0 个已填格）', g.filled === 0, 'filled=' + g.filled);
    check('分数 / 最高分已由脚本写进 DOM', g.score === '0' && g.hs === '0', `score=${g.score} hs=${g.hs}`);
    check('页面标题是本站口径', g.title === '俄罗斯方块 - AI故事创作', g.title);
    check('存档 key 仍是 blockblast_* 前缀（不与词库同源串数据）',
        g.keys.every(k => k.startsWith('blockblast_')), g.keys.join(',') || '（暂无）');
    check('棋盘完整落在视口内、无横向溢出',
        g.board && g.board.x >= 0 && g.board.x + g.board.w <= g.vw + 1 && !g.overflowX,
        g.board ? `board x=${g.board.x} w=${g.board.w} vw=${g.vw} overflowX=${g.overflowX}` : 'null');
    await shot('41-game-desktop');

    /* ---------- 3. 设置菜单 ---------- */
    await evalJs(`document.getElementById('settings-btn').click()`);
    await sleep(260);
    const menu = await evalJs(READ_MENU);
    check('点设置按钮展开菜单', menu.open, 'open=' + menu.open);
    const ids = menu.items.map(i => i.id);
    check('菜单项顺序未变（刷新 / 全屏 / 重新开始 / 返回）',
        JSON.stringify(ids) === JSON.stringify(['menu-refresh', 'menu-fullscreen', 'menu-newgame', 'menu-quit']),
        ids.join(','));
    const back = menu.items.find(i => i.id === 'menu-quit');
    check('第 4 项已改成【返回】（原版是「退出游戏」）',
        !!back && back.label === '返回', back ? back.icon + ' ' + back.label : 'null');
    check('菜单里不再有「退出游戏」', menu.items.every(i => i.label !== '退出游戏'),
        menu.items.map(i => i.label).join(' '));
    check('菜单不超出视口',
        menu.box.x >= 0 && menu.box.y >= 0
        && menu.box.x + menu.box.w <= 1280 + 1 && menu.box.y + menu.box.h <= 900 + 1,
        `box=${JSON.stringify(menu.box)}`);
    await shot('42-game-settings-menu');

    /* ---------- 4. 点【返回】真的回到 /learn ---------- */
    await evalJs(`document.getElementById('menu-quit').click()`);
    const backOk = await waitFor(`location.pathname === '/learn'`, 40, 150);
    check('点【返回】跳回上一级 /learn（本站唯一新增的交互）', backOk,
        await evalJs(`location.pathname`));

    /* ---------- 5. 手机视口不溢出 ---------- */
    await send('Emulation.setDeviceMetricsOverride',
        { width: 390, height: 844, deviceScaleFactor: 2, mobile: true });
    await nav(PAGE, `document.querySelectorAll('#board .cell').length === 64`);
    await sleep(300);
    const m = await evalJs(READ_GAME);
    check('手机 390×844：棋盘不溢出、无横向滚动',
        !!m.board && m.board.x >= 0 && m.board.x + m.board.w <= m.vw + 1 && !m.overflowX,
        m.board ? `board x=${m.board.x} w=${m.board.w} vw=${m.vw} overflowX=${m.overflowX}` : 'null');
    const tray = await evalJs(`(() => {
        const t = document.getElementById('pieces-tray');
        const r = t.getBoundingClientRect();
        return { x: Math.round(r.x), w: Math.round(r.width),
                 n: t.querySelectorAll('.piece-hitarea').length };
    })()`);
    check('手机视口下托盘也在屏幕上，3 个方块齐全',
        tray.x >= 0 && tray.x + tray.w <= m.vw + 1 && tray.n === 3,
        `tray x=${tray.x} w=${tray.w} n=${tray.n}`);
    await shot('43-game-mobile');

        /* ---------- 5b. 手机视口下的卡片墙（色块整体缩一档，不缩没、不溢出） ---------- */
    await nav(LEARN, `document.querySelectorAll('main .card svg.wm-bb-icon').length === 1`);
    await waitFor(`(() => {
        const el = document.querySelector('main .card .sc-learn-icon');
        if (!el) return false;
        const r = el.getBoundingClientRect();
        return parseFloat(getComputedStyle(el).fontSize) >= ${rem2px(M_ICON_REM) - 4}
            && r.width >= ${rem2px(M_CHIP_REM) - 6} && r.width <= ${rem2px(M_CHIP_REM) + 6}
            && Math.abs(r.width - r.height) < 1;
    })()`, 40, 100);
    const mob = await evalJs(`(() => {
        const svg = document.querySelector('main .card svg.wm-bb-icon');
        const r = svg.getBoundingClientRect();
        const boxes = Array.from(document.querySelectorAll('main .card .sc-learn-icon')).map(b => {
            const q = b.getBoundingClientRect();
            return { w: +q.width.toFixed(1), h: +q.height.toFixed(1) };
        });
        const cards = Array.from(document.querySelectorAll('main .card'));
        const dls = Array.from(document.querySelectorAll('main .card .card-text')).map(el => {
            const rg = document.createRange();
            rg.selectNodeContents(el);
            const byLine = new Map();
            Array.from(rg.getClientRects()).filter(q => q.width > 0.5 && q.height > 0.5)
                .forEach(q => {
                    const k = Math.round(q.top);
                    byLine.set(k, (byLine.get(k) || 0) + q.width);
                });
            return Array.from(byLine.values()).map(w => +w.toFixed(1));
        });
        const gearEl = document.querySelector('.sc-learn-extra');
        const gBox = gearEl ? gearEl.getBoundingClientRect() : null;
        const gCard = gearEl ? gearEl.closest('main .card').getBoundingClientRect() : null;
        return { w: +r.width.toFixed(1), h: +r.height.toFixed(1), boxes, descLines: dls,
                 gear: gBox ? { w: +gBox.width.toFixed(1),
                                inside: gBox.left >= gCard.left - 0.5 && gBox.right <= gCard.right + 0.5,
                                belowCardTop: gBox.top >= gCard.top - 0.5 } : null,
                 cardRightMax: Math.round(Math.max(...cards.map(c => c.getBoundingClientRect().right))),
                 vw: window.innerWidth,
                 overflowX: document.documentElement.scrollWidth > window.innerWidth + 1 };
    })()`);
    check('手机 390：卡片图标仍完整可见（≥50px 字形）、是正方形，三张卡色块同尺寸',
        mob.w >= rem2px(M_ICON_REM) - 10 && Math.abs(mob.h / mob.w - 1) < 0.05
        && mob.boxes.length === 3
        && mob.boxes.every(b => Math.abs(b.w - mob.boxes[0].w) < 0.5 && Math.abs(b.h - b.w) < 0.5),
        `svg ${mob.w}×${mob.h}，色块 ${mob.boxes.map(b => b.w + '×' + b.h).join('/')}`);
    check('手机 390：卡片墙不溢出、无横向滚动',
        !mob.overflowX && mob.cardRightMax <= mob.vw + 1,
        `卡右边界 ${mob.cardRightMax} / vw ${mob.vw} 溢出=${mob.overflowX}`);
    check('手机 390：三卡说明文字仍是两行且长度均衡（窄屏更防孤字）',
        mob.descLines.length === 3
        && mob.descLines.every(l => l.length === 2 && Math.min(...l) >= Math.max(...l) * 0.7),
        mob.descLines.map(l => l.join('+')).join(' | '));
    check('手机 390：右上角齿轮仍在卡内、且不压到卡片顶边之外',
        !!mob.gear && mob.gear.inside && mob.gear.belowCardTop && mob.gear.w >= 30,
        JSON.stringify(mob.gear));
    await shot('44-learn-mobile');

    /* ---------- 6. 信号 ---------- */
    check('无外部资源请求（不引任何 CDN）', external.length === 0, external.join(' | ') || '零外部请求');
    check('无资源加载失败', failures.length === 0, failures.join(' | ') || '无');
    check('无未捕获的运行时 JS 错误', pageErrors.length === 0, pageErrors.join(' | ') || '无');

    ws.close();
    chrome.kill();

    const bad = results.filter(r => !r.ok);
    console.log(`\n===== ${results.length - bad.length}/${results.length} 通过 =====`);
    if (bad.length) {
        bad.forEach(r => console.log('  FAIL ' + r.name));
        process.exit(1);
    }
    console.log('截图目录：' + OUT);
}

main().catch((e) => {
    console.error('探针异常：', e);
    try { chrome.kill(); } catch (_) { /* ignore */ }
    process.exit(1);
});
