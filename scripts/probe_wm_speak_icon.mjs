#!/usr/bin/env node
/**
 * /learn/word-match 定点验证：卡片内「朗读图标」的点击行为。
 *
 * 背景（用户报的 BUG）：英文卡片右上角嵌了个小喇叭 `.wm-speak`，
 * 原实现是 `@click.stop="speak(card.text)"` —— 只朗读、且阻止冒泡，
 * 于是手滑点到图标时单词**不会被选中**。
 *
 * 修复后的契约（本脚本逐条守护）：
 *  P1 结构：只有英文卡有小喇叭；记录它的实际触控尺寸
 *  P2 【核心】未选中时点小喇叭 -> 该卡必须进入 is-sel
 *     （这条同时是「@click.stop 是否还在」的闸门：若 stop 被误删，
 *       事件会冒泡再触发一次 pick()，表现为选中后立刻被取消 -> P2 立刻红）
 *  P3 已选中时再点小喇叭 -> 仍保持选中（语义是「重听一遍」，不是取消）
 *  P4 紧接着点配对的中文卡 -> 两张都 is-done（pick 链路完整）
 *  P5 回归：点卡片本体 -> 正常选中
 *  P6 回归：点已选中的卡片本体 -> 取消选中（原行为不变）
 *  P7 运行期无 JS 异常 / console error
 *
 * 用法：node scripts/probe_wm_speak_icon.mjs
 * 可选环境变量：WM_BASE（默认 http://localhost:1888）、STORY_BROWSER_PATH
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

const results = [];
const check = (name, ok, extra = '') => {
    results.push({ name, ok: !!ok, extra });
    console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${extra ? '  [' + extra + ']' : ''}`);
};

const userDataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'wm-probe-'));
const chrome = spawn(CHROME, [
    '--headless=new', '--no-sandbox', '--disable-gpu', '--hide-scrollbars',
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

async function clickAt(x, y) {
    const p = { x, y, button: 'left', buttons: 1, clickCount: 1, pointerType: 'mouse' };
    await send('Input.dispatchMouseEvent', { type: 'mousePressed', ...p });
    await send('Input.dispatchMouseEvent', { type: 'mouseReleased', ...p });
}

/* 取元素中心点（只在可见元素里找） */
async function centerOf(selector, nth = 0) {
    return evalJs(`(() => {
        const els = Array.from(document.querySelectorAll(${JSON.stringify(selector)}))
            .filter(e => e.getClientRects().length > 0);
        const el = els[${nth}];
        if (!el) return null;
        const r = el.getBoundingClientRect();
        return { x: Math.round(r.x + r.width / 2), y: Math.round(r.y + r.height / 2),
                 w: Math.round(r.width), h: Math.round(r.height) };
    })()`);
}

async function waitCards() {
    for (let i = 0; i < 60; i++) {
        const n = await evalJs(`document.querySelectorAll('.wm-col-en .wm-card').length`);
        if (n >= 3) return n;
        await sleep(200);
    }
    throw new Error('卡片未渲染');
}

/* 当前选中的是哪张卡（null = 没有选中）。读 .wm-card-text 的 textContent，
   不用容器 innerText —— 后者会把图标的 title 之类混进来。 */
async function selInfo() {
    return evalJs(`(() => {
        const el = document.querySelector('.wm-card.is-sel');
        if (!el) return null;
        const col = el.closest('.wm-col');
        return { text: el.querySelector('.wm-card-text').textContent.trim(),
                 side: col && col.classList.contains('wm-col-en') ? 'L' : 'R' };
    })()`);
}

/* 第 nth 张英文卡的文本（用于断言点中的是哪张） */
async function leftCardText(nth) {
    return evalJs(`document.querySelectorAll('.wm-col-en .wm-card-text')[${nth}].textContent.trim()`);
}

async function main() {
    let ver = null;
    for (let i = 0; i < 80; i++) {
        try {
            const r = await fetch(`http://127.0.0.1:${PORT}/json/version`);
            if (r.ok) { ver = await r.json(); break; }
        } catch (e) { /* 还没起来 */ }
        await sleep(250);
    }
    if (!ver) throw new Error('Chrome CDP 端点未就绪，无法继续');
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
        }
    };

    await send('Page.enable');
    await send('Runtime.enable');
    await send('Emulation.setDeviceMetricsOverride', {
        width: 1280, height: 940, deviceScaleFactor: 1, mobile: false
    });

    /* 干净起点：清空存储（静音，避免本机 TTS 整轮念）后回到第一册第一关 */
    await send('Page.navigate', { url: PAGE });
    await sleep(700);
    await evalJs(`localStorage.clear()`);
    await evalJs(`localStorage.setItem('word_match_sound_v1', '0')`);
    await send('Page.navigate', { url: PAGE });
    await waitCards();
    await sleep(400);

    /* ---------- P1 结构 ---------- */
    console.log('\n— P1 结构：朗读图标只存在于英文卡 —');
    const enSpeak = await evalJs(`document.querySelectorAll('.wm-col-en .wm-card .wm-speak').length`);
    const zhSpeak = await evalJs(`document.querySelectorAll('.wm-col-zh .wm-card .wm-speak').length`);
    const enCard = await evalJs(`document.querySelectorAll('.wm-col-en .wm-card').length`);
    check('英文卡每张都有朗读图标', enSpeak === enCard, `英文卡 ${enCard} 张 / 图标 ${enSpeak} 个`);
    check('中文卡没有朗读图标', zhSpeak === 0, `中文卡内图标 ${zhSpeak} 个`);

    const iconBox = await centerOf('.wm-col-en .wm-card .wm-speak');
    check('朗读图标可点区域可取到', !!iconBox,
        iconBox ? `约 ${iconBox.w}x${iconBox.h} px（44px 触控红线之下，所以它必须「点了不丢选中」）` : '');

    /* ---------- P2 核心：误触图标也要选中 ---------- */
    console.log('\n— P2 误触图标：单词必须照样被选中 —');
    check('起点无选中', (await selInfo()) === null);

    const wantText = await leftCardText(0);
    await clickAt(iconBox.x, iconBox.y);
    await sleep(300);
    let sel = await selInfo();
    check('点朗读图标后第一张英文卡被选中', !!sel && sel.side === 'L' && sel.text === wantText,
        `期望「${wantText}」，实际 ${sel ? `「${sel.text}」(${sel.side})` : '未选中'}`);

    /* ---------- P3 已选中时点图标 = 重听，不取消 ---------- */
    console.log('\n— P3 已选中时点图标：应保持选中 —');
    const box2 = await centerOf('.wm-col-en .wm-card .wm-speak');
    await clickAt(box2.x, box2.y);
    await sleep(300);
    sel = await selInfo();
    check('已选中时再点图标仍保持选中（重听语义）', !!sel && sel.text === wantText,
        sel ? `选中「${sel.text}」` : '被取消了选中（退化成 point 卡片本体的「取消选中」）');

    /* ---------- P4 pick 链路完整：点配对中文卡能配对成功 ---------- */
    console.log('\n— P4 点图标选中的卡，仍能正常完成配对 —');
    const pairIdx = await evalJs(`(() => {
        const d = Alpine.$data(document.querySelector('.wm-board'));
        if (!d.sel) return -1;
        return d.rightCards.findIndex(c => !c.done && c.text === d.sel.mean);
    })()`);
    let matched = false;
    if (pairIdx >= 0) {
        const zhBox = await centerOf('.wm-col-zh .wm-card', pairIdx);
        await clickAt(zhBox.x, zhBox.y);
        await sleep(350);
        matched = await evalJs(`(() => {
            const d = Alpine.$data(document.querySelector('.wm-board'));
            return d.matchedCount;
        })()`) > 0;
    }
    check('配对成功（matchedCount > 0）', matched, pairIdx >= 0 ? `配对中文卡下标 ${pairIdx}` : '找不到配对中文卡');

    /* ---------- P5/P6 回归：卡片本体行为不变 ---------- */
    console.log('\n— P5/P6 回归：点卡片本体仍然「选中 / 再点取消」 —');
    const bodyBox = await centerOf('.wm-col-en .wm-card', 1);
    const bodyText = await leftCardText(1);
    await clickAt(bodyBox.x, bodyBox.y);
    await sleep(250);
    sel = await selInfo();
    check('点卡片本体 -> 选中', !!sel && sel.text === bodyText, `期望「${bodyText}」，实际 ${sel ? `「${sel.text}」` : '未选中'}`);

    const bodyBox2 = await centerOf('.wm-col-en .wm-card', 1);
    await clickAt(bodyBox2.x, bodyBox2.y);
    await sleep(250);
    sel = await selInfo();
    check('再点同一张卡片本体 -> 取消选中（原行为）', sel === null, sel ? `仍选中「${sel.text}」` : '已取消 ✔');

    /* ---------- P7 运行期错误 ---------- */
    console.log('\n— P7 运行期异常 —');
    check('无 JS 运行时异常', runtimeErrors.length === 0, runtimeErrors.slice(0, 3).join(' | '));
    check('无 console.error', consoleErrors.length === 0, consoleErrors.slice(0, 3).join(' | '));

    const bad = results.filter(r => !r.ok);
    console.log(`\n==== ${results.length - bad.length}/${results.length} 通过 ====`);
    return bad.length === 0;
}

let ok = false;
try {
    ok = await main();
} catch (e) {
    console.error('\n脚本异常: ' + e.message);
    ok = false;
} finally {
    try { ws && ws.close(); } catch (e) { /* ignore */ }
    chrome.kill('SIGKILL');
    try { fs.rmSync(userDataDir, { recursive: true, force: true }); } catch (e) { /* ignore */ }
}
process.exit(ok ? 0 : 1);
