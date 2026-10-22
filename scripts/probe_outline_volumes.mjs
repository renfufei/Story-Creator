#!/usr/bin/env node
/**
 * 大纲页 `/projects/{id}/workflow/outline` 的「章节大纲归卷」回归（2026-10-22）。
 *
 * 故障背景：卷的 `chapter_start/chapter_end` 被 VolumeService 拿去表达**正文**分卷范围
 * （正文章节常常比大纲少，线上见过正文 6 章 / 大纲 30 章），后端又拿这个区间去筛章节大纲，
 * 于是区间外的新章节全部被挡掉，页面上就是「新生成的章节大纲不出现在已有分卷下面」。
 *
 * 现在口径：章节自带 volume_number 优先，区间只兜底，且不许丢章节。
 * 本脚本用真实项目 P26（vol1 区间陈旧为 1-6、实际 30 条章节大纲）逐条守护：
 *   P1 分卷头显示的真实章范围是 1-30（不是过期的 1-6）
 *   P2 徽标章数与实际行数一致 = 30
 *   P3 卷下列出的章号正好是 1..30，一条不漏、不重复
 *   P4 章节行状态自洽（「待生成」与状态徽标互补，不依赖项目当前生成进度）
 *   P5 运行期无 JS 异常、无横向溢出
 *   P6 另一个「无分卷」的项目显示空态，不残留幽灵卷
 *
 * 用法：
 *   OUTLINE_BASE=http://127.0.0.1:1889 node scripts/probe_outline_volumes.mjs
 * 环境变量：OUTLINE_BASE（默认 http://localhost:1888）、OUTLINE_PID（默认 26）、
 *           OUTLINE_EMPTY_PID（默认 1）、STORY_BROWSER_PATH
 */
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const BASE = process.env.OUTLINE_BASE || 'http://localhost:1888';
const PID = Number(process.env.OUTLINE_PID || 26);
const EMPTY_PID = Number(process.env.OUTLINE_EMPTY_PID || 1);
const CHROME = process.env.STORY_BROWSER_PATH
    || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const PORT = 9386;
const SHOT_DIR = '/tmp/outline-shots';

const results = [];
const check = (name, ok, extra = '') => {
    results.push({ name, ok: !!ok, extra });
    console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${extra ? '  [' + extra + ']' : ''}`);
};

const userDataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'outline-probe-'));
const chrome = spawn(CHROME, [
    '--headless=new', '--no-sandbox', '--disable-gpu', '--hide-scrollbars', '--mute-audio',
    '--remote-debugging-port=' + PORT, '--user-data-dir=' + userDataDir, 'about:blank'
], { stdio: 'ignore' });

const sleep = (ms) => new Promise(r => setTimeout(r, ms));

let ws, msgId = 0;
const pending = new Map();
const runtimeErrors = [];

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

async function waitFor(label, fn, timeout = 15000, step = 150) {
    const t0 = Date.now();
    while (Date.now() - t0 < timeout) {
        if (await fn()) return true;
        await sleep(step);
    }
    return false;
}

const SNAP = `(() => {
    const host = document.querySelector('[x-data]');
    const data = window.Alpine ? Alpine.$data(host) : null;
    const items = Array.from(document.querySelectorAll('#outlineAccordion .accordion-item'));
    const vols = items.map(it => {
        const head = it.querySelector('.accordion-button');
        const badge = head ? head.querySelector('.badge') : null;
        const rows = Array.from(it.querySelectorAll('.list-group-item'));
        return {
            headText: head ? head.innerText.replace(/\\s+/g, ' ').trim() : null,
            badge: badge ? badge.innerText.trim() : null,
            rowCount: rows.length,
            nums: rows.map(r => {
                const m = (r.innerText || '').match(/第(\\d+)章/);
                return m ? Number(m[1]) : null;
            }),
            emptyRows: rows.filter(r => (r.innerText || '').indexOf('待生成') >= 0).length,
            doneRows: rows.filter(r => /已生成|已精修|精修中|生成中/.test(r.innerText || '')).length,
            firstSummary: rows[0] ? (rows[0].innerText || '').replace(/\\s+/g, ' ').trim().slice(0, 60) : null
        };
    });
    const bodyText = document.body.innerText || '';
    return {
        ready: !!(data && window.Alpine),
        booting: data ? data.booting : null,
        volumeCount: data && data.outlineVolumes ? data.outlineVolumes.length : -1,
        totalChapterRows: vols.reduce((n, v) => n + v.rowCount, 0),
        vols,
        emptyStateVisible: bodyText.indexOf('请通过全自动创作流程生成大纲') >= 0,
        docScrollW: document.documentElement.scrollWidth,
        docClientW: document.documentElement.clientWidth
    };
})()`;
const snap = async (ms = 250) => { if (ms) await sleep(ms); return evalJs(SNAP); };

async function shot(name) {
    const r = await send('Page.captureScreenshot', { format: 'png' });
    fs.mkdirSync(SHOT_DIR, { recursive: true });
    fs.writeFileSync(path.join(SHOT_DIR, name + '.png'), Buffer.from(r.data, 'base64'));
}

/** 按元素裁剪截图：分卷区块在页面很靠下，整屏拍会被 sticky 头挡住看不到卷头。 */
async function shotElement(selector, name, maxHeight = 1100) {
    const box = await evalJs(`(() => {
        const el = document.querySelector(${JSON.stringify(selector)});
        if (!el) return null;
        const r = el.getBoundingClientRect();
        return { x: Math.max(0, r.left), y: r.top + window.scrollY,
                 w: r.width, h: Math.min(r.height, ${maxHeight}) };
    })()`);
    if (!box) return false;
    const r = await send('Page.captureScreenshot', {
        format: 'png',
        clip: { x: box.x, y: box.y, width: box.w, height: box.h, scale: 1 },
        captureBeyondViewport: true
    });
    fs.mkdirSync(SHOT_DIR, { recursive: true });
    fs.writeFileSync(path.join(SHOT_DIR, name + '.png'), Buffer.from(r.data, 'base64'));
    return true;
}

let navSeq = 0;
async function goto(urlPath, width = 1280, height = 1000) {
    await send('Emulation.setDeviceMetricsOverride', { width, height, deviceScaleFactor: 1, mobile: false });
    await send('Page.navigate', { url: BASE + urlPath + '?_probe=' + (++navSeq) });
}

async function waitBooted() {
    return waitFor('booting 结束', async () => {
        try {
            const s = await snap(0);
            return s.ready && s.booting === false;
        } catch (e) { return false; }
    }, 25000);
}

function connect() {
    return new Promise((resolve, reject) => {
        const t0 = Date.now();
        const tick = async () => {
            try {
                const list = await (await fetch(`http://127.0.0.1:${PORT}/json/list`)).json();
                const page = list.find(t => t.type === 'page');
                if (!page) throw new Error('no page');
                ws = new WebSocket(page.webSocketDebuggerUrl);
                ws.onmessage = (ev) => {
                    const msg = JSON.parse(ev.data);
                    if (msg.id && pending.has(msg.id)) {
                        const { res, rej } = pending.get(msg.id);
                        pending.delete(msg.id);
                        msg.error ? rej(new Error(msg.error.message)) : res(msg.result);
                    } else if (msg.method === 'Runtime.exceptionThrown') {
                        runtimeErrors.push(msg.params.exceptionDetails?.exception?.description
                            || msg.params.exceptionDetails?.text || 'unknown');
                    }
                };
                ws.onopen = async () => {
                    await send('Runtime.enable');
                    await send('Page.enable');
                    resolve();
                };
            } catch (e) {
                if (Date.now() - t0 > 20000) return reject(e);
                setTimeout(tick, 200);
            }
        };
        tick();
    });
}

/* ==================== 主流程 ==================== */
(async () => {
    try {
        await connect();

        /* ---- 有分卷的项目 ---- */
        await goto(`/projects/${PID}/workflow/outline`);
        const booted = await waitBooted();
        check('大纲页加载完成（booting 结束）', booted);

        let s = await snap(400);
        await waitFor('分卷渲染', async () => (await snap(0)).vols.length > 0, 10000);
        s = await snap(200);
        await shot('outline-volumes');
        // 分卷区块通常在首屏以下，按元素裁一张，直观核对「章节挂在卷下面」
        await shotElement('#outlineAccordion .accordion-item', 'outline-volumes-accordion');

        check('至少渲染出一个分卷', s.vols.length >= 1, 'vols=' + s.vols.length);
        const v0 = s.vols[0] || { headText: '', badge: '', nums: [], rowCount: 0 };
        check('卷头显示真实章范围 1-30（不是过期的 1-6）',
            /第1-30章/.test(v0.headText), v0.headText);
        check('徽标章数 = 30', v0.badge === '30章', v0.badge);
        check('卷下列出 30 行章节大纲', v0.rowCount === 30, 'rows=' + v0.rowCount);

        const nums = v0.nums.filter(n => n !== null);
        const expected = Array.from({ length: 30 }, (_, i) => i + 1);
        check('章号正好是 1..30（不漏不重复）',
            nums.length === 30 && nums.every((n, i) => n === expected[i]),
            'nums=' + nums.length + ' head=' + nums.slice(0, 5).join(',') + ' tail=' + nums.slice(-3).join(','));
        check('章节行状态自洽（每行要么「待生成」、要么带状态徽标）',
            v0.emptyRows + v0.doneRows === v0.rowCount,
            `pending=${v0.emptyRows} done=${v0.doneRows} rows=${v0.rowCount}`);
        check('至少一章已有大纲正文（不是全空壳）', v0.doneRows > 0, 'done=' + v0.doneRows);
        check('页面无横向溢出', s.docScrollW <= s.docClientW + 1,
            s.docScrollW + '/' + s.docClientW);

        /* ---- 无分卷的项目：空态、不残留幽灵卷 ---- */
        await goto(`/projects/${EMPTY_PID}/workflow/outline`);
        await waitBooted();
        const e = await snap(600);
        await shot('outline-empty');
        check('无分卷项目：分卷数为 0（不残留上一次的临时卷）', e.volumeCount === 0, 'vols=' + e.volumeCount);
        check('无分卷项目：显示空态提示', e.emptyStateVisible === true);

        check('运行期无 JS 异常', runtimeErrors.length === 0,
            runtimeErrors.slice(0, 2).join(' | '));
    } catch (err) {
        check('探针执行未抛异常', false, err.message);
    } finally {
        console.log('');
        const failed = results.filter(r => !r.ok).length;
        console.log(`==== ${results.length - failed}/${results.length} 通过 ====`);
        if (failed) console.log('失败项: ' + results.filter(r => !r.ok).map(r => r.name).join('; '));
        try { ws && ws.close(); } catch (e) { /* ignore */ }
        chrome.kill('SIGKILL');
        // Chrome 的 Cache_Data 常有一两个文件在被延迟释放，删不掉不影响结论；别让它把退出码搞成异常
        try {
            fs.rmSync(userDataDir, { recursive: true, force: true, maxRetries: 5, retryDelay: 120 });
        } catch (e) { /* ignore */ }
        process.exit(failed ? 1 : 0);
    }
})();
