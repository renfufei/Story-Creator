#!/usr/bin/env node
/**
 * 针对性探针：**只勾选大学就导出**（最容易被悄悄改坏的一条路径）。
 *
 * 为什么单独验：大学词库是**按需加载**的（首屏只带册元信息）。导出用的是
 * buildStandaloneWordMatch(ids, ...)，它读的是 window.__WORD_MATCH_DATA__。
 * 如果导出前没先把词库载进来，产物里那两册就是**空的** —— 页面照样生成、
 * 文件名照样对、双击也能打开，只是打开后没有关卡。这种"静默残缺"必须实测兜住。
 *
 * 本探针刻意**不打开册次弹层**（打开会触发预取，就测不出未加载的情况），
 * 直接：启动 -> 设置 -> 导出 -> 勾四级 -> 真点导出 -> 校验落盘产物 -> file:// 打开跑一关。
 *
 * 用法：node probe_cet_export.mjs   （需脱沙箱）
 */
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const BASE = process.env.WM_BASE || 'http://localhost:1888';
const PAGE = BASE + '/learn/word-match';
const CHROME = process.env.STORY_BROWSER_PATH
    || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const PORT = 9334;
const OUT = process.env.WM_OUT || '/tmp/wm-shots';

const results = [];
const check = (name, ok, extra = '') => {
    results.push({ name, ok: !!ok });
    console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${extra ? '  [' + extra + ']' : ''}`);
};

fs.mkdirSync(OUT, { recursive: true });
const userDataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'wm-cet-'));
const downloadDir = fs.mkdtempSync(path.join(os.tmpdir(), 'wm-cet-export-'));
const chrome = spawn(CHROME, [
    '--headless=new', '--no-sandbox', '--disable-gpu', '--hide-scrollbars',
    '--allow-file-access-from-files',
    '--remote-debugging-port=' + PORT, '--user-data-dir=' + userDataDir, 'about:blank'
], { stdio: 'ignore' });

const sleep = (ms) => new Promise(r => setTimeout(r, ms));
let ws, msgId = 0;
const pending = new Map();
const pageErrors = [];

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
async function clickAt(x, y) {
    const p = { x, y, button: 'left', buttons: 1, clickCount: 1, pointerType: 'mouse' };
    await send('Input.dispatchMouseEvent', { type: 'mousePressed', ...p });
    await send('Input.dispatchMouseEvent', { type: 'mouseReleased', ...p });
}
async function centerOf(selector, nth = 0, text = null) {
    return evalJs(`(() => {
        let els = Array.from(document.querySelectorAll(${JSON.stringify(selector)}))
            .filter(e => e.getClientRects().length > 0);
        ${text ? `els = els.filter(e => e.innerText.trim().includes(${JSON.stringify(text)}));` : ''}
        const el = els[${nth}];
        if (!el) return null;
        const r = el.getBoundingClientRect();
        return { x: Math.round(r.x + r.width / 2), y: Math.round(r.y + r.height / 2),
                 text: el.innerText.trim().replace(/\\s+/g, ' ').slice(0, 60) };
    })()`);
}
async function clickSel(selector, nth = 0, text = null) {
    const box = await centerOf(selector, nth, text);
    if (!box) throw new Error('找不到可点元素：' + selector + (text ? ' text=' + text : ''));
    await clickAt(box.x, box.y);
    return box;
}
async function shot(name) {
    const r = await send('Page.captureScreenshot', { format: 'png' });
    fs.writeFileSync(path.join(OUT, name + '.png'), Buffer.from(r.data, 'base64'));
}
async function waitCards() {
    for (let i = 0; i < 80; i++) {
        const n = await evalJs(`document.querySelectorAll('.wm-col-en .wm-card').length`);
        if (n >= 3) return true;
        await sleep(150);
    }
    throw new Error('卡片没渲染出来');
}

async function main() {
    for (let i = 0; i < 60; i++) {
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
    };
    await send('Page.enable');
    await send('Runtime.enable');
    await send('Emulation.setDeviceMetricsOverride', {
        width: 1280, height: 940, deviceScaleFactor: 1, mobile: false
    });
    await send('Browser.setDownloadBehavior',
        { behavior: 'allow', downloadPath: downloadDir, eventsEnabled: true });

    await send('Page.navigate', { url: PAGE });
    await sleep(700);
    await evalJs(`localStorage.clear()`);
    await evalJs(`localStorage.setItem('word_match_sound_v1', '0')`);
    await send('Page.navigate', { url: PAGE });
    await waitCards();
    await sleep(400);

    /* 起点：还没打开过册次弹层，大学词库应当**未加载** */
    const before = await evalJs(`(() => {
        const d = Alpine.$data(document.querySelector('.wm-root'));
        return { loaded: d.cetLoaded, books: d.books.length, id: d.bookId,
                 cetLevels: Object.keys(d.allLevels).filter(k => k.indexOf('cet') === 0).length };
    })()`);
    check('P1 起点：大学词库未加载（没开过册次弹层，不该预取）',
        before.loaded === false && before.books === 26 && before.cetLevels === 0,
        JSON.stringify(before));

    /* 设置 -> 导出：全程真实鼠标 */
    await clickSel('.wm-set-btn');
    await sleep(200);
    await clickSel('.wm-set-item.is-export');
    await sleep(420);
    await shot('cet-01-export-dialog');

    const stillLazy = await evalJs(`(() => {
        const d = Alpine.$data(document.querySelector('.wm-root'));
        return { loaded: d.cetLoaded, cetLevels: Object.keys(d.allLevels).filter(k => k.indexOf('cet') === 0).length };
    })()`);
    check('P1 打开导出弹层也不预取（只有册次弹层才预热）',
        stillLazy.loaded === false && stillLazy.cetLevels === 0, JSON.stringify(stillLazy));

    /* 只勾四级：勾选 UI 本身已由冒烟覆盖，这里直接改数据，避开脆弱的逐项点击。
       坑：改完必须**另起一次**求值再读 DOM —— Alpine 的重渲染是异步批处理的，
       同一次表达式里读到的还是改之前那版文案（第一版就是这样假失败的）。 */
    await evalJs(`(() => {
        const d = Alpine.$data(document.querySelector('.wm-root'));
        d.exportToggleAll(false);
        d.toggleExportBook('cet-4', true);
        return true;
    })()`);
    await sleep(220);
    const sel = await evalJs(`(() => {
        const d = Alpine.$data(document.querySelector('.wm-root'));
        return { n: d.exportSelCount, ids: d.exportSelList.join(','),
                 sum: document.querySelector('.wm-export-sum').textContent.trim(),
                 goOff: document.querySelector('#wm-export-go').disabled };
    })()`);
    check('P1 只勾「四级」后，汇总显示 1 册 / 1251 关 / 7508 词',
        sel.n === 1 && sel.ids === 'cet-4' && /已选 1 册/.test(sel.sum)
        && /1251 关/.test(sel.sum) && /7508 词/.test(sel.sum) && sel.goOff === false,
        sel.sum);

    /* 真点「导出 HTML」：runExport 必须先把词库载完再打包 */
    const beforeFiles = new Set(fs.readdirSync(downloadDir));
    await clickSel('#wm-export-go');
    let outFile = null;
    for (let i = 0; i < 90; i++) {
        await sleep(400);
        const fresh = fs.readdirSync(downloadDir).filter(f => !beforeFiles.has(f));
        if (fresh.length) { outFile = path.join(downloadDir, fresh[0]); break; }
    }
    check('P1 产物真的落盘了', !!outFile, outFile ? path.basename(outFile) : 'no file');
    if (!outFile) return false;

    const after = await evalJs(`(() => {
        const d = Alpine.$data(document.querySelector('.wm-root'));
        return { loaded: d.cetLoaded, levels: (d.allLevels['cet-4'] || []).length };
    })()`);
    check('P1 导出后词库已就绪（cet-4 1251 关）', after.loaded === true && after.levels === 1251,
        JSON.stringify(after));

    const html = fs.readFileSync(outFile, 'utf8');
    const cetKeys = (html.match(/"key":"cet-4:/g) || []).length;
    check('P1 产物内含四级全部 7508 条词（不是空壳）', cetKeys === 7508, `cet-4 词条=${cetKeys}`);
    check('P1 产物里没有六级（没勾它）', html.indexOf('"key":"cet-6:') < 0
        && html.indexOf('"cet-6"') < 0);
    check('P1 产物锁定的起始册 = 四级（勾选册里最靠前的那一册）',
        /window\.__WM_DEFAULT_BOOK__ = "cet-4"/.test(html));
    check('P1 产物里不掺人教版那 24 册', html.indexOf('"key":"pep-') < 0);
    check('P1 四级的释义原样带走（含词性前缀）', html.indexOf('n. 通道，入口') >= 0);
    check('P1 产物仍是零资源外链', !/\ssrc="(?!data:)/.test(html)
        && !/url\((?![\'"]?data:)/.test(html));

    /* file:// 打开产物，跑一关：证明确实能玩 */
    await send('Page.navigate', { url: 'file://' + outFile });
    await waitCards();
    await sleep(500);
    const solo = await evalJs(`(() => {
        const d = Alpine.$data(document.querySelector('.wm-root'));
        const b = document.querySelectorAll('.wm-col-en .wm-card');
        return { alpine: typeof Alpine, id: d.bookId, label: d.bookLabel,
                 levels: d.levels.length, books: d.books.length,
                 theme: d.currentLevel ? d.currentLevel.theme : '',
                 counter: (document.querySelector('.wm-counter') || {}).innerText || '',
                 cards: b.length, standalone: !!window.__WM_STANDALONE__ };
    })()`);
    check('P1 单机件默认就落在四级、1251 关、每关按词性主题成关',
        solo.standalone === true && solo.id === 'cet-4' && solo.label === '四级'
        && solo.levels === 1251 && solo.books === 1 && solo.cards >= 3
        && solo.counter.replace(/\s+/g, '').startsWith('1/1251'),
        JSON.stringify(solo));
    await shot('cet-02-standalone-cet4');

    const played = await evalJs(`(() => {
        const g = (sel) => Array.from(document.querySelectorAll(sel + ' .wm-card')).map(e => {
            const r = e.getBoundingClientRect();
            return { text: e.innerText.trim(), done: e.classList.contains('is-done'),
                     x: Math.round(r.x + r.width / 2), y: Math.round(r.y + r.height / 2) };
        });
        /* 坑：Apline 的响应式是 Proxy，直接把 pairs[0] 整个 returnByValue 回来会得到空对象
           （CDP 的序列化器不认 Proxy）=> 只取原始值重新拼普通对象 */
        const p = Alpine.$data(document.querySelector('.wm-root')).currentLevel.pairs[0];
        return { en: g('.wm-col-en'), zh: g('.wm-col-zh'),
                 pairEn: String(p.en), pairZh: String(p.zh) };
    })()`);
    const enCard = played.en.find(c => c.text === played.pairEn);
    const zhCard = played.zh.find(c => c.text === played.pairZh);
    check('P1 单机件棋盘上的词就是四级第 1 关的词', !!enCard && !!zhCard,
        `${played.pairEn} / ${played.pairZh}`);
    if (enCard && zhCard) {
        await clickAt(enCard.x, enCard.y);
        await sleep(150);
        await clickAt(zhCard.x, zhCard.y);
        await sleep(500);
        const done = await evalJs(`(() => {
            const g = (sel) => Array.from(document.querySelectorAll(sel + ' .wm-card'))
                .filter(e => e.classList.contains('is-done')).length;
            const d = Alpine.$data(document.querySelector('.wm-root'));
            return { en: g('.wm-col-en'), zh: g('.wm-col-zh'), matched: d.matchedCount };
        })()`);
        check('P1 单机件里四级单词配对照常工作', done.en === 1 && done.zh === 1 && done.matched === 1,
            JSON.stringify(done));
        await shot('cet-03-standalone-cet4-matched');
    }

    check('P1 全程没有未捕获的页面错误', pageErrors.length === 0, pageErrors.join(' | '));

    const failed = results.filter(r => !r.ok);
    console.log(`\n===== ${results.length - failed.length}/${results.length} 通过 =====`);
    failed.forEach(f => console.log('  FAILED: ' + f.name));
    return failed.length === 0;
}

let ok = false;
try {
    ok = await main();
} catch (e) {
    console.error('执行异常：' + (e && e.stack || e));
} finally {
    try { ws && ws.close(); } catch (e) { /* ignore */ }
    try { chrome.kill('SIGKILL'); } catch (e) { /* ignore */ }
    try { fs.rmSync(userDataDir, { recursive: true, force: true }); } catch (e) { /* ignore */ }
}
process.exit(ok ? 0 : 1);
