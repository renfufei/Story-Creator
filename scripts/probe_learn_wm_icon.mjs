#!/usr/bin/env node
/**
 * 探针：/learn 卡片墙上「英语单词匹配」那张卡的图标 —— 从 bootstrap-icons 的 bi-translate
 * 换成了与 favicon 同源的 A/B 双色积木内联 SVG。
 *
 * 验什么：
 *   1. 那张卡的图标是 `svg.wm-ab-icon`（不是 <i class="bi bi-translate"> 的老图标），
 *      且 SVG 里就是 favicon 那对积木（2 个 <g>、橙 #f0954f / 青 #5ab5a8、无 <text>、零外链）；
 *   2. **真的画出来了**：80×80（等于 .sc-learn-icon 的 5rem 字形）、正方形、在 7rem 色块里居中、
 *      不溢出、不压标题 —— 并且落到**截图像素**上核对烘焙色（只信 getComputedStyle 会漏掉
 *      「量得对但画面没重绘」这种假绿）；
 *   3. 点卡片真的进得去 /learn/word-match（内联 SVG 会不会把整卡点击吃掉，只有真点才知道）；
 *   4. 390px 手机档：色块缩到 5.5rem、图标跟着缩到 60×60，卡片墙不横向溢出；
 *   5. 全页零外部请求、零未捕获 JS 错误（内联 SVG 不该带回任何 CDN 依赖）。
 *
 * 为什么要拦导航：静态页与 CSS 都打进 jar，改完不重启服务就看不到新东西。
 * 这里把 /learn 与 /css/app.css 的响应换成本地文件，其余请求照旧走真实后端；
 * 打包重启后可以 WM_NO_INTERCEPT=1 直接验线上页。
 *
 * 用法：node scripts/probe_learn_wm_icon.mjs
 *       WM_NO_INTERCEPT=1 node scripts/probe_learn_wm_icon.mjs
 *       WM_OUT=/tmp/xxx node scripts/probe_learn_wm_icon.mjs
 *   需要 managed Node 22（全局 WebSocket）；像素核对走 managed Python 的 PIL（PY_BIN 可改）。
 */
import { spawn, execFileSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { SILENT_AUDIO_FLAGS, installSilentAudio } from './lib/silent-audio.mjs';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const LOCAL = {
    [new URL('http://x/learn').pathname]: path.join(ROOT, 'src/main/resources/static/pages/learn.html'),
    [new URL('http://x/css/app.css').pathname]: path.join(ROOT, 'src/main/resources/static/css/app.css'),
};
const MIME = (f) => f.endsWith('.css') ? 'text/css; charset=utf-8' : 'text/html; charset=utf-8';
const BASE = process.env.WM_BASE || 'http://localhost:1888';
const CHROME = process.env.STORY_BROWSER_PATH
    || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const PY_BIN = process.env.PY_BIN
    || '/Users/renfufei/.workbuddy/binaries/python/envs/default/bin/python';
const PORT = Number(process.env.WM_CDP_PORT || 9348);
const OUT = process.env.WM_OUT || '/tmp/wm-icon-probe';
const NO_INTERCEPT = process.env.WM_NO_INTERCEPT === '1';

fs.mkdirSync(OUT, { recursive: true });
const userDataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'wmi-'));

const results = [];
const check = (name, ok, extra = '') => {
    results.push({ name, ok: !!ok });
    console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${extra ? '  [' + extra + ']' : ''}`);
};

const chrome = spawn(CHROME, [
    '--headless=new', '--no-sandbox', '--disable-gpu', '--hide-scrollbars',
    ...SILENT_AUDIO_FLAGS,
    '--remote-debugging-port=' + PORT, '--user-data-dir=' + userDataDir, 'about:blank'
], { stdio: 'ignore' });

const sleep = (ms) => new Promise(r => setTimeout(r, ms));
let ws, msgId = 0;
const pending = new Map();
const pageErrors = [];
const external = [];

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
async function waitFor(predicate, tries = 80, gap = 150) {
    for (let i = 0; i < tries; i++) {
        try { if (await evalJs(predicate)) return true; } catch (e) { /* 导航中 */ }
        await sleep(gap);
    }
    return false;
}
async function nav(url, predicate, tries = 80, gap = 150) {
    await send('Page.navigate', { url });
    return waitFor(predicate, tries, gap);
}
/* 只截某块区域的图：先滚到顶再在同一帧里量 rect，然后让**浏览器**按 rect 裁（clip）。
   为什么不让 PIL 截大图再裁：getBoundingClientRect 是视口坐标，而 captureScreenshot
   在页面被滚动过时按文档坐标出图，两者差一个 scrollY —— 这次就差了 57px，
   橙块被裁掉一截、青块整块落到框外，看着像「图标没画出来」。 */
async function shotRegion(name, jsPick) {
    const r = await evalJs(`(async () => {
        window.scrollTo(0, 0);
        await new Promise(res => requestAnimationFrame(() => requestAnimationFrame(res)));
        const el = ${jsPick};
        const b = el.getBoundingClientRect();
        return { x: b.x, y: b.y, w: b.width, h: b.height, scrollY: window.scrollY };
    })()`);
    const s = await send('Page.captureScreenshot', {
        format: 'png',
        clip: { x: r.x, y: r.y, width: r.w, height: r.h, scale: 1 },
        captureBeyondViewport: false
    });
    const file = path.join(OUT, name + '.png');
    fs.writeFileSync(file, Buffer.from(s.data, 'base64'));
    console.log(`  截图 ${file}（${Math.round(r.w)}×${Math.round(r.h)} @ ${Math.round(r.x)},${Math.round(r.y)}）`);
    return { file, box: r };
}

/** 在落盘 PNG 的指定区域内数「接近某色」的像素个数（PIL）。区域坐标是 CSS px，按 dsf 换算。
 *  顺手回报每种色**在裁剪框内**的包围盒：bbox 贴到框边上就说明图被裁掉一截。 */
function countPixels(png, box, colors, dsf) {
    const py = `
import json, sys
from PIL import Image
png, x, y, w, h, dsf, colors = sys.argv[1], *map(float, sys.argv[2:7]), json.loads(sys.argv[7])
im = Image.open(png).convert('RGB')
box = (int(x*dsf), int(y*dsf), int((x+w)*dsf), int((y+h)*dsf))
crop = im.crop(box)
W, H = crop.size
px = crop.load()
out = {'crop': list(box), 'size': [W, H]}
for name, rgb in colors.items():
    n = 0; bx = [W, H, -1, -1]
    for yy in range(H):
        for xx in range(W):
            p = px[xx, yy]
            if abs(p[0]-rgb[0]) <= 10 and abs(p[1]-rgb[1]) <= 10 and abs(p[2]-rgb[2]) <= 10:
                n += 1
                if xx < bx[0]: bx[0] = xx
                if yy < bx[1]: bx[1] = yy
                if xx > bx[2]: bx[2] = xx
                if yy > bx[3]: bx[3] = yy
    out[name] = n
    out[name + '_bbox'] = bx if n else None
print(json.dumps(out))
`;
    try {
        const s = execFileSync(PY_BIN, ['-c', py, png, String(box.x), String(box.y),
            String(box.w), String(box.h), String(dsf), JSON.stringify(colors)],
            { encoding: 'utf8', timeout: 60000 });
        return JSON.parse(s.trim().split('\n').pop());
    } catch (e) {
        return null;
    }
}

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
        if (m.id && pending.has(m.id)) {
            const p = pending.get(m.id);
            pending.delete(m.id);
            m.error ? p.rej(new Error(JSON.stringify(m.error))) : p.res(m.result);
        }
    };
    await installSilentAudio(send);
    await send('Runtime.enable');
    await send('Network.enable');
    await send('Page.enable');
    await send('Fetch.enable', { patterns: [{ urlPattern: '*' }] });
    await send('Emulation.setDeviceMetricsOverride',
        { width: 1280, height: 1000, deviceScaleFactor: 1, mobile: false });

    /* ---------- 1. 桌面：卡片墙上的图标 ---------- */
    const ok = await nav(BASE + '/learn', `document.querySelectorAll('main .card').length >= 3`);
    check('/learn 卡片墙打开了', ok,
        `${await evalJs(`document.querySelectorAll('main .card').length`)} 张卡`);

    /* 样式表生效的守门值由 app.css 的常量推出来：色块 7rem / 字形 5rem。
       裸 DOM 下量得到尺寸会假绿，所以同时要求字号与圆角都对。 */
    const styled = await waitFor(`(() => {
        const el = document.querySelector('.sc-learn-icon');
        if (!el) return false;
        const cs = getComputedStyle(el), r = el.getBoundingClientRect();
        return parseFloat(cs.fontSize) >= 76 && parseFloat(cs.borderTopLeftRadius) > 0
            && Math.abs(r.width - 112) < 6 && Math.abs(r.width - r.height) < 1;
    })()`, 60, 100);
    check('/learn 样式表已生效（色块 112px / 字号 80px 量得到）', styled);

    const READ = `(() => {
        const card = Array.from(document.querySelectorAll('main .card'))
            .find(c => (c.querySelector('.card-title') || {}).textContent === '英语单词匹配');
        if (!card) return { found: false };
        const art = card.querySelector('.sc-learn-icon');
        const svg = art.querySelector('svg.wm-ab-icon');
        const ar = art.getBoundingClientRect();
        const sr = svg ? svg.getBoundingClientRect() : null;
        const ti = card.querySelector('.card-title');
        const tr = ti.getBoundingClientRect();
        const html = svg ? svg.outerHTML : '';
        const gs = svg ? Array.from(svg.querySelectorAll('g')) : [];
        const rects = svg ? Array.from(svg.querySelectorAll('rect')) : [];
        return {
            found: true,
            hasSvg: !!svg,
            oldGlyphs: Array.from(art.querySelectorAll('i')).map(e => e.className),
            art: { x: ar.x, y: ar.y, w: +ar.width.toFixed(1), h: +ar.height.toFixed(1),
                   radius: getComputedStyle(art).borderRadius,
                   fs: getComputedStyle(art).fontSize },
            svg: sr ? { x: +sr.x.toFixed(1), y: +sr.y.toFixed(1),
                        w: +sr.width.toFixed(1), h: +sr.height.toFixed(1),
                        display: getComputedStyle(svg).display,
                        vb: svg.getAttribute('viewBox'),
                        viewBoxOk: (() => { const b = svg.viewBox.baseVal;
                            return b && b.width > 0 && b.height > 0; })(),
                        aria: svg.getAttribute('aria-label') || '',
                        groups: gs.length,
                        rects: rects.map(r => r.getAttribute('fill')),
                        texts: svg.querySelectorAll('text').length,
                        ext: (html.match(/(?:href|src)\\s*=\\s*["']https?:/gi) || []).length,
                        bytes: new TextEncoder().encode(html).length } : null,
            titleTop: +tr.top.toFixed(1),
            cardTop: +card.getBoundingClientRect().top.toFixed(1)
        };
    })()`;
    const d = await evalJs(READ);

    check('「英语单词匹配」这张卡在卡片墙上', d.found === true);
    check('图标已换成内联 SVG（老的 bi-translate 字形不再出现在色块里）',
        d.hasSvg === true && (d.oldGlyphs || []).length === 0,
        d.hasSvg ? 'svg.wm-ab-icon' : '没有 svg，遗留字形：' + (d.oldGlyphs || []).join(','));
    check('SVG 里就是 favicon 那对 A/B 积木（2 组 <g>、橙 + 青两块、无 <text>、零外链）',
        d.svg && d.svg.groups === 2 && d.svg.texts === 0 && d.svg.ext === 0
            && d.svg.rects.join(',') === '#f0954f,#5ab5a8',
        d.svg ? `${d.svg.groups} 组 / ${d.svg.rects.join('+')} / text=${d.svg.texts} / 外链=${d.svg.ext}` : '');
    check('图标自带 viewBox 与 aria-label（可无级缩放、读屏能念）',
        d.svg && d.svg.viewBoxOk && d.svg.aria.length > 0,
        d.svg ? `${d.svg.vb} · ${d.svg.aria}` : '');
    check('图标与隔壁卡的 bootstrap-icons 同号：80×80 正方形（= .sc-learn-icon 的 5rem 字形）',
        d.svg && Math.abs(d.svg.w - 80) < 1.5 && Math.abs(d.svg.h - 80) < 1.5
            && Math.abs(d.svg.w - d.svg.h) < 0.5,
        d.svg ? `${d.svg.w}×${d.svg.h} · display=${d.svg.display}` : '');
    check('图标居中在 7rem 色块里、不越界、不压到标题',
        d.svg && Math.abs((d.svg.x + d.svg.w / 2) - (d.art.x + d.art.w / 2)) <= 1
            && Math.abs((d.svg.y + d.svg.h / 2) - (d.art.y + d.art.h / 2)) <= 1
            && d.svg.x >= d.art.x - 0.5 && d.svg.x + d.svg.w <= d.art.x + d.art.w + 0.5
            && d.svg.y >= d.art.y - 0.5 && d.svg.y + d.svg.h <= d.art.y + d.art.h + 0.5
            && d.titleTop > d.art.y + d.art.h,
        d.svg ? `色块 ${d.art.w}×${d.art.h} r=${d.art.radius} · 图标偏 ${(d.svg.x - d.art.x).toFixed(1)},${(d.svg.y - d.art.y).toFixed(1)}` : '');

    /* 像素核对：把色块区域裁下来数烘焙色。svg 元素量得再对，也可能是「没重绘/被盖住」的假绿。 */
    const TILE = `Array.from(document.querySelectorAll('main .card'))
        .find(c => (c.querySelector('.card-title') || {}).textContent === '英语单词匹配')
        .querySelector('.sc-learn-icon')`;
    const picked = await shotRegion('learn-card-icon', TILE);
    const px = countPixels(picked.file, { x: 0, y: 0, w: picked.box.w, h: picked.box.h },
        { orange: [240, 149, 79], teal: [90, 181, 168] }, 1);
    const bb = px && `橙 bbox ${(px.orange_bbox || []).join(',')} / 青 bbox ${(px.teal_bbox || []).join(',')}`;
    check('截图像素里同时数得到橙 #f0954f 与青 #5ab5a8（真的画出来了，不是空 SVG）',
        px && px.orange > 150 && px.teal > 150,
        px ? `橙 ${px.orange} / 青 ${px.teal} 像素 · ${bb}` : 'PIL 不可用或裁图失败');
    const side = px && (px.orange + px.teal);
    /* 两块 24/52 见方的积木落在 112px 色块里，理论占比 ~16%；给到 8%~45% 就是
       「既不是几个像素（假绿）、也没铺满色块（画大了）」。 */
    check('两块积木在色块里的占比合理（8%~45%，太小发虚、太大顶格）',
        !!side && picked.box.w > 0 && side / (picked.box.w * picked.box.h) > 0.08
            && side / (picked.box.w * picked.box.h) < 0.45,
        side ? `${(100 * side / (picked.box.w * picked.box.h)).toFixed(1)}%` : '');
    /* 青块被裁到框边上 = 图根本没截全，上面两个数字就不可信了 */
    const insetOk = px && px.orange_bbox && px.teal_bbox
        && [px.orange_bbox, px.teal_bbox].every(b =>
            b[0] > 0 && b[1] > 0 && b[2] < picked.box.w - 1 && b[3] < picked.box.h - 1);
    check('两块积木都完整落在截图框内（没有一行贴边 ⇒ 图没裁歪）', insetOk, insetOk ? bb : bb);

    /* ---------- 2. 真点整卡：内联 SVG 不能把卡片的点击吃掉 ---------- */
    const pt = await evalJs(`(() => {
        const card = Array.from(document.querySelectorAll('main .card'))
            .find(c => (c.querySelector('.card-title') || {}).textContent === '英语单词匹配');
        const r = card.querySelector('svg.wm-ab-icon').getBoundingClientRect();
        return { x: Math.round(r.x + r.width / 2), y: Math.round(r.y + r.height / 2) };
    })()`);
    const mp = { x: pt.x, y: pt.y, button: 'left', buttons: 1, clickCount: 1, pointerType: 'mouse' };
    await send('Input.dispatchMouseEvent', { type: 'mousePressed', ...mp });
    await send('Input.dispatchMouseEvent', { type: 'mouseReleased', ...mp });
    const arrived = await waitFor(`location.pathname === '/learn/word-match'`, 60, 150);
    check('点图标（整卡）能进 /learn/word-match', arrived);
    if (arrived) {
        /* pathname 一变就求值会撞上「新文档还没解析完」—— 等词库页的 Alpine 根挂上再读 */
        const ready = await waitFor(`!!document.querySelector('.wm-root') && document.title.length > 0`,
            60, 150);
        const t = await evalJs(`({ title: document.title,
            root: !!document.querySelector('.wm-root') })`);
        check('进去的是单词匹配页（标题对、Alpine 根已挂上）',
            ready && /英语单词匹配/.test(t.title) && t.root, `${t.title} · root=${t.root}`);
    }

    /* ---------- 3. 手机 390：色块 5.5rem、图标 60×60、不溢出 ---------- */
    await send('Emulation.setDeviceMetricsOverride',
        { width: 390, height: 844, deviceScaleFactor: 2, mobile: true });
    await nav(BASE + '/learn', `document.querySelector('main .card svg.wm-ab-icon') !== null`);
    await sleep(400);
    const m = await evalJs(`(() => {
        const svg = document.querySelector('main .card svg.wm-ab-icon');
        const art = svg.closest('.sc-learn-icon');
        const sr = svg.getBoundingClientRect(), ar = art.getBoundingClientRect();
        return {
            svg: { w: +sr.width.toFixed(1), h: +sr.height.toFixed(1) },
            art: { w: +ar.width.toFixed(1), h: +ar.height.toFixed(1) },
            sw: document.documentElement.scrollWidth,
            cw: document.documentElement.clientWidth
        };
    })()`);
    check('手机 390：图标跟着色块缩一档（60×60 落在 88px 色块里，仍是正方形）',
        Math.abs(m.svg.w - 60) < 1.5 && Math.abs(m.svg.h - 60) < 1.5
            && Math.abs(m.svg.w - m.svg.h) < 0.5 && Math.abs(m.art.w - 88) < 6,
        `图标 ${m.svg.w}×${m.svg.h} / 色块 ${m.art.w}×${m.art.h}`);
    check('手机 390：卡片墙不横向溢出', m.sw <= m.cw + 1, `${m.sw} <= ${m.cw}`);
    await shot('learn-card-icon-mobile');

    check('全页零外部请求（内联 SVG 没带回任何 CDN 依赖）', external.length === 0,
        external.slice(0, 4).join(' | ') || '零外部请求');
    check('无未捕获的运行时 JS 错误', pageErrors.length === 0,
        pageErrors.slice(0, 3).join(' | ') || '无');
}

let ok = false;
try {
    console.log(NO_INTERCEPT
        ? '— 直接验线上页（未拦截）—'
        : `— 已把 /learn 与 /css/app.css 的响应替换为本地文件 —`);
    await main();
    const failed = results.filter(r => !r.ok);
    console.log(`\n===== ${results.length - failed.length}/${results.length} 通过 =====`);
    failed.forEach(f => console.log('  FAILED: ' + f.name));
    ok = failed.length === 0;
} catch (e) {
    console.error('执行异常：' + (e && e.stack || e));
} finally {
    try { ws && ws.close(); } catch (e) { /* ignore */ }
    try { chrome.kill('SIGKILL'); } catch (e) { /* ignore */ }
    try { fs.rmSync(userDataDir, { recursive: true, force: true, maxRetries: 5 }); } catch (e) { /* ignore */ }
}
process.exit(ok ? 0 : 1);
