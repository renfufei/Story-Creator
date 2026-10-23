#!/usr/bin/env node
/**
 * 无头导出「英语单词匹配」单页 HTML，并对产物做一次**真机验收**。
 *
 * 为什么要有它：页面的导出入口是 `buildStandaloneWordMatch()`（设置 → 导出 → 导出 HTML），
 * 只能靠浏览器里点按钮落盘。要把产物发布成网站，就该用**同一条路径**生成，
 * 而不是另写一段「服务端拼 HTML」—— 那是第二份真相，早晚和页面跑偏。
 *
 * 做两件事：
 *   1) 真点按钮导出（全选所有册次，含按需加载的四六级），复制成 `<out>/index.html`；
 *   2) 起一个本地 HTTP 服务打开它，确认「零资源外链 + 能开局能配对」——
 *      发布出去的链接是 http，不是 file://，所以要按 http 验。
 *
 * 用法：
 *   node scripts/learn/export_word_match_html.mjs                 # → publish/word-match
 *   node scripts/learn/export_word_match_html.mjs --out /tmp/x    # 换输出目录
 *   WM_BASE=http://localhost:1888 可换源站
 */
import { spawn } from 'node:child_process';
import http from 'node:http';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { SILENT_AUDIO_FLAGS, installSilentAudio } from '../lib/silent-audio.mjs';

const BASE = process.env.WM_BASE || 'http://localhost:1888';
const PAGE = BASE + '/learn/word-match';
const CHROME = process.env.STORY_BROWSER_PATH
    || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const CDP_PORT = Number(process.env.WM_CDP_PORT || 9346);
const SHOT_DIR = process.env.WM_OUT || '/tmp/wm-publish-shots';

/* 输出目录：默认仓库内 publish/word-match（已 gitignore） */
const argOut = (() => {
    const i = process.argv.indexOf('--out');
    return i > 0 ? process.argv[i + 1] : null;
})();
const OUT_DIR = path.resolve(argOut || path.join(process.cwd(), 'publish', 'word-match'));

const results = [];
const check = (name, ok, extra = '') => {
    results.push({ name, ok: !!ok });
    console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${extra ? '  [' + extra + ']' : ''}`);
};

fs.mkdirSync(SHOT_DIR, { recursive: true });
fs.mkdirSync(OUT_DIR, { recursive: true });
const userDataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'wm-pub-'));
const downloadDir = fs.mkdtempSync(path.join(os.tmpdir(), 'wm-pub-dl-'));

const sleep = (ms) => new Promise(r => setTimeout(r, ms));

let chrome = null, ws = null, msgId = 0;
const pending = new Map();
const pageErrors = [];
const netExternal = [];        // 产物页面在 http 下发出的**站外**请求（应为 0）
let trackNet = false;

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
    fs.writeFileSync(path.join(SHOT_DIR, name + '.png'), Buffer.from(r.data, 'base64'));
}
async function centerOf(selector, text = null) {
    return evalJs(`(() => {
        let els = Array.from(document.querySelectorAll(${JSON.stringify(selector)}))
            .filter(e => e.getClientRects().length > 0);
        ${text ? `els = els.filter(e => e.innerText.trim().includes(${JSON.stringify(text)}));` : ''}
        const el = els[0];
        if (!el) return null;
        const r = el.getBoundingClientRect();
        return { x: Math.round(r.x + r.width / 2), y: Math.round(r.y + r.height / 2) };
    })()`);
}
async function clickSel(selector, text = null) {
    const box = await centerOf(selector, text);
    if (!box) throw new Error('找不到可点元素：' + selector + (text ? ' text=' + text : ''));
    const p = { x: box.x, y: box.y, button: 'left', buttons: 1, clickCount: 1, pointerType: 'mouse' };
    await send('Input.dispatchMouseEvent', { type: 'mousePressed', ...p });
    await send('Input.dispatchMouseEvent', { type: 'mouseReleased', ...p });
    return box;
}

async function startChrome() {
    chrome = spawn(CHROME, [
        '--headless=new', '--no-sandbox', '--disable-gpu', '--hide-scrollbars',
        ...SILENT_AUDIO_FLAGS,
        '--remote-debugging-port=' + CDP_PORT, '--user-data-dir=' + userDataDir, 'about:blank'
    ], { stdio: 'ignore' });

    let ver = null;
    for (let i = 0; i < 80; i++) {
        try {
            ver = await (await fetch(`http://127.0.0.1:${CDP_PORT}/json/version`)).json();
            if (ver && ver['Browser']) break;
        } catch (e) { /* 还没起来 */ }
        await sleep(250);
    }
    if (!ver || !ver['Browser']) throw new Error('Chrome 没起来（端口 ' + CDP_PORT + '）');

    const target = await (await fetch(`http://127.0.0.1:${CDP_PORT}/json/new?about:blank`,
        { method: 'PUT' })).json();
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
            pageErrors.push(m.params.exceptionDetails.exception?.description
                || m.params.exceptionDetails.text);
        }
        if (m.method === 'Network.requestWillBeSent') {
            const url = m.params.request.url || '';
            if (trackNet && /^https?:/i.test(url)
                && !/^http:\/\/(127\.0\.0\.1|localhost):/i.test(url)) {
                netExternal.push(url);
            }
        }
    };

    await installSilentAudio(send);
    await send('Runtime.enable');
    await send('Network.enable');
    await send('Emulation.setDeviceMetricsOverride', {
        width: 1280, height: 940, deviceScaleFactor: 1, mobile: false
    });
    await send('Browser.setDownloadBehavior',
        { behavior: 'allow', downloadPath: downloadDir, eventsEnabled: true });
}

/** ① 走真实 UI 导出：设置 → 导出 → 全选 → 导出 HTML */
async function exportFromUi() {
    await send('Page.navigate', { url: PAGE });
    await sleep(900);
    await evalJs(`localStorage.setItem('word_match_sound_v1', '0')`);

    for (let i = 0; i < 60; i++) {
        const n = await evalJs(`document.querySelectorAll('.wm-col-en .wm-card').length`);
        if (n >= 3) break;
        await sleep(150);
    }

    await clickSel('.wm-set-btn');
    await sleep(220);
    await clickSel('.wm-set-item.is-export');
    await sleep(450);

    /* 大学两册的关卡是按需拉的；不先 load 完，产物里那两册会是空的（静默残缺） */
    await evalJs(`(() => {
        const d = Alpine.$data(document.querySelector('.wm-root'));
        return d.ensureCet().then(() => true);
    })()`);
    await sleep(250);

    await evalJs(`(() => {
        const d = Alpine.$data(document.querySelector('.wm-root'));
        d.exportToggleAll(true);
        return true;
    })()`);
    await sleep(300);

    /* 改完必须**另起一次求值**再读 DOM：Alpine 重渲染是异步批处理的 */
    const sel = await evalJs(`(() => {
        const d = Alpine.$data(document.querySelector('.wm-root'));
        return {
            n: d.exportSelCount, total: d.books.length,
            levels: d.exportLevelCount, words: d.exportWordCount,
            sum: (document.querySelector('.wm-export-sum') || {}).textContent || '',
            stages: (d.bookGroups || []).map(g => g.stage + ':' + g.books.length).join(',')
        };
    })()`);
    console.log(`  册次：${sel.n}/${sel.total} · ${sel.levels} 关 · ${sel.words} 词`);
    console.log(`  学段：${sel.stages}`);
    console.log(`  汇总条：${sel.sum.trim()}`);
    check('全选按钮把每一册都勾上了', sel.n > 0 && sel.n === sel.total, `${sel.n}/${sel.total}`);
    await shot('pub-01-export-dialog');

    const before = new Set(fs.readdirSync(downloadDir));
    await clickSel('#wm-export-go');

    let outFile = null;
    for (let i = 0; i < 150; i++) {
        await sleep(300);
        const fresh = fs.readdirSync(downloadDir).filter(f => !before.has(f) && !f.endsWith('.crdownload'));
        if (fresh.length) {
            const p = path.join(downloadDir, fresh[0]);
            await sleep(250);
            const s1 = fs.statSync(p).size;
            await sleep(250);
            if (fs.statSync(p).size === s1 && s1 > 1000) { outFile = p; break; }
        }
    }
    if (!outFile) throw new Error('导出没有落盘（按钮没点中或导出报错）');
    return { file: outFile, sel };
}

/** ② 起本地静态服务，按「发布后」的 http 场景验收产物 */
function serveStatic(dir) {
    const MIME = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
        '.css': 'text/css; charset=utf-8', '.png': 'image/png', '.ico': 'image/x-icon' };
    const server = http.createServer((req, res) => {
        const rel = decodeURIComponent((req.url || '/').split('?')[0]);
        const file = path.join(dir, rel === '/' ? 'index.html' : rel.replace(/^\/+/, ''));
        if (!file.startsWith(dir) || !fs.existsSync(file) || fs.statSync(file).isDirectory()) {
            res.writeHead(404, { 'content-type': 'text/plain; charset=utf-8' });
            res.end('not found');
            return;
        }
        res.writeHead(200, { 'content-type': MIME[path.extname(file)] || 'application/octet-stream' });
        fs.createReadStream(file).pipe(res);
    });
    return new Promise(res => server.listen(0, '127.0.0.1', () => res(server)));
}

async function verifyStandalone(server, expect) {
    const port = server.address().port;
    const url = `http://127.0.0.1:${port}/`;
    trackNet = true;
    netExternal.length = 0;
    pageErrors.length = 0;

    await send('Page.navigate', { url });
    await sleep(1200);

    let cards = 0;
    for (let i = 0; i < 80; i++) {
        cards = await evalJs(`document.querySelectorAll('.wm-col-en .wm-card').length`);
        if (cards >= 3) break;
        await sleep(150);
    }

    const st = await evalJs(`(() => {
        const d = Alpine.$data(document.querySelector('.wm-root'));
        const data = window.__WORD_MATCH_DATA__ || {};
        const books = data.books || [];
        let levels = 0, words = 0;
        books.forEach(b => {
            const arr = (data.levels || {})[b.id] || [];
            levels += arr.length;
            arr.forEach(l => { words += (l.pairs || []).length; });
        });
        return {
            standalone: !!window.__WM_STANDALONE__,
            title: document.title,
            books: books.length,
            stages: Array.from(new Set(books.map(b => b.stage))),
            levels, words,
            view: d.view,
            booting: d.booting,
            bookLabel: (books.find(b => b.id === d.bookId) || {}).label || '',
            navGone: !document.getElementById('site-nav'),
            en: Array.from(document.querySelectorAll('.wm-col-en .wm-card')).length,
            zh: Array.from(document.querySelectorAll('.wm-col-zh .wm-card')).length
        };
    })()`);

    check('产物是单机件（__WM_STANDALONE__ = true）', st.standalone === true);
    check('标题带「英语单词匹配」', /英语单词匹配/.test(st.title || ''), st.title);
    check('打开就直接落棋盘，不是检索页', st.view === 'play', st.view);
    check('骨架已收（booting=false）', st.booting === false, String(st.booting));
    check('站点导航已摘掉（单机件没有站内页可去）', st.navGone === true);
    check('册数与导出时一致', st.books === expect.sel.n, `${st.books} vs ${expect.sel.n}`);
    check('关卡数与导出时一致', st.levels === expect.sel.levels, `${st.levels} vs ${expect.sel.levels}`);
    check('词数与导出时一致', st.words === expect.sel.words, `${st.words} vs ${expect.sel.words}`);
    check('学段齐全（含专题/大学）', st.stages.length >= 4, st.stages.join(','));
    check('棋盘两列都铺出来了', st.en >= 3 && st.zh >= 3, `en=${st.en} zh=${st.zh}`);
    await shot('pub-02-standalone-home');

    /* 真点一配对，确认不是「渲染得出来但玩不了」 */
    const played = await evalJs(`(() => {
        const d = Alpine.$data(document.querySelector('.wm-root'));
        const lv = d.currentLevel || {};
        const ps = lv.pairs || [];
        const p = ps[0] || {};
        const pos = (sel, t) => {
            const el = Array.from(document.querySelectorAll(sel + ' .wm-card'))
                .find(e => (e.innerText || '').trim() === t);
            if (!el) return null;
            const r = el.getBoundingClientRect();
            return { x: Math.round(r.x + r.width / 2), y: Math.round(r.y + r.height / 2) };
        };
        return { pairEn: p.en, pairZh: p.zh,
                 en: pos('.wm-col-en', p.en), zh: pos('.wm-col-zh', p.zh) };
    })()`);
    if (played.en && played.zh) {
        for (const pt of [played.en, played.zh]) {
            const p = { x: pt.x, y: pt.y, button: 'left', buttons: 1, clickCount: 1, pointerType: 'mouse' };
            await send('Input.dispatchMouseEvent', { type: 'mousePressed', ...p });
            await send('Input.dispatchMouseEvent', { type: 'mouseReleased', ...p });
            await sleep(200);
        }
        await sleep(400);
        const matched = await evalJs(`(() => {
            const d = Alpine.$data(document.querySelector('.wm-root'));
            const done = s => Array.from(document.querySelectorAll(s + ' .wm-card'))
                .filter(e => e.classList.contains('is-done')).length;
            return { en: done('.wm-col-en'), zh: done('.wm-col-zh'), n: d.matchedCount };
        })()`);
        check('点一对词能配对成功（不是空壳页面）',
            matched.en === 1 && matched.zh === 1 && matched.n === 1, JSON.stringify(matched));
        await shot('pub-03-standalone-matched');
    } else {
        check('棋盘上能找到当前关的词卡', false, `${played.pairEn} / ${played.pairZh}`);
    }

    await sleep(300);
    check('【核心】产物不发任何站外请求（零资源外链）',
        netExternal.length === 0, netExternal.slice(0, 5).join(' | '));
    check('无未捕获的页面错误', pageErrors.length === 0, pageErrors.join(' | '));

    /* 换册：直接挑「专题」那册（走 applyBook，和点弹层里的条目同一条路径），确认多册都能进 */
    const picked = await evalJs(`(() => {
        const d = Alpine.$data(document.querySelector('.wm-root'));
        const b = d.books.find(x => x.id === 'pep-topic') || d.books[d.books.length - 1];
        d.applyBook(b.id);
        return b.id;
    })()`);
    await sleep(700);
    const after = await evalJs(`(() => {
        const d = Alpine.$data(document.querySelector('.wm-root'));
        return { id: d.bookId, en: document.querySelectorAll('.wm-col-en .wm-card').length,
                 theme: (d.currentLevel || {}).theme || '' };
    })()`);
    check('切到另一册能正常出题', after.id === picked && after.en >= 3,
        `${after.id} / ${after.theme} / ${after.en} 张`);
    await shot('pub-04-standalone-switch-book');

    /* 公开链接十有八九是手机上点开的，窄屏必须不横向溢出 */
    await send('Emulation.setDeviceMetricsOverride',
        { width: 390, height: 844, deviceScaleFactor: 2, mobile: true });
    await sleep(600);
    const mob = await evalJs(`({
        sw: document.documentElement.scrollWidth,
        cw: document.documentElement.clientWidth,
        en: document.querySelectorAll('.wm-col-en .wm-card').length,
        zh: document.querySelectorAll('.wm-col-zh .wm-card').length
    })`);
    check('390px 窄屏不横向溢出', mob.sw <= mob.cw + 1, `${mob.sw} <= ${mob.cw}`);
    check('390px 窄屏两列词卡都在', mob.en >= 3 && mob.zh >= 3, `en=${mob.en} zh=${mob.zh}`);
    await shot('pub-05-mobile');

    trackNet = false;
}

let server = null, ok = false;
try {
    console.log('== 1/4 起 Chrome ==');
    await startChrome();

    console.log('== 2/4 走真实 UI 导出（设置 → 导出 → 全选 → 导出 HTML）==');
    const exp = await exportFromUi();
    const size = fs.statSync(exp.file).size;
    console.log(`  落盘：${path.basename(exp.file)} · ${(size / 1024).toFixed(0)} KB`);

    console.log('== 3/4 写入发布目录 ==');
    const html = fs.readFileSync(exp.file, 'utf8');
    fs.writeFileSync(path.join(OUT_DIR, 'index.html'), html);
    fs.writeFileSync(path.join(OUT_DIR, '.nojekyll'), '');
    console.log(`  ${path.join(OUT_DIR, 'index.html')} · ${(Buffer.byteLength(html) / 1024).toFixed(0)} KB`);

    /* 静态体检：**资源**外链必须为 0（`src=` 与 CSS 里的 `url(http)`）。
       `href` 不在此列 —— 设置里「在线发音版」（已发布的公网链接）与「源码」（仓库）
       都是导航链接、不加载任何东西，单机件里点开只是跳走，离线打开也毫发无损。
       这条口径与页面自检一致。 */
    const extSrc = (html.match(/\ssrc\s*=\s*["']https?:\/\//gi) || []).length;
    const extCss = (html.match(/url\(\s*["']?https?:\/\//gi) || []).length;
    check('产物里没有 http(s) 资源引用', extSrc === 0 && extCss === 0, `src=${extSrc} css=${extCss}`);

    const extHref = Array.from(html.matchAll(/\shref\s*=\s*["'](https?:\/\/[^"']+)/gi)).map(m => m[1]);
    /* 两条**导航**链接放行：「在线发音版」= 已发布的公网链接、「源码」= 仓库。
       都是 <a href>，不加载任何资源，离线点了只是没反应，所以不算「资源外链」。 */
    const NAV_OK = [/^https:\/\/word-match-[\w-]+\.app\.workbuddy\.host\//,
                    /^https:\/\/github\.com\/renfufei\/Story-Creator$/];
    const offNav = extHref.filter(u => !NAV_OK.some(re => re.test(u)));
    check('产物只有两条导航链接（在线发音版 + 源码），没有别的外链',
        offNav.length === 0 && extHref.length <= 2,
        offNav.length ? offNav.join(' | ') : extHref.join(' | '));
    check('「在线发音版」这条跳转真的带进了产物（单页件里也能回到线上版）',
        extHref.some(u => NAV_OK[0].test(u)), extHref.join(' | '));

    /* 图标必须**自带**：站点版是 /favicon-word-match.svg 外链，单机件里应已被换成 data URI。
       口径两条 —— ① 有 link[rel=icon]；② href 是 data:image/svg+xml 且解码后确实是 <svg>。
       只查「有标签」不够：href 还指站点路径的话，产物一旦换目录就变成破图标。 */
    const iconTag = (html.match(/<link[^>]*rel=["'](?:shortcut )?icon["'][^>]*>/i) || [''])[0];
    const iconData = (iconTag.match(/href=["']data:image\/svg\+xml,([^"']+)["']/i) || [])[1] || '';
    let iconOk = false;
    if (iconData) {
        try { iconOk = decodeURIComponent(iconData).trimStart().startsWith('<svg'); } catch (e) { iconOk = false; }
    }
    check('图标已内联成 data URI（换目录 / 断网都不丢）', iconOk,
        iconData ? `${(iconData.length / 1024).toFixed(1)}KB` : (iconTag ? 'href 不是 data URI' : '没有 icon 标签'));

    console.log('== 4/4 按发布后的 http 场景验收 ==');
    server = await serveStatic(OUT_DIR);
    await verifyStandalone(server, exp);

    const failed = results.filter(r => !r.ok);
    console.log(`\n===== ${results.length - failed.length}/${results.length} 通过 =====`);
    failed.forEach(f => console.log('  FAILED: ' + f.name));
    ok = failed.length === 0;
    console.log('\n发布目录：' + OUT_DIR);
    console.log('本地预览：' + `http://127.0.0.1:${server.address().port}/`);
} catch (e) {
    console.error('执行异常：' + (e && e.stack || e));
} finally {
    try { server && server.close(); } catch (e) { /* ignore */ }
    try { ws && ws.close(); } catch (e) { /* ignore */ }
    try { chrome && chrome.kill('SIGKILL'); } catch (e) { /* ignore */ }
    try { fs.rmSync(userDataDir, { recursive: true, force: true, maxRetries: 5 }); } catch (e) { /* ignore */ }
}
process.exit(ok ? 0 : 1);
