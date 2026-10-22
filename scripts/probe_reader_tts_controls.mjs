#!/usr/bin/env node
/**
 * /projects/{id}/read 阅读页 · **章节 TTS 三态按钮 + 播完 3 秒自动续播**（2026-10-21）。
 *
 * 改造前后的差别正是本脚本要守的东西：
 *   - 原来一个按钮兼「播放/停止」：靠 `tts.playing === cn` 切换图标，播放中没法暂停
 *   - 现在分三态：停止=只【播放】／播放中=【暂停】【停止】／暂停中=【播放】【停止】，
 *     两个按钮之间留 6px，按钮定宽 4.5rem（三态切换不跳动）
 *   - 一章播完不再「就地归零」：先保持暂停态等 3 秒，再自动接下一章；
 *     这 3 秒里【停止】能掐掉续播，【播放】能立刻续播
 *
 * 页面是本地文件拦截注入的（1888 上跑的是旧 jar），数据/TTS 接口全部 mock。
 * 音频用内存里合成的 0.3s 静音 WAV，保证 onended 真会触发。
 *
 * 用法：
 *   node scripts/probe_reader_tts_controls.mjs            # 默认打 1888（只借它的静态资源）
 * 环境变量：READER_BASE / STORY_BROWSER_PATH
 */
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const PAGE_FILE = path.join(HERE, '..', 'src/main/resources/static/pages/reader.html');
const BASE = process.env.READER_BASE || 'http://localhost:1888';
const CHROME = process.env.STORY_BROWSER_PATH
    || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const PORT = 9377;
const SHOT_DIR = '/tmp/reader-tts-shots';

const results = [];
const check = (name, ok, extra = '') => {
    results.push({ name, ok: !!ok, extra });
    console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${extra ? '  [' + extra + ']' : ''}`);
};

const sleep = (ms) => new Promise(r => setTimeout(r, ms));

/* ==================== 0.3s 静音 WAV（保证 onended 会触发） ==================== */
function silentWav(seconds = 0.3, rate = 8000) {
    const n = Math.floor(seconds * rate);
    const dataLen = n * 2;
    const buf = Buffer.alloc(44 + dataLen);
    buf.write('RIFF', 0);
    buf.writeUInt32LE(36 + dataLen, 4);
    buf.write('WAVE', 8);
    buf.write('fmt ', 12);
    buf.writeUInt32LE(16, 16);
    buf.writeUInt16LE(1, 20);
    buf.writeUInt16LE(1, 22);
    buf.writeUInt32LE(rate, 24);
    buf.writeUInt32LE(rate * 2, 28);
    buf.writeUInt16LE(2, 32);
    buf.writeUInt16LE(16, 34);
    buf.write('data', 36);
    buf.writeUInt32LE(dataLen, 40);
    return buf.toString('base64');
}
const WAV_B64 = silentWav(0.3);

/* ==================== 假数据 ==================== */
const PARA = '雨下了一整夜。山道泥泞，他一步一滑。';
const DATA = {
    projectId: 1,
    projectTitle: 'TTS 三态探针项目',
    author: '张三',
    volumes: [{
        volumeNumber: 1, title: '第一卷', arcName: '开篇',
        chapterStart: 1, chapterEnd: 3, chapterNumbers: [1, 2, 3]
    }],
    chapters: [1, 2, 3].map(n => ({
        number: n,
        title: '第' + n + '章标题',
        content: Array.from({ length: 2 }, () => PARA).join('\n\n'),
        wordCount: 40,
        expansionStatus: null
    })),
    sideStories: []
};
const CONFIGS = [{
    id: 1, displayName: '探针 TTS 配置', isDefault: true,
    voices: 'alloy,echo', format: 'wav'
}];

/* ==================== Chrome ==================== */
const userDataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'reader-tts-probe-'));
const chrome = spawn(CHROME, [
    '--headless=new', '--no-sandbox', '--disable-gpu', '--hide-scrollbars', '--mute-audio',
    // 没有真实用户手势也要允许 play()，否则音频永远停在 paused、onended 不触发
    '--autoplay-policy=no-user-gesture-required',
    '--remote-debugging-port=' + PORT, '--user-data-dir=' + userDataDir, 'about:blank'
], { stdio: 'ignore' });

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

async function waitFor(label, fn, timeout = 8000, step = 100) {
    const t0 = Date.now();
    while (Date.now() - t0 < timeout) {
        if (await fn()) return true;
        await sleep(step);
    }
    return false;
}

/* ==================== 网络拦截 ==================== */
const PAGE_HTML = fs.readFileSync(PAGE_FILE, 'utf8');
const b64 = (s) => Buffer.from(s, 'utf8').toString('base64');
function fulfilRaw(requestId, code, contentType, bodyB64) {
    return send('Fetch.fulfillRequest', {
        requestId,
        responseCode: code,
        responseHeaders: [{ name: 'Content-Type', value: contentType }],
        body: bodyB64
    });
}
const fulfilText = (id, code, ct, text) => fulfilRaw(id, code, ct, b64(text));
const fulfilJson = (id, obj) => fulfilText(id, 200, 'application/json; charset=utf-8', JSON.stringify(obj));

async function handlePaused(p) {
    const u = new URL(p.request.url);
    const pn = u.pathname;
    if (/^\/api\/projects\/1\/read-data$/.test(pn)) return fulfilJson(p.requestId, DATA);
    if (pn === '/projects/1/read') {
        // 1888 上跑的是旧 jar，页面本体必须由本地文件顶上
        return fulfilText(p.requestId, 200, 'text/html; charset=utf-8', PAGE_HTML);
    }
    if (pn === '/projects/1/tts/configs') return fulfilJson(p.requestId, CONFIGS);
    if (/^\/api\/tts-export\/projects\/1\/completed-files$/.test(pn)) return fulfilJson(p.requestId, {});
    if (/^\/projects\/1\/tts\/chapter\/\d+\/chunks$/.test(pn)) return fulfilJson(p.requestId, ['第一段文本。']);
    if (pn === '/projects/1/tts/chunk') return fulfilRaw(p.requestId, 200, 'audio/wav', WAV_B64);
    return send('Fetch.continueRequest', { requestId: p.requestId });
}

/* ==================== 快照 ==================== */
const SNAP = `(() => {
    const d = window.Alpine ? Alpine.$data(document.body) : null;
    const vis = (el) => !!el && el.getClientRects().length > 0
        && getComputedStyle(el).display !== 'none';
    const rows = Array.from(document.querySelectorAll('.reader-main h3[id^="chapter-"]')).map(h3 => {
        const box = h3.parentElement;
        const btns = Array.from(box.querySelectorAll('.tts-chapter-btn')).filter(vis);
        const rects = btns.map(b => b.getBoundingClientRect());
        const hint = box.querySelector('.tts-next-hint');
        return {
            cn: parseInt(h3.id.replace('chapter-', '')),
            labels: btns.map(b => (b.innerText || '').replace(/\\s+/g, ' ').trim()),
            widths: rects.map(r => Math.round(r.width)),
            gap: rects.length > 1 ? Math.round(rects[1].left - rects[0].right) : null,
            sameRow: rects.length > 1 ? Math.abs(rects[0].top - rects[1].top) < 2 : null,
            hint: vis(hint) ? (hint.innerText || '').trim() : ''
        };
    });
    return {
        ready: !!(d && window.Alpine),
        booted: d ? d.booting === false : null,
        playing: d ? d.tts.playing : null,
        paused: d ? d.tts.paused : null,
        loading: d ? d.tts.loading : null,
        nextChapter: d ? d.tts.nextChapter : null,
        playlistActive: d ? d.playlist.isActive : null,
        plIndex: d ? d.playlist.currentIndex : null,
        rows
    };
})()`;
const snap = async (ms = 200) => { if (ms) await sleep(ms); return evalJs(SNAP); };

/** 点某章那一行里文案含 label 的可见按钮 */
async function clickRowBtn(cn, label) {
    return evalJs(`(() => {
        const h3 = document.getElementById('chapter-${cn}');
        if (!h3) return 'no-row';
        const box = h3.parentElement;
        const btn = Array.from(box.querySelectorAll('.tts-chapter-btn'))
            .find(b => b.getClientRects().length && (b.innerText || '').indexOf(${JSON.stringify(label)}) >= 0);
        if (!btn) return 'no-btn';
        btn.click();
        return 'ok';
    })()`);
}

async function shot(name) {
    const r = await send('Page.captureScreenshot', { format: 'png' });
    fs.mkdirSync(SHOT_DIR, { recursive: true });
    fs.writeFileSync(path.join(SHOT_DIR, name + '.png'), Buffer.from(r.data, 'base64'));
}

let navSeq = 0;
async function goto(width = 1280, height = 900) {
    await send('Emulation.setDeviceMetricsOverride', { width, height, deviceScaleFactor: 1, mobile: false });
    await send('Page.navigate', { url: BASE + '/projects/1/read?probe=' + (++navSeq) });
}

async function waitReady() {
    return waitFor('页面就绪', async () => {
        try {
            const s = await snap(0);
            return s.ready && s.booted && s.rows.length === 3;
        } catch (e) { return false; }
    }, 20000, 150);
}

/* ==================== 主流程 ==================== */
async function main() {
    let ver = null;
    for (let i = 0; i < 80; i++) {
        try {
            const r = await fetch(`http://127.0.0.1:${PORT}/json/version`);
            if (r.ok) { ver = await r.json(); break; }
        } catch (e) { /* 还没起来 */ }
        await sleep(250);
    }
    if (!ver) throw new Error('Chrome CDP 端点未就绪');
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
        } else if (m.method === 'Fetch.requestPaused') {
            handlePaused(m.params).catch(e => consoleErrors.push('Fetch 拦截失败: ' + e.message));
        }
    };

    await send('Runtime.enable');
    await send('Page.enable');
    await send('Fetch.enable', {
        patterns: [
            { urlPattern: '*/projects/1/read*', requestStage: 'Request' },
            { urlPattern: '*/tts/*', requestStage: 'Request' },
            { urlPattern: '*/completed-files*', requestStage: 'Request' }
        ]
    });

    /* ---------- P0 停止态 ---------- */
    console.log('\n— P0 停止态：每章只有一个【播放】 —');
    await goto();
    check('页面就绪并渲染 3 章', await waitReady());
    let s = await snap(300);
    const allSingle = s.rows.every(r => r.labels.length === 1 && r.labels[0] === '播放');
    check('【核心】停止态每章只有 1 个按钮且文案是【播放】',
        allSingle, JSON.stringify(s.rows.map(r => r.labels)));
    check('【核心】按钮已加宽（≥ 60px，原来纯图标约 31px）',
        s.rows.every(r => r.widths[0] >= 60), JSON.stringify(s.rows.map(r => r.widths)));
    check('初始 tts.playing 为 null', s.playing === null, 'playing=' + s.playing);
    await shot('01-stopped');

    /* ---------- P1 播放态 ---------- */
    console.log('\n— P1 播放中：【暂停】【停止】两个按钮，且有间隔 —');
    check('点第 1 章【播放】', (await clickRowBtn(1, '播放')) === 'ok');
    check('进入播放态（playing=1, paused=false）',
        await waitFor('playing=1', async () => {
            const x = await snap(0);
            return x.playing === 1 && x.paused === false;
        }, 6000));
    s = await snap(150);
    const row1 = s.rows.find(r => r.cn === 1);
    check('【核心】播放中该章是两个按钮【暂停】【停止】',
        row1.labels.length === 2 && row1.labels[0] === '暂停' && row1.labels[1] === '停止',
        JSON.stringify(row1.labels));
    check('【核心】两个按钮之间留了间隔（≥ 4px）', row1.gap !== null && row1.gap >= 4, 'gap=' + row1.gap);
    check('两个按钮在同一行（不换行）', row1.sameRow === true);
    check('三态按钮等宽（切换不跳动）',
        row1.widths[0] === row1.widths[1], JSON.stringify(row1.widths));
    check('其它章仍是单【播放】',
        s.rows.filter(r => r.cn !== 1).every(r => r.labels.length === 1 && r.labels[0] === '播放'),
        JSON.stringify(s.rows.filter(r => r.cn !== 1).map(r => r.labels)));
    await shot('02-playing');

    /* ---------- P2 暂停态 ---------- */
    console.log('\n— P2 暂停中：【播放】【停止】，且真的停在那儿 —');
    check('点【暂停】', (await clickRowBtn(1, '暂停')) === 'ok');
    s = await snap(200);
    check('【核心】暂停后 playing 仍是 1、paused=true', s.playing === 1 && s.paused === true,
        `playing=${s.playing} paused=${s.paused}`);
    let rowP = s.rows.find(r => r.cn === 1);
    check('【核心】暂停态两个按钮是【播放】【停止】',
        rowP.labels.length === 2 && rowP.labels[0] === '播放' && rowP.labels[1] === '停止',
        JSON.stringify(rowP.labels));
    check('暂停态按钮宽度与播放态一致（不跳动）',
        rowP.widths[0] === row1.widths[0], `${rowP.widths[0]} vs ${row1.widths[0]}`);
    // 暂停后音频不该自己播完：等 1.5s 看有没有被续播抢走
    await sleep(1500);
    s = await snap(0);
    check('【核心】暂停 1.5s 后仍停在第 1 章（没有偷偷播完/续播）',
        s.playing === 1 && s.paused === true && s.nextChapter === null,
        `playing=${s.playing} paused=${s.paused} next=${s.nextChapter}`);
    await shot('03-paused');

    /* ---------- P3 恢复 ---------- */
    console.log('\n— P3 暂停态点【播放】恢复 —');
    check('点【播放】', (await clickRowBtn(1, '播放')) === 'ok');
    s = await snap(200);
    check('【核心】恢复后回到播放态（paused=false，按钮变回【暂停】【停止】）',
        s.paused === false && (s.rows.find(r => r.cn === 1).labels[0] === '暂停'),
        `paused=${s.paused} labels=${JSON.stringify(s.rows.find(r => r.cn === 1).labels)}`);

    /* ---------- P4 播完 3 秒自动续播 ---------- */
    console.log('\n— P4 第 1 章播完：3 秒空档后自动播第 2 章 —');
    check('【核心】播完后进入「暂停态 + 待续播」空档（nextChapter=2）',
        await waitFor('nextChapter=2', async () => {
            const x = await snap(0);
            return x.playing === 1 && x.paused === true && x.nextChapter === 2;
        }, 6000), '');
    s = await snap(0);
    const rowGap = s.rows.find(r => r.cn === 1);
    check('空档期按钮仍可操作（【播放】【停止】）',
        rowGap.labels.length === 2 && rowGap.labels[0] === '播放' && rowGap.labels[1] === '停止',
        JSON.stringify(rowGap.labels));
    check('【核心】空档期给出「即将播放第N章」提示',
        rowGap.hint.indexOf('即将播放第2章') >= 0, rowGap.hint);
    await shot('03b-next-hint');
    check('【核心】3 秒后自动切到第 2 章（playing=2, paused=false）',
        await waitFor('auto next', async () => {
            const x = await snap(0);
            return x.playing === 2 && x.paused === false;
        }, 7000, 120));
    s = await snap(150);
    check('自动切换后第 2 章显示【暂停】【停止】',
        (() => { const r = s.rows.find(x => x.cn === 2); return r.labels[0] === '暂停' && r.labels[1] === '停止'; })(),
        JSON.stringify(s.rows.find(x => x.cn === 2).labels));
    check('自动切换未污染 playlist（单章连播不冒充「全文收听」）', s.playlistActive === false,
        'playlistActive=' + s.playlistActive);
    await shot('04-auto-next');

    /* ---------- P5 空档里点停止 = 掐掉续播 ---------- */
    console.log('\n— P5 空档期点【停止】：不许再自己续播 —');
    check('等第 2 章播完进入空档',
        await waitFor('nextChapter=3', async () => {
            const x = await snap(0);
            return x.playing === 2 && x.nextChapter === 3;
        }, 6000));
    check('点【停止】', (await clickRowBtn(2, '停止')) === 'ok');
    s = await snap(150);
    check('【核心】点停止后立刻回到停止态（playing=null）', s.playing === null && s.nextChapter === null,
        `playing=${s.playing} next=${s.nextChapter}`);
    await sleep(4000);
    s = await snap(0);
    check('【核心】之后 4 秒内没有再自动开播（定时器被清掉）',
        s.playing === null && s.nextChapter === null, 'playing=' + s.playing);
    check('第 2 章回到单【播放】按钮',
        (() => { const r = s.rows.find(x => x.cn === 2); return r.labels.length === 1 && r.labels[0] === '播放'; })());

    /* ---------- P6 最后一章播完不续播 ---------- */
    console.log('\n— P6 最后一章播完：停在停止态 —');
    check('点第 3 章【播放】', (await clickRowBtn(3, '播放')) === 'ok');
    check('第 3 章开始播', await waitFor('playing=3', async () => (await snap(0)).playing === 3, 6000));
    check('【核心】最后一章播完直接停止（无 nextChapter，不空转）',
        await waitFor('stopped', async () => {
            const x = await snap(0);
            return x.playing === null && x.nextChapter === null;
        }, 6000));
    await shot('05-last-chapter-stopped');

    /* ---------- P7 手动切章 ---------- */
    console.log('\n— P7 播放中直接点另一章的【播放】：就地切过去 —');
    check('点第 1 章【播放】', (await clickRowBtn(1, '播放')) === 'ok');
    await waitFor('playing=1', async () => (await snap(0)).playing === 1, 6000);
    check('点第 3 章【播放】', (await clickRowBtn(3, '播放')) === 'ok');
    s = await snap(250);
    check('【核心】立刻切到第 3 章，第 1 章回到单【播放】',
        s.playing === 3
        && (() => { const r = s.rows.find(x => x.cn === 1); return r.labels.length === 1 && r.labels[0] === '播放'; })(),
        `playing=${s.playing} row1=${JSON.stringify(s.rows.find(x => x.cn === 1).labels)}`);
    check('切章后没有残留的续播定时器', s.nextChapter === null, 'next=' + s.nextChapter);

    /* ---------- P8 窄屏 ---------- */
    console.log('\n— P8 390px 窄屏：两按钮不换行、无横向溢出 —');
    await goto(390, 780);
    await waitReady();
    await clickRowBtn(1, '播放');
    await waitFor('playing=1', async () => (await snap(0)).playing === 1, 6000);
    s = await snap(200);
    const rowM = s.rows.find(r => r.cn === 1);
    const overflow = await evalJs('(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)()');
    check('窄屏两按钮仍同一行', rowM.sameRow === true, JSON.stringify(rowM.labels));
    check('窄屏无横向溢出', overflow <= 1, 'overflow=' + overflow);
    await shot('06-mobile-playing');

    /* ---------- P8b 全文收听：续播路径被重构过，必须同时守住 ---------- */
    console.log('\n— P8b 全文收听：同样走「3 秒后续下一章」，进度要跟着走 —');
    await goto();
    await waitReady();
    const clicked = await evalJs(`(() => {
        const b = Array.from(document.querySelectorAll('.tts-toolbar button'))
            .find(x => (x.innerText || '').indexOf('全文收听') >= 0);
        if (!b) return false;
        b.click();
        return true;
    })()`);
    check('点【全文收听】', clicked === true);
    check('进入全文收听并开始播第 1 章',
        await waitFor('playlist playing', async () => {
            const x = await snap(0);
            return x.playlistActive === true && x.playing === 1;
        }, 8000));
    check('【核心】全文收听也等 3 秒再续（先经过 nextChapter 空档）',
        await waitFor('gap', async () => (await snap(0)).nextChapter === 2, 8000));
    check('【核心】自动续到第 2 章且 playlist.currentIndex 同步为 1',
        await waitFor('next ch2', async () => {
            const x = await snap(0);
            return x.playing === 2 && x.plIndex === 1;
        }, 8000, 120));
    s = await snap(150);
    check('全文收听中顶部进度文案跟着更新',
        (await evalJs(`(() => {
            const el = Array.from(document.querySelectorAll('.tts-toolbar .small.text-muted'))
                .find(x => (x.innerText || '').indexOf('章 (') >= 0);
            return el ? el.innerText.trim() : '';
        })()`)).indexOf('第2章 (2/3)') >= 0);
    check('停止全文收听后回到停止态',
        await evalJs(`(() => {
            const b = Array.from(document.querySelectorAll('.tts-toolbar button'))
                .find(x => (x.innerText || '').indexOf('停止') >= 0);
            if (!b) return false;
            b.click();
            return true;
        })()`));
    s = await snap(200);
    check('【核心】全文收听停止后 playlist 收摊、没有残留续播',
        s.playlistActive === false && s.playing === null && s.nextChapter === null,
        `active=${s.playlistActive} playing=${s.playing} next=${s.nextChapter}`);

    /* ---------- P9 运行期 ---------- */
    console.log('\n— P9 运行期异常 —');
    check('【核心】全程无 JS 运行时异常', runtimeErrors.length === 0, runtimeErrors.slice(0, 2).join(' | '));

    const failed = results.filter(x => !x.ok).length;
    console.log(`\n===== ${results.length - failed}/${results.length} 通过 =====`);
    if (failed) console.log('失败项：\n' + results.filter(x => !x.ok).map(x => ' - ' + x.name).join('\n'));

    try { await send('Browser.close'); } catch (e) { /* ignore */ }
    try { chrome.kill(); } catch (e) { /* ignore */ }
    process.exit(failed ? 1 : 0);
}

main().catch(async (e) => {
    console.error('PROBE FAILED: ' + e.message);
    try { chrome.kill(); } catch (err) { /* ignore */ }
    process.exit(2);
});
