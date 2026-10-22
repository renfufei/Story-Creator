#!/usr/bin/env node
/**
 * /learn/sudoku 定点验证：**数独**（3×3 ~ 9×9 / 三档难度）。
 *
 * 需求（用户，2026-10-21）：
 *   教学板块新增「数独游戏」，可配置行列数量（3-5-7-9 等），要有背景、样式、
 *   输入数字的键盘，并且 PC 与移动端都要能用。
 *
 * 这个探针守护的是「玩起来成不成立」，不是「页面能不能打开」：
 *   D1  默认落地 9×9 普通：81 格、粗线宫格边界齐、已知数在合理区间
 *   D2  ★ 每种尺寸 × 每种难度都**唯一解**（页面内直接调出题器，用求解器数解）
 *   D3  3×3 / 5×5 / 7×7 是拉丁方（无宫格线），4/6/8/9 有宫格线
 *   D4  点格子 + 点数字能填；再按同一个数字会擦掉
 *   D5  同行重复数字 → 两格一起标红（冲突即时可见）
 *   D6  笔记模式：记候选数、再点一次去掉、格子不显示大盘面数字
 *   D7  撤销（按钮 + Ctrl+Z）逐步回退
 *   D8  提示：填对一格、计次 +1、格子有 is-hint 标记、额度按尺寸×难度给
 *   D9  检查：标出错误格、次数 3→2、几秒后自动消失；用完即禁用
 *   D10 自动清理笔记：同行填了同一个数字，候选数里自动去掉
 *   D11 键盘：方向键移动、N 切笔记、Esc 取消选中
 *   D12 存档：刷新后回到同一盘（题目、盘面、用时都还在）
 *   D13 换尺寸：4×4 有宫格、3×3 无宫格，格子数与图例文案跟着变
 *   D14 填满整盘 → 结算浮层出现、计时停下、成绩写入 localStorage
 *   D15 移动端 375×667：键盘条钉在视口底部、工具 5 个一行、9 个数字一行、
 *       无横向滚动、正文留出键盘高度
 *   D16 移动端 3×3：棋盘收窄（不铺满一屏）、数字键更少
 *   D17 教学首页第 7 张卡（数独 / indigo）链接可达
 *   D18 设置弹层：可开可收、点外面关闭、规则弹层、开关生效
 *   D20 尺寸下拉：常玩只留 3×3 / 6×6、其余 5 种折叠、【当前/最近】置顶、
 *       按钮文案与 localStorage 三处同步、刷新后默认落在上次选的尺寸
 *   D21 教学关卡：6 步推演（数空格 → 列排除 → 换宫看）、迷你盘高亮、
 *       教学期间计时暂停、看过不再提示、手机上浮层不出屏
 *   D22 提示额度：空格越多给越多、难度越高给越多、小盘保底、用完即禁用
 *   D19 运行期无 JS 异常 / console.error
 *
 * 用法：
 *   SUDOKU_BASE=http://localhost:1889 node scripts/probe_sudoku.mjs
 *   默认 http://localhost:1888（⚠️ 1888 跑的是 fat jar，静态页改动要重新打包才生效；
 *   验新页面请用临时实例：java -cp "target/classes:$(cat /tmp/sc-shared/cp.txt)" … --server.port=1889）
 * 可选环境变量：SUDOKU_BASE、STORY_BROWSER_PATH、SHOT_DIR（默认 /tmp/sudoku-shots）
 */
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const BASE = process.env.SUDOKU_BASE || 'http://localhost:1888';
const PAGE = BASE + '/learn/sudoku';
const CHROME = process.env.STORY_BROWSER_PATH
    || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const PORT = 9353;
const SHOT_DIR = process.env.SHOT_DIR || '/tmp/sudoku-shots';

const results = [];
const check = (name, ok, extra = '') => {
    results.push({ name, ok: !!ok, extra });
    console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${extra ? '  [' + extra + ']' : ''}`);
};

const userDataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'sudoku-probe-'));
const chrome = spawn(CHROME, [
    '--headless=new', '--no-sandbox', '--disable-gpu', '--hide-scrollbars',
    '--mute-audio',
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

async function waitFor(label, fn, timeout = 15000, step = 120) {
    const t0 = Date.now();
    while (Date.now() - t0 < timeout) {
        if (await fn()) return true;
        await sleep(step);
    }
    throw new Error('等待超时：' + label);
}

/* ---------------- 页面操作小封装 ---------------- */

const snap = () => evalJs(`(() => {
    const st = window.__sudoku.state;
    const cells = document.querySelectorAll('.sd-cell');
    const hintPill = document.getElementById('sdHintPill');
    /* ⚠️ 页面刚重载时 puzzle/value/notes 都还是 null（出题在 rAF 之后跑），
       这里必须给兜底值 —— 否则 waitFor 的第一次探测就抛
       "Cannot read properties of null (reading 'join')"，整个脚本当场中断。 */
    return {
        n: st.n, diff: st.diff, sel: st.sel, solved: st.solved,
        notesMode: st.notesMode, hints: st.hints, checks: st.checks,
        hintTotal: st.hintTotal, hintLeft: Math.max(0, (st.hintTotal || 0) - (st.hints || 0)),
        hintHud: (document.getElementById('sdHints') || {}).textContent || '',
        hintBadge: (document.getElementById('sdHintBadge') || {}).textContent || '',
        hintBadgeHidden: !!(document.getElementById('sdHintBadge') || {}).hidden,
        hintPillWarn: !!(hintPill && hintPill.classList.contains('is-warn')),
        hintDisabled: !!((document.querySelector('.sd-tool[data-act="hint"]') || {}).disabled),
        elapsed: st.elapsed, undoLen: st.undo ? st.undo.length : 0,
        puzzle: st.puzzle ? st.puzzle.join('') : '',
        value: st.value ? st.value.join('') : '',
        notes: st.notes ? Array.from(st.notes) : [],
        givens: st.puzzle ? Array.from(st.puzzle).filter(Boolean).length : 0,
        cells: cells.length,
        br: document.querySelectorAll('.sd-cell.is-br').length,
        bb: document.querySelectorAll('.sd-cell.is-bb').length,
        bad: document.querySelectorAll('.sd-cell.is-bad').length,
        wrong: st.wrong ? st.wrong.size : 0,
        hinted: document.querySelectorAll('.sd-cell.is-hint').length,
        legend: (document.getElementById('sdLegendText') || {}).textContent || ''
    };
})()`);

const clickCell = (i) => evalJs(`(() => {
    const c = document.querySelectorAll('.sd-cell')[${i}];
    if (!c) return false;
    c.click();
    return true;
})()`);

const press = (key, extra = {}) => evalJs(`(() => {
    document.dispatchEvent(new KeyboardEvent('keydown', Object.assign(
        { key: ${JSON.stringify(key)}, bubbles: true, cancelable: true }, ${JSON.stringify(extra)})));
    return true;
})()`);

const clickTool = (act) => evalJs(`(() => {
    const b = document.querySelector('.sd-tool[data-act="${act}"]');
    if (!b) return false;
    b.click();
    return true;
})()`);

/**
 * 通过下拉面板选尺寸：常玩区直接点；折叠在【更多尺寸】里的先展开。
 * （旧版是 7 个 .sd-chip 平铺，2026-10-21 改成下拉面板后选择器全变了）
 */
const pickSize = async (n) => {
    const ok = await evalJs(`(() => {
        const pop = document.getElementById('sdSizePop');
        if (pop.hidden) document.getElementById('sdSizeBtn').click();
        let opt = document.querySelector('.sd-size-opt[data-n="${n}"]');
        if (!opt) {
            if (document.getElementById('sdMoreList').hidden) document.getElementById('sdMoreBtn').click();
            opt = document.querySelector('.sd-size-opt[data-n="${n}"]');
        }
        if (!opt) return false;
        opt.click();
        return true;
    })()`);
    if (!ok) throw new Error('下拉面板里找不到尺寸 ' + n);
    return ok;
};

const clickDiff = (k) => evalJs(`(() => {
    const b = document.querySelector('.sd-diff[data-k="${k}"]');
    if (!b) return false;
    b.click();
    return true;
})()`);

const newGame = async () => {
    await evalJs(`document.getElementById('sdNew').click()`);
    await sleep(400);
};

/** 找一个空格（可限定行） */
const emptyCell = (row) => evalJs(`(() => {
    const st = window.__sudoku.state, n = st.n;
    for (let i = 0; i < n * n; i++) {
        ${row === undefined ? '' : `if (((i / n) | 0) !== ${row}) continue;`}
        if (!st.value[i]) return i;
    }
    return -1;
})()`);

/** 同一行里两个相邻空格的候选（用于冲突/自动清笔记） */
const rowWithTwoEmpty = () => evalJs(`(() => {
    const st = window.__sudoku.state, n = st.n, out = [];
    for (let r = 0; r < n; r++) {
        const list = [];
        for (let c = 0; c < n; c++) { const i = r * n + c; if (!st.value[i]) list.push(i); }
        if (list.length >= 2) out.push({ r, a: list[0], b: list[1] });
    }
    return out;
})()`);

async function shot(name) {
    try {
        fs.mkdirSync(SHOT_DIR, { recursive: true });
        const r = await send('Page.captureScreenshot', { format: 'png', captureBeyondViewport: false });
        fs.writeFileSync(path.join(SHOT_DIR, name + '.png'), Buffer.from(r.data, 'base64'));
    } catch (e) {
        console.log('截图失败 ' + name + ': ' + e.message);
    }
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
    console.log('目标: ' + PAGE);

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
    await send('Page.enable');
    await send('Emulation.setDeviceMetricsOverride', {
        width: 1280, height: 940, deviceScaleFactor: 1, mobile: false
    });

    /* ==================== D0 前置：干净状态 ==================== */
    console.log('\n— D0 前置 —');
    await send('Page.navigate', { url: PAGE });
    await sleep(600);
    await evalJs(`localStorage.clear()`);
    await send('Page.navigate', { url: PAGE });
    await waitFor('棋盘渲染', async () => !!(await evalJs(
        `!!(window.__sudoku && window.__sudoku.state.puzzle && document.querySelectorAll('.sd-cell').length)`)), 20000, 120);
    check('页面装载：出题钩子就绪且棋盘已渲染', true);

    /* ==================== D1 默认棋盘 ==================== */
    console.log('\n— D1 默认 9×9 / 普通 —');
    let st = await snap();
    check('默认 9×9、普通难度、81 个格子', st.n === 9 && st.diff === 'normal' && st.cells === 81,
        `n=${st.n} diff=${st.diff} cells=${st.cells}`);
    check('已知数落在困难～简单的合理区间（20~45）', st.givens >= 20 && st.givens <= 45,
        `givens=${st.givens}`);
    check('宫格边界：9×9 应有 (9/3-1)*9=18 条竖向 + 18 条横向粗线',
        st.br === 18 && st.bb === 18, `br=${st.br} bb=${st.bb}`);
    check('图例说明了规则与难度', /9×9 · 普通 · 标准数独（3×3 宫）/.test(st.legend), st.legend);
    check('数字键盘 9 个键、工具 5 个', await evalJs(
        `document.querySelectorAll('.sd-key').length === 9 && document.querySelectorAll('.sd-tool').length === 5`));
    check('键盘右下角的「还剩几个」与盘面一致', await evalJs(`(() => {
        const st = window.__sudoku.state, n = st.n, cnt = new Array(n + 1).fill(0);
        for (const v of st.value) if (v) cnt[v]++;
        return Array.from(document.querySelectorAll('.sd-key')).every(k => {
            const v = Number(k.dataset.v);
            return k.querySelector('.sd-key-r').textContent === String(n - cnt[v]);
        });
    })()`));
    await shot('01-default');

    /* ==================== D2 出题器：唯一解 ==================== */
    console.log('\n— D2 每种尺寸×难度都唯一解 —');
    const gen = await evalJs(`(() => {
        const S = window.__sudoku, out = [];
        for (const n of [3,4,5,6,7,8,9]) {
            for (const d of ['easy','normal','hard']) {
                const g = S.generate(n, d);
                const solver = S.makeSolver(n, S.geomFor(n));
                const cnt = solver.countSolutions(g.puzzle, 3, Date.now() + 8000);
                let givens = 0, consistent = true;
                for (let i = 0; i < g.puzzle.length; i++) {
                    if (!g.puzzle[i]) continue;
                    givens++;
                    if (g.puzzle[i] !== g.solution[i]) consistent = false;
                }
                out.push({ n, d, cnt, givens, consistent });
            }
        }
        return out;
    })()`);
    const badCnt = gen.filter(g => g.cnt !== 1);
    check('★ 21 组（7 尺寸 × 3 难度）全部只有 1 个解', badCnt.length === 0,
        badCnt.map(g => `${g.n}x${g.n}/${g.d}=${g.cnt}`).join(' '));
    check('已知数都取自答案（不会出现题目与答案打架）', gen.every(g => g.consistent));
    check('三档难度的已知数递增（难度越高给得越少）',
        [3, 4, 5, 6, 7, 8, 9].every(n => {
            const e = gen.find(g => g.n === n && g.d === 'easy');
            const h = gen.find(g => g.n === n && g.d === 'hard');
            return e.givens >= h.givens;
        }),
        gen.filter(g => g.d !== 'normal').map(g => `${g.n}x${g.n}:${g.d}=${g.givens}/${g.n * g.n}`).join(' '));

    /* ==================== D3 拉丁方 vs 宫格 ==================== */
    console.log('\n— D3 拉丁方 / 宫格规则 —');
    const latin = await evalJs(`(() => {
        const S = window.__sudoku;
        return [[3, true], [4, false], [5, true], [6, false], [7, true], [8, false], [9, false]]
            .map(([n, isLatin]) => ({ n, isLatin, geom: S.geomFor(n) }));
    })()`);
    check('3/5/7 无宫格（拉丁方），4/6/8/9 有宫格',
        latin.filter(x => x.isLatin).every(x => x.geom === null)
        && latin.filter(x => !x.isLatin).every(x => !!x.geom),
        latin.map(x => `${x.n}${x.geom ? '宫' + x.geom.bh + 'x' + x.geom.bw : '拉丁'}`).join(' '));

    /* ==================== D4 点击 + 数字键盘 ==================== */
    console.log('\n— D4 点格子 + 点数字 —');
    const cellA = await emptyCell();
    const ansA = await evalJs(`window.__sudoku.state.solution[${cellA}]`);
    await clickCell(cellA);
    st = await snap();
    check('点格子后进入选中态', st.sel === cellA, `sel=${st.sel} expect=${cellA}`);
    check('选中的格子带 is-sel，同行列宫带 is-peer', await evalJs(
        `document.querySelectorAll('.sd-cell.is-sel').length === 1
         && document.querySelectorAll('.sd-cell.is-peer').length > 0`));
    await evalJs(`document.querySelector('.sd-key[data-v="${ansA}"]').click()`);
    st = await snap();
    check('点数字键能填进选中格', st.value[cellA] === String(ansA), `v=${st.value[cellA]} expect=${ansA}`);
    check('填对后不标红（无冲突）', st.bad === 0, `bad=${st.bad}`);
    await evalJs(`document.querySelector('.sd-key[data-v="${ansA}"]').click()`);
    st = await snap();
    check('再点同一个数字＝擦掉', st.value[cellA] === '0', `v=${st.value[cellA]}`);

    /* ==================== D5 冲突即时可见 ==================== */
    console.log('\n— D5 同行重复 → 标红 —');
    const pairs = await rowWithTwoEmpty();
    const p0 = pairs[0];
    const vDup = await evalJs(`window.__sudoku.state.solution[${p0.a}]`);
    await clickCell(p0.a);
    await press(String(vDup));
    await clickCell(p0.b);
    await press(String(vDup));
    st = await snap();
    check('同一行放两个相同数字 → 两格一起标红', st.bad >= 2, `bad=${st.bad}`);
    await clickTool('undo');
    await clickTool('undo');
    st = await snap();
    check('撤销两步后恢复干净', st.bad === 0 && st.value[p0.a] === '0' && st.value[p0.b] === '0',
        `bad=${st.bad} a=${st.value[p0.a]} b=${st.value[p0.b]}`);

    /* ==================== D6 笔记 ==================== */
    console.log('\n— D6 笔记模式 —');
    await clickTool('notes');
    check('【笔记】按钮进入按下态', await evalJs(
        `document.querySelector('.sd-tool[data-act="notes"]').classList.contains('is-on')`));
    const cellN = await emptyCell();
    await clickCell(cellN);
    await press('3');
    st = await snap();
    check('笔记模式点数字 → 记成候选数，格子里没有大盘面数字',
        st.notes[cellN] === (1 << 2) && st.value[cellN] === '0', `mask=${st.notes[cellN]}`);
    check('候选数渲染在格内小网格里',
        await evalJs(`(() => {
            const c = document.querySelectorAll('.sd-cell')[${cellN}];
            return c.textContent.trim() === '3' && !c.classList.contains('has-val');
        })()`));
    await press('3');
    st = await snap();
    check('再点一次同一个数字 → 候选数去掉', st.notes[cellN] === 0);
    await press('5');
    await clickTool('notes');
    check('再按【笔记】退出笔记模式', await evalJs(
        `!document.querySelector('.sd-tool[data-act="notes"]').classList.contains('is-on')`));

    /* ==================== D10 自动清理笔记 ==================== */
    console.log('\n— D10 填数字自动清候选数 —');
    const p1 = pairs[1] || pairs[0];
    const vClean = 5;
    await clickCell(p1.a);
    await press(String(vClean));      // 现在 a 格填了 5
    st = await snap();
    check('同行另一格的候选数 5 被自动清掉',
        (st.notes[p1.b] & (1 << (vClean - 1))) === 0,
        `b.notes=${st.notes[p1.b]}（此前记过 5=${cellN === p1.b}`);

    /* ==================== D7 撤销 ==================== */
    console.log('\n— D7 撤销 —');
    const beforeUndo = await snap();
    await clickTool('undo');
    st = await snap();
    check('撤销按钮回退一步（盘面复原）', st.value[p1.a] === '0' && st.undoLen === beforeUndo.undoLen - 1,
        `v=${st.value[p1.a]} undo=${st.undoLen}`);
    await clickCell(p1.a);
    await press(String(vClean));
    await press('z', { ctrlKey: true });
    st = await snap();
    check('Ctrl+Z 也能撤销', st.value[p1.a] === '0', `v=${st.value[p1.a]}`);

    /* ==================== D8 提示 ==================== */
    console.log('\n— D8 提示 —');
    const hintsBefore = (await snap()).hints;
    await clickTool('hint');
    st = await snap();
    const hintCell = await evalJs(`(() => {
        const st = window.__sudoku.state;
        for (let i = 0; i < st.value.length; i++) {
            if (st.value[i] && st.value[i] === st.solution[i] && !st.puzzle[i]) return i;
        }
        return -1;
    })()`);
    check('提示填对一格（与答案一致）', hintCell >= 0 && st.value[hintCell] === String(
        await evalJs(`window.__sudoku.state.solution[${hintCell}]`)), `cell=${hintCell}`);
    check('提示次数 +1，且该格带 is-hint 标记', st.hints === hintsBefore + 1 && st.hinted >= 1,
        `hints=${st.hints} hinted=${st.hinted}`);
    /* 额度是按「空格数 × 难度系数」现算的，9×9 普通实测 7 次（52 空格） */
    check('提示额度已按尺寸×难度给出，HUD 与工具徽标同步「剩余」次数',
        st.hintTotal >= 5 && st.hintTotal <= 12 && !st.hintDisabled
        && st.hintHud === String(st.hintLeft) && st.hintBadge === String(st.hintLeft),
        `total=${st.hintTotal} left=${st.hintLeft} hud=${st.hintHud} badge=${st.hintBadge}`);

    /* ==================== D9 检查 ==================== */
    console.log('\n— D9 检查（每局 3 次）—');
    const wrongCell = await emptyCell();
    const rightAns = await evalJs(`window.__sudoku.state.solution[${wrongCell}]`);
    const wrongVal = rightAns === 1 ? 2 : 1;
    await clickCell(wrongCell);
    await press(String(wrongVal));
    await clickTool('check');
    st = await snap();
    /* 注意：is-bad 里既有"检查标出的错"，也有"同行列宫重复"的即时冲突。
       错填的数字很可能顺便和别的格子撞上，那部分是**长期**标红的，
       所以"几秒后消失"只能看 state.wrong（检查的临时标记），不能看 is-bad。 */
    check('检查标出填错的格子', st.wrong >= 1 && st.bad >= 1, `wrong=${st.wrong} bad=${st.bad}`);
    check('检查次数 3 → 2，按钮徽标同步', st.checks === 2 && await evalJs(
        `document.getElementById('sdChecks').textContent === '2'
         && document.getElementById('sdCheckBadge').textContent === '2'`), `checks=${st.checks}`);
    await sleep(3100);
    st = await snap();
    check('临时标记几秒后自动消失（不长期赖在棋盘上）', st.wrong === 0, `wrong=${st.wrong}`);

    /* ==================== D11 键盘 ==================== */
    console.log('\n— D11 键盘操作 —');
    const selBefore = (await snap()).sel;
    await press('ArrowRight');
    const selAfter = (await snap()).sel;
    check('方向键移动选中格', selAfter === (selBefore + 1) % 81, `${selBefore} → ${selAfter}`);
    await press('n');
    check('N 切换笔记模式', (await snap()).notesMode === true);
    await press('n');
    await press('Escape');
    check('Esc 取消选中', (await snap()).sel === -1);

    /* ==================== D12 存档 / 刷新恢复 ==================== */
    console.log('\n— D12 刷新后接着上一局 —');
    const c1 = await emptyCell();
    await clickCell(c1);
    await press(String(await evalJs(`window.__sudoku.state.solution[${c1}]`)));
    await sleep(700);                       // 等防抖落盘（400ms）
    const before = await snap();
    await send('Page.navigate', { url: PAGE });
    await waitFor('刷新后棋盘就绪', async () => !!(await evalJs(
        `!!(window.__sudoku && window.__sudoku.state.puzzle)`)), 20000, 120);
    const after = await snap();
    check('刷新后回到同一道题（题目一字不差）', before.puzzle === after.puzzle);
    check('刷新后玩家填的数字还在', before.value === after.value,
        `${before.value.slice(0, 20)}… vs ${after.value.slice(0, 20)}…`);
    check('刷新后尺寸/难度与用时都保住', after.n === 9 && after.diff === 'normal',
        `n=${after.n} diff=${after.diff} elapsed=${after.elapsed}`);
    check('刷新后答案由求解器还原（检查/提示仍可用）',
        await evalJs(`window.__sudoku.state.solution && window.__sudoku.state.solution.length === 81`));

    /* ==================== D13 换尺寸 ==================== */
    console.log('\n— D13 换尺寸（宫格 / 拉丁方）—');
    await pickSize(4);
    await sleep(500);
    st = await snap();
    check('切到 4×4：16 格、宫格粗线 4+4 条', st.n === 4 && st.cells === 16 && st.br === 4 && st.bb === 4,
        `cells=${st.cells} br=${st.br} bb=${st.bb}`);
    check('4×4 图例说明 2×2 宫', /4×4 · .*2×2 宫/.test(st.legend), st.legend);

    await pickSize(3);
    await sleep(500);
    st = await snap();
    check('切到 3×3：9 格、没有任何宫格粗线（拉丁方）',
        st.n === 3 && st.cells === 9 && st.br === 0 && st.bb === 0, `cells=${st.cells} br=${st.br}`);
    check('3×3 图例写明是拉丁方、为什么没有宫', /拉丁方/.test(st.legend) && /素数/.test(st.legend), st.legend);
    check('3×3 键盘只有 3 个数字键', await evalJs(`document.querySelectorAll('.sd-key').length === 3`));
    await shot('02-3x3');

    /* ==================== D14 完成一局 ==================== */
    console.log('\n— D14 填满 → 结算 —');
    await clickDiff('easy');
    await sleep(500);
    const fillCount = await evalJs(`(() => {
        const st = window.__sudoku.state, n = st.n, N = n * n;
        const cells = document.querySelectorAll('.sd-cell');
        let k = 0;
        for (let i = 0; i < N; i++) {
            if (st.value[i]) continue;
            cells[i].click();
            document.dispatchEvent(new KeyboardEvent('keydown',
                { key: String(st.solution[i]), bubbles: true, cancelable: true }));
            k++;
        }
        return k;
    })()`);
    st = await snap();
    check('整盘填满即判定完成', st.solved === true && fillCount > 0, `filled=${fillCount} solved=${st.solved}`);
    check('结算浮层弹出', await evalJs(`document.getElementById('sdWin').hidden === false`));
    check('结算浮层显示用时/提示/检查', await evalJs(`(() => {
        const t = document.getElementById('sdWinTime').textContent.trim();
        const s = document.getElementById('sdWinSub').textContent.trim();
        return /^\\d{2}:\\d{2}$/.test(t) && /3×3/.test(s);
    })()`));
    check('成绩写入 localStorage', await evalJs(`(() => {
        const s = JSON.parse(localStorage.getItem('sudoku_stats_v1') || '{}');
        return !!s['3-easy'] && s['3-easy'].done >= 1 && s['3-easy'].best > 0;
    })()`));
    const el1 = (await snap()).elapsed;
    await sleep(1300);
    const el2 = (await snap()).elapsed;
    check('完成后计时停止', el1 === el2, `${el1} → ${el2}`);
    await shot('03-win');

    /* ==================== D17 教学首页卡片 ==================== */
    console.log('\n— D17 教学首页第 7 张卡 —');
    await send('Page.navigate', { url: BASE + '/learn' });
    await waitFor('/learn 就绪', async () => !!(await evalJs(
        `document.querySelectorAll('.sc-learn-card').length >= 7`)), 15000, 120);
    check('教学首页有 7 张模块卡', await evalJs(`document.querySelectorAll('.sc-learn-card').length`) === 7,
        'count=' + await evalJs(`document.querySelectorAll('.sc-learn-card').length`));
    check('数独卡存在、指向 /learn/sudoku、主题色 indigo、图标不与乘法口诀重复',
        await evalJs(`(() => {
            const card = Array.from(document.querySelectorAll('.sc-learn-card'))
                .find(c => c.textContent.includes('数独'));
            if (!card) return false;
            const link = card.querySelector('a[href="/learn/sudoku"]');
            return !!link && card.dataset.accent === 'indigo'
                && !!card.querySelector('.bi-grid-3x3');
        })()`));

    /* ==================== D18 设置弹层 ==================== */
    console.log('\n— D18 设置弹层 —');
    await send('Page.navigate', { url: PAGE });
    await waitFor('棋盘就绪', async () => !!(await evalJs(
        `!!(window.__sudoku && window.__sudoku.state.puzzle)`)), 15000, 120);
    await evalJs(`document.getElementById('sdSetBtn').click()`);
    check('点齿轮打开设置弹层', await evalJs(
        `document.getElementById('sdSetPop').hidden === false
         && document.getElementById('sdSetBtn').getAttribute('aria-expanded') === 'true'`));
    check('弹层条目齐全（规则/重来/两个开关/成绩/清空/源码）', await evalJs(
        `document.querySelectorAll('#sdSetPop .lt-set-item').length === 7`),
        'items=' + await evalJs(`document.querySelectorAll('#sdSetPop .lt-set-item').length`));

    await evalJs(`Array.from(document.querySelectorAll('#sdSetPop [data-set]'))
        .find(x => x.dataset.set === 'help').click()`);
    await sleep(200);
    check('【规则说明】弹出玩法弹层', await evalJs(`document.getElementById('sdHelp').hidden === false`));
    await evalJs(`document.querySelector('#sdHelp .sd-modal-x').click()`);
    check('帮助弹层可关闭', await evalJs(`document.getElementById('sdHelp').hidden === true`));

    await evalJs(`document.getElementById('sdSetBtn').click()`);
    await evalJs(`Array.from(document.querySelectorAll('#sdSetPop [data-set]'))
        .find(x => x.dataset.set === 'same').click()`);
    check('【高亮相同数字】开关生效（写进 state 且记进 localStorage）', await evalJs(`(() => {
        const st = window.__sudoku.state;
        const o = JSON.parse(localStorage.getItem('sudoku_opts_v1') || '{}');
        return st.opts.sameNum === false && o.sameNum === false;
    })()`));
    await evalJs(`Array.from(document.querySelectorAll('#sdSetPop [data-set]'))
        .find(x => x.dataset.set === 'same').click()`);     // 关掉再打开，别影响后续
    await evalJs(`document.body.click()`);
    check('点弹层外面自动收起', await evalJs(`document.getElementById('sdSetPop').hidden === true`));

    /* ==================== D15 移动端布局 ==================== */
    console.log('\n— D15 移动端 375×667 —');
    await send('Emulation.setDeviceMetricsOverride', {
        width: 375, height: 667, deviceScaleFactor: 2, mobile: true
    });
    await send('Page.navigate', { url: PAGE });
    await waitFor('移动端棋盘就绪', async () => !!(await evalJs(
        `!!(window.__sudoku && window.__sudoku.state.puzzle)`)), 15000, 120);
    await sleep(400);

    /* 上一轮可能停在 3×3（键盘只有 3 个键），这里强制切到最坏情况 9×9 */
    if ((await snap()).n !== 9) {
        await pickSize(9);
        await sleep(700);
    }

    const m = await evalJs(`(() => {
        const side = document.querySelector('.sd-side');
        const sr = side.getBoundingClientRect();
        const keys = Array.from(document.querySelectorAll('.sd-key')).map(k => k.getBoundingClientRect());
        const tools = Array.from(document.querySelectorAll('.sd-tool')).map(k => k.getBoundingClientRect());
        const board = document.querySelector('.sd-board').getBoundingClientRect();
        const main = document.querySelector('.sd-main');
        return {
            vw: window.innerWidth, vh: window.innerHeight,
            scrollW: document.documentElement.scrollWidth,
            pos: getComputedStyle(side).position,
            sideTop: Math.round(sr.top), sideBottom: Math.round(sr.bottom),
            keyRows: new Set(keys.map(k => Math.round(k.top))).size,
            keyW: Math.round(keys[0].width), keyCount: keys.length,
            toolRows: new Set(tools.map(k => Math.round(k.top))).size,
            toolCount: tools.length,
            boardTop: Math.round(board.top), boardBottom: Math.round(board.bottom),
            boardW: Math.round(board.width),
            padBottom: parseInt(getComputedStyle(main).paddingBottom, 10)
        };
    })()`);
    check('页面无横向滚动（375 宽）', m.scrollW <= m.vw + 1, `scrollW=${m.scrollW} vw=${m.vw}`);
    check('数字键盘条 fixed 钉在视口底部', m.pos === 'fixed' && Math.abs(m.sideBottom - m.vh) <= 1,
        `pos=${m.pos} bottom=${m.sideBottom} vh=${m.vh}`);
    check('9 个数字键排成一行且宽度够点（≥30px）', m.keyRows === 1 && m.keyCount === 9 && m.keyW >= 30,
        `rows=${m.keyRows} w=${m.keyW}`);
    check('5 个工具排成一行', m.toolRows === 1 && m.toolCount === 5, `rows=${m.toolRows}`);
    check('正文预留了键盘高度（padding-bottom ≥ 120px）', m.padBottom >= 120, `padBottom=${m.padBottom}`);
    check('棋盘宽度贴合屏幕（不留大片空白、也不溢出）', m.boardW > 300 && m.boardW <= 375,
        `boardW=${m.boardW}`);
    await shot('04-mobile-9x9');

    /* 滚到页面底部，确认最后一行不被键盘条盖住。
       ⚠️ 滚动与度量必须分成两次 evaluate：同一次调用里 scrollTo 之后立刻取
       getBoundingClientRect，移动端模拟下量到的还是滚动前的布局（实测只挪了十几像素）。 */
    await evalJs(`window.scrollTo(0, document.documentElement.scrollHeight)`);
    await sleep(450);
    const covered = await evalJs(`(() => {
        const b = document.querySelector('.sd-board').getBoundingClientRect();
        const l = document.querySelector('.sd-legend').getBoundingClientRect();
        const s = document.querySelector('.sd-side').getBoundingClientRect();
        return {
            gap: Math.round(b.bottom - s.top),
            legendGap: Math.round(l.bottom - s.top),
            scrollTop: Math.round(document.scrollingElement.scrollTop)
        };
    })()`);
    check('滚到底后棋盘整体在键盘条之上（正文 padding 生效）',
        covered.gap <= 0 && covered.legendGap <= 0,
        `board=${covered.gap} legend=${covered.legendGap} scrollTop=${covered.scrollTop}`);
    await evalJs(`window.scrollTo(0, 0)`);

    /* ==================== D16 移动端 3×3 ==================== */
    console.log('\n— D16 移动端 3×3 —');
    await evalJs(`window.scrollTo(0, 0)`);
    await pickSize(3);
    await sleep(600);
    const m3 = await evalJs(`(() => {
        const b = document.querySelector('.sd-board').getBoundingClientRect();
        const keys = document.querySelectorAll('.sd-key');
        return { w: Math.round(b.width), keys: keys.length,
                 rows: new Set(Array.from(keys).map(k => Math.round(k.getBoundingClientRect().top))).size };
    })()`);
    check('3×3 棋盘不再铺满（≤340px），数字键 3 个一行', m3.w <= 340 && m3.keys === 3 && m3.rows === 1,
        `w=${m3.w} keys=${m3.keys} rows=${m3.rows}`);
    await shot('05-mobile-3x3');

    /* ==================== D20 尺寸下拉 ==================== */
    console.log('\n— D20 尺寸下拉（常玩 / 更多 / 最近）—');
    await send('Emulation.setDeviceMetricsOverride', {
        width: 1280, height: 940, deviceScaleFactor: 1, mobile: false
    });
    /* 从这里开始是"配置 / 教学"的专项：先把状态清干净，保证起点是默认 9×9。
       ⚠️ 不能"在数独页里 clear() 然后 navigate"：页面卸载时 visibilitychange /
       pagehide 会调 saveNow()，把刚清掉的存档又写回去（实测 D20 起点变成了 3×3）。
       先跳到 /learn（同源但不带数独逻辑），在那里清，再回数独页。 */
    await send('Page.navigate', { url: BASE + '/learn' });
    await sleep(600);
    await evalJs(`localStorage.clear()`);
    await send('Page.navigate', { url: PAGE });
    await waitFor('桌面重载就绪', async () => !!(await evalJs(
        `!!(window.__sudoku && window.__sudoku.state.puzzle)`)), 20000, 120);
    await sleep(300);

    check('下拉默认收起，按钮文案就是当前尺寸', await evalJs(`(() => {
        const st = window.__sudoku.state;
        return document.getElementById('sdSizePop').hidden === true
            && document.getElementById('sdSizeLabel').textContent === st.n + '×' + st.n;
    })()`));

    await evalJs(`document.getElementById('sdSizeBtn').click()`);
    const menu = await evalJs(`(() => {
        const q = (s) => Array.from(document.querySelectorAll(s));
        return {
            open: document.getElementById('sdSizePop').hidden === false,
            expanded: document.getElementById('sdSizeBtn').getAttribute('aria-expanded') === 'true',
            common: q('#sdCommon .sd-size-opt').map(x => Number(x.dataset.n)),
            moreHidden: document.getElementById('sdMoreList').hidden,
            moreCount: q('#sdMoreList .sd-size-opt').length,
            recent: q('#sdRecent .sd-size-opt').map(x => Number(x.dataset.n)),
            allCount: q('.sd-size-opt').length,
            uniq: Array.from(new Set(q('.sd-size-opt').map(x => Number(x.dataset.n)))).sort((a, b) => a - b),
            onN: q('.sd-size-opt.is-on').map(x => Number(x.dataset.n)),
            popW: Math.round(document.getElementById('sdSizePop').getBoundingClientRect().width)
        };
    })()`);
    check('点按钮打开下拉面板（aria-expanded 同步）', menu.open && menu.expanded);
    check('常玩区只有 3×3 与 6×6', JSON.stringify(menu.common) === '[3,6]', menu.common.join(','));
    check('其余 5 种折叠进【更多尺寸】、默认收起', menu.moreHidden && menu.moreCount === 5,
        `count=${menu.moreCount}`);
    check('「当前 / 最近用过」把当前尺寸排在最前', menu.recent[0] === 9, 'recent=' + menu.recent.join(','));
    check('7 种尺寸齐全（3~9 各一次；「最近」是重复入口，所以节点数是 8）',
        JSON.stringify(menu.uniq) === '[3,4,5,6,7,8,9]' && menu.allCount === 8,
        `uniq=${menu.uniq.join(',')} total=${menu.allCount}`);
    check('当前尺寸在面板里高亮', menu.onN.indexOf(9) >= 0, 'on=' + menu.onN.join(','));
    check('面板宽度不撑破视口（≤340px）', menu.popW > 200 && menu.popW <= 340, 'w=' + menu.popW);
    await shot('06-size-menu');

    await evalJs(`document.getElementById('sdMoreBtn').click()`);
    check('【更多尺寸】可展开', await evalJs(
        `document.getElementById('sdMoreList').hidden === false
         && document.getElementById('sdMoreBtn').getAttribute('aria-expanded') === 'true'`));

    await evalJs(`document.querySelector('#sdMoreList .sd-size-opt[data-n="4"]').click()`);
    await waitFor('切到 4×4', async () => (await snap()).n === 4, 20000, 120);
    await sleep(600);       // 等 applyPuzzle 落盘（newGame 里是 rAF + 20ms 异步）
    check('选中后面板自动收起', await evalJs(`document.getElementById('sdSizePop').hidden === true`));
    check('按钮文案 / 最近用过 / localStorage 三处同步', await evalJs(`(() => {
        const label = document.getElementById('sdSizeLabel').textContent;
        const recent = window.__sudoku.recentList();
        const o = JSON.parse(localStorage.getItem('sudoku_opts_v1') || '{}');
        return label === '4×4' && recent[0] === 4 && o.lastN === 4
            && Array.isArray(o.recent) && o.recent[0] === 4;
    })()`));

    await evalJs(`document.getElementById('sdSizeBtn').click()`);
    await evalJs(`document.body.click()`);
    check('点面板外面自动收起', await evalJs(`document.getElementById('sdSizePop').hidden === true`));

    await send('Page.navigate', { url: PAGE });
    await waitFor('刷新后回到 4×4', async () => (await snap()).n === 4, 20000, 120);
    check('刷新后默认值 = 上次选的尺寸（按钮与盘面一致）', await evalJs(`(() => {
        const st = window.__sudoku.state;
        return st.n === 4 && document.getElementById('sdSizeLabel').textContent === '4×4';
    })()`));

    /* 常玩区一键切回 3×3（不应依赖【更多】） */
    await pickSize(3);
    await waitFor('切回 3×3', async () => (await snap()).n === 3, 20000, 120);
    check('常玩区点一下就能换到 3×3', (await snap()).n === 3);

    /* ==================== D21 教学关卡 ==================== */
    console.log('\n— D21 教学关卡（6 步推演）—');
    /* 抹掉"看过"标记，验证首次访问的引导条 */
    await evalJs(`(() => {
        const o = JSON.parse(localStorage.getItem('sudoku_opts_v1') || '{}');
        delete o.teachSeen;
        localStorage.setItem('sudoku_opts_v1', JSON.stringify(o));
    })()`);
    await send('Page.navigate', { url: PAGE });
    await waitFor('引导条就绪', async () => !!(await evalJs(
        `!!(window.__sudoku && window.__sudoku.state.puzzle)`)), 20000, 120);
    await sleep(300);
    check('没看过教学时显示新手引导条', await evalJs(`document.getElementById('sdCoach').hidden === false`));

    await evalJs(`document.getElementById('sdCoachGo').click()`);
    await sleep(350);
    check('点【看教学】打开教学浮层', await evalJs(
        `document.getElementById('sdTeach').hidden === false`));

    const t1 = await evalJs(`(() => {
        const cells = Array.from(document.querySelectorAll('.sd-tc'));
        return {
            cells: cells.length,
            br: document.querySelectorAll('.sd-tc.is-br').length,
            bb: document.querySelectorAll('.sd-tc.is-bb').length,
            nums: cells.map(c => c.textContent).join(''),
            step: document.getElementById('sdTeachStep').textContent,
            kicker: document.getElementById('sdTeachKicker').textContent,
            dots: document.querySelectorAll('#sdTeachDots i').length,
            onDots: document.querySelectorAll('#sdTeachDots i.is-on').length,
            prevDis: document.getElementById('sdTeachPrev').disabled,
            formulaHidden: document.getElementById('sdTeachFormula').hidden
        };
    })()`);
    check('教学盘 4×4 / 16 格 / 宫线各 4 段（1 条内列 × 4 行 + 1 条内行 × 4 列）',
        t1.cells === 16 && t1.br === 4 && t1.bb === 4,
        `cells=${t1.cells} br=${t1.br} bb=${t1.bb}`);
    check('第 1 步讲规则：圆点 6 个、上一步禁用、无算式', /第 1 步 \/ 共 6 步/.test(t1.step)
        && t1.dots === 6 && t1.onDots === 1 && t1.prevDis === true && t1.formulaHidden === true, t1.step);
    check('教学盘开局只有题目给的 6 个数', (t1.nums.match(/[1-4]/g) || []).length === 6, t1.nums);

    /* 第 2 步：数空格 */
    await evalJs(`document.getElementById('sdTeachNext').click()`);
    await sleep(220);
    const t2 = await evalJs(`(() => ({
        formula: document.getElementById('sdTeachFormula').textContent,
        hidden: document.getElementById('sdTeachFormula').hidden,
        hl: document.querySelectorAll('.sd-tc.is-hl').length,
        dim: document.querySelectorAll('.sd-tc.is-dim').length
    }))()`);
    check('第 2 步演示「怎么数空格」并给出算式',
        t2.hidden === false && /4 格 − 2 个已知 = 2 个空格/.test(t2.formula), t2.formula);
    check('第 2 步只高亮第 1 行（4 格），其余 12 格淡化', t2.hl === 4 && t2.dim === 12,
        `hl=${t2.hl} dim=${t2.dim}`);

    /* 第 3 步：列排除 → 自动填 4 */
    await evalJs(`document.getElementById('sdTeachNext').click()`);
    await sleep(220);
    const t3 = await evalJs(`(() => {
        const cells = Array.from(document.querySelectorAll('.sd-tc'));
        const f = cells.findIndex(c => c.classList.contains('is-focus'));
        return {
            focus: f,
            focusText: f >= 0 ? cells[f].textContent : '',
            filled: document.querySelectorAll('.sd-tc.is-filled').length,
            hl: document.querySelectorAll('.sd-tc.is-hl').length,
            formula: document.getElementById('sdTeachFormula').textContent
        };
    })()`);
    check('第 3 步聚焦右上角空格、并演示「列里已有 2 ⇒ 只能 4」',
        t3.focus === 3 && t3.focusText === '4' && t3.filled === 1,
        `focus=${t3.focus} text=${t3.focusText} filled=${t3.filled}`);
    check('第 3 步同时高亮第 1 行与第 4 列（7 格）', t3.hl === 7, 'hl=' + t3.hl);

    /* 第 5 步：换宫看 */
    await evalJs(`window.__sudoku.teach.go(4)`);
    await sleep(220);
    const t5 = await evalJs(`(() => {
        const cells = Array.from(document.querySelectorAll('.sd-tc'));
        const f = cells.findIndex(c => c.classList.contains('is-focus'));
        return {
            boxAll: [2, 3, 6, 7].every(i => cells[i].classList.contains('is-hl')),
            focus: f,
            focusText: f >= 0 ? cells[f].textContent : '',
            filled: document.querySelectorAll('.sd-tc.is-filled').length,
            formula: document.getElementById('sdTeachFormula').textContent
        };
    })()`);
    check('第 5 步改按宫高亮（2×2 四格）、空格填 1（累计已填 3 格）',
        t5.boxAll && t5.focus === 6 && t5.focusText === '1' && t5.filled === 3,
        `focus=${t5.focus} text=${t5.focusText} filled=${t5.filled}`);
    check('第 5 步的算式说明「宫内只缺 1」', /只缺 1/.test(t5.formula), t5.formula);

    /* 计时在教学中暂停 */
    const tp1 = (await snap()).elapsed;
    await sleep(1300);
    const tp2 = (await snap()).elapsed;
    check('教学期间主局计时停住（1.3 秒内没走表）', tp1 === tp2 && await evalJs(
        `window.__sudoku.state.paused === true`), `${tp1} → ${tp2}`);

    /* 最后一步 + 关闭 */
    await evalJs(`window.__sudoku.teach.go(5)`);
    await sleep(180);
    check('最后一步讲工具、按钮变成【开始玩】', await evalJs(
        `document.getElementById('sdTeachNext').textContent.indexOf('开始玩') >= 0`));
    await evalJs(`document.getElementById('sdTeachNext').click()`);
    await sleep(250);
    check('点【开始玩】关闭教学并恢复计时', await evalJs(
        `document.getElementById('sdTeach').hidden === true && window.__sudoku.state.paused === false`));
    check('看过教学后引导条不再出现', await evalJs(`document.getElementById('sdCoach').hidden === true`));
    check('教学"看过"标记已落盘', await evalJs(
        `JSON.parse(localStorage.getItem('sudoku_opts_v1') || '{}').teachSeen === true`));

    await send('Page.navigate', { url: PAGE });
    await waitFor('刷新后教学标记仍在', async () => !!(await evalJs(
        `!!(window.__sudoku && window.__sudoku.state.puzzle)`)), 20000, 120);
    check('刷新后引导条不再出现（但【教学】按钮仍可重看）', await evalJs(
        `document.getElementById('sdCoach').hidden === true
         && !!document.getElementById('sdTeachBtn')`));

    /* 手机上教学浮层不能出屏 */
    await send('Emulation.setDeviceMetricsOverride', {
        width: 375, height: 667, deviceScaleFactor: 2, mobile: true
    });
    await send('Page.navigate', { url: PAGE });
    await waitFor('移动端就绪', async () => !!(await evalJs(
        `!!(window.__sudoku && window.__sudoku.state.puzzle)`)), 20000, 120);
    await evalJs(`document.getElementById('sdTeachBtn').click()`);
    await sleep(450);
    const tm = await evalJs(`(() => {
        const card = document.querySelector('.sd-teach-card').getBoundingClientRect();
        const grid = document.getElementById('sdTeachBoard').getBoundingClientRect();
        return {
            w: Math.round(card.width), h: Math.round(card.height),
            inside: card.top >= -1 && card.bottom <= window.innerHeight + 1,
            gridW: Math.round(grid.width),
            scrollW: document.documentElement.scrollWidth,
            vh: window.innerHeight
        };
    })()`);
    check('手机上教学卡片不出屏、棋盘收窄、无横向滚动',
        tm.inside && tm.w <= 376 && tm.scrollW <= 376 && tm.gridW <= 200,
        `card=${tm.w}x${tm.h} grid=${tm.gridW} scrollW=${tm.scrollW}`);
    await shot('07-mobile-teach');
    await evalJs(`window.__sudoku.teach.close()`);

    /* ==================== D22 提示额度（按尺寸 × 难度） ==================== */
    console.log('\n— D22 提示额度 —');
    const quota = (n, d, givens) => evalJs(`window.__sudoku.hintQuota(${n}, '${d}', ${givens})`);

    /* 纯函数：3×3 按比例算出来是 0，靠尺寸保底给到 1，所以三档都是 1 */
    const q3 = [await quota(3, 'easy', 7), await quota(3, 'normal', 5), await quota(3, 'hard', 4)];
    check('3×3 三档都给 1 次（比例算出来是 0，靠尺寸保底）', q3.every(v => v === 1), q3.join('/'));

    /* 同一盘（空格数固定）时，难度越高给得越多 */
    const qd = [await quota(9, 'easy', 24), await quota(9, 'normal', 24), await quota(9, 'hard', 24)];
    check('同为 57 空格：简单 < 普通 < 困难', qd[0] < qd[1] && qd[1] < qd[2], qd.join(' < '));

    /* 同难度时，空格越多给得越多（9×9 普通实测 52 空格 = 7 次） */
    const qb = [await quota(9, 'normal', 60), await quota(9, 'normal', 40), await quota(9, 'normal', 20)];
    check('同为普通：空格越多给得越多', qb[0] < qb[1] && qb[1] < qb[2], qb.join(' < '));

    /* 极端：只剩 1 个空格，额度也不能超过空格数（否则最后一段等于自动通关） */
    check('空格所剩无几时额度不超过空格数',
        await quota(3, 'hard', 8) === 1 && await quota(4, 'hard', 15) === 1);

    /* 实盘：3×3 简单 = 1 次，用完后按钮禁用 + 徽标隐藏 + 再按 H 也填不动 */
    await evalJs(`window.__sudoku.newGame(3, 'easy')`);
    await sleep(900);
    st = await snap();
    check('实盘 3×3 简单：额度就是 1 次', st.hintTotal === 1 && st.hintHud === '1',
        `total=${st.hintTotal} hud=${st.hintHud}`);

    await clickTool('hint');
    await sleep(200);
    st = await snap();
    check('唯一 1 次用完：按钮禁用、徽标隐藏、HUD 归 0 且变红',
        st.hintLeft === 0 && st.hints === 1 && st.hintDisabled && st.hintBadgeHidden
        && st.hintHud === '0' && st.hintPillWarn,
        `left=${st.hintLeft} disabled=${st.hintDisabled} hidden=${st.hintBadgeHidden} hud=${st.hintHud} warn=${st.hintPillWarn}`);

    const quotaFilled = st.value;
    await press('h');          /* 绕过 disabled 的按钮，走键盘这条路 */
    await sleep(250);
    st = await snap();
    check('额度用完后按 H 也不会再填（hint() 内部二次拦截）',
        st.value === quotaFilled && st.hints === 1, `hints=${st.hints}`);

    /* ==================== D19 运行期异常 ==================== */
    console.log('\n— D19 运行期异常 —');
    check('无 JS 运行时异常', runtimeErrors.length === 0, runtimeErrors.slice(0, 3).join(' | '));
    check('无 console.error', consoleErrors.length === 0, consoleErrors.slice(0, 3).join(' | '));

    const bad = results.filter(r => !r.ok);
    console.log(`\n==== ${results.length - bad.length}/${results.length} 通过 ====`);
    if (bad.length) console.log('未通过：\n' + bad.map(r => ' - ' + r.name + (r.extra ? '  [' + r.extra + ']' : '')).join('\n'));
    console.log('截图目录: ' + SHOT_DIR);
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
