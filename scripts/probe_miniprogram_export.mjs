#!/usr/bin/env node
/**
 * /learn/word-match「设置 → 导出小程序」的针对性探针。
 *
 * 为什么单独写：这个入口的前半截（模板 + 词库打包）由服务端完成，已经有单测
 * （MiniprogramExporterTest）和接口探针（probe_mp_export）兜住；这里只验
 * **用户真正会碰到的那几屏**：设置里的条目、弹窗文案、下载链接，以及最容易被
 * 忽略的「单页导出件里要跟着消失」（那是个纯 CSS 约定，改类名就会静默失效）。
 *
 * 用法：WM_BASE=http://localhost:1889 node scripts/probe_miniprogram_export.mjs
 * 产物：/tmp/wm-mp-shots/*.png
 */
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { SILENT_AUDIO_FLAGS, installSilentAudio } from './lib/silent-audio.mjs';

const BASE = process.env.WM_BASE || 'http://localhost:1888';
const PAGE = BASE + '/learn/word-match';
const CHROME = process.env.STORY_BROWSER_PATH
    || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const PORT = 9400 + Math.floor(Math.random() * 90);
const OUT = process.env.WM_OUT || '/tmp/wm-mp-shots';
const ZIP_URL = BASE + '/learn/word-match/miniprogram.zip';

fs.mkdirSync(OUT, { recursive: true });

let pass = 0;
let fail = 0;
function check(name, ok, detail) {
    if (ok) { pass++; console.log('  ✓ ' + name + (detail ? ' — ' + detail : '')); }
    else { fail++; console.log('  ✗ ' + name + (detail ? ' — ' + detail : '')); }
}

const userDataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'wm-mp-'));
const chrome = spawn(CHROME, [
    '--headless=new', '--no-sandbox', '--disable-gpu', '--hide-scrollbars',
    ...SILENT_AUDIO_FLAGS,
    '--remote-debugging-port=' + PORT, '--user-data-dir=' + userDataDir, 'about:blank'
], { stdio: 'ignore' });
chrome.on('error', (e) => {
    console.error('浏览器启动失败：' + e.message);
    process.exit(1);
});

const sleep = (ms) => new Promise(r => setTimeout(r, ms));
let ws;
let msgId = 0;
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
        return { x: Math.round(r.x + r.width / 2), y: Math.round(r.y + r.height / 2) };
    })()`);
}
async function clickSel(selector, text = null) {
    const box = await centerOf(selector, text);
    if (!box) throw new Error('找不到可点元素：' + selector + (text ? ' text=' + text : ''));
    await clickAt(box.x, box.y);
}
async function shot(name) {
    const r = await send('Page.captureScreenshot', { format: 'png' });
    fs.writeFileSync(path.join(OUT, name + '.png'), Buffer.from(r.data, 'base64'));
}
async function waitFor(desc, fn, tries = 60, gap = 150) {
    for (let i = 0; i < tries; i++) {
        if (await fn()) return true;
        await sleep(gap);
    }
    throw new Error('等待超时：' + desc);
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
    await installSilentAudio(send);
    await send('Runtime.enable');
    await send('Page.enable');
    await send('Emulation.setDeviceMetricsOverride', {
        width: 1280, height: 940, deviceScaleFactor: 1, mobile: false
    });

    /* ---------- 1. 设置弹层 ---------- */
    console.log('\n— 设置弹层 —');
    await send('Page.navigate', { url: PAGE });
    await waitFor('页面初始化', async () => await evalJs(`!!document.querySelector('.wm-set-btn')`));
    await sleep(1200);

    await clickSel('.wm-set-btn');
    await waitFor('设置弹层展开', async () => await evalJs(
        `(() => { const p = document.querySelector('.wm-set-pop'); return !!p && p.getClientRects().length > 0; })()`));

    const panel = await evalJs(`(() => Array.from(document.querySelectorAll('.wm-set-item'))
        .map(it => ({
            title: it.querySelector('.wm-set-title').innerText.trim(),
            desc: it.querySelector('.wm-set-desc').innerText.trim(),
            visible: getComputedStyle(it).display !== 'none'
        })))()`);
    const titles = panel.map(p => p.title);
    check('设置里有 7 个条目', panel.length === 7, titles.join(' / '));
    check('两个导出条目并存且文案不撞车',
        titles.includes('导出 HTML') && titles.includes('导出小程序'), titles.join(' / '));
    check('两个导出都有说明文字',
        panel.filter(p => p.title.startsWith('导出')).every(p => p.desc.length > 6),
        panel.filter(p => p.title.startsWith('导出')).map(p => p.desc).join(' | '));
    /* 弹层有 wm-pop-soft(.26s) 的缩放+淡入；不等它跑完就截图，
       底下的「搜索」按钮会从半透明里透出来，看起来像 z-index 被击穿。 */
    await sleep(360);
    const paintOk = await evalJs(`(() => {
        const pop = document.querySelector('.wm-set-pop');
        const r = pop.getBoundingClientRect();
        const el = document.elementFromPoint(Math.round(r.x + r.width - 24), Math.round(r.y + 60));
        return { hit: el ? (el.className || '').toString().slice(0, 40) : '',
                 inside: !!el && !!el.closest('.wm-set-pop') };
    })()`);
    check('设置弹层没有被页面元素穿透（弹层内命中弹层自己）',
        paintOk.inside === true, paintOk.hit || 'null');
    await shot('01-settings');

    /* ---------- 2. 打开导出小程序弹窗 ---------- */
    console.log('\n— 导出小程序弹窗 —');
    await clickSel('.wm-set-item', '导出小程序');
    await waitFor('弹窗可见', async () => await evalJs(
        `(() => { const m = document.querySelector('#wm-mp-modal'); return !!m && m.getClientRects().length > 0; })()`));

    const modal = await evalJs(`(() => {
        const m = document.querySelector('#wm-mp-modal');
        const link = document.querySelector('#wm-mp-go');
        const d = Alpine.$data(document.querySelector('.wm-root'));
        return {
            head: m.querySelector('.wm-modal-head h5').innerText.trim(),
            badge: m.querySelector('.wm-count-total').innerText.trim(),
            text: m.innerText.replace(/\\s+/g, ' '),
            blocks: m.querySelectorAll('.wm-mp-block').length,
            blockTitles: Array.from(m.querySelectorAll('.wm-mp-block-title'))
                .map(e => e.innerText.trim()).join(' / '),
            bullets: m.querySelectorAll('.wm-mp-block ul li').length,
            steps: m.querySelectorAll('.wm-mp-block ol li').length,
            hasDownloadHandler: typeof d.onMpDownload === 'function',
            href: link ? link.getAttribute('href') : '',
            download: link ? link.getAttribute('download') : '',
            btn: link ? link.innerText.trim() : ''
        };
    })()`);
    check('标题是「导出小程序源码」', modal.head.includes('导出小程序源码'), modal.head);
    check('说清了导入去向（微信开发者工具）', /微信开发者工具/.test(modal.text));
    check('右上徽标同时报册数与关数（不再只报册数）',
        /\d+\s*册/.test(modal.badge) && /\d+\s*关/.test(modal.badge), modal.badge);
    check('内容分成「包里有什么 / 下载后三步」两块',
        modal.blocks === 2 && modal.blockTitles === '包里有什么 / 下载后三步', modal.blockTitles);
    check('包内容三条（页面代码 / 完整词库 / 项目配置）', modal.bullets === 3, String(modal.bullets));
    check('下载后三步齐全（解压 / 导入 / 预览上传）', modal.steps === 3, String(modal.steps));
    check('点明了词库是现算的 / 与网页一致', /一致/.test(modal.text));
    check('提醒了 appid 留空', /appid/.test(modal.text));
    check('下载按钮接了收尾处理器（关弹窗 + 后续指引）', modal.hasDownloadHandler === true);
    check('下载链接指向导出接口', modal.href === '/learn/word-match/miniprogram.zip', modal.href);
    check('链接带 download 文件名', modal.download === 'word-pair-miniprogram.zip', modal.download);
    check('按钮文案是「下载小程序包」', modal.btn.includes('下载小程序包'), modal.btn);
    await sleep(360);            // 等 wm-pop-soft（.26s）跑完，否则截到半透明的中间态
    await shot('02-export-modal');

    /* ---------- 3. 取消能关 ---------- */
    console.log('\n— 取消关闭 —');
    await clickSel('#wm-mp-cancel');
    await waitFor('弹窗关闭', async () => await evalJs(
        `(() => { const m = document.querySelector('#wm-mp-modal'); return !m || m.getClientRects().length === 0; })()`));
    check('点「取消」后弹窗关闭', true);

    /* ---------- 3.5 窄屏 390：内容多了一倍，别把弹窗撑出视口 ---------- */
    console.log('\n— 窄屏 390 —');
    await send('Emulation.setDeviceMetricsOverride', {
        width: 390, height: 780, deviceScaleFactor: 2, mobile: true
    });
    await sleep(300);
    await clickSel('.wm-set-btn');
    await waitFor('窄屏设置弹层展开', async () => await evalJs(
        `(() => { const p = document.querySelector('.wm-set-pop'); return !!p && p.getClientRects().length > 0; })()`));
    await clickSel('.wm-set-item', '导出小程序');
    await waitFor('窄屏弹窗可见', async () => await evalJs(
        `(() => { const m = document.querySelector('#wm-mp-modal'); return !!m && m.getClientRects().length > 0; })()`));
    await sleep(360);
    const narrow = await evalJs(`(() => {
        const m = document.querySelector('#wm-mp-modal');
        const r = m.getBoundingClientRect();
        return {
            docW: document.documentElement.scrollWidth, winW: window.innerWidth,
            left: Math.round(r.left), right: Math.round(r.right),
            bottom: Math.round(r.bottom), vh: window.innerHeight,
            /* 内容被裁 = 有横向滚动却看不见滚动条，用户会以为句子就这么短 */
            hClip: m.scrollWidth > m.clientWidth + 1
        };
    })()`);
    check('窄屏(390) 弹窗没超出左右边界', narrow.left >= -1 && narrow.right <= narrow.winW + 1,
        `left=${narrow.left} right=${narrow.right} win=${narrow.winW}`);
    check('窄屏(390) 页面没有横向溢出', narrow.docW <= narrow.winW + 2,
        `${narrow.docW}/${narrow.winW}`);
    check('窄屏(390) 弹窗纵向放得下（超长时自身滚动）', narrow.bottom <= narrow.vh + 1,
        `bottom=${narrow.bottom} vh=${narrow.vh}`);
    check('窄屏(390) 内容没有被横向裁掉', narrow.hClip === false, String(narrow.hClip));
    await shot('03-narrow');
    await clickSel('#wm-mp-cancel');
    await send('Emulation.setDeviceMetricsOverride', {
        width: 1280, height: 940, deviceScaleFactor: 1, mobile: false
    });

    /* ---------- 4. 接口本身（与页面同源，确认链接不是死链） ---------- */
    console.log('\n— 下载链接可达 —');
    const resp = await fetch(ZIP_URL);
    const buf = Buffer.from(await resp.arrayBuffer());
    check('链接返回 200', resp.status === 200, String(resp.status));
    check('是 zip（PK 魔数）', buf.length > 2 && buf[0] === 0x50 && buf[1] === 0x4b,
        (buf.length / 1024).toFixed(0) + ' KB');

    /* ---------- 5. 无 JS 异常 ---------- */
    check('全程零 JS 异常', pageErrors.length === 0, pageErrors.join(' | ') || '0');

    console.log('\n结果：' + pass + ' 通过 / ' + fail + ' 失败');
    console.log('截图：' + OUT);
}

try {
    await main();
} catch (e) {
    console.error('探针中断：' + e.message);
    fail++;
} finally {
    try { ws && ws.close(); } catch (e) { /* ignore */ }
    chrome.kill('SIGKILL');
    // Chrome 刚被杀时缓存文件可能还占着，删不掉就算了（临时目录，系统会清）
    try { fs.rmSync(userDataDir, { recursive: true, force: true }); } catch (e) { /* ignore */ }
}
process.exit(fail === 0 ? 0 : 1);
