#!/usr/bin/env node
/**
 * 探针：已配对卡片的「一组一色」（tint-N）。
 *
 * 验什么：
 *   1. 未配对时**不带** tint-N（颜色不能提前泄题）；
 *   2. 配对后同一对的中英文拿到**同一个** tint-N，不同对拿到**不同**的 N；
 *   3. 每个 tint 组恰好「1 张英文 + 1 张中文」且这两张确实是一对（按分组判，兼容同义关）；
 *   4. 底色 / 边框 / 文字都取自本组那套 CSS 变量，不硬编码色号；
 *   5. 底色既不是纯白、也不是棋盘底色，且**非常浅**（每个通道 ≥ 240：离白的深度只留 1/3）；
 *   6. 7 组调色板全是冷色相（115°~300°：没有红、没有黄）；
 *   7. 文字/底色对比度 ≥ 4.5:1（WCAG AA）；
 *   8. 底色退到 1/3 深度后**不再承担两两区分**（相邻 ΔE 只有 1.8~2.6），
 *      改断言：7 组边框两两可辨（最大通道差 ≥ 8）—— 认组靠 1.5px 边框；
 *   9. 悬停已配对卡片，边框/底色不被 hover 抹回中性线色。
 *
 * 为什么要在脚本里塞 Fetch 拦截：静态页打进 jar，改完 HTML 后服务不重启就看不到新页。
 * 这里把导航请求的响应替换成本地 `src/main/resources/static/pages/*.html`，数据仍走真实后端。
 *
 * 用法：node scripts/probe_card_tints.mjs            （需 managed Node 22：有全局 WebSocket）
 *       WM_NO_INTERCEPT=1 node scripts/probe_card_tints.mjs   （重启后直接验线上页）
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
const PORT = 9341;
const OUT = process.env.WM_OUT || '/tmp/wm-tint-probe';
const NO_INTERCEPT = process.env.WM_NO_INTERCEPT === '1';

fs.mkdirSync(OUT, { recursive: true });
const userDataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'wm-tint-'));

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
async function clickAt(x, y) {
    const p = { x, y, button: 'left', buttons: 1, clickCount: 1, pointerType: 'mouse' };
    await send('Input.dispatchMouseEvent', { type: 'mousePressed', ...p });
    await send('Input.dispatchMouseEvent', { type: 'mouseReleased', ...p });
}
async function shot(name) {
    const r = await send('Page.captureScreenshot', { format: 'png' });
    const file = path.join(OUT, name + '.png');
    fs.writeFileSync(file, Buffer.from(r.data, 'base64'));
    console.log('  截图 ' + file);
    return file;
}

/* 一次读全：调色板（读页面变量，不硬编码色号）+ 每张卡的状态色 + 棋盘底色 */
const READ = `(() => {
    const hex = (h) => { const v = parseInt(String(h).trim().replace('#',''), 16);
        return 'rgb(' + ((v >> 16) & 255) + ', ' + ((v >> 8) & 255) + ', ' + (v & 255) + ')'; };
    const root = getComputedStyle(document.querySelector('.wm-root'));
    const pal = [];
    for (let i = 0; i < 7; i++) {
        const g = (k) => hex(root.getPropertyValue('--wm-tint-' + i + '-' + k));
        pal.push({ bg: g('bg'), line: g('line'), ink: g('ink') });
    }
    const cards = Array.from(document.querySelectorAll('.wm-card')).map(el => {
        const cs = getComputedStyle(el);
        const m = (el.className.match(/\\btint-(\\d)\\b/) || [])[1];
        const r = el.getBoundingClientRect();
        return {
            text: el.innerText.trim(),
            side: el.closest('.wm-col').classList.contains('wm-col-en') ? 'en' : 'zh',
            done: el.classList.contains('is-done'),
            ok: el.classList.contains('is-ok'),
            sel: el.classList.contains('is-sel'),
            tint: m === undefined ? null : Number(m),
            bg: cs.backgroundColor, line: cs.borderTopColor, color: cs.color,
            x: Math.round(r.x + r.width / 2), y: Math.round(r.y + r.height / 2)
        };
    });
    const d = Alpine.$data(document.querySelector('.wm-root'));
    /* 显式映射成纯对象：Alpine 的 $data 是响应式 Proxy，直接塞进 returnByValue 里
       可能序列化不出来（实测 dom.pairs 会变 undefined），逐字段取出来最稳。 */
    const lv = (d.allLevels[d.bookId] || [])[d.levelIndex];
    const pairs = (lv && lv.pairs ? lv.pairs : []).map(p => ({ key: p.key, en: p.en, zh: p.zh }));
    return { pal, cards, pairs,
             boardBg: getComputedStyle(document.querySelector('.wm-board')).backgroundColor,
             title: document.querySelector('.wm-title').innerText.trim(),
             counter: document.querySelector('.wm-counter').innerText.replace(/\\s+/g, '') };
})()`;

const chan = (s) => (String(s).match(/\d+/g) || []).map(Number);
const relLum = (c) => {
    const f = (v) => { v /= 255; return v <= 0.03928 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4; };
    return 0.2126 * f(c[0]) + 0.7152 * f(c[1]) + 0.0722 * f(c[2]);
};
const contrast = (a, b) => {
    const la = relLum(a), lb = relLum(b);
    return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
};
const hueOf = (c) => {
    const r = c[0] / 255, g = c[1] / 255, b = c[2] / 255;
    const mx = Math.max(r, g, b), mn = Math.min(r, g, b), d = mx - mn;
    if (!d) return 0;
    let h = mx === r ? ((g - b) / d) % 6 : mx === g ? (b - r) / d + 2 : (r - g) / d + 4;
    h *= 60; return h < 0 ? h + 360 : h;
};
const maxDiff = (a, b) => Math.max(...[0, 1, 2].map(k => Math.abs(a[k] - b[k])));

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
        if ((await evalJs(`document.querySelectorAll('.wm-col-en .wm-card').length`)) >= 3) break;
        await sleep(150);
    }
    await sleep(300);

    const servedLocal = await evalJs(`document.querySelectorAll('.wm-root').length &&
        (getComputedStyle(document.querySelector('.wm-root')).getPropertyValue('--wm-tint-0-bg') || '').trim().length`);
    console.log(NO_INTERCEPT
        ? '— 直接验线上页（未拦截）—'
        : `— 已把导航响应替换为本地 ${path.relative(ROOT, LOCAL_HTML)} —`);
    if (!NO_INTERCEPT) {
        check('本地新页已生效（能读到 --wm-tint-0-bg）', !!servedLocal, '值长度 ' + servedLocal);
    }

    /* 切到本册「满员关」（对数最多的一关，最多 7 对）—— 一关一关点过去太慢，
       这里直接改 Alpine 的 levelIndex 再 loadLevel()，等价于用户连点「下一关」。
       ⚠️ 刻意避开最后一关：打完最后一关会弹「本册全部通关」遮住棋盘，截不到图。 */
    const pick = await evalJs(`(() => {
        const d = Alpine.$data(document.querySelector('.wm-root'));
        const lv = d.allLevels[d.bookId] || [];
        let best = -1, n = -1;
        lv.forEach((l, i) => {
            if (!l || !l.pairs || i >= lv.length - 1) return;
            if (l.pairs.length > n) { n = l.pairs.length; best = i; }
        });
        if (best < 0) return null;
        d.levelIndex = best; d.loadLevel();
        return { idx: best, pairs: n, book: d.bookLabel, total: lv.length };
    })()`);
    if (!pick) throw new Error('没有可用的关卡');
    await sleep(400);
    console.log(`— 已切到「${pick.book}」第 ${pick.idx + 1} 关（${pick.pairs} 对）—`);
    check('满员关至少 5 对（能覆盖大部分调色板）', pick.pairs >= 5, `${pick.pairs} 对`);

    /* ---------- 1. 配对前：不许有 tint ---------- */
    let dom = await evalJs(READ);
    check('配对前没有一张卡带 tint-N（颜色不能提前泄题）',
        dom.cards.every(c => c.tint === null),
        dom.cards.filter(c => c.tint !== null).map(c => c.text + ':' + c.tint).join(' | ') || '全部为 null');
    check('配对前卡片是白底（默认 --wm-surface）',
        dom.cards.every(c => c.bg === 'rgb(255, 255, 255)'),
        dom.cards.map(c => c.bg).join(' '));
    await shot('01-before-matching');

    /* ---------- 2. 全部配对 ---------- */
    for (const p of dom.pairs) {
        const b = await evalJs(READ);
        const e = b.cards.find(c => c.side === 'en' && c.text === p.en);
        if (!e || e.done) continue;
        await clickAt(e.x, e.y);
        await sleep(90);
        const b2 = await evalJs(READ);
        const z = b2.cards.find(c => c.side === 'zh' && c.text === p.zh && !c.done);
        if (!z) throw new Error('找不到中文卡：' + p.zh);
        await clickAt(z.x, z.y);
        await sleep(200);
    }
    /* 最后一对配完会启动 950ms 的自动跳关定时器；这里把它掐掉，
       好让「回顾态」留在屏幕上慢慢断言 + 截图（探针专用，页面代码不动）。 */
    await evalJs(`(() => { const d = Alpine.$data(document.querySelector('.wm-root'));
        if (d.advanceTimer) { clearTimeout(d.advanceTimer); d.advanceTimer = null; } return true; })()`);
    await sleep(750);      // 跨过 .5s 闪绿 + .16s 过渡，读稳定态

    dom = await evalJs(READ);
    const done = dom.cards.filter(c => c.done);
    const undone = dom.cards.filter(c => !c.done);

    /* ---------- 3. 调色板本身 ---------- */
    check('7 组调色板都读得到（底色 / 边框 / 文字）',
        dom.pal.length === 7 && dom.pal.every(p => /^rgb\(/.test(p.bg) && /^rgb\(/.test(p.line) && /^rgb\(/.test(p.ink)),
        dom.pal[0].bg + ' / ' + dom.pal[0].line + ' / ' + dom.pal[0].ink);
    check('7 组全是冷色相（115°~300°：没有红、没有黄）',
        dom.pal.every(p => { const h = hueOf(chan(p.bg)); return h >= 115 && h <= 300; }),
        dom.pal.map(p => hueOf(chan(p.bg)).toFixed(0)).join(' '));
    const chroma = (c) => Math.max(...c) - Math.min(...c);
    check('7 组底色都不是白、也不是近白（至少一个通道比白低 10 以上）',
        dom.pal.every(p => maxDiff(chan(p.bg), [255, 255, 255]) >= 10),
        dom.pal.map(p => maxDiff(chan(p.bg), [255, 255, 255])).join(' '));
    check('7 组底色都极浅（每通道 ≥ 240：离白的深度只留 1/3，不抢眼）',
        dom.pal.every(p => chan(p.bg).every(v => v >= 240)),
        dom.pal.map(p => Math.min(...chan(p.bg))).join(' '));
    check('7 组底色都仍带色相（不是纯灰：最大通道差 ≥ 8）',
        dom.pal.every(p => chroma(chan(p.bg)) >= 8),
        dom.pal.map(p => chroma(chan(p.bg))).join(' '));
    check('每组「文字 / 底色」对比度 ≥ 4.5:1（WCAG AA）',
        dom.pal.every(p => contrast(chan(p.bg), chan(p.ink)) >= 4.5),
        dom.pal.map(p => contrast(chan(p.bg), chan(p.ink)).toFixed(2)).join(' '));
    let minBg = 999, minLine = 999;
    for (let i = 0; i < 7; i++) for (let j = i + 1; j < 7; j++) {
        minBg = Math.min(minBg, maxDiff(chan(dom.pal[i].bg), chan(dom.pal[j].bg)));
        minLine = Math.min(minLine, maxDiff(chan(dom.pal[i].line), chan(dom.pal[j].line)));
    }
    /* 底色只留 1/3 深度之后，它在物理上就做不到「7 组两两可辨」了（相邻 ΔE 只有 1.8~2.6）。
       所以这里不再要求底色两两可辨，把「认组」的责任交给边框 —— 边框色别再往浅里调。 */
    check('7 组边框两两可辨（最大通道差 ≥ 8）—— 底色变浅后全靠它',
        minLine >= 8, `边框 ${minLine} / 底色（仅供参考，已不再要求） ${minBg}`);
    check('新配色与旧的统一灰明显不同（不再是 #f1f3f5）',
        dom.pal.every(p => p.bg !== 'rgb(241, 243, 245)'),
        dom.pal.map(p => p.bg).join(' '));

    /* ---------- 4. 上色结果 ---------- */
    check(`本关 ${dom.pairs.length} 对全部配上色（每对 2 张）`,
        done.length === dom.pairs.length * 2 && undone.length === 0,
        `已配对 ${done.length} 张 / 未配对 ${undone.length} 张`);
    check('每张已配对卡都带 tint-N', done.every(c => c.tint !== null),
        done.map(c => `${c.text}:${c.tint}`).join(' | '));
    check('底色 / 边框 / 文字都取自本组那套变量（不硬编码色号）',
        done.every(c => c.bg === dom.pal[c.tint].bg && c.line === dom.pal[c.tint].line
            && c.color === dom.pal[c.tint].ink),
        done.slice(0, 3).map(c => `${c.tint}:${c.bg}`).join(' | '));
    check('底色极浅（每个通道 ≥ 240：只留 1/3 深度，跟未配对的白底几乎是一层雾）',
        done.every(c => chan(c.bg).every(v => v >= 240)), done[0] && done[0].bg);

    const byTint = new Map();
    done.forEach(c => { if (!byTint.has(c.tint)) byTint.set(c.tint, []); byTint.get(c.tint).push(c); });
    const pairsOfEn = new Map(dom.pairs.map(p => [p.en, p.zh]));
    const groups = [...byTint.entries()].sort((a, b) => a[0] - b[0]);
    check('不同对拿到不同 tint（本关内不重复）',
        byTint.size === dom.pairs.length, `tint 去重 ${byTint.size} 个 / ${dom.pairs.length} 对`);
    check('每个 tint 组恰好 2 张：左英文 + 右中文',
        groups.every(([, g]) => g.length === 2 && g.some(c => c.side === 'en') && g.some(c => c.side === 'zh')),
        groups.map(([t, g]) => t + ':' + g.map(c => c.side).join('+')).join(' '));
    check('同组的两张卡确实是同一对（按分组判，兼容同义关）',
        groups.every(([, g]) => {
            const e = g.find(c => c.side === 'en'), z = g.find(c => c.side === 'zh');
            return !!e && !!z && pairsOfEn.get(e.text) === z.text;
        }),
        groups.map(([t, g]) => t + ':' + g.map(c => c.text).join('+')).join(' | '));

    await shot('02-after-tinted');

    /* ---------- 5. 悬停已配对卡片：颜色不能被 hover 抢走 ----------
       用 CDP 的 CSS.forcePseudoState 造 :hover（真实鼠标移动在无头里不一定带出 :hover，
       之前用 mouseMoved 就取不到 .wm-card:hover）—— 这是本仓库验伪类的一贯做法。 */
    await send('DOM.enable');
    await send('CSS.enable');
    const docRoot = await send('DOM.getDocument', { depth: -1 });
    const hoverQ = await send('DOM.querySelector',
        { nodeId: docRoot.root.nodeId, selector: '.wm-card.is-done' });
    const hoverTarget = done[0];
    await send('CSS.forcePseudoState',
        { nodeId: hoverQ.nodeId, forcedPseudoClasses: ['hover'] });
    await sleep(200);
    const hovered = await evalJs(`(() => {
        const el = document.querySelector('.wm-card.is-done');
        if (!el) return null;
        const cs = getComputedStyle(el);
        return { line: cs.borderTopColor, bg: cs.backgroundColor,
                 tint: Number((el.className.match(/\\btint-(\\d)\\b/) || [])[1]) };
    })()`);
    check('悬停已配对卡片：边框与底色仍是本组颜色（不被 hover 抹回中性线色）',
        !!hovered && hovered.line === dom.pal[hoverTarget.tint].line
        && hovered.bg === dom.pal[hoverTarget.tint].bg,
        JSON.stringify(hovered) + ' 期望 ' + JSON.stringify(dom.pal[hoverTarget.tint]));
    await send('CSS.forcePseudoState', { nodeId: hoverQ.nodeId, forcedPseudoClasses: [] });
    const leaky = await evalJs(`(() => {
        for (const ss of document.styleSheets) {
            let rules; try { rules = ss.cssRules; } catch (e) { continue; }
            for (const r of rules) {
                if (r.selectorText && r.selectorText.indexOf('.wm-board.is-auto') === 0
                    && r.selectorText.includes(':hover') && r.style.borderColor
                    && !r.selectorText.includes(':not(.is-done)')) return r.selectorText;
            }
        }
        return null;
    })()`);
    check('自动学习模式的 hover 规则排除了已配对卡片（否则 tint 边框会被抹掉）',
        leaky === null, '漏网规则: ' + leaky);

    /* ---------- 6. 「改前」对照图：把 7 组变量全改成旧的中性灰 ---------- */
    await evalJs(`(() => {
        const root = document.querySelector('.wm-root').style;
        for (let i = 0; i < 7; i++) {
            root.setProperty('--wm-tint-' + i + '-bg', '#f1f3f5');
            root.setProperty('--wm-tint-' + i + '-line', '#e8ebee');
            root.setProperty('--wm-tint-' + i + '-ink', '#b4bbc4');
        }
        return true;
    })()`);
    await sleep(260);
    const oldContrast = await evalJs(`(() => {
        const cs = getComputedStyle(document.querySelector('.wm-card.is-done'));
        return { bg: cs.backgroundColor, color: cs.color };
    })()`);
    console.log('  改前（旧中性灰）对比度 = '
        + contrast(chan(oldContrast.bg), chan(oldContrast.color)).toFixed(2) + ':1');
    await shot('03-before-old-gray');

    check('页面无 JS 异常', pageErrors.length === 0, pageErrors.slice(0, 2).join(' | '));

    const bad = results.filter(r => !r.ok).length;
    console.log(`\n${results.length - bad}/${results.length} 通过    截图目录：${OUT}`);
    try { chrome.kill(); } catch (e) { /* ignore */ }
    process.exit(bad ? 1 : 0);
}

main().catch(e => {
    console.error('FAILED:', e.message);
    try { chrome.kill(); } catch (_) { /* ignore */ }
    process.exit(1);
});
