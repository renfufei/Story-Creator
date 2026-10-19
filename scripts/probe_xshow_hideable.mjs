#!/usr/bin/env node
/**
 * 守护「x-show 与 Bootstrap 显示类（!important）同标签 ⇒ 隐藏不掉」这个坑。
 *
 * 背景（2026-10-18）：
 *   Bootstrap 5 的 .d-flex / .d-block 等都是 `display:…!important`，而 Alpine 的 x-show 隐藏时
 *   只写内联 `display:none`（非 important）。样式表里的 important 声明压过内联普通声明，于是
 *   `<div class="d-flex" x-show="false">` 永远显示 —— 点了没反应、面板关不掉，全是这一条。
 *   修法：x-show 所在元素不带任何 display 工具类，布局类下沉到内层。
 *
 * 本探针验证的是「机制」而不是「某个页面能打开」：
 *   H1 每个页面都真的初始化了 Alpine，且存在若干 [x-show] 元素（否则测了个空）
 *   H2 ★ 对页面上**每一个** [x-show] 元素：按 Alpine 的方式置 display:none（非 important），
 *        计算样式必须变成 none —— 只要有一个变不了，就是还有 important 冲突
 *   H3 源码层面的同类约束：任何 [x-show] 元素都不带 display 工具类
 *   H4 下沉后的结构仍在位（存在即校验）：step-model 表单 / 图片占位 h-100 / 确认按钮组 / 章节操作组 /
 *      角色定位块 / 全文收听控制条
 *   H5 运行期无 JS 异常、无 console.error
 *
 * 用法：
 *   bash /tmp/settings-verify/run.sh scripts/probe_xshow_hideable.mjs     # 1889 临时实例（复制真实库）
 *   XSHOW_BASE=http://localhost:1888 node scripts/probe_xshow_hideable.mjs
 * 可选环境变量：XSHOW_BASE、STORY_BROWSER_PATH、SHOT_DIR（默认 /tmp/xshow-shots）
 */
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const BASE = process.env.XSHOW_BASE || process.env.WM_BASE || 'http://localhost:1888';
const CHROME = process.env.STORY_BROWSER_PATH
    || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const PORT = 9347;
const SHOT_DIR = process.env.SHOT_DIR || '/tmp/xshow-shots';

const results = [];
const check = (name, ok, extra = '') => {
    results.push({ name, ok: !!ok, extra });
    console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${extra ? '  [' + extra + ']' : ''}`);
};

const userDataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'xshow-probe-'));
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

async function waitFor(label, fn, timeout = 20000, step = 150) {
    const t0 = Date.now();
    while (Date.now() - t0 < timeout) {
        if (await fn()) return true;
        await sleep(step);
    }
    throw new Error('等待超时：' + label);
}

async function shot(name) {
    try {
        fs.mkdirSync(SHOT_DIR, { recursive: true });
        const r = await send('Page.captureScreenshot', { format: 'png', captureBeyondViewport: false });
        fs.writeFileSync(path.join(SHOT_DIR, name + '.png'), Buffer.from(r.data, 'base64'));
    } catch (e) {
        console.log('截图失败 ' + name + ': ' + e.message);
    }
}

async function apiJson(p) {
    try {
        const r = await fetch(BASE + p);
        if (!r.ok) return null;
        return await r.json();
    } catch (e) {
        return null;
    }
}

function asList(js) {
    if (Array.isArray(js)) return js;
    if (!js || typeof js !== 'object') return [];
    for (const k of ['content', 'items', 'data', 'list', 'records']) {
        if (Array.isArray(js[k])) return js[k];
    }
    return [];
}

/* ============ 页面内执行的两段审计 ============ */

/** H2：逐个 x-show 元素强制隐藏，看计算样式认不认 */
const AUDIT_HIDEABLE = `(() => {
    const els = Array.from(document.querySelectorAll('[x-show]'));
    const bad = [];
    let alreadyHidden = 0;
    for (const el of els) {
        const prev = el.style.display;
        if (prev === 'none') alreadyHidden++;
        el.style.display = 'none';
        const got = getComputedStyle(el).display;
        if (got !== 'none') {
            bad.push(el.tagName.toLowerCase()
                + (el.className ? '.' + String(el.className).trim().split(/\\s+/).join('.') : '')
                + ' -> ' + got);
        }
        if (prev === 'none') el.style.display = 'none';
        else el.style.removeProperty('display');
    }
    return { total: els.length, alreadyHidden, badCount: bad.length, bad: bad.slice(0, 6) };
})()`;

/** H3/H4：结构审计（存在即校验，不要求每页都有） */
const AUDIT_STRUCTURE = `(() => {
    const issues = [];
    const DISPLAY = /(?:^|\\s)d-(?:flex|block|grid|inline|inline-block|inline-flex|inline-grid|table)(?:\\s|$)/;

    // H3：x-show 元素不得带 display 工具类
    document.querySelectorAll('[x-show]').forEach(el => {
        const cls = String(el.className || '');
        if (DISPLAY.test(cls)) {
            issues.push('[x-show] 仍带 display 类: ' + el.tagName.toLowerCase() + '.' + cls.trim().replace(/\\s+/g, '.'));
        }
    });

    // H4：下沉后的结构仍在位
    document.querySelectorAll('form[action*="step-model"], form[action$="/step-model"]').forEach(f => {
        if (!f.querySelector(':scope > div.d-flex')) issues.push('step-model 表单缺少内层 flex 容器');
    });
    document.querySelectorAll('[x-show="!img.hasImage"]').forEach(d => {
        if (!d.querySelector(':scope > div.h-100')) issues.push('图片占位缺少 h-100 内层');
    });
    document.querySelectorAll('[x-show*="proofreadCompletedCount"]').forEach(d => {
        if (!d.querySelector(':scope > div.d-flex.justify-content-end')) issues.push('确认按钮组缺少内层右对齐 flex');
    });
    document.querySelectorAll('[x-show*="characterImages.length > 0"]').forEach(d => {
        if (!d.querySelector(':scope > div.d-flex.flex-column')) issues.push('图片卡容器缺少内层纵向 flex');
    });
    document.querySelectorAll('span.d-block.text-truncate > small[x-show="c.role"]').forEach(() => {});
    document.querySelectorAll('[x-show="c.role"]').forEach(s => {
        if (!s.parentElement || !s.parentElement.classList.contains('d-block')
            || !s.parentElement.classList.contains('text-truncate')) {
            issues.push('角色定位缺少块级包裹层');
        }
    });
    document.querySelectorAll('[x-show="playlist.isActive"]').forEach(d => {
        if (!d.querySelector(':scope > div.d-flex')) issues.push('全文收听控制条缺少内层 flex');
    });
    document.querySelectorAll('[x-show="selectedCharIds.length > 0"]').forEach(d => {
        if (!d.querySelector(':scope > div.d-flex')) issues.push('关联角色徽标区缺少内层 flex');
    });
    document.querySelectorAll('[x-show="active"]').forEach(d => {
        if (!d.querySelector(':scope > div.d-flex')) issues.push('扩写进度条缺少内层 flex');
    });
    document.querySelectorAll('[x-show="!img.hasImage"]').forEach(d => {});
    // 章节操作按钮组（x-show 里含 ch.editing）
    document.querySelectorAll('[x-show*="ch.editing"]').forEach(d => {
        if (!d.querySelector(':scope > div.d-flex.gap-1')) issues.push('章节操作按钮组缺少内层 flex');
    });
    return issues;
})()`;

async function auditPage(label, url, opts = {}) {
    console.log('\n— ' + label + ' —');
    runtimeErrors.length = 0;
    consoleErrors.length = 0;
    await send('Page.navigate', { url });
    let ready = false;
    try {
        ready = await waitFor('Alpine 初始化', async () => !!(await evalJs(
            `!!(window.Alpine && document.querySelector('[x-show]') && document.readyState === 'complete')`
        )), 25000, 200);
    } catch (e) { /* 落到下面的 FAIL */ }
    await sleep(900);   // 让 x-init / 首个数据请求落定

    const hide = await evalJs(AUDIT_HIDEABLE).catch(e => ({ total: 0, badCount: -1, bad: [e.message], alreadyHidden: 0 }));
    check(`${label}：Alpine 初始化且页面存在 [x-show] 元素`, ready && hide.total > 0,
        `x-show=${hide.total} 当前已隐藏=${hide.alreadyHidden}`);
    check(`${label}：★ 每个 [x-show] 元素都隐藏得掉（无 important 冲突）`, hide.badCount === 0,
        hide.badCount > 0 ? hide.bad.join(' | ') : `${hide.total} 个全部 OK`);

    const issues = await evalJs(AUDIT_STRUCTURE).catch(e => [e.message]);
    check(`${label}：下沉后的结构在位`, issues.length === 0,
        issues.length ? issues.slice(0, 4).join(' | ') : 'OK');

    if (opts.shot) await shot(opts.shot);
    return { hide, issues };
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
    if (!ver) throw new Error('Chrome CDP 端点未就绪');
    console.log('Chrome: ' + ver['Browser']);
    console.log('目标: ' + BASE);

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
    await send('Runtime.enable');
    await send('Emulation.setDeviceMetricsOverride', {
        width: 1440, height: 960, deviceScaleFactor: 1, mobile: false
    });

    /* ---------- 找到用于验证的真实项目 ---------- */
    const projects = asList(await apiJson('/api/projects'));
    const pid = projects.length ? (projects[0].id ?? projects[0].projectId) : null;
    console.log('项目: ' + (pid ? '#' + pid + ' ' + (projects[0].name || '') : '（无，改用静态页）'));

    let sid = null;
    if (pid) {
        const ss = asList(await apiJson(`/api/projects/${pid}/side-stories`));
        if (ss.length) sid = ss[0].id ?? ss[0].sideStoryId;
    }

    /* ---------- 逐页审计 ---------- */
    const pages = [];
    if (pid) {
        for (const step of ['world-building', 'characters', 'outline', 'chapters', 'polishing', 'proofreading']) {
            pages.push([`workflow/${step}`, `${BASE}/projects/${pid}/workflow/${step}`]);
        }
        pages.push(['reader', `${BASE}/projects/${pid}/read`]);
        pages.push(['side-story-list', `${BASE}/projects/${pid}/side-stories`]);
        pages.push(['expansion', `${BASE}/projects/${pid}/expansion`]);
        if (sid) pages.push(['side-story', `${BASE}/projects/${pid}/side-stories/${sid}`]);
    }
    // 整合版大页（未拆分前的 workflow.html）与 txt-import 直接吃静态文件
    pages.push(['workflow.html(整合版)', `${BASE}/pages/workflow.html`]);
    pages.push(['txt-import', `${BASE}/pages/txt-import.html`]);

    let grand = { total: 0, bad: 0 };
    for (const [label, url] of pages) {
        const shotName = label.replace(/[^\w.-]+/g, '_');
        const { hide } = await auditPage(label, url, { shot: shotName });
        grand.total += hide.total;
        grand.bad += Math.max(0, hide.badCount);
    }

    /* ---------- H5 运行期异常 ---------- */
    console.log('\n— 运行期异常 —');
    check('无 JS 运行期异常', runtimeErrors.length === 0, runtimeErrors.slice(0, 2).join(' | '));
    check('无 console.error', consoleErrors.length === 0, consoleErrors.slice(0, 2).join(' | '));

    /* ---------- 汇总 ---------- */
    const pass = results.filter(r => r.ok).length;
    console.log(`\n==== ${pass}/${results.length} 通过 ====`);
    console.log(`共审计 ${pages.length} 个页面、${grand.total} 个 [x-show] 元素，隐藏失败 ${grand.bad} 个`);
    console.log('截图目录: ' + SHOT_DIR);

    chrome.kill();
    process.exit(pass === results.length ? 0 : 1);
}

main().catch(e => {
    console.error('探针异常: ' + e.message);
    chrome.kill();
    process.exit(2);
});
