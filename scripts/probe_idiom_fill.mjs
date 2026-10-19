#!/usr/bin/env node
/**
 * /learn/idiom-fill 定点验证：**成语填空**（小学 / 初中 / 高中三学段）。
 *
 * 需求（用户，2026-10-18）：
 *   教学板块新增「成语填空」，分小学 / 初中 / 高中三部分；每条成语**随机拆掉一个字**放到右边，
 *   交互参考「英语单词匹配」；词库取人教（部编）版课本内容。
 *
 * 这个探针守护的是「玩法能不能成立」，不是「页面能不能打开」：
 *   C1  三学段页签齐全，默认落在小学第 1 关
 *   C2  本关 6 题 / 6 个缺口 / 6 个字块
 *   C3  ★ 字块的字**两两不同**（否则两个一样的字块，玩家无从判断该填哪格）
 *   C4  每张卡恰好一个缺口，其余三格有字
 *   C5  ★ 缺口位置确实是「随机拆字」：30 关 180 个缺口覆盖四个位置，且没有任何一位占绝对多数
 *   C6  ★ 同一关重复进入：缺口位置与成语顺序都会重新随机（用户 2026-10-18 反馈「每次刷新缺的都是
 *         同一个字、顺序也一模一样」⇒ 改掉原来的固定种子，每次进关真随机）
 *   C7  答对：卡片变绿、缺口显示该字、字块变「已用」、提示条给出释义、已填数 +1
 *   C7b ★ 点整条成语（非缺口字格 / 卡片空白处）就能选中这条的缺口；再点一次取消
 *   C8  答错：卡片短暂抖动标红、失误 +1、缺口仍是空的、没有误判为完成
 *   C9  顺序无关：先点缺口、再点字块同样能填
 *   C9b ★ 先点字块、再点整条成语（不戳缺口）也能填对
 *   C10 全关填完：完成页出现并列出 6 条成语与释义、进度段变绿、localStorage 记下本关
 *   C11 点【下一关】进入下一关，棋盘重置
 *   C12 切到【初中】：题库换成初中的主题与成语，页签高亮跟着走
 *   C13 朗读：点小喇叭念的是这条成语（zh-CN）；关掉朗读后不再调用
 *   C13b ★ 设置弹层：齿轮可开可收，6 个条目齐（错题本/重做/朗读/导出/源码/重置），
 *         GitHub 那条是 <a target=_blank> 且指向项目仓库，弹层不超出视口
 *   C13c ★ 错题本：填错一条后自动入册（同一条只留一份），可清空
 *   C14 刷新页面后进度仍在（localStorage 生效）
 *   C14b ★ 重置进度：确认后清空全部学段记录，错题本保留
 *   C15 移动端 375×667：无横向滚动、答题盘钉在底部且不压住提示条、6 张卡都在
 *   C15b ★ 移动端完成页：长列表在文本区内部滚动，按钮区始终完整可见
 *   C16 教学首页：六张模块卡两行三列，成语填空卡的入口与主题色正确
 *   C18 ★ 导出：产物是单文件 HTML（无外链、词库内联、data-standalone），
 *        用 iframe 实跑一遍能渲染出棋盘，且「返回/导出/源码」在离线件里被收起
 *   C17 运行期无 JS 异常 / console.error
 *
 * 用法：
 *   bash /tmp/settings-verify/run.sh scripts/probe_idiom_fill.mjs   # 起 1889 临时实例跑（推荐）
 *   IDIOM_BASE=http://localhost:1888 node scripts/probe_idiom_fill.mjs
 * 可选环境变量：IDIOM_BASE、STORY_BROWSER_PATH、SHOT_DIR（截图目录，默认 /tmp/idiom-shots）
 */
import { spawn } from 'node:child_process';
import { SILENT_AUDIO_FLAGS, installSilentAudio } from './lib/silent-audio.mjs';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const BASE = process.env.IDIOM_BASE || process.env.WM_BASE || 'http://localhost:1888';
const PAGE = BASE + '/learn/idiom-fill';
const CHROME = process.env.STORY_BROWSER_PATH
    || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const PORT = 9341;
const SHOT_DIR = process.env.SHOT_DIR || '/tmp/idiom-shots';

const results = [];
const check = (name, ok, extra = '') => {
    results.push({ name, ok: !!ok, extra });
    console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${extra ? '  [' + extra + ']' : ''}`);
};

const userDataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'idiom-probe-'));
const chrome = spawn(CHROME, [
    '--headless=new', '--no-sandbox', '--disable-gpu', '--hide-scrollbars',
    ...SILENT_AUDIO_FLAGS,
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

/** 组件状态快照（只取可 JSON 化的字段，函数不碰） */
const state = () => evalJs(`(() => {
    const d = Alpine.$data(document.querySelector('.ic-root'));
    if (!d) return null;
    return {
        stageId: d.stageId,
        theme: d.currentLevel ? d.currentLevel.theme : '',
        levelIndex: d.levelIndex,
        levelCount: d.levelCount,
        filled: d.filledCount,
        mistakes: d.mistakes,
        finished: d.finished,
        remaining: d.remaining,
        tip: d.tipText,
        items: d.items.map(it => ({
            idiom: it.idiom, meaning: it.meaning, done: it.done,
            chars: it.chars.map(c => ({ ch: c.ch, blank: c.blank, filled: c.filled }))
        })),
        tiles: d.tiles.map(t => ({ ch: t.ch, used: t.used })),
        stages: d.stages.map(s => ({ id: s.id, label: s.label, levelCount: s.levelCount, idiomCount: s.idiomCount })),
        progress: JSON.parse(JSON.stringify(d.progress))
    };
})()`);

async function clickSlot(itemIdx, slotIdx) {
    return evalJs(`(() => {
        const card = document.querySelectorAll('.ic-col-idiom .ic-item')[${itemIdx}];
        if (!card) return false;
        const s = card.querySelectorAll('.ic-slot')[${slotIdx}];
        if (!s || s.disabled) return false;
        s.click();
        return true;
    })()`);
}

async function clickTile(ch) {
    return evalJs(`(() => {
        const t = Array.from(document.querySelectorAll('.ic-col-char .ic-tile'))
            .find(el => el.textContent.trim() === ${JSON.stringify(ch)} && !el.disabled);
        if (!t) return false;
        t.click();
        return true;
    })()`);
}

const clickSpeak = (itemIdx) => evalJs(`(() => {
    const card = document.querySelectorAll('.ic-col-idiom .ic-item')[${itemIdx}];
    if (!card) return false;
    const b = card.querySelector('.ic-speak');
    if (!b) return false;
    b.click();
    return true;
})()`);

/** 找第 i 条成语的「缺口位置」与「答案字」 */
function blankOf(st, i) {
    const chars = st.items[i].chars;
    for (let k = 0; k < chars.length; k++) {
        if (chars[k].blank) return { slot: k, ch: chars[k].ch, filled: chars[k].filled };
    }
    return null;
}

/* ---------- 设置弹层：三页结构一致，按类名点 ---------- */

const openSettings = async () => {
    const ok = await evalJs(`(() => {
        const b = document.querySelector('.ic-toolbar-actions .lt-set-btn');
        if (!b) return false;
        if (b.getAttribute('aria-expanded') !== 'true') b.click();
        return true;
    })()`);
    await sleep(180);
    return ok;
};

const closeSettings = async () => {
    await evalJs(`(() => {
        const b = document.querySelector('.ic-toolbar-actions .lt-set-btn');
        if (b && b.getAttribute('aria-expanded') === 'true') b.click();
        return true;
    })()`);
    await sleep(150);
};

/** 点设置弹层里某个条目（按文案匹配；danger/外部链接都在同一层） */
const clickSetItem = async (re) => evalJs(`(() => {
    const items = Array.from(document.querySelectorAll('.lt-set-pop .lt-set-item'));
    const el = items.find(x => new RegExp(${JSON.stringify(re.source)}).test(x.textContent));
    if (!el) return false;
    el.click();
    return true;
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

    await installSilentAudio(send);
    await send('Runtime.enable');
    await send('Emulation.setDeviceMetricsOverride', {
        width: 1280, height: 940, deviceScaleFactor: 1, mobile: false
    });

    /* ==================== C0 前置：干净状态进页面 ==================== */
    console.log('\n— C0 前置 —');
    await send('Page.navigate', { url: PAGE });
    await sleep(700);
    await evalJs(`localStorage.clear()`);
    await send('Page.navigate', { url: PAGE });
    const loaded = await waitFor('组件初始化', async () => !!(await evalJs(
        `!!(window.Alpine && document.querySelector('.ic-root') && Alpine.$data(document.querySelector('.ic-root')).ready
          && document.querySelectorAll('.ic-col-idiom .ic-item').length)`)), 20000, 150);
    check('页面装载：Alpine 初始化完成且棋盘已渲染 6 张卡', loaded &&
        (await evalJs(`document.querySelectorAll('.ic-col-idiom .ic-item').length`)) === 6);
    check('静音守门已接管 speechSynthesis（探针不会真的念出来）', await evalJs(`(() => {
        const st = window.__smokeAudioOff;
        return !!(st && st.guard && window.speechSynthesis && window.speechSynthesis.speak
                  && window.speechSynthesis.speak.__smokeGuard);
    })()`));

    let st = await state();

    /* ==================== C1 三学段 + 默认关卡 ==================== */
    console.log('\n— C1 学段页签 —');
    const tabs = await evalJs(`Array.from(document.querySelectorAll('.ic-stage')).map(b => ({
        label: b.querySelector('.ic-stage-label').textContent.trim(),
        grade: b.querySelector('.ic-stage-grade').textContent.trim(),
        on: b.classList.contains('is-on'),
        num: b.querySelector('.ic-stage-num').textContent.trim()
    }))`);
    check('三个学段页签：小学 / 初中 / 高中',
        tabs.length === 3 && tabs.map(t => t.label).join(',') === '小学,初中,高中',
        tabs.map(t => t.label + '(' + t.grade + ')').join(' '));
    check('默认停在小学、第 1 关',
        st.stageId === 'primary' && st.levelIndex === 0 && tabs[0].on,
        `stage=${st.stageId} level=${st.levelIndex}`);
    check('每学段 10 关、60 条成语', st.stages.every(s => s.levelCount === 10 && s.idiomCount === 60),
        st.stages.map(s => s.label + ':' + s.levelCount + '关/' + s.idiomCount + '条').join(' '));

    /* ==================== C2 本关结构 ==================== */
    console.log('\n— C2 本关结构 —');
    check('本关 6 题', st.items.length === 6, `items=${st.items.length} theme=${st.theme}`);
    check('6 个缺口、6 个字块', st.items.reduce((n, it) => n + it.chars.filter(c => c.blank).length, 0) === 6
        && st.tiles.length === 6, `tiles=${st.tiles.map(t => t.ch).join('')}`);
    check('每张卡恰好一个缺口，其余三格有字',
        st.items.every(it => it.chars.filter(c => c.blank).length === 1 && it.chars.length === 4
            && it.chars.every(c => c.ch.length === 1)));

    /* ==================== C3 字块两两不同（核心红线） ==================== */
    console.log('\n— C3 拆出来的字互不相同 —');
    const tileChars = st.tiles.map(t => t.ch);
    check('★ 字块的字两两不同（否则两个一样的字块无法判断该填哪格）',
        new Set(tileChars).size === tileChars.length, tileChars.join(' '));
    check('字块的字 == 各缺口被拆掉的字（不多不少）',
        tileChars.slice().sort().join('') ===
        st.items.map((it, i) => blankOf(st, i).ch).sort().join(''),
        `tiles=${tileChars.slice().sort().join('')} blanks=${st.items.map((it, i) => blankOf(st, i).ch).sort().join('')}`);

    /* ==================== C4/C5 随机拆字 ==================== */
    console.log('\n— C4/C5 缺口位置随机 —');
    const positions = [];
    for (const stage of st.stages) {
        for (let i = 0; i < stage.levelCount; i++) {
            const pos = await evalJs(`(() => {
                const d = Alpine.$data(document.querySelector('.ic-root'));
                d.stageId = ${JSON.stringify(stage.id)};
                d.loadLevel(${i});
                return d.items.map(it => it.chars.findIndex(c => c.blank));
            })()`);
            positions.push(...pos);
        }
    }
    const dist = [0, 0, 0, 0];
    positions.forEach(p => dist[p]++);
    const total = positions.length;
    check('★ 缺口位置覆盖四个字位（真的是「随机拆一个字」，不是固定拆第 N 字）',
        dist.every(n => n > 0), `位置分布 第1字:${dist[0]} 第2字:${dist[1]} 第3字:${dist[2]} 第4字:${dist[3]}（共 ${total}）`);
    check('没有哪个字位占绝对多数（最大占比 < 60%）',
        Math.max(...dist) / total < 0.6, `max=${(Math.max(...dist) / total * 100).toFixed(1)}%`);

    /* ==================== C6 每次进关重新随机 ==================== */
    console.log('\n— C6 重复进入：缺口位置与成语顺序都重新随机 —');
    const reloads = await evalJs(`(() => {
        const d = Alpine.$data(document.querySelector('.ic-root'));
        d.stageId = 'primary';
        const rows = [];
        for (let k = 0; k < 8; k++) {
            d.loadLevel(0);
            rows.push({
                pos: d.items.map(it => it.chars.findIndex(c => c.blank)).join(''),
                order: d.items.map(it => it.idiom).join('/')
            });
        }
        return rows;
    })()`);
    const posSet = new Set(reloads.map(r => r.pos));
    const orderSet = new Set(reloads.map(r => r.order));
    check('★ 同一关重复进入，缺口位置重新随机（8 次出现多种组合）',
        posSet.size >= 2, `8 次里 ${posSet.size} 种位置组合：${Array.from(posSet).slice(0, 4).join(' ')}`);
    check('★ 同一关重复进入，成语顺序也重新随机（不再固定排列）',
        orderSet.size >= 2, `8 次里 ${orderSet.size} 种排列`);
    check('重洗只换顺序与缺口，题目内容不变（仍是本关那 6 条）',
        new Set(reloads.map(r => r.order.split('/').sort().join('/'))).size === 1);

    /* ==================== C7 答对 ==================== */
    console.log('\n— C7 答对 —');
    st = await state();
    const b0 = blankOf(st, 0);
    check('先点字块：命中「' + b0.ch + '」', await clickTile(b0.ch));
    check('再点缺口：位次 ' + (b0.slot + 1), await clickSlot(0, b0.slot));
    await sleep(180);
    st = await state();
    const card0 = await evalJs(`(() => {
        const c = document.querySelectorAll('.ic-col-idiom .ic-item')[0];
        const slot = c.querySelectorAll('.ic-slot')[${b0.slot}];
        return { done: c.classList.contains('is-done'), text: slot.textContent.trim(),
                 filled: slot.classList.contains('is-filled'), tick: !!c.querySelector('.ic-tick') };
    })()`);
    check('卡片变绿（is-done）+ 打勾', card0.done && card0.tick);
    check('缺口里显示出这个字', card0.text === b0.ch && card0.filled, `slot="${card0.text}"`);
    check('该字块被标记为「已用」并禁用', st.tiles.find(t => t.ch === b0.ch).used);
    check('已填数 1/6', st.filled === 1, `filled=${st.filled}`);
    check('提示条给出这条成语的释义（学的落点）', st.tip === st.items[0].meaning, `tip=${st.tip.slice(0, 18)}…`);
    await shot('01-desktop-correct');

    /* ==================== C7b 点整条成语即选中 ==================== */
    console.log('\n— C7b 点整条成语就能选中 —');
    // 用第 2 条（第 1 条已完成，点它没反应）；缺口位是 b1pre.slot
    st = await state();
    const b1pre = blankOf(st, 1);
    const otherSlot = [0, 1, 2, 3].find(k => k !== b1pre.slot);
    const clickItemSlot = (i, k) => evalJs(`(() => {
        const card = document.querySelectorAll('.ic-col-idiom .ic-item')[${i}];
        const slots = card.querySelectorAll('.ic-slot');
        if (!slots[${k}]) return false;
        slots[${k}].click();
        return true;
    })()`);

    // ① 点「非缺口」字格 —— 原来这里是 disabled，点了没反应
    check('点该条的非缺口字格能命中', await clickItemSlot(1, otherSlot));
    await sleep(160);
    check('点非缺口字格：整条进入选中态（is-sel）', await evalJs(
        `document.querySelectorAll('.ic-col-idiom .ic-item')[1].classList.contains('is-sel')`));
    check('选中的正是这条的缺口格（不是被点的那个字格）', await evalJs(
        `!!document.querySelectorAll('.ic-col-idiom .ic-item')[1].querySelector('.ic-slot.is-sel.is-blank')`));
    await shot('08-desktop-item-selected');

    // ② 再点一次 = 取消（与点缺口格的语义一致）
    await clickItemSlot(1, otherSlot);
    await sleep(140);
    check('再点一次同一条：取消选中', await evalJs(
        `!document.querySelector('.ic-item.is-sel') && !document.querySelector('.ic-slot.is-sel')`));

    // ③ 点卡片空白处（.ic-item 自身，既不是字格也不是按钮）
    await evalJs(`document.querySelectorAll('.ic-col-idiom .ic-item')[1].click()`);
    await sleep(150);
    check('点卡片空白区：整条也能选中', await evalJs(
        `document.querySelectorAll('.ic-col-idiom .ic-item')[1].classList.contains('is-sel')`));

    // 收尾：清掉选中，避免影响 C8（否则点字块会立刻对第 2 条判错）
    await evalJs(`(() => {
        const d = Alpine.$data(document.querySelector('.ic-root'));
        d.selBlank = null;
        return true;
    })()`);
    await sleep(100);

    /* ==================== C8 答错 ==================== */
    console.log('\n— C8 答错 —');
    const b1 = blankOf(st, 1);
    const wrongCh = st.tiles.find(t => !t.used && t.ch !== b1.ch).ch;
    await evalJs(`(() => {
        window.__wrongSeen = 0;
        if (window.__wrongObs) window.__wrongObs.disconnect();
        window.__wrongObs = new MutationObserver(() => {
            if (document.querySelector('.ic-item.is-wrong')) window.__wrongSeen++;
        });
        window.__wrongObs.observe(document.querySelector('.ic-col-idiom'),
            { attributes: true, subtree: true, attributeFilter: ['class'] });
        return true;
    })()`);
    const mistakesBefore = st.mistakes;
    await clickTile(wrongCh);
    await clickSlot(1, b1.slot);
    await sleep(160);
    st = await state();
    const wrongSeen = await evalJs(`window.__wrongSeen`);
    check('答错：卡片出现 is-wrong（抖动标红）', wrongSeen > 0, `__wrongSeen=${wrongSeen}`);
    check('答错：失误 +1', st.mistakes === mistakesBefore + 1, `${mistakesBefore} -> ${st.mistakes}`);
    check('答错：缺口仍是空的、卡片没变绿',
        !st.items[1].done && st.items[1].chars[b1.slot].filled === false);
    check('答错：那个字块没被消耗（还能继续用）', !st.tiles.find(t => t.ch === wrongCh).used);
    await shot('02-desktop-wrong');

    /* ==================== C9 顺序无关 + 取消选择 ==================== */
    console.log('\n— C9 先点缺口、再点字块 —');
    // C8 答错后那个错字仍处于选中态（刻意的：它可能属于别的空）。
    // 再点一次同一个字 = 取消选择，然后按「先缺口、后字块」的顺序重来。
    await clickTile(wrongCh);
    await sleep(120);
    check('再点一次已选中的字：取消选择',
        await evalJs(`!document.querySelector('.ic-tile.is-sel') && !document.querySelector('.ic-slot.is-sel')`));
    await clickSlot(1, b1.slot);
    await sleep(140);
    check('先点缺口：缺口出现选中态', await evalJs(`!!document.querySelector('.ic-slot.is-sel')`));
    await clickTile(b1.ch);
    await sleep(160);
    st = await state();
    check('再点字块：同样填对（与「先点字块」等价）', st.items[1].done && st.filled === 2, `filled=${st.filled}`);

    /* ==================== C9b 先点字块、再点整条成语 ==================== */
    console.log('\n— C9b 先点字块、再点整条成语 —');
    // 手机上最常用的打法：字在下面、手点上面那条成语，不必精准戳中缺口
    await evalJs(`Alpine.$data(document.querySelector('.ic-root')).selBlank = null`);
    st = await state();
    const b2c = blankOf(st, 2);
    check('点字块「' + b2c.ch + '」', await clickTile(b2c.ch));
    await evalJs(`(() => {
        const card = document.querySelectorAll('.ic-col-idiom .ic-item')[2];
        card.querySelectorAll('.ic-slot')[0].click();   // 点第 3 条的非缺口字格
        return true;
    })()`);
    await sleep(220);
    st = await state();
    check('★ 先点字块、再点整条成语：直接填对（不用精准戳缺口）',
        st.items[2].done, `filled=${st.filled}`);

    /* ==================== C10 全关通关 ==================== */
    console.log('\n— C10 全关通关 —');
    for (let i = 0; i < 6; i++) {
        st = await state();
        const b = blankOf(st, i);
        if (!b || b.filled) continue;
        await clickTile(b.ch);
        await clickSlot(i, b.slot);
        await sleep(110);
    }
    await waitFor('完成页', async () => (await evalJs(
        `!!document.querySelector('.ic-done') && document.querySelector('.ic-done').getClientRects().length > 0`)), 6000, 100);
    st = await state();
    const doneInfo = await evalJs(`(() => {
        const d = document.querySelector('.ic-done');
        const rows = Array.from(d.querySelectorAll('.ic-done-list li')).map(li => ({
            word: li.querySelector('.ic-done-word').textContent.trim(),
            mean: li.querySelector('.ic-done-mean').textContent.trim()
        }));
        return { rows, title: d.querySelector('.ic-done-title').textContent.trim(),
                 stat: d.querySelector('.ic-done-stat').textContent.trim(),
                 next: d.querySelector('.ic-done-actions .btn-primary span').textContent.trim() };
    })()`);
    check('全关填完后出现完成页', st.finished && doneInfo.title.includes('本关完成'), doneInfo.title);
    check('完成页列出 6 条成语，且都带释义',
        doneInfo.rows.length === 6 && doneInfo.rows.every(r => r.word.length === 4 && r.mean.length > 3),
        doneInfo.rows.map(r => r.word).join(' '));
    check('完成页给出用时与失误次数', /用时 \d\d:\d\d/.test(doneInfo.stat), doneInfo.stat);
    check('完成页按钮是【下一关】', doneInfo.next === '下一关', doneInfo.next);
    const segDone = await evalJs(`document.querySelectorAll('.ic-progress .ic-seg.is-done').length`);
    check('进度条第 1 段变绿', segDone === 1, `is-done 段数=${segDone}`);
    check('进度写进 localStorage（idiom_fill_v1 里 primary.done 含 0）',
        !!(st.progress.primary && st.progress.primary.done.includes(0)),
        JSON.stringify(st.progress.primary));
    await shot('03-desktop-level-done');

    /* ==================== C11 下一关 ==================== */
    console.log('\n— C11 下一关 —');
    await evalJs(`document.querySelector('.ic-done-actions .btn-primary').click()`);
    await sleep(320);
    st = await state();
    check('进入第 2 关且棋盘重置', st.levelIndex === 1 && st.items.length === 6 && st.filled === 0
        && !st.finished, `level=${st.levelIndex} filled=${st.filled}`);
    check('第 2 关的字块仍两两不同',
        new Set(st.tiles.map(t => t.ch)).size === st.tiles.length, st.tiles.map(t => t.ch).join(' '));

    /* ==================== C12 切学段 ==================== */
    console.log('\n— C12 切到初中 —');
    const primaryWords = st.items.map(i => i.idiom).join(' ');
    await evalJs(`Array.from(document.querySelectorAll('.ic-stage'))[1].click()`);
    await sleep(320);
    st = await state();
    const juniorWords = st.items.map(i => i.idiom).join(' ');
    check('学段切到初中，页签高亮跟着走', st.stageId === 'junior' && await evalJs(
        `document.querySelectorAll('.ic-stage')[1].classList.contains('is-on')`), `theme=${st.theme}`);
    check('初中题库确实换了（成语与小学完全不同）',
        st.items.every(it => primaryWords.indexOf(it.idiom) < 0) && juniorWords !== primaryWords,
        juniorWords);
    check('初中也是 6 题、字块两两不同',
        st.items.length === 6 && new Set(st.tiles.map(t => t.ch)).size === 6,
        st.tiles.map(t => t.ch).join(' '));
    await shot('04-desktop-junior');

    /* ==================== C13 朗读 ==================== */
    console.log('\n— C13 朗读 —');
    await evalJs(`(() => {
        window.__spoke = [];
        const inner = window.speechSynthesis.speak;
        window.speechSynthesis.speak = function (u) {
            window.__spoke.push({ text: String(u && u.text || ''), lang: String(u && u.lang || '') });
            return inner.call(this, u);
        };
        return true;
    })()`);
    const target0 = st.items[0].idiom;
    check('点第 1 条的小喇叭', await clickSpeak(0));
    await sleep(200);
    let spoke = await evalJs(`window.__spoke.slice()`);
    check('朗读的正是这条成语，语言 zh-CN',
        spoke.length === 1 && spoke[0].text === target0 && spoke[0].lang === 'zh-CN',
        JSON.stringify(spoke));
    // 答对也会念一次
    const b2 = blankOf(st, 0);
    await clickTile(b2.ch);
    await clickSlot(0, b2.slot);
    await sleep(200);
    spoke = await evalJs(`window.__spoke.slice()`);
    check('填对时自动念一遍成语', spoke.length === 2 && spoke[1].text === target0, JSON.stringify(spoke.slice(-1)));
    // 关掉朗读（设置弹层里的「音效与朗读」）
    check('设置弹层里能关掉朗读', await openSettings() && await clickSetItem(/音效与朗读/));
    await closeSettings();
    await sleep(120);
    await evalJs(`window.__spoke = []`);
    await clickSpeak(1);
    await sleep(200);
    spoke = await evalJs(`window.__spoke.slice()`);
    check('关掉朗读后点喇叭不再调用 TTS', spoke.length === 0, `calls=${spoke.length}`);
    check('再从设置里打开朗读', await openSettings() && await clickSetItem(/音效与朗读/));
    await closeSettings();   // 恢复，供后续用例

    /* ==================== C13b 设置弹层 ==================== */
    console.log('\n— C13b 设置弹层 —');
    await openSettings();
    const panel = await evalJs(`(() => {
        const pop = document.querySelector('.lt-set-pop');
        if (!pop || !pop.getClientRects().length) return null;
        const items = Array.from(pop.querySelectorAll('.lt-set-item'));
        const r = pop.getBoundingClientRect();
        const src = pop.querySelector('.lt-set-item.is-source');
        return {
            open: true,
            titles: items.map(x => x.querySelector('.lt-set-title').textContent.trim().split('\\n')[0].trim()),
            srcTag: src ? src.tagName : '',
            srcHref: src ? src.getAttribute('href') : '',
            srcTarget: src ? src.getAttribute('target') : '',
            left: Math.round(r.left), right: Math.round(r.right), innerW: window.innerWidth,
            gearOpen: document.querySelector('.lt-set-btn').classList.contains('is-open')
        };
    })()`);
    check('点齿轮能打开设置弹层', !!panel && panel.open);
    check('齿轮按钮同步进入打开态（弹层从哪冒出来的看得出来）', !!panel && panel.gearOpen);
    check('六个条目齐全：错题本 / 重做本关 / 音效与朗读 / 导出 / 源码 / 重置进度',
        !!panel && panel.titles.join(',') === '错题本,重做本关,音效与朗读,导出,源码,重置进度',
        panel && panel.titles.join(' / '));
    check('★ 源码是 <a target=_blank> 指向 GitHub 项目仓库',
        !!panel && panel.srcTag === 'A' && panel.srcTarget === '_blank'
        && /^https:\/\/github\.com\/renfufei\/Story-Creator\/?$/.test(panel.srcHref),
        panel && `${panel.srcTag} ${panel.srcHref} ${panel.srcTarget}`);
    check('弹层不超出视口右侧', !!panel && panel.right <= panel.innerW,
        panel && `right=${panel.right} innerW=${panel.innerW}`);
    await shot('09-settings-panel');
    await closeSettings();
    check('再点齿轮能收起弹层', await evalJs(
        `!document.querySelector('.lt-set-pop').getClientRects().length`));

    /* ==================== C13c 错题本 ==================== */
    console.log('\n— C13c 错题本 —');
    st = await state();
    // 前面 C8 已经制造过一次错填，错题本里本来就可能有记录 —— 先取基线再比增量
    const wbBefore = await evalJs(`Alpine.$data(document.querySelector('.ic-root')).wrongCount`);
    // 故意填错第 2 条：挑一个字块，它不是这条的答案
    const ans2 = blankOf(st, 2);
    const badTile2 = st.tiles.find(t => !t.used && t.ch !== ans2.ch);
    await clickSlot(2, ans2.slot);
    await clickTile(badTile2.ch);
    await sleep(200);
    const wb = await evalJs(`(() => {
        const d = Alpine.$data(document.querySelector('.ic-root'));
        return { count: d.wrongCount, items: d.wrongItems.map(w => ({ label: w.label, sub: w.sub })) };
    })()`);
    check('填错一条后错题本里记下了它（新增 1 条，最新在最前）',
        wb.count === wbBefore + 1 && wb.items[0].label === st.items[2].idiom,
        `${wbBefore} → ${wb.count}｜${JSON.stringify(wb.items[0] || {})}`);
    check('错题条目带释义（能当复习卡看）', !!(wb.items[0] && wb.items[0].sub),
        wb.items[0] && wb.items[0].sub);
    // 再错同一条：不应变成两条
    await clickSlot(2, ans2.slot);
    await clickTile(st.tiles.find(t => !t.used && t.ch !== ans2.ch).ch);
    await sleep(200);
    check('同一条成语只记一份（不重复累加）',
        (await evalJs(`Alpine.$data(document.querySelector('.ic-root')).wrongCount`)) === wbBefore + 1);
    await openSettings();
    check('设置按钮上的计数徽标同步显示条数', (await evalJs(`(() => {
        const c = document.querySelector('.lt-set-btn .lt-count');
        return c && c.getClientRects().length ? c.textContent.trim() : '';
    })()`)) === String(wbBefore + 1));
    await clickSetItem(/错题本/);
    await sleep(250);
    const book = await evalJs(`(() => {
        const m = document.querySelectorAll('.ic-modal-backdrop');
        const el = Array.from(m).find(x => /错题本/.test(x.textContent) && x.getClientRects().length);
        if (!el) return null;
        return {
            rows: el.querySelectorAll('.lt-wrong-item').length,
            label: el.querySelector('.lt-wrong-label').textContent.trim(),
            bodyScroll: getComputedStyle(el.querySelector('.lt-wrong-list')).overflowY
        };
    })()`);
    check('错题本弹窗列出该条成语（最新一条在最前）',
        !!book && book.rows === wbBefore + 1 && book.label === st.items[2].idiom,
        book && `${book.rows} 行 ${book.label}`);
    check('列表是独立滚动容器（条目多了不会把按钮顶出去）',
        !!book && book.bodyScroll === 'auto', book && book.bodyScroll);
    await shot('10-wrong-book');
    await evalJs(`(() => {
        const btns = Array.from(document.querySelectorAll('.ic-modal-backdrop button'))
            .filter(b => /清空错题本/.test(b.textContent) && b.getClientRects().length);
        if (btns[0]) btns[0].click();
        return true;
    })()`);
    await sleep(200);
    check('清空错题本后归零', (await evalJs(
        `Alpine.$data(document.querySelector('.ic-root')).wrongCount`)) === 0);
    await evalJs(`Alpine.$data(document.querySelector('.ic-root')).closeWrongBook()`);
    await sleep(150);

    /* ==================== C14 刷新后进度还在 ==================== */
    console.log('\n— C14 刷新保留进度 —');
    await send('Page.navigate', { url: PAGE });
    await waitFor('重新装载', async () => !!(await evalJs(
        `!!window.Alpine && document.querySelector('.ic-col-idiom .ic-item')`)), 20000, 150);
    st = await state();
    check('刷新后仍停在上次学段（初中）', st.stageId === 'junior', `stage=${st.stageId} level=${st.levelIndex}`);
    const keep = await evalJs(`(() => {
        const raw = JSON.parse(localStorage.getItem('idiom_fill_v1') || '{}');
        return { primary: (raw.primary || {}).done || [] };
    })()`);
    check('小学第 1 关的完成记录还在 localStorage', keep.primary.includes(0), JSON.stringify(keep));

    /* ==================== C14b 重置进度（放到进度校验之后） ==================== */
    console.log('\n— C14b 重置进度 —');
    // 错题本先放一条进去，重置后它必须还在（两件事分开）
    await evalJs(`(() => {
        const d = Alpine.$data(document.querySelector('.ic-root'));
        d.recordWrong({ idiom: '守株待兔', meaning: '守着树桩等兔子，比喻死守经验、不知变通' });
        return d.wrongCount;
    })()`);
    check('重置前错题本里有 1 条', (await evalJs(
        `Alpine.$data(document.querySelector('.ic-root')).wrongCount`)) === 1);
    await openSettings();
    check('点【重置进度】', await clickSetItem(/重置进度/));
    await sleep(300);
    const dlg = await evalJs(`(() => {
        const el = document.querySelector('.sc-modal-backdrop');
        if (!el || !el.getClientRects().length) return null;
        return { text: el.textContent.replace(/\\s+/g, ' ').trim().slice(0, 80) };
    })()`);
    check('弹出确认对话框（不是点一下就抹掉）', !!dlg, dlg && dlg.text);
    await evalJs(`(() => {
        const btns = Array.from(document.querySelectorAll('.sc-modal-backdrop button'));
        const ok = btns.find(b => /重置/.test(b.textContent) && !/取消/.test(b.textContent));
        if (ok) ok.click();
        return true;
    })()`);
    await sleep(400);
    const after = await evalJs(`(() => {
        const raw = localStorage.getItem('idiom_fill_v1');
        const o = raw ? JSON.parse(raw) : {};
        // loadLevel(0) 会立刻写下一条 {done:[], current:0}，所以不能断言「键没了」；
        // 真正的不变量是「所有学段的 done 都空了」
        let doneCount = 0;
        Object.keys(o).forEach(k => { doneCount += ((o[k] && o[k].done) || []).length; });
        const d = Alpine.$data(document.querySelector('.ic-root'));
        return { doneCount, stageDone: d.stageDone(d.stageId), wrongCount: d.wrongCount, level: d.levelIndex };
    })()`);
    check('确认后清空进度记录并回到第 1 关',
        after.doneCount === 0 && after.stageDone === 0 && after.level === 0,
        `done=${after.doneCount} stageDone=${after.stageDone} level=${after.level}`);
    check('错题本不被「重置进度」牵连', after.wrongCount === 1, `wrong=${after.wrongCount}`);

    /* ==================== C15 移动端 375×667 ==================== */
    console.log('\n— C15 移动端 375×667 —');
    await send('Emulation.setDeviceMetricsOverride', {
        width: 375, height: 667, deviceScaleFactor: 2, mobile: true
    });
    await send('Page.navigate', { url: PAGE });
    await waitFor('移动端装载', async () => !!(await evalJs(
        `document.querySelectorAll('.ic-col-idiom .ic-item').length === 6`)), 20000, 150);
    await sleep(350);
    const m = await evalJs(`(() => {
        const doc = document.documentElement;
        const bar = document.querySelector('.ic-col-char');
        const r = bar.getBoundingClientRect();
        const tip = document.querySelector('.ic-tip').getBoundingClientRect();
        const tiles = Array.from(document.querySelectorAll('.ic-tile'));
        const hit = tiles[0].getBoundingClientRect();
        const el = document.elementFromPoint(hit.left + hit.width / 2, hit.top + hit.height / 2);
        return {
            scrollW: doc.scrollWidth, innerW: window.innerWidth,
            barTop: Math.round(r.top), barBottom: Math.round(r.bottom), innerH: window.innerHeight,
            barPos: getComputedStyle(bar).position,
            barCols: getComputedStyle(bar).gridTemplateColumns.split(' ').length,
            tileHit: !!(el && el.closest('.ic-tile')),
            tipTop: Math.round(tip.top), tipBottom: Math.round(tip.bottom),
            cards: document.querySelectorAll('.ic-col-idiom .ic-item').length,
            slotH: Math.round(document.querySelector('.ic-slot').getBoundingClientRect().height)
        };
    })()`);
    check('375 宽无横向滚动', m.scrollW <= m.innerW + 1, `scrollW=${m.scrollW} innerW=${m.innerW}`);
    check('字块栏钉在视口底部（position:fixed 的答题盘，不是 sticky）',
        m.barPos === 'fixed' && m.barTop >= 0 && m.barBottom <= m.innerH + 1,
        `pos=${m.barPos} barTop=${m.barTop} barBottom=${m.barBottom} innerH=${m.innerH}`);
    check('提示条（释义）在答题盘上方可见，不会被它压住',
        m.tipTop > 0 && m.tipBottom <= m.barTop + 1,
        `tipTop=${m.tipTop} tipBottom=${m.tipBottom} barTop=${m.barTop}`);
    check('字块栏 6 列排布，且最上层元素就是字块本身（可点）',
        m.barCols === 6 && m.tileHit, `cols=${m.barCols} hit=${m.tileHit}`);
    check('6 张成语卡都在、字格高度够点（≥32px）', m.cards === 6 && m.slotH >= 32, `slotH=${m.slotH}`);
    await shot('05-mobile-375');

    // 移动端真的点一次：点字块 + 点缺口
    st = await state();
    const mb = blankOf(st, 0);
    check('移动端：点字块 + 点缺口可以正常填上',
        (await clickTile(mb.ch)) && (await clickSlot(0, mb.slot)));
    await sleep(180);
    st = await state();
    check('移动端填对后卡片变绿', st.items[0].done, `filled=${st.filled}`);
    await shot('06-mobile-filled');

    /* ==================== C15b 移动端完成页：列表滚动、按钮固定 ==================== */
    console.log('\n— C15b 移动端完成页 —');
    // 直接在数据层把本关填完（逐格点真实点击路径已由 C10 覆盖），把完成页逼出来
    await evalJs(`(() => {
        const d = Alpine.$data(document.querySelector('.ic-root'));
        d.items.forEach(it => {
            const k = it.chars.findIndex(c => c.blank);
            if (k >= 0) { it.chars[k].filled = true; it.done = true; }
        });
        d.checkFinished();
        return true;
    })()`);
    await sleep(320);
    // 375 宽下 6 条释义恰好装得进面板（clientH == scrollH），不制造压力就复现不出原来的 bug。
    // 这里把第 1 条释义拉长 4 倍，逼出「列表超出可视区」这个真实场景（只改内存，不动词库）。
    await evalJs(`(() => {
        const d = Alpine.$data(document.querySelector('.ic-root'));
        d.__origMean = d.items[0].meaning;
        d.items[0].meaning = d.__origMean.repeat(4);
        return true;
    })()`);
    await sleep(220);
    const dp = await evalJs(`(() => {
        const d = document.querySelector('.ic-done');
        const list = document.querySelector('.ic-done-list');
        const acts = document.querySelector('.ic-done-actions');
        if (!d || !list || !acts) return null;
        const dr = d.getBoundingClientRect(), ar = acts.getBoundingClientRect();
        const btns = Array.from(acts.querySelectorAll('.btn')).map(b => b.getBoundingClientRect());
        return {
            shown: d.getClientRects().length > 0,
            doneOverflow: getComputedStyle(d).overflowY,
            listOverflow: getComputedStyle(list).overflowY,
            listScrollable: list.scrollHeight > list.clientHeight + 1,
            listH: Math.round(list.clientHeight), listScrollH: Math.round(list.scrollHeight),
            actsInside: ar.top >= dr.top - 1 && ar.bottom <= dr.bottom + 1,
            actsGapBottom: Math.round(dr.bottom - ar.bottom),
            btnsInside: btns.every(b => b.top >= dr.top - 1 && b.bottom <= dr.bottom + 1),
            btnsTall: btns.every(b => b.height >= 28)
        };
    })()`);
    check('移动端完成页弹出来了', !!dp && dp.shown);
    check('完成页本身不再整体滚动（overflow:hidden）',
        !!dp && dp.doneOverflow === 'hidden', dp && dp.doneOverflow);
    check('列表是独立滚动容器（overflow-y:auto）',
        !!dp && ['auto', 'scroll'].includes(dp.listOverflow), dp && dp.listOverflow);
    check('小屏下列表确实超出可视区（这就是原来按钮被挤出去的原因）',
        !!dp && dp.listScrollable, dp && `clientH=${dp.listH} scrollH=${dp.listScrollH}`);
    check('★ 按钮区完整落在面板内、没被内容挤出去',
        !!dp && dp.actsInside && dp.btnsInside && dp.btnsTall,
        dp && `距底 ${dp.actsGapBottom}px`);
    await shot('07-mobile-level-done');
    // 还原释义（后续用例会重新加载页面，这里只是保持数据干净）
    await evalJs(`(() => {
        const d = Alpine.$data(document.querySelector('.ic-root'));
        if (d && d.__origMean) d.items[0].meaning = d.__origMean;
        return true;
    })()`);

    // 320 宽（老机型）再确认一次不横向溢出
    await send('Emulation.setDeviceMetricsOverride', {
        width: 320, height: 568, deviceScaleFactor: 2, mobile: true
    });
    await sleep(300);
    const narrow = await evalJs(`({
        scrollW: document.documentElement.scrollWidth, innerW: window.innerWidth,
        slotFont: getComputedStyle(document.querySelector('.ic-slot')).fontSize,
        tileFont: getComputedStyle(document.querySelector('.ic-tile')).fontSize
    })`);
    check('320 宽（iPhone SE 一代）也不横向溢出',
        narrow.scrollW <= narrow.innerW + 1, `scrollW=${narrow.scrollW} w=${narrow.innerW}`);
    check('320 档字号没被压到看不清（字格 ≥16px、字块 ≥17px）',
        parseFloat(narrow.slotFont) >= 16 && parseFloat(narrow.tileFont) >= 17,
        `slot=${narrow.slotFont} tile=${narrow.tileFont}`);
    await shot('07-mobile-320');

    /* ==================== C16 教学首页：六张卡 ==================== */
    console.log('\n— C16 教学首页六张卡 —');
    await send('Emulation.setDeviceMetricsOverride', {
        width: 1280, height: 900, deviceScaleFactor: 1, mobile: false
    });
    await send('Page.navigate', { url: BASE + '/learn' });
    await waitFor('教学首页', async () => (await evalJs(
        `document.querySelectorAll('.sc-learn-card').length`)) === 6, 15000, 150);
    const cards = await evalJs(`(() => {
        const cs = Array.from(document.querySelectorAll('.sc-learn-card'));
        const rect = e => { const r = e.getBoundingClientRect(); return { x: Math.round(r.x), y: Math.round(r.y), w: Math.round(r.width), h: Math.round(r.height) }; };
        return {
            n: cs.length,
            titles: cs.map(c => c.querySelector('.card-title').textContent.trim()),
            hrefs: cs.map(c => (c.querySelector('.sc-learn-actions a') || {}).getAttribute
                ? c.querySelector('.sc-learn-actions a').getAttribute('href') : ''),
            accents: cs.map(c => c.getAttribute('data-accent')),
            card: cs.map(rect),
            icon: cs.map(c => rect(c.querySelector('.sc-learn-icon'))),
            btn: cs.map(c => rect(c.querySelector('.sc-learn-actions .btn')))
        };
    })()`);
    check('教学首页六张模块卡',
        cards.n === 6 && cards.titles.join(',')
            === '乘法口诀,英语单词匹配,成语填空,近义词匹配,反义词匹配,俄罗斯方块',
        cards.titles.join(' / '));
    check('成语填空卡指向 /learn/idiom-fill，主题色 amber',
        cards.hrefs[2] === '/learn/idiom-fill' && cards.accents[2] === 'amber',
        `${cards.hrefs[2]} ${cards.accents[2]}`);
    check('两行三列：每行三张、行内等高、第二行在第一行下方',
        cards.card[0].y === cards.card[2].y
        && cards.card[3].y === cards.card[5].y
        && cards.card[3].y > cards.card[0].y
        && [0, 3].every(s => cards.card.slice(s, s + 3).every(c => c.h === cards.card[s].h)),
        cards.card.map(c => `y${c.y}h${c.h}`).join(' '));
    check('六卡图标色块同尺寸，行内按钮同高同底边',
        cards.icon.every(i => i.w === cards.icon[0].w && i.h === cards.icon[0].h)
        && [0, 3].every(s => cards.btn.slice(s, s + 3)
            .every(b => b.h === cards.btn[s].h && b.y === cards.btn[s].y)),
        `icon=${cards.icon[0].w}x${cards.icon[0].h} btn=${cards.btn[0].w}x${cards.btn[0].h}`);
    await shot('08-learn-index');

    /* ==================== C18 导出单页 HTML ==================== */
    console.log('\n— C18 导出单页 HTML —');
    await send('Page.navigate', { url: PAGE });
    await waitFor('导出前装载', async () => !!(await evalJs(
        `!!window.LearnTools && document.querySelectorAll('.ic-col-idiom .ic-item').length === 6`)),
        20000, 150);
    const exp = await evalJs(`(async () => {
        const d = Alpine.$data(document.querySelector('.ic-root'));
        /* 用页面自己那份导出参数（按钮走的就是它），不在这里另抄一套 */
        window.__exp = await LearnTools.exportPage(d.exportOptions());
        const h = window.__exp.html;
        return {
            name: window.__exp.filename,
            kb: Math.round(h.length / 1024),
            scriptSrc: (h.match(/<script[^>]+src=/gi) || []).length,
            cssLink: (h.match(/<link[^>]+rel=["']?stylesheet/gi) || []).length,
            standalone: h.indexOf('data-standalone') >= 0,
            flag: h.indexOf('__LEARN_STANDALONE__') >= 0,
            navGone: h.indexOf('id="site-nav"') < 0,
            meanings: (h.match(/"meaning":/g) || []).length,
            hasInlinedCss: h.indexOf('data-lt-inline') >= 0,
            fontInline: h.indexOf('data:font/woff2;base64,') >= 0,
            inlineScripts: (h.match(/<script(?![^>]*\bsrc=)/gi) || []).length
        };
    })()`);
    check('导出文件名带时间戳', /^idiom-fill-\d{12}\.html$/.test(exp.name), exp.name);
    check('产物不是空壳（≥ 300KB，含图标字体与词库）', exp.kb >= 300, exp.kb + ' KB');
    check('★ 零外链：没有 script[src]、没有外链样式', exp.scriptSrc === 0 && exp.cssLink === 0,
        `script=${exp.scriptSrc} link=${exp.cssLink}`);
    check('CSS 内联 + 图标字体内联成 data URI + 所有脚本都已内联',
        exp.hasInlinedCss && exp.fontInline && exp.inlineScripts >= 3,
        `css=${exp.hasInlinedCss} font=${exp.fontInline} inlineScripts=${exp.inlineScripts}`);
    check('★ 词库随文件带走：180 条释义都在产物里', exp.meanings === 180, `meanings=${exp.meanings}`);
    check('打了 data-standalone 标记、站内导航已摘掉', exp.standalone && exp.flag && exp.navGone,
        `standalone=${exp.standalone} flag=${exp.flag} navGone=${exp.navGone}`);

    // 真跑一遍：把产物塞进 iframe 渲染，看棋盘能不能出来、站内入口有没有被收起
    const live = await evalJs(`(async () => {
        const url = URL.createObjectURL(new Blob([window.__exp.html], { type: 'text/html' }));
        const f = document.createElement('iframe');
        f.style.cssText = 'position:fixed;left:-9999px;top:0;width:1100px;height:820px;border:0';
        f.src = url;
        document.body.appendChild(f);
        await new Promise(res => { f.onload = res; });
        let ok = false;
        for (let i = 0; i < 80; i++) {
            const d = f.contentDocument;
            if (d && d.querySelectorAll('.ic-col-idiom .ic-item').length === 6) { ok = true; break; }
            await new Promise(r2 => setTimeout(r2, 100));
        }
        const d = f.contentDocument;
        const dis = sel => {
            const el = d.querySelector(sel);
            return el ? getComputedStyle(el).display : 'missing';
        };
        const out = {
            ok: ok,
            cards: d.querySelectorAll('.ic-col-idiom .ic-item').length,
            tiles: d.querySelectorAll('.ic-col-char .ic-tile').length,
            stageTabs: d.querySelectorAll('.ic-stage').length,
            gear: !!d.querySelector('.lt-set-btn'),
            back: dis('.lt-back'),
            exportItem: dis('.lt-set-item.is-export'),
            sourceItem: dis('.lt-set-item.is-source'),
            standaloneAttr: d.body.getAttribute('data-standalone')
        };
        f.remove();
        URL.revokeObjectURL(url);
        return out;
    })()`);
    check('★ 导出件在浏览器里真的能开局：6 张卡 / 6 个字块 / 三个学段',
        live.ok && live.cards === 6 && live.tiles === 6 && live.stageTabs === 3,
        `cards=${live.cards} tiles=${live.tiles} tabs=${live.stageTabs}`);
    check('导出件里设置弹层还在', live.gear);
    check('★ 只在站内成立的入口被收起：返回 / 导出 / 源码',
        live.back === 'none' && live.exportItem === 'none' && live.sourceItem === 'none',
        `back=${live.back} export=${live.exportItem} source=${live.sourceItem}`);

    // 把导出件铺满视口截一张：这是用户拿到手真正会看到的样子（没有站内导航、没有返回键）
    await evalJs(`(async () => {
        const url = URL.createObjectURL(new Blob([window.__exp.html], { type: 'text/html' }));
        const f = document.createElement('iframe');
        f.id = 'lt-standalone-shot';
        f.style.cssText = 'position:fixed;left:0;top:0;width:1280px;height:940px;'
            + 'border:0;z-index:99999;background:#fff';
        f.src = url;
        document.body.appendChild(f);
        await new Promise(res => { f.onload = res; });
        for (let i = 0; i < 60; i++) {
            if (f.contentDocument.querySelectorAll('.ic-col-idiom .ic-item').length === 6) break;
            await new Promise(r2 => setTimeout(r2, 100));
        }
        return true;
    })()`);
    await sleep(400);
    await shot('11-standalone-offline');
    await evalJs(`(() => {
        const f = document.getElementById('lt-standalone-shot');
        if (f) f.remove();
        return true;
    })()`);

    /* ==================== C17 运行期异常 ==================== */
    console.log('\n— C17 运行期异常 —');
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
