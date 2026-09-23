#!/usr/bin/env node
/**
 * 针对性探针：本册通关弹窗（「本册全部通关！」）。
 *
 * 为什么单独验：这个弹窗改的是**按钮语义**——
 *   1) 去掉「继续看看」，关闭只走右上角的叉 / 点遮罩；
 *   2) 主按钮（默认蓝色）从「重做本册」换成「下一册」。
 * 这类改动最容易出的错是「看着对、实际点不动」：叉被别的东西盖住、或蓝色主按钮
 * 撞色成白字压白底（本项目在 .wm-nav 上真踩过这个坑）。所以这里用真实鼠标点，
 * 并把主按钮的**计算色**也断言掉。
 *
 * 还有一个只在「最后一册」出现的分支：没有下一册时，蓝色要退回「重做本册」，
 * 否则弹窗里一个主按钮都没有 —— 这条也必须实测。
 *
 * 用法：node probe_finish_dialog.mjs   （需脱沙箱）
 */
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const BASE = process.env.WM_BASE || 'http://localhost:1888';
const PAGE = BASE + '/learn/word-match';
const CHROME = process.env.STORY_BROWSER_PATH
    || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const PORT = 9336;
const OUT = process.env.WM_OUT || '/tmp/wm-shots';

const results = [];
const check = (name, ok, extra = '') => {
    results.push({ name, ok: !!ok });
    console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${extra ? '  [' + extra + ']' : ''}`);
};

fs.mkdirSync(OUT, { recursive: true });
const userDataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'wm-fin-'));
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
async function centerOf(selector, text = null) {
    return evalJs(`(() => {
        let els = Array.from(document.querySelectorAll(${JSON.stringify(selector)}))
            .filter(e => e.getClientRects().length > 0);
        ${text ? `els = els.filter(e => e.innerText.trim().includes(${JSON.stringify(text)}));` : ''}
        const el = els[0];
        if (!el) return null;
        const r = el.getBoundingClientRect();
        return { x: Math.round(r.x + r.width / 2), y: Math.round(r.y + r.height / 2),
                 text: el.innerText.trim().replace(/\\s+/g, ' ').slice(0, 60) };
    })()`);
}
async function clickSel(selector, text = null) {
    const box = await centerOf(selector, text);
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
/* 直接打开通关弹窗：它平时要打完整册才出现，这里只测弹窗本身，就手动置位。
   改数据后必须**另起一次求值**再读 DOM —— Alpine 重渲染是异步批处理的。 */
async function openFinish() {
    await evalJs(`(() => {
        Alpine.$data(document.querySelector('.wm-root')).finishedAll = true;
        return true;
    })()`);
    await sleep(320);
}
const BTN_INFO = `(() => {
    const m = document.querySelector('.wm-modal.is-finish');
    if (!m) return null;
    const pick = t => Array.from(m.querySelectorAll('button'))
        .find(b => b.innerText.replace(/\\s+/g, '').includes(t));
    const info = b => {
        if (!b) return null;
        const cs = getComputedStyle(b);
        return { cls: b.className, bg: cs.backgroundColor, color: cs.color,
                 visible: b.getClientRects().length > 0,
                 text: b.innerText.trim().replace(/\\s+/g, ' ') };
    };
    return { replay: info(pick('重做本册')), next: info(pick('下一册')),
             closeCount: m.querySelectorAll('.wm-modal-close').length,
             modalCls: m.className, modalPos: getComputedStyle(m).position };
})()`;

/* 撞色判定：颜色通道差之和 > 60 才算能看清（与既有排查口径一致） */
function contrastOk(fg, bg) {
    const num = (s) => (s.match(/\d+/g) || []).slice(0, 3).map(Number);
    const a = num(fg), b = num(bg);
    if (a.length < 3 || b.length < 3) return false;
    return Math.abs(a[0] - b[0]) + Math.abs(a[1] - b[1]) + Math.abs(a[2] - b[2]) > 60;
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
    await send('Emulation.setDeviceMetricsOverride',
        { width: 1280, height: 940, deviceScaleFactor: 1, mobile: false });

    await send('Page.navigate', { url: PAGE });
    await sleep(700);
    await evalJs(`localStorage.clear()`);
    await evalJs(`localStorage.setItem('word_match_sound_v1', '0')`);
    await send('Page.navigate', { url: PAGE });
    await waitCards();
    await sleep(400);

    /* ---------- F1. 弹窗结构：叉在右上、继续看看已消失 ---------- */
    await openFinish();
    const info = await evalJs(BTN_INFO);
    check('F1 通关弹窗打开（.wm-modal.is-finish 存在且 position:relative）',
        !!info && info.modalCls.includes('is-finish') && info.modalPos === 'relative',
        info ? info.modalCls + ' / ' + info.modalPos : 'null');

    const noMoreLook = await evalJs(`(() => {
        const m = document.querySelector('.wm-modal.is-finish');
        return Array.from(m.querySelectorAll('button'))
            .filter(b => b.innerText.replace(/\\s+/g, '').includes('继续看看') && b.getClientRects().length > 0).length;
    })()`);
    check('F2 「继续看看」按钮已移除（可见按钮里一个都没有）', noMoreLook === 0, '可见数量=' + noMoreLook);

    const closeBox = await evalJs(`(() => {
        const m = document.querySelector('.wm-modal.is-finish');
        const x = m.querySelector('.wm-modal-close');
        if (!x || !x.getClientRects().length) return null;
        const mr = m.getBoundingClientRect(), xr = x.getBoundingClientRect();
        return { rightGap: Math.round(mr.right - xr.right), topGap: Math.round(xr.top - mr.top),
                 w: Math.round(xr.width), h: Math.round(xr.height) };
    })()`);
    check('F3 右上角关闭叉存在且可见，贴在弹窗右上（right/top 内缩 < 40px）',
        !!closeBox && closeBox.rightGap < 40 && closeBox.topGap < 40,
        closeBox ? `right=${closeBox.rightGap} top=${closeBox.topGap} ${closeBox.w}x${closeBox.h}` : 'null');

    /* ---------- F2. 主按钮语义：下一册蓝、重做本册描边 ---------- */
    check('F4 有下一册时：「下一册」是 btn-primary（默认蓝色主按钮）',
        !!info && !!info.next && info.next.visible && /btn-primary/.test(info.next.cls),
        info && info.next ? info.next.cls : 'null');
    check('F5 有下一册时：「重做本册」降为 btn-outline-secondary',
        !!info && !!info.replay && /btn-outline-secondary/.test(info.replay.cls),
        info && info.replay ? info.replay.cls : 'null');
    const nextBlue = info && info.next
        && info.next.bg === 'rgb(13, 110, 253)' && contrastOk(info.next.color, info.next.bg);
    check('F6 主按钮计算底色 = Bootstrap 蓝 #0d6efd，且文字与底色不撞（差 > 60）',
        nextBlue, info && info.next ? `${info.next.bg} / ${info.next.color}` : 'null');
    const replayOk = info && info.replay && contrastOk(info.replay.color, info.replay.bg);
    check('F7 「重做本册」文字与底色不撞（避免白字压白底那类坑）',
        replayOk, info && info.replay ? `${info.replay.bg} / ${info.replay.color}` : 'null');
    await shot('fin-01-dialog');

    /* ---------- F3. 关闭：点叉 ---------- */
    await clickSel('.wm-modal.is-finish .wm-modal-close');
    await sleep(300);
    const closedByX = await evalJs(
        `Alpine.$data(document.querySelector('.wm-root')).finishedAll`);
    check('F8 点右上角的叉能关掉弹窗（finishedAll -> false）', closedByX === false,
        'finishedAll=' + closedByX);

    /* ---------- F4. 最后一册：蓝色退回「重做本册」 ---------- */
    const lastBook = await evalJs(`(() => {
        const d = Alpine.$data(document.querySelector('.wm-root'));
        const last = d.books[d.books.length - 1];
        d.bookId = last.id;
        return { id: last.id, label: last.label };
    })()`);
    await openFinish();
    const infoLast = await evalJs(BTN_INFO);
    check('F9 已是最后一册时：「下一册」不显示',
        !!infoLast && !!infoLast.next && infoLast.next.visible === false,
        infoLast && infoLast.next ? 'visible=' + infoLast.next.visible : 'null');
    check('F10 已是最后一册时：蓝色主按钮退回「重做本册」（弹窗里始终有一个主按钮）',
        !!infoLast && !!infoLast.replay
        && /btn-primary/.test(infoLast.replay.cls)
        && infoLast.replay.bg === 'rgb(13, 110, 253)',
        infoLast && infoLast.replay ? `${infoLast.replay.cls} / ${infoLast.replay.bg}` : 'null');
    await shot('fin-02-last-book');

    check('F11 页面无未捕获 JS 异常', pageErrors.length === 0, pageErrors.slice(0, 2).join(' | '));

    const pass = results.filter(r => r.ok).length;
    console.log(`\n${pass}/${results.length} 通过`);
    chrome.kill();
    process.exit(pass === results.length ? 0 : 1);
}

main().catch(e => { console.error('探针异常:', e); chrome.kill(); process.exit(2); });
