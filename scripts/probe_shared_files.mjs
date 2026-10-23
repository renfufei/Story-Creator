#!/usr/bin/env node
/**
 * 「资源共享」页面（/shared）的端到端探针，2026-10-21
 *
 * 形态：
 *   · 浏览 ${STORY_DB_PATH}/shared 及其子目录（面包屑 / 进入 / 返回上级）；
 *   · 上传（多选 + 拖拽）、下载（浏览器原生 attachment）、新建文件夹；
 *   · ★ 页面**不提供删除**（2026-10-22 用户要求），这里反向钉住「一点都不许留」；
 *   · ★ 主操作按钮够大（上传 ≥40px、行内下载 ≥32px），别退回 btn-sm 的 30px；
 *   · 安全：路径穿越在页面上表现为「加载失败」错误态，而不是白屏或列出别处的内容。
 *
 * ★ 需要真实后端（临时 1889 实例，见 /tmp/sc-shared/run.sh），不碰用户的 1888。
 * ★ 用 Node 22（Node 20 没有全局 WebSocket）。
 *
 * 用法：SHARED_BASE=http://127.0.0.1:1889 node scripts/probe_shared_files.mjs
 */
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const BASE = process.env.SHARED_BASE || 'http://127.0.0.1:1889';
const CHROME = process.env.STORY_BROWSER_PATH
    || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const CDP_PORT = 9401;
const SHOT_DIR = '/tmp/shared-shots';

const results = [];
const check = (name, ok, extra = '') => {
    results.push({ name, ok: !!ok, extra });
    console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${extra ? '  [' + extra + ']' : ''}`);
};
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

/* ==================== 后端就绪 ==================== */
async function waitBackend(timeoutMs = 120000) {
    const t0 = Date.now();
    while (Date.now() - t0 < timeoutMs) {
        try {
            const r = await fetch(BASE + '/shared/api/list');
            if (r.ok) return true;
        } catch (e) { /* 还没起来 */ }
        await sleep(500);
    }
    return false;
}

/* ==================== Chrome / CDP ==================== */
const userDataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'shared-probe-'));
const chrome = spawn(CHROME, [
    '--headless=new', '--no-sandbox', '--disable-gpu', '--hide-scrollbars', '--mute-audio',
    '--remote-debugging-port=' + CDP_PORT, '--user-data-dir=' + userDataDir, 'about:blank'
], { stdio: 'ignore' });

let ws, msgId = 0;
const pending = new Map();
const runtimeErrors = [];

function send(method, params = {}) {
    const id = ++msgId;
    return new Promise((res, rej) => { pending.set(id, { res, rej }); ws.send(JSON.stringify({ id, method, params })); });
}
async function evalJs(expression) {
    const r = await send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true });
    if (r.exceptionDetails) {
        throw new Error('页面 JS 异常: ' + (r.exceptionDetails.exception?.description || r.exceptionDetails.text));
    }
    return r.result.value;
}
async function waitFor(fn, timeout = 15000, step = 150) {
    const t0 = Date.now();
    while (Date.now() - t0 < timeout) {
        try { if (await fn()) return true; } catch (e) { /* 轮询期忽略瞬时错误 */ }
        await sleep(step);
    }
    return false;
}
async function shot(name) {
    const r = await send('Page.captureScreenshot', { format: 'png' });
    fs.mkdirSync(SHOT_DIR, { recursive: true });
    fs.writeFileSync(path.join(SHOT_DIR, name + '.png'), Buffer.from(r.data, 'base64'));
}
let navSeq = 0;
async function goto(query = '', width = 1280, height = 900) {
    await send('Emulation.setDeviceMetricsOverride', { width, height, deviceScaleFactor: 1, mobile: false });
    await send('Page.navigate', { url: BASE + '/shared?_p=' + (++navSeq) + (query ? '&' + query : '') });
}

/* 真实鼠标点击 */
async function clickSel(selector) {
    const pt = await evalJs(`(() => {
        const el = document.querySelector(${JSON.stringify(selector)});
        if (!el) return null;
        el.scrollIntoView({ block: 'center' });
        return null;
    })()`);
    await sleep(120);
    const box = await evalJs(`(() => {
        const el = document.querySelector(${JSON.stringify(selector)});
        if (!el) return null;
        const r = el.getBoundingClientRect();
        if (!r.width && !r.height) return null;
        return { x: r.left + r.width / 2, y: r.top + r.height / 2 };
    })()`);
    if (!box) throw new Error('点击目标不存在或不可见: ' + selector);
    await send('Input.dispatchMouseEvent', { type: 'mouseMoved', x: box.x, y: box.y, button: 'none', clickCount: 0 });
    await send('Input.dispatchMouseEvent', { type: 'mousePressed', x: box.x, y: box.y, button: 'left', clickCount: 1 });
    await send('Input.dispatchMouseEvent', { type: 'mouseReleased', x: box.x, y: box.y, button: 'left', clickCount: 1 });
    return pt;
}

const PAGE_STATE = `(() => {
    const txt = (sel) => {
        const el = document.querySelector(sel);
        return el ? (el.innerText || '').replace(/\\s+/g, ' ').trim() : null;
    };
    const rows = Array.from(document.querySelectorAll('#listArea tbody tr')).map(tr => {
        const nameEl = tr.querySelector('.sc-share-name .txt');
        const link = tr.querySelector('a[data-act="download"]');
        /* 按列 class 取，不按 children 下标 —— 列顺序以后还会变（这轮就加了「类型 / 创建时间」） */
        const cell = (cls) => {
            const td = tr.querySelector('.' + cls);
            return td ? (td.innerText || '').replace(/\\s+/g, ' ').trim() : '';
        };
        return {
            name: nameEl ? nameEl.innerText.trim() : null,
            isDir: !!tr.querySelector('[data-act="enter"]'),
            type: cell('sc-c-type'),
            size: cell('sc-c-size'),
            ctime: cell('sc-c-ctime'),
            time: cell('sc-c-time'),
            dlHref: link ? link.getAttribute('href') : null
        };
    });
    const grid = Array.from(document.querySelectorAll('#listArea .sc-share-grid .sc-card')).map(card => ({
        name: (card.querySelector('.nm') ? card.querySelector('.nm').innerText : '').trim(),
        dir: !!card.querySelector('.ico-big.dir'),
        meta: (card.querySelector('.meta') ? card.querySelector('.meta').innerText : '').trim()
    }));
    const sortSel = document.getElementById('sortField');
    const dirTextEl = document.getElementById('sortDirText');
    const h = (sel) => {
        const el = document.querySelector(sel);
        return el ? Math.round(el.getBoundingClientRect().height) : 0;
    };
    return {
        rows,
        grid,
        view: document.querySelector('#listArea .sc-share-grid') ? 'grid'
            : (document.querySelector('#listArea table') ? 'list' : 'none'),
        sortField: sortSel ? sortSel.value : null,
        sortDirText: dirTextEl ? dirTextEl.textContent.trim() : null,
        crumbs: Array.from(document.querySelectorAll('#crumbs a, #crumbs .cur'))
            .map(el => (el.innerText || '').replace(/\\s+/g, ' ').trim()),
        crumbLinks: Array.from(document.querySelectorAll('#crumbs a'))
            .map(el => el.getAttribute('data-path')),
        h1: txt('#main h1'),
        stat: txt('#dirStat'),
        empty: !!document.querySelector('#listArea .sc-share-empty'),
        listText: txt('#listArea'),
        toasts: Array.from(document.querySelectorAll('.sc-toast')).map(el => el.textContent.trim()),
        progressVisible: (() => {
            const p = document.getElementById('progressWrap');
            return !!p && !p.classList.contains('d-none');
        })(),
        /* 尺寸测量：主操作按钮必须够大（2026-10-22 用户反馈「太小不好点」） */
        hUpload: h('#btnUpload'),
        hMkdir: h('#btnMkdir'),
        /* ★ 必须限定 td.sc-c-act：文件名本身也是 a[data-act="download"]，
           不限定就会量到名称链接（22px）而不是右侧的按钮。 */
        hRowAct: h('#listArea tbody td.sc-c-act a[data-act]'),
        /* 删除功能已移除：任何一处残留都算回归 */
        delCount: document.querySelectorAll('[data-act="del"], .bi-trash').length,
        docScrollW: document.documentElement.scrollWidth,
        docClientW: document.documentElement.clientWidth,
        booting: document.body.hasAttribute('data-booting')
    };
})()`;
const state = async (ms = 200) => { await sleep(ms); return evalJs(PAGE_STATE); };

/** 用 DataTransfer 造真实 File 塞进 input，模拟用户选文件（不需要磁盘上的真实文件） */
async function uploadViaInput(files) {
    const expr = '(() => {' +
        '  const dt = new DataTransfer();' +
        '  const list = ' + JSON.stringify(files) + ';' +
        '  list.forEach(function (f) {' +
        '    dt.items.add(new File([f.content], f.name, { type: f.type || "text/plain" }));' +
        '  });' +
        '  const input = document.getElementById("fileInput");' +
        '  input.files = dt.files;' +
        /* ★ 必须先记下数量：change handler 末尾会 fileInput.value = ''（允许重复上传同一个文件），
           那一步会把 FileList 清空，事后再读 dt.files.length 就是 0 了。 */
        '  const n = dt.files.length;' +
        '  input.dispatchEvent(new Event("change", { bubbles: true }));' +
        '  return n;' +
        '})()';
    return evalJs(expr);
}

async function confirmDialog() {
    await waitFor(async () => await evalJs(`!!document.querySelector('.sc-modal-backdrop [data-act="ok"]')`), 5000);
    await evalJs(`(() => { const b = document.querySelector('.sc-modal-backdrop [data-act="ok"]'); if (b) b.click(); return !!b; })()`);
}

async function promptDialog(value) {
    await waitFor(async () => await evalJs(`!!document.querySelector('.sc-modal-backdrop [data-act="input"]')`), 5000);
    await evalJs(`(() => {
        const i = document.querySelector('.sc-modal-backdrop [data-act="input"]');
        if (!i) return false;
        i.value = ${JSON.stringify(value)};
        document.querySelector('.sc-modal-backdrop [data-act="ok"]').click();
        return true;
    })()`);
}

/** 在 <select id="sortField"> 里选一个排序字段（要自己 dispatch change，赋值不会触发） */
async function selectSortField(field) {
    return evalJs(`(() => {
        const sel = document.getElementById('sortField');
        if (!sel) return null;
        sel.value = ${JSON.stringify(field)};
        sel.dispatchEvent(new Event('change', { bubbles: true }));
        return sel.value;
    })()`);
}

/* ==================== 主流程 ==================== */
async function main() {
    const backendOk = await waitBackend();
    if (!backendOk) throw new Error('后端未就绪：' + BASE + '/shared/api/list');

    let ver = null;
    for (let i = 0; i < 80; i++) {
        try { const r = await fetch(`http://127.0.0.1:${CDP_PORT}/json/version`); if (r.ok) { ver = await r.json(); break; } } catch (e) {}
        await sleep(250);
    }
    if (!ver) throw new Error('Chrome CDP 端点未就绪');

    const target = await (await fetch(`http://127.0.0.1:${CDP_PORT}/json/new?about:blank`, { method: 'PUT' })).json();
    ws = new WebSocket(target.webSocketDebuggerUrl);
    await new Promise((res, rej) => { ws.onopen = res; ws.onerror = rej; });
    ws.onmessage = (ev) => {
        const m = JSON.parse(ev.data);
        if (m.id && pending.has(m.id)) {
            const p = pending.get(m.id); pending.delete(m.id);
            m.error ? p.rej(new Error(JSON.stringify(m.error))) : p.res(m.result);
            return;
        }
        if (m.method === 'Runtime.exceptionThrown') {
            runtimeErrors.push(m.params.exceptionDetails.exception?.description || m.params.exceptionDetails.text);
        }
    };
    await send('Runtime.enable');
    await send('Page.enable');

    /* ---------- S1 首屏 ---------- */
    console.log('\n— S1 首屏：骨架 → 空态 / 列表 —');
    await goto();
    const settled = await waitFor(async () => (await state(60)).booting === false, 15000);
    check('页面 boot 完成（data-booting 已摘掉）', settled === true);
    let s = await state(300);
    check('页头是「资源共享」', /资源共享/.test(s.h1 || ''), s.h1);
    check('根目录没有「返回上级」面包屑', s.crumbLinks.length === 0, 'links=' + JSON.stringify(s.crumbLinks));
    check('空目录显示空态提示', s.empty === true && /还没有共享文件/.test(s.listText || ''), s.listText);
    check('操作条三个按钮都在',
        (await evalJs(`!!(document.getElementById('btnUpload') && document.getElementById('btnMkdir') && document.getElementById('btnRefresh'))`)) === true);
    check('【核心】上传 / 新建文件夹按钮够大（≥40px 高）',
        s.hUpload >= 40 && s.hMkdir >= 40, `upload=${s.hUpload}px mkdir=${s.hMkdir}px`);
    check('【核心】页面上完全没有删除入口',
        s.delCount === 0, 'n=' + s.delCount);
    check('空状态里直接给出「选择文件上传」按钮',
        (await evalJs(`!!document.querySelector('#listArea [data-act="pick"]')`)) === true);
    await shot('shared-01-empty');

    /* ---------- S2 上传（含中文名 + 大一点的文件） ---------- */
    console.log('\n— S2 上传：多选 + 中文名 —');
    const big = 'x'.repeat(3000);       // 撑出 KB 级别的体积，顺便验证大小文案
    const n1 = '探针 报告.txt';
    const n2 = 'probe-data.txt';
    const uploaded = await uploadViaInput([
        { name: n1, content: '资源共享探针内容 A', type: 'text/plain' },
        { name: n2, content: big, type: 'text/plain' }
    ]);
    check('input 拿到 2 个文件', uploaded === 2, 'n=' + uploaded);
    const gotBoth = await waitFor(async () => {
        const st = await state(80);
        const names = st.rows.map(r => r.name);
        return names.indexOf(n1) >= 0 && names.indexOf(n2) >= 0;
    }, 20000);
    s = await state(250);
    check('【核心】两个文件都出现在列表里', gotBoth, s.rows.map(r => r.name).join(' | '));
    check('文件行没有「进入」按钮（只有目录才有）',
        s.rows.filter(r => !r.isDir).length === 2, 'rows=' + s.rows.length);
    const bigRow = s.rows.find(r => r.name === n2);
    check('大小按 KB 显示（不显示原始字节数）',
        !!bigRow && /KB$/.test(bigRow.size), bigRow && bigRow.size);
    check('修改时间列有值', !!bigRow && /\d{4}-\d{2}-\d{2}/.test(bigRow.time), bigRow && bigRow.time);
    check('【核心】行内「下载」按钮带文字标签且高度 ≥32px', s.hRowAct >= 32, 'h=' + s.hRowAct + 'px');
    check('行内按钮是「下载」文字 + 图标，不是光一个图标',
        /下载/.test(await evalJs(`(document.querySelector('#listArea tbody td.sc-c-act a[data-act="download"]') || {}).innerText || ''`)) === true);
    check('页头统计报了文件数与总大小',
        !!s.stat && /2 个文件/.test(s.stat) && /共/.test(s.stat), s.stat);
    check('上传完成后进度条已收起', s.progressVisible === false);
    check('上传成功有 toast 提示',
        s.toasts.some(t => /已上传/.test(t)), s.toasts.join(' | '));
    await shot('shared-02-uploaded');

    /* ---------- S3 下载链接 ---------- */
    console.log('\n— S3 下载：链接指向 attachment 接口，内容一致 —');
    const dlHref = s.rows.find(r => r.name === n1)?.dlHref;
    check('下载链接指向 /shared/api/download 并带编码后的 path',
        !!dlHref && dlHref.indexOf('/shared/api/download?path=') === 0
        && dlHref.indexOf(encodeURIComponent(n1)) > 0, dlHref);
    const dlBody = await evalJs(`fetch(${JSON.stringify(dlHref)}).then(r => r.text())`);
    check('【核心】按该链接取回的内容与上传的一致', dlBody === '资源共享探针内容 A', String(dlBody).slice(0, 40));
    const dlHeaders = await evalJs(`fetch(${JSON.stringify(dlHref)}).then(r => JSON.stringify({
        cd: r.headers.get('content-disposition'), nosniff: r.headers.get('x-content-type-options')
    }))`);
    const hdr = JSON.parse(dlHeaders || '{}');
    check('下载响应带 attachment（强制下载，不在页面渲染）',
        (hdr.cd || '').indexOf('attachment') === 0, hdr.cd);
    check('下载响应带 nosniff', hdr.nosniff === 'nosniff', hdr.nosniff);

    /* ---------- S4 新建文件夹 ---------- */
    console.log('\n— S4 新建文件夹 —');
    await clickSel('#btnMkdir');
    await promptDialog('probe-dir');
    const dirShown = await waitFor(async () => (await state(80)).rows.some(r => r.name === 'probe-dir' && r.isDir), 10000);
    s = await state(250);
    check('【核心】新文件夹出现在列表里', dirShown, s.rows.map(r => r.name + (r.isDir ? '/' : '')).join(' '));
    check('目录排在文件前面（目录优先）', s.rows[0] && s.rows[0].isDir === true, s.rows[0] && s.rows[0].name);
    check('目录行的大小列是 —', s.rows[0] && s.rows[0].size === '—', s.rows[0] && s.rows[0].size);

    /* ---------- S4.5 展示方式（列表 / 卡片）+ 四种字段排序 ---------- */
    console.log('\n— S4.5 展示方式切换与排序 —');
    // 再补两个不同类型的文件，否则「按类型排序」验证不出东西
    await uploadViaInput([
        { name: 'probe-alpha.md', content: 'a', type: 'text/markdown' },
        { name: 'probe-zeta.json', content: '{}', type: 'application/json' }
    ]);
    await waitFor(async () => (await state(80)).rows.filter(r => !r.isDir).length >= 4, 15000);
    s = await state(200);
    const mkTotal = s.rows.length;

    check('默认是列表视图', s.view === 'list', s.view);
    check('工具栏有展示方式与排序控件',
        (await evalJs(`!!(document.getElementById('btnViewGrid') && document.getElementById('btnViewList')
            && document.getElementById('sortField') && document.getElementById('btnSortDir'))`)) === true);
    const firstFile = s.rows.find(r => !r.isDir) || {};
    check('列表有「类型」列且文件显示扩展名', /^[A-Z]+$/.test(firstFile.type || ''), firstFile.type);
    check('列表有「创建时间」列且有值',
        /\d{4}-\d{2}-\d{2}/.test(firstFile.ctime || ''), firstFile.ctime);

    await clickSel('#btnViewGrid');
    s = await state(250);
    check('【核心】点卡片图标切到卡片视图', s.view === 'grid', s.view);
    check('卡片数量 = 条目数', s.grid.length === mkTotal, `grid=${s.grid.length} rows=${mkTotal}`);
    check('卡片有「文件夹 / 类型 · 大小」元信息',
        s.grid.some(g => g.dir === true) && s.grid.some(g => /·/.test(g.meta)),
        JSON.stringify(s.grid.map(g => g.name + '|' + g.meta)));
    check('卡片视图里文件夹仍排最前', s.grid[0] && s.grid[0].dir === true, s.grid[0] && s.grid[0].name);
    await shot('shared-03b-grid');

    await goto();
    await waitFor(async () => (await state(80)).booting === false, 12000);
    s = await state(200);
    check('刷新后展示方式被记住（仍是卡片）', s.view === 'grid', s.view);

    await clickSel('#btnViewList');
    s = await state(250);
    check('点列表图标切回列表视图', s.view === 'list', s.view);

    /* 名称：正序 → 倒序 */
    const ascNames = s.rows.filter(r => !r.isDir).map(r => r.name);
    await clickSel('#btnSortDir');
    s = await state(250);
    const descNames = s.rows.filter(r => !r.isDir).map(r => r.name);
    check('【核心】按名称倒序 = 正序的反向',
        JSON.stringify(descNames) === JSON.stringify(ascNames.slice().reverse()),
        ascNames.join(' > ') + '  ||  ' + descNames.join(' > '));
    check('方向按钮文案变「倒序」', s.sortDirText === '倒序', s.sortDirText);
    check('倒序时文件夹依然排最前', s.rows[0] && s.rows[0].isDir === true, s.rows[0] && s.rows[0].name);
    await clickSel('#btnSortDir');            // 切回正序
    s = await state(200);

    /* 类型：升序，扩展名必须非递减 */
    await selectSortField('type');
    s = await state(250);
    const typeOrder = s.rows.filter(r => !r.isDir).map(r => r.name);
    const extOf = (n) => { const i = n.lastIndexOf('.'); return i > 0 ? n.slice(i + 1).toLowerCase() : ''; };
    const extAsc = typeOrder.every((n, i) => i === 0 || extOf(typeOrder[i - 1]) <= extOf(n));
    check('【核心】按类型排（扩展名升序）', extAsc && s.sortField === 'type',
        typeOrder.map(n => extOf(n)).join(' , '));

    /* 修改日期：换字段默认「最新在前」 */
    await selectSortField('modifiedAt');
    s = await state(250);
    check('换到修改日期默认倒序（最新在前）',
        s.sortField === 'modifiedAt' && s.sortDirText === '倒序', s.sortField + '/' + s.sortDirText);
    const mtimeDesc = s.rows.filter(r => !r.isDir).map(r => r.time);
    check('修改时间列都有值', mtimeDesc.every(t => /\d{4}-\d{2}-\d{2}/.test(t)), mtimeDesc.join(' | '));
    await clickSel('#btnSortDir');
    s = await state(250);
    check('修改日期可切成正序', s.sortDirText === '正序', s.sortDirText);

    /* 创建日期 */
    await selectSortField('createdAt');
    s = await state(250);
    const ctDesc = s.rows.filter(r => !r.isDir).map(r => r.ctime);
    check('【核心】按创建日期排序且列都有值',
        s.sortField === 'createdAt' && s.sortDirText === '倒序'
        && ctDesc.every(t => /\d{4}-\d{2}-\d{2}/.test(t)), ctDesc.join(' | '));

    /* 收尾：回到「列表 + 名称正序」，后面的用例依赖这个顺序 */
    await selectSortField('name');
    s = await state(200);
    check('切回名称排序后恢复正序', s.sortField === 'name' && s.sortDirText === '正序',
        s.sortField + '/' + s.sortDirText);

    /* ---------- S5 进入子目录 / 面包屑 / 返回 ---------- */
    console.log('\n— S5 进入子目录与面包屑 —');
    await clickSel('#listArea tbody tr:first-child a[data-act="enter"]');
    await waitFor(async () => (await state(80)).crumbLinks.length === 1, 8000);
    s = await state(250);
    check('【核心】已进入子目录（面包屑出现一级可点链接）',
        s.crumbLinks.length === 1 && s.crumbLinks[0] === '', JSON.stringify(s.crumbLinks));
    check('面包屑文案 = 共享资源 / probe-dir',
        s.crumbs.join('/') === '共享资源/probe-dir', s.crumbs.join('/'));
    check('页头仍然是「资源共享」（目录名不再重复做标题）',
        /资源共享/.test(s.h1 || '') && (await evalJs(`!document.getElementById('dirTitle')`)) === true, s.h1);
    check('子目录是空的', s.rows.length === 0, 'rows=' + s.rows.length);
    await shot('shared-03-subdir');

    /* 在子目录里上传一个文件（验证 path 参数落到子目录） */
    await uploadViaInput([{ name: 'inner.txt', content: 'inner', type: 'text/plain' }]);
    const innerOk = await waitFor(async () => (await state(80)).rows.some(r => r.name === 'inner.txt'), 15000);
    check('在子目录上传的文件落在子目录里', innerOk);

    console.log('\n— S6 页面上没有删除入口（后端端点保留给运维）—');
    await clickSel('#crumbs a');
    await waitFor(async () => (await state(80)).crumbLinks.length === 0, 8000);
    s = await state(250);
    check('已回到根目录', s.crumbLinks.length === 0 && s.rows.length >= 3, 'rows=' + s.rows.length);
    check('根目录里能看到 probe-dir', s.rows.some(r => r.name === 'probe-dir'));
    check('【核心】列表里没有删除按钮（DOM 与 trash 图标都没有）', s.delCount === 0, 'n=' + s.delCount);
    check('【核心】文件行的动作列只剩「下载」一个按钮',
        (await evalJs(`(() => {
            const tr = Array.from(document.querySelectorAll('#listArea tbody tr'))
                .find(t => t.querySelector('a[data-act="download"]'));
            return tr ? tr.querySelectorAll('td.sc-c-act a, td.sc-c-act button').length : -1;
        })()`)) === 1);
    check('【核心】整页文案里不再出现「删除」',
        (await evalJs(`!/(删除|清空)/.test(document.getElementById('main').innerText)`)) === true);
    await shot('shared-04-no-delete');

    /* ---------- S8 页内路径穿越 ---------- */
    console.log('\n— S8 越界路径：页面上表现为错误态 —');
    await goto('path=' + encodeURIComponent('../..'));
    const errShown = await waitFor(async () => {
        const st = await state(120);
        return /加载失败/.test(st.listText || '');
    }, 12000);
    check('【核心】path=../.. 显示「加载失败」，不白屏、不列出别处内容', errShown,
        (await state(100)).listText || '');
    const absErr = await (async () => {
        await goto('path=' + encodeURIComponent('/etc'));
        return waitFor(async () => /加载失败/.test((await state(120)).listText || ''), 12000);
    })();
    check('【核心】path=/etc 同样被拒', absErr === true);

    /* ---------- S9 窄屏 ---------- */
    console.log('\n— S9 390px 窄屏 —');
    await goto('', 390, 844);
    await waitFor(async () => (await state(80)).booting === false, 12000);
    s = await state(300);
    check('窄屏无横向溢出', s.docScrollW <= s.docClientW + 1, `scrollW=${s.docScrollW} clientW=${s.docClientW}`);
    check('窄屏工具栏按钮仍在一屏内（不换行挤爆）',
        (await evalJs(`document.getElementById('btnUpload').getBoundingClientRect().width > 0`)) === true);
    check('窄屏主操作按钮更高（≥44px，触控友好）', s.hUpload >= 44, 'h=' + s.hUpload + 'px');
    await shot('shared-05-mobile');

    /* 窄屏下的卡片视图：两列、不溢出 */
    await clickSel('#btnViewGrid');
    s = await state(300);
    check('窄屏卡片视图无横向溢出',
        s.view === 'grid' && s.docScrollW <= s.docClientW + 1,
        `view=${s.view} scrollW=${s.docScrollW} clientW=${s.docClientW}`);
    const narrowCardW = await evalJs(`(() => {
        const c = document.querySelector('#listArea .sc-card');
        return c ? Math.round(c.getBoundingClientRect().width) : 0;
    })()`);
    check('窄屏卡片是两列且没被压扁', narrowCardW > 100 && narrowCardW < 240, 'w=' + narrowCardW + 'px');
    await shot('shared-06-mobile-grid');
    await clickSel('#btnViewList');

    check('全程无 JS 运行时异常', runtimeErrors.length === 0, runtimeErrors.join(' | ').slice(0, 300));

    const ok = results.filter(r => r.ok).length;
    console.log(`\n===== ${ok}/${results.length} 通过 =====`);
    return ok === results.length ? 0 : 1;
}

let code = 1;
try { code = await main(); } catch (e) { console.error('探针异常: ' + e.message); }
finally {
    try { ws && ws.close(); } catch (e) {}
    chrome.kill();
    try { fs.rmSync(userDataDir, { recursive: true, force: true }); } catch (e) {}
}
process.exit(code);
