#!/usr/bin/env node
/**
 * 探针：册次弹层里的「已学词汇」展示（册 / 学段 / 全库 三个层级）。
 *
 * 验什么：
 *   1. 册项  → 「64 词（已学 N）· 13 关」，N 只统计**已完成关卡**里的单词条目数；
 *   2. 学段行 → 「8 册 · 846 词（已学 M）」，M 是该学段各册之和；
 *   3. 顶部   → 独立小块「已学词汇 X / Y 词 · Z%」+ 进度条宽度跟着 Z 走；
 *   4. 练习 ∪ 自动学习 取并集（同一关在两套里都完成只算一次）；
 *   5. 没学过的册显示 0 且带 is-zero 灰字。
 *
 * 为什么要在脚本里塞 Fetch 拦截：静态页打进 jar，改完 HTML 后服务不重启就看不到新页。
 * 这里把导航请求的响应替换成本地 `src/main/resources/static/pages/*.html`，
 * 数据仍从真实后端拉 —— 于是「未重启也能验前端」。
 *
 * 用法：node scripts/probe_book_learned_words.mjs   （需脱沙箱）
 */
import { spawn } from 'node:child_process';
// 静音守门：--mute-audio + 注入 src/test/resources/silent-audio.js（管住平台 TTS）
import { SILENT_AUDIO_FLAGS, installSilentAudio } from './lib/silent-audio.mjs';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const LOCAL_HTML = path.join(ROOT, 'src/main/resources/static/pages/learn-word-match.html');
const BASE = process.env.WM_BASE || 'http://localhost:1888';
const PAGE = BASE + '/learn/word-match';
const CHROME = process.env.STORY_BROWSER_PATH
    || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const PORT = 9338;
const OUT = process.env.WM_OUT || '/tmp/wm-learned';
const NO_INTERCEPT = process.env.WM_NO_INTERCEPT === '1';   // 重启后可直接验线上页

fs.mkdirSync(OUT, { recursive: true });
const userDataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'wm-learned-'));

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
async function clickSel(selector) {
    const box = await evalJs(`(() => {
        const el = Array.from(document.querySelectorAll(${JSON.stringify(selector)}))
            .find(e => e.getClientRects().length > 0);
        if (!el) return null;
        const r = el.getBoundingClientRect();
        return { x: Math.round(r.x + r.width / 2), y: Math.round(r.y + r.height / 2) };
    })()`);
    if (!box) throw new Error('找不到可点元素：' + selector);
    const p = { x: box.x, y: box.y, button: 'left', buttons: 1, clickCount: 1, pointerType: 'mouse' };
    await send('Input.dispatchMouseEvent', { type: 'mousePressed', ...p });
    await send('Input.dispatchMouseEvent', { type: 'mouseReleased', ...p });
}
async function shot(name) {
    const r = await send('Page.captureScreenshot', { format: 'png' });
    fs.writeFileSync(path.join(OUT, name + '.png'), Buffer.from(r.data, 'base64'));
}

/* 读弹层三个层级 + 由页面自身算出的期望值（同一份数据源，避免两边算法漂移） */
const READ_DOM = `(() => {
    const back = Array.from(document.querySelectorAll('.wm-modal-backdrop'))
        .find(e => e.getClientRects().length > 0);
    if (!back) return { err: '没有可见弹层' };
    const d = Alpine.$data(document.querySelector('.wm-root'));
    const bid = d.books[0].id;
    const stage = d.books[0].stage || '小学';
    const items = Array.from(back.querySelectorAll('.wm-book-item'));
    const firstRow = items.find(it => it.textContent.includes(d.books[0].label));
    const heads = Array.from(back.querySelectorAll('.wm-book-group-head'));
    const head = heads.find(h => h.textContent.trim().startsWith(stage));
    const total = back.querySelector('.wm-learn-total');
    const bar = back.querySelector('.wm-learn-track > i');
    const learnedSpans = firstRow ? Array.from(firstRow.querySelectorAll('.wm-learned')) : [];
    return {
        bid: bid, stage: stage, label: d.books[0].label,
        expectBook: d.learnedWords(bid),
        expectStage: d.learnedSummary.stages[stage],
        expectTotal: d.learnedSummary.total,
        expectTotalWords: d.learnedSummary.totalWords,
        expectPct: d.learnedSummary.pct,
        bookText: firstRow ? firstRow.innerText.replace(/\\s+/g, ' ').trim() : null,
        bookLearnedText: learnedSpans.length ? learnedSpans[0].textContent.trim() : null,
        bookLearnedZero: learnedSpans.length ? learnedSpans[0].classList.contains('is-zero') : null,
        stageText: head ? head.innerText.replace(/\\s+/g, ' ').trim() : null,
        totalText: total ? total.innerText.replace(/\\s+/g, ' ').trim() : null,
        barWidth: bar ? getComputedStyle(bar).width : null,
        trackWidth: back.querySelector('.wm-learn-track') ? getComputedStyle(back.querySelector('.wm-learn-track')).width : null
    };
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
            if (!NO_INTERCEPT && request.url === PAGE) {
                const html = fs.readFileSync(LOCAL_HTML, 'utf8');
                send('Fetch.fulfillRequest', {
                    requestId, responseCode: 200,
                    responseHeaders: [{ name: 'Content-Type', value: 'text/html; charset=utf-8' }],
                    body: Buffer.from(html, 'utf8').toString('base64')
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
        if (m.id && pending.has(m.id)) {
            const p = pending.get(m.id);
            pending.delete(m.id);
            m.error ? p.rej(new Error(JSON.stringify(m.error))) : p.res(m.result);
        }
    };
    // ② 平台 TTS 管不到就去注入守门脚本（addScriptToEvaluateOnNewDocument，对每次导航都生效）
    await installSilentAudio(send);
    await send('Runtime.enable');
    if (!NO_INTERCEPT) await send('Fetch.enable', {
        patterns: [{ urlPattern: PAGE, requestStage: 'Request' }]
    });
    await send('Emulation.setDeviceMetricsOverride',
        { width: 1280, height: 940, deviceScaleFactor: 1, mobile: false });

    await send('Page.navigate', { url: PAGE });
    await sleep(900);
    await evalJs(`localStorage.clear()`);
    await evalJs(`localStorage.setItem('word_match_sound_v1', '0')`);
    await send('Page.navigate', { url: PAGE });
    for (let i = 0; i < 80; i++) {
        const n = await evalJs(`document.querySelectorAll('.wm-col-en .wm-card').length`);
        if (n >= 3) break;
        await sleep(150);
    }
    await sleep(300);

    const servedLocal = await evalJs(`document.querySelectorAll('.wm-learn-total').length`);
    console.log(NO_INTERCEPT
        ? '— 直接验线上页（未拦截）—'
        : `— 已把导航响应替换为本地 ${path.relative(ROOT, LOCAL_HTML)}（命中 ${servedLocal} 处新标记）—`);
    if (!NO_INTERCEPT) {
        check('本地新页已生效（.wm-learn-total 存在）', servedLocal === 1,
            '命中 ' + servedLocal);
    }

    /* 造进度：第一册 练习 0~2 关 + 自动学习 0~3 关（重叠的 0~2 关不能重复计） */
    const ids = await evalJs(`(() => {
        const d = Alpine.$data(document.querySelector('.wm-root'));
        return { bid: d.books[0].id, lv: (d.allLevels[d.books[0].id] || []).length };
    })()`);
    await evalJs(`(() => {
        const d = Alpine.$data(document.querySelector('.wm-root'));
        d.progress[${JSON.stringify(ids.bid)}] = [0, 1, 2];
        d.autoProgress[${JSON.stringify(ids.bid)}] = [0, 1, 2, 3];
        return true;
    })()`);
    await sleep(200);

    await clickSel('.wm-book-btn');
    await sleep(1600);

    const dom = await evalJs(READ_DOM);
    if (dom.err) throw new Error('读弹层失败: ' + dom.err);

    const expectUnion = await evalJs(`(() => {
        const d = Alpine.$data(document.querySelector('.wm-root'));
        const lv = d.allLevels[${JSON.stringify(dom.bid)}] || [];
        let n = 0;
        [0, 1, 2, 3].forEach(i => { if (lv[i] && lv[i].pairs) n += lv[i].pairs.length; });
        return n;
    })()`);

    check('册项显示「N 词（已学 M） · K 关」格式',
        /词（已学 \d+） · \d+ 关/.test(dom.bookText || ''), dom.bookText);
    check('册项已学数 = 练习∪自动学习 的去重关卡词数（本册 0~3 关）',
        dom.bookLearnedText === '（已学 ' + expectUnion + '）' && dom.expectBook === expectUnion,
        `DOM=${dom.bookLearnedText} 期望=已学 ${expectUnion} 方法值=${dom.expectBook}`);
    check('册项已学数 > 0 时不带 is-zero（绿字）',
        dom.bookLearnedZero === false, 'is-zero=' + dom.bookLearnedZero);
    check('学段行显示「M 册 · K 词（已学 S）」，S = 该学段各册之和',
        /册 · \d+ 词（已学 \d+）/.test(dom.stageText || '') &&
        (dom.stageText || '').includes('（已学 ' + dom.expectStage + '）'),
        `${dom.stageText} / 期望 ${dom.expectStage}`);
    check('顶部总览显示「已学词汇 X / Y 词 · Z%」',
        /已学词汇/.test(dom.totalText || '') &&
        (dom.totalText || '').includes(dom.expectTotal.toLocaleString('en-US')) &&
        (dom.totalText || '').includes(dom.expectTotalWords.toLocaleString('en-US')) &&
        (dom.totalText || '').includes(dom.expectPct + '%'),
        `${dom.totalText} / 期望 ${dom.expectTotal} 之 ${dom.expectTotalWords} · ${dom.expectPct}%`);
    const barPct = parseFloat(dom.barWidth) / parseFloat(dom.trackWidth) * 100;
    check('进度条宽度 ≈ 已学百分比',
        Math.abs(barPct - dom.expectPct) < 1.5,
        `条宽 ${dom.barWidth}/${dom.trackWidth} = ${barPct.toFixed(1)}% vs ${dom.expectPct}%`);

    /* 没学过的册应当是「（已学 0）」+ is-zero 灰字 */
    const zero = await evalJs(`(() => {
        const back = Array.from(document.querySelectorAll('.wm-modal-backdrop'))
            .find(e => e.getClientRects().length > 0);
        const d = Alpine.$data(document.querySelector('.wm-root'));
        const unlearned = d.books.find(b => b.id !== ${JSON.stringify(dom.bid)} && d.learnedWords(b.id) === 0);
        if (!unlearned) return null;
        const item = Array.from(back.querySelectorAll('.wm-book-item'))
            .find(it => it.textContent.includes(unlearned.label));
        if (!item) return null;   // 该册所在学段是折叠的
        const sp = item.querySelector('.wm-learned');
        return { text: sp ? sp.textContent.trim() : null,
                 zero: sp ? sp.classList.contains('is-zero') : null,
                 color: sp ? getComputedStyle(sp).color : null };
    })()`);
    if (zero) {
        check('没学过的册显示「（已学 0）」且带 is-zero（灰字，不抢注意力）',
            zero.text === '（已学 0）' && zero.zero === true, `${zero.text} / color=${zero.color}`);
    } else {
        console.log('SKIP  没学过的册（所在学段折叠中，未取样）');
    }

    await shot('picker-with-learned');

    check('页面无 JS 异常', pageErrors.length === 0, pageErrors.slice(0, 2).join(' | '));

    const bad = results.filter(r => !r.ok).length;
    console.log(`\n${results.length - bad}/${results.length} 通过    截图：${OUT}/picker-with-learned.png`);
    try { chrome.kill(); } catch (e) { /* ignore */ }
    process.exit(bad ? 1 : 0);
}

main().catch(e => {
    console.error('FAILED:', e.message);
    try { chrome.kill(); } catch (_) { /* ignore */ }
    process.exit(1);
});
