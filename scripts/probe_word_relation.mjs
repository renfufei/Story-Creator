#!/usr/bin/env node
/**
 * /learn/synonym-match + /learn/antonym-match 定点验证：**近义词匹配 / 反义词匹配**。
 *
 * 需求（用户，2026-10-18）：
 *   教学板块新增【近义词匹配】【反义词匹配】两个模块，分小学 / 初中 / 高中；
 *   不要求收全词汇，但**同一关不能出现 3 个及以上同义词**，否则会干扰游戏性。
 *
 * 这个探针守护的是「玩法能不能成立 + 两条红线」，不是「页面能不能打开」：
 *   W0  页面装载：Alpine 就绪、本关 6 对、静音守门生效
 *   W1  三学段页签（小学 / 初中 / 高中）、默认小学第 1 关、每学段 6 关 36 对
 *   W2  本关结构：左 6 词 / 右 6 词；左词两两不同；右词恰好是 6 个答案；左右不重叠
 *   W3  ★ 唯一解：每个右词在左列只对应一个题面（两个题面共用一个答案 = 玩家无从判断）
 *   W4  ★ 右列顺序与左列不同（打乱生效，不能照位置对号入座）
 *   W5  答对：两边变绿、卡片就地显示答案与辨析、提示条给出辨析、已配 +1
 *   W6  答错：失误 +1、没有误判为完成、短暂标红后自动恢复
 *   W7  顺序无关（先右后左也能配）；再点一次取消选择
 *   W8  通关：完成页列出 6 对与辨析、localStorage 记下本关
 *   W9  【下一关】进入第 2 关、棋盘重置
 *   W10 切到【初中】：题库换成初中的主题、页签高亮跟着走
 *   W11 朗读：小喇叭念「词，答案」（zh-CN）；关掉朗读后不再调用
 *   W11b ★ 设置弹层：齿轮可开可收、6 个条目齐、GitHub 那条是 <a target=_blank>
 *   W11c ★ 错题本：配错一对自动入册（同一对只留一份）、可清空
 *   W12 刷新页面后进度仍在（localStorage 生效）
 *   W12b ★ 重置进度：确认后清空全部学段记录，错题本保留
 *   W13 移动端 375×667：无横向滚动、候选区 fixed 钉在底部、6 个词块都在、提示条在棋盘上方
 *   W14 ★ 移动端完成页：长列表在文本区内部滚动，按钮区始终完整可见
 *   W15 教学首页：6 张卡排成两行三列，两个新入口与主题色正确
 *   W16 ★ 反义词页：独立词库（大/小）、独立强调色、独立进度键，不串近义词的数据
 *   W18 ★ 导出：产物单文件、无外链、词库内联；在 iframe 里实跑一遍
 *        **仍然是反义词模式**（模式靠 pathname 判定，file:// 下会落回近义词，必须由产物自己写死）
 *   W17 运行期无 JS 异常 / console.error
 *
 * 用法：
 *   bash /tmp/settings-verify/run.sh scripts/probe_word_relation.mjs
 *   WR_BASE=http://localhost:1888 node scripts/probe_word_relation.mjs
 * 可选环境变量：WR_BASE（或 WM_BASE）、STORY_BROWSER_PATH、SHOT_DIR（默认 /tmp/wr-shots）
 */
import { spawn } from 'node:child_process';
import { SILENT_AUDIO_FLAGS, installSilentAudio } from './lib/silent-audio.mjs';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const BASE = process.env.WR_BASE || process.env.WM_BASE || 'http://localhost:1888';
const PAGE = BASE + '/learn/synonym-match';
const ANTONYM_PAGE = BASE + '/learn/antonym-match';
const HOME = BASE + '/learn';
const CHROME = process.env.STORY_BROWSER_PATH
    || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const PORT = 9343;
const SHOT_DIR = process.env.SHOT_DIR || '/tmp/wr-shots';

const results = [];
const check = (name, ok, extra = '') => {
    results.push({ name, ok: !!ok, extra });
    console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${extra ? '  [' + extra + ']' : ''}`);
};

const userDataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'wr-probe-'));
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

/** 组件状态快照 */
const state = () => evalJs(`(() => {
    const el = document.querySelector('.wr-root');
    const d = el && window.Alpine && Alpine.$data(el);
    if (!d) return null;
    return {
        mode: d.mode, label: d.label,
        stageId: d.stageId,
        theme: d.currentLevel ? d.currentLevel.theme : '',
        levelIndex: d.levelIndex,
        levelCount: d.levelCount,
        matched: d.matchedCount,
        mistakes: d.mistakes,
        finished: d.finished,
        remaining: d.remaining,
        tip: d.tipText,
        items: d.items.map(it => ({ word: it.word, match: it.match, note: it.note, done: it.done, wrong: it.wrong })),
        tiles: d.tiles.map(t => ({ word: t.word, used: t.used })),
        stages: d.stages.map(s => ({ id: s.id, label: s.label, levelCount: s.levelCount, pairCount: s.pairCount })),
        progress: JSON.parse(JSON.stringify(d.progress))
    };
})()`);

const clickItem = (i) => evalJs(`(() => {
    const it = document.querySelectorAll('.wr-col-word .wr-item')[${i}];
    if (!it) return false;
    it.click();
    return true;
})()`);

const clickTile = (word) => evalJs(`(() => {
    const t = Array.from(document.querySelectorAll('.wr-col-pick .wr-tile'))
        .find(el => el.textContent.trim() === ${JSON.stringify(word)} && !el.disabled);
    if (!t) return false;
    t.click();
    return true;
})()`);

const clickSpeak = (i) => evalJs(`(() => {
    const card = document.querySelectorAll('.wr-col-word .wr-item')[${i}];
    if (!card) return false;
    const b = card.querySelector('.wr-speak');
    if (!b) return false;
    b.click();
    return true;
})()`);

/* ---------- 设置弹层：三页结构一致，按类名点 ---------- */

const openSettings = async () => {
    const ok = await evalJs(`(() => {
        const b = document.querySelector('.wr-toolbar-actions .lt-set-btn');
        if (!b) return false;
        if (b.getAttribute('aria-expanded') !== 'true') b.click();
        return true;
    })()`);
    await sleep(180);
    return ok;
};

const closeSettings = async () => {
    await evalJs(`(() => {
        const b = document.querySelector('.wr-toolbar-actions .lt-set-btn');
        if (b && b.getAttribute('aria-expanded') === 'true') b.click();
        return true;
    })()`);
    await sleep(150);
};

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

    /* ==================== W0 前置：干净状态进页面 ==================== */
    console.log('\n— W0 前置 —');
    await send('Page.navigate', { url: PAGE });
    await sleep(700);
    await evalJs(`localStorage.clear()`);
    await send('Page.navigate', { url: PAGE });
    const loaded = await waitFor('组件初始化', async () => !!(await evalJs(
        `!!(window.Alpine && document.querySelector('.wr-root')
          && Alpine.$data(document.querySelector('.wr-root')).ready
          && document.querySelectorAll('.wr-col-word .wr-item').length)`)), 20000, 150);
    check('页面装载：Alpine 初始化完成且棋盘渲染出 6 个题面', loaded &&
        (await evalJs(`document.querySelectorAll('.wr-col-word .wr-item').length`)) === 6);
    check('静音守门已接管 speechSynthesis（探针不会真的念出来）', await evalJs(`(() => {
        const st = window.__smokeAudioOff;
        return !!(st && st.guard && window.speechSynthesis && window.speechSynthesis.speak
                  && window.speechSynthesis.speak.__smokeGuard);
    })()`));
    check('模式判定：/learn/synonym-match ⇒ 近义词',
        (await evalJs(`Alpine.$data(document.querySelector('.wr-root')).mode`)) === 'synonym');

    let st = await state();

    /* ==================== W1 三学段 ==================== */
    console.log('\n— W1 学段页签 —');
    const tabs = await evalJs(`Array.from(document.querySelectorAll('.wr-stage')).map(b => ({
        label: b.querySelector('.wr-stage-label').textContent.trim(),
        grade: b.querySelector('.wr-stage-grade').textContent.trim(),
        on: b.classList.contains('is-on')
    }))`);
    check('三个学段页签：小学 / 初中 / 高中',
        tabs.length === 3 && tabs.map(t => t.label).join(',') === '小学,初中,高中',
        tabs.map(t => t.label + '(' + t.grade + ')').join(' '));
    check('默认停在小学、第 1 关',
        st.stageId === 'primary' && st.levelIndex === 0 && tabs[0].on,
        `stage=${st.stageId} level=${st.levelIndex}`);
    check('每学段 30 关、180 对（10 主题 × 18 对）',
        st.stages.every(s => s.levelCount === 30 && s.pairCount === 180),
        st.stages.map(s => s.label + ':' + s.levelCount + '关/' + s.pairCount + '对').join(' '));

    /* ==================== W2 本关结构 ==================== */
    console.log('\n— W2 本关结构 —');
    check('本关 6 对', st.items.length === 6, `items=${st.items.length} theme=${st.theme}`);
    check('左 6 个词、右 6 个候选（一一对应）',
        st.items.length === 6 && st.tiles.length === 6, `tiles=${st.tiles.length}`);
    const leftWords = st.items.map(it => it.word);
    check('左列题面两两不同', new Set(leftWords).size === leftWords.length, leftWords.join(' '));
    const tileWords = st.tiles.map(t => t.word);
    check('右列候选两两不同', new Set(tileWords).size === tileWords.length, tileWords.join(' '));
    check('右列恰好是 6 个答案（不多不少、没混进题面）',
        tileWords.slice().sort().join('|') === st.items.map(it => it.match).sort().join('|'),
        `tiles=${tileWords.join(',')}`);
    check('题面与候选不重叠（否则会出现「自己配自己」）',
        leftWords.every(w => tileWords.indexOf(w) < 0));
    check('每对都有辨析（配对后要就地讲清楚差别）',
        st.items.every(it => it.note && it.note.length >= 6),
        st.items.map(it => it.note.length).join(','));

    /* ==================== W3/W4 唯一解 + 打乱 ==================== */
    console.log('\n— W3/W4 唯一解与打乱 —');
    const matchCount = {};
    st.items.forEach(it => { matchCount[it.match] = (matchCount[it.match] || 0) + 1; });
    check('★ 每个候选只对应一个题面（两个题面共用一个答案 ⇒ 玩家无从判断）',
        Object.values(matchCount).every(n => n === 1),
        JSON.stringify(matchCount));
    check('★ 右列顺序与左列不同（打乱生效，不能照位置对号入座）',
        tileWords.join('|') !== st.items.map(it => it.match).join('|'),
        tileWords.join(','));

    // 逐关扫一遍：每关都必须满足「候选唯一 + 左右不重叠」，防止某些主题的数据出问题
    const scan = await evalJs(`(() => {
        const d = Alpine.$data(document.querySelector('.wr-root'));
        const bad = [];
        for (const s of d.stages) {
            for (let i = 0; i < s.levelCount; i++) {
                d.stageId = s.id;
                d.loadLevel(i);
                const tiles = d.tiles.map(t => t.word);
                const words = d.items.map(it => it.word);
                const cnt = {};
                d.items.forEach(it => { cnt[it.match] = (cnt[it.match] || 0) + 1; });
                if (new Set(tiles).size !== tiles.length) bad.push(s.id + '#' + i + ' 候选重复');
                if (Object.values(cnt).some(n => n !== 1)) bad.push(s.id + '#' + i + ' 候选不唯一');
                if (words.some(w => tiles.indexOf(w) >= 0)) bad.push(s.id + '#' + i + ' 左右重叠');
                if (d.items.length !== 6) bad.push(s.id + '#' + i + ' 对数 ' + d.items.length);
            }
        }
        d.stageId = 'primary';
        d.loadLevel(0);
        return bad;
    })()`);
    check('★ 全库 90 关逐关复查：候选唯一、不重复、左右不重叠、每关 6 对',
        Array.isArray(scan) && scan.length === 0, (scan || []).slice(0, 4).join('; '));

    /* ==================== W5 答对 ==================== */
    console.log('\n— W5 答对 —');
    st = await state();
    const pair0 = st.items[0];
    check('点左列第 1 个词', await clickItem(0));
    check('点中后该卡出现选中态', await evalJs(
        `document.querySelectorAll('.wr-col-word .wr-item')[0].classList.contains('is-sel')`));
    check('点右列对应候选', await clickTile(pair0.match));
    await sleep(220);
    check('卡片变绿并就地显示答案',
        await evalJs(`(() => {
            const c = document.querySelectorAll('.wr-col-word .wr-item')[0];
            return c.classList.contains('is-done')
                && c.querySelector('.wr-answer').textContent.trim() === ${JSON.stringify(pair0.match)};
        })()`), pair0.word + ' → ' + pair0.match);
    check('候选变「已配对」', await evalJs(`(() => {
        const t = Array.from(document.querySelectorAll('.wr-col-pick .wr-tile'))
            .find(el => el.textContent.trim() === ${JSON.stringify(pair0.match)});
        return !!t && t.classList.contains('is-used') && t.disabled;
    })()`));
    check('已配对数 +1', (await state()).matched === 1);
    check('提示条给出这条的辨析', (await state()).tip.indexOf(pair0.note.slice(0, 8)) >= 0,
        (await state()).tip.slice(0, 26));
    check('选中态已清空（不会误配下一次）',
        await evalJs(`document.querySelectorAll('.wr-item.is-sel, .wr-tile.is-sel').length === 0`));
    await shot('01-desktop-matched');

    /* ==================== W6 答错 ==================== */
    console.log('\n— W6 答错 —');
    st = await state();
    const pair1 = st.items[1];
    const wrongWord = st.tiles.find(t => t.word !== pair1.match && !t.used).word;
    check('点左列第 2 个词，再点一个错的候选', (await clickItem(1)) && (await clickTile(wrongWord)));
    await sleep(150);
    let s2 = await state();
    check('失误 +1、该条仍未完成', s2.mistakes === 1 && !s2.items[1].done);
    check('答错时两边短暂标红',
        await evalJs(`document.querySelectorAll('.wr-item.is-wrong, .wr-tile.is-wrong').length > 0`));
    await sleep(420);
    check('标红自动恢复（不会一直卡在错误态）',
        await evalJs(`document.querySelectorAll('.wr-item.is-wrong, .wr-tile.is-wrong').length === 0`));
    check('答错后提示条说明「不是它的近义词」', (await state()).tip.indexOf('不是') >= 0,
        (await state()).tip.slice(0, 30));
    check('没有误判为完成', !(await state()).finished);

    /* ==================== W7 顺序无关 + 取消选择 ==================== */
    console.log('\n— W7 顺序无关 / 取消选择 —');
    st = await state();
    const pair2 = st.items[2];
    check('先点右列候选', await clickTile(pair2.match));
    check('再点左列对应的词 → 直接配上', (await clickItem(2)) && await (async () => {
        await sleep(150);
        const s = await state();
        return s.items[2].done && s.matched === 2;
    })());
    check('再点一次已选中的候选 = 取消选择', await (async () => {
        const s0 = await state();
        const free = s0.tiles.find(t => !t.used);
        await clickTile(free.word);
        const on = await evalJs(`document.querySelectorAll('.wr-tile.is-sel').length === 1`);
        await clickTile(free.word);
        const off = await evalJs(`document.querySelectorAll('.wr-tile.is-sel').length === 0`);
        return on && off;
    })());
    check('再点一次已选中的词 = 取消选择', await (async () => {
        const s0 = await state();
        const idx = s0.items.findIndex(it => !it.done);
        await clickItem(idx);
        const on = await evalJs(`document.querySelectorAll('.wr-item.is-sel').length === 1`);
        await clickItem(idx);
        const off = await evalJs(`document.querySelectorAll('.wr-item.is-sel').length === 0`);
        return on && off;
    })());

    /* ==================== W8 通关 ==================== */
    console.log('\n— W8 通关 —');
    const done = await evalJs(`(() => {
        const d = Alpine.$data(document.querySelector('.wr-root'));
        for (const it of d.items) {
            if (it.done) continue;
            d.pickItem(it);
            const tile = d.tiles.find(t => !t.used && t.word === it.match);
            d.pickTile(tile);
        }
        return true;
    })()`);
    check('把剩下的全部配对', done);
    await waitFor('完成页出现', async () => (await state()).finished, 6000, 100);
    await sleep(320);
    s2 = await state();
    check('完成页出现，6 对全部完成', s2.finished && s2.matched === 6);
    const donePanel = await evalJs(`(() => {
        const p = document.querySelector('.wr-done');
        return {
            visible: !!p && p.offsetParent !== null,
            rows: p ? p.querySelectorAll('.wr-done-list li').length : 0,
            notes: p ? Array.from(p.querySelectorAll('.wr-done-note')).filter(n => n.textContent.trim().length > 3).length : 0,
            title: p ? p.querySelector('.wr-done-title').textContent.trim() : ''
        };
    })()`);
    check('完成页列出 6 对，每对都带辨析',
        donePanel.visible && donePanel.rows === 6 && donePanel.notes === 6,
        `rows=${donePanel.rows} notes=${donePanel.notes}`);
    check('完成页给出用时与失误', await evalJs(
        `/用时/.test(document.querySelector('.wr-done-stat').textContent)`));
    check('localStorage 记下本关（word_relation_v1_synonym）', await evalJs(
        `(() => { const o = JSON.parse(localStorage.getItem('word_relation_v1_synonym') || '{}');
                  return !!(o.primary && o.primary.done && o.primary.done.indexOf(0) >= 0); })()`));
    check('进度段变绿', await evalJs(
        `document.querySelectorAll('.wr-seg')[0].classList.contains('is-done')`));
    await shot('02-desktop-level-done');

    /* ==================== W9 下一关 ==================== */
    console.log('\n— W9 下一关 —');
    check('点【下一关】', await evalJs(`(() => {
        const b = Array.from(document.querySelectorAll('.wr-done-actions .btn'))
            .find(el => /下一关/.test(el.textContent));
        if (!b) return false; b.click(); return true;
    })()`));
    await sleep(350);
    s2 = await state();
    check('进入第 2 关、棋盘重置（0 已配、无完成页）',
        s2.levelIndex === 1 && s2.matched === 0 && !s2.finished,
        `level=${s2.levelIndex} theme=${s2.theme}`);
    check('第 2 关仍是同一主题（每主题 18 对切成三关）', s2.theme === st.theme,
        `${st.theme} → ${s2.theme}`);
    check('第 2 关题面与第 1 关不同（换到后半 6 对）',
        s2.items.map(i => i.word).join() !== leftWords.join(),
        s2.items.map(i => i.word).slice(0, 3).join('/'));

    /* ==================== W10 切学段 ==================== */
    console.log('\n— W10 切学段 —');
    check('点【初中】', await evalJs(`(() => {
        const b = Array.from(document.querySelectorAll('.wr-stage'))
            .find(el => el.textContent.indexOf('初中') >= 0);
        if (!b) return false; b.click(); return true;
    })()`));
    await sleep(400);
    s2 = await state();
    check('题库换成初中：stage=junior、6 对、主题是初中主题',
        s2.stageId === 'junior' && s2.items.length === 6, s2.theme);
    check('页签高亮跟着走', await evalJs(
        `Array.from(document.querySelectorAll('.wr-stage')).filter(b => b.classList.contains('is-on')).length === 1
         && document.querySelectorAll('.wr-stage')[1].classList.contains('is-on')`));
    check('初中的词与小学不重复（不是同一批题）',
        s2.items.every(it => leftWords.indexOf(it.word) < 0));
    await shot('03-desktop-junior');

    /* ==================== W11 朗读 ==================== */
    console.log('\n— W11 朗读 —');
    await evalJs(`(() => {
        window.__spoke = [];
        const inner = window.speechSynthesis.speak;
        window.speechSynthesis.speak = function (u) {
            window.__spoke.push({ text: String(u && u.text || ''), lang: String(u && u.lang || '') });
            return inner.call(this, u);
        };
        return true;
    })()`);
    const word0 = (await state()).items[0].word;
    check('点第 1 条的小喇叭', await clickSpeak(0));
    await sleep(200);
    let spoke = await evalJs(`window.__spoke`);
    check('念的是这条词（zh-CN）', spoke.length >= 1 && spoke[0].text === word0 && spoke[0].lang === 'zh-CN',
        spoke.map(s => s.text + '/' + s.lang).join(' '));
    check('设置弹层里能关掉朗读', await openSettings() && await clickSetItem(/音效与朗读/));
    await closeSettings();
    await evalJs(`window.__spoke = []`);
    await clickSpeak(0);
    await sleep(200);
    check('关掉后不再调用朗读', (await evalJs(`window.__spoke.length`)) === 0);
    check('朗读开关的偏好被记住', (await evalJs(`localStorage.getItem('word_relation_sound_synonym')`)) === '0');
    check('再从设置里打开朗读', await openSettings() && await clickSetItem(/音效与朗读/));
    await closeSettings();

    /* ==================== W11b 设置弹层 ==================== */
    console.log('\n— W11b 设置弹层 —');
    await openSettings();
    const panel = await evalJs(`(() => {
        const pop = document.querySelector('.lt-set-pop');
        if (!pop || !pop.getClientRects().length) return null;
        const items = Array.from(pop.querySelectorAll('.lt-set-item'));
        const r = pop.getBoundingClientRect();
        const src = pop.querySelector('.lt-set-item.is-source');
        return {
            titles: items.map(x => x.querySelector('.lt-set-title').textContent.trim().split('\\n')[0].trim()),
            srcTag: src ? src.tagName : '',
            srcHref: src ? src.getAttribute('href') : '',
            srcTarget: src ? src.getAttribute('target') : '',
            right: Math.round(r.right), innerW: window.innerWidth,
            accentBorder: getComputedStyle(document.querySelector('.lt-set-btn')).borderColor
        };
    })()`);
    check('齿轮能打开设置弹层', !!panel);
    check('六个条目齐全（与成语填空同一套）',
        !!panel && panel.titles.join(',') === '错题本,重做本关,音效与朗读,导出,源码,重置进度',
        panel && panel.titles.join(' / '));
    check('★ 源码是 <a target=_blank> 指向 GitHub 项目仓库',
        !!panel && panel.srcTag === 'A' && panel.srcTarget === '_blank'
        && /^https:\/\/github\.com\/renfufei\/Story-Creator\/?$/.test(panel.srcHref),
        panel && `${panel.srcTag} ${panel.srcHref}`);
    check('弹层不超出视口右侧', !!panel && panel.right <= panel.innerW,
        panel && `right=${panel.right} innerW=${panel.innerW}`);
    await shot('08-settings-panel');
    await closeSettings();
    check('再点齿轮能收起弹层', await evalJs(
        `!document.querySelector('.lt-set-pop').getClientRects().length`));

    /* ==================== W11c 错题本 ==================== */
    console.log('\n— W11c 错题本 —');
    s2 = await state();
    // W6 已经制造过一次配错，错题本里本就有记录 —— 取基线再比增量
    const wbBefore = await evalJs(`Alpine.$data(document.querySelector('.wr-root')).wrongCount`);
    const it1 = s2.items[1];
    const badTile = s2.tiles.find(t => !t.used && t.word !== it1.match);
    await clickItem(1);
    await clickTile(badTile.word);
    await sleep(220);
    const wb = await evalJs(`(() => {
        const d = Alpine.$data(document.querySelector('.wr-root'));
        return { count: d.wrongCount, items: d.wrongItems.map(w => ({ label: w.label, sub: w.sub, k: w.k })) };
    })()`);
    check('配错一对后错题本里记下了它（新增 1 条，最新在最前）',
        wb.count === wbBefore + 1 && wb.items[0].label === it1.word + ' → ' + it1.match,
        `${wbBefore} → ${wb.count}｜${JSON.stringify(wb.items[0] || {})}`);
    check('错题条目带辨析（能当复习卡看）', !!(wb.items[0] && wb.items[0].sub), wb.items[0] && wb.items[0].sub);
    check('错题键带模式前缀（近义词的错题不会跑到反义词页）',
        wb.items[0].k.indexOf('synonym|') === 0, wb.items[0].k);
    const bad2 = (await state()).tiles.find(t => !t.used && t.word !== it1.match);
    await clickItem(1);
    await clickTile(bad2.word);
    await sleep(220);
    check('同一对词只记一份', (await evalJs(
        `Alpine.$data(document.querySelector('.wr-root')).wrongCount`)) === wbBefore + 1);
    await openSettings();
    check('设置按钮上的计数徽标同步显示条数', (await evalJs(`(() => {
        const c = document.querySelector('.lt-set-btn .lt-count');
        return c && c.getClientRects().length ? c.textContent.trim() : '';
    })()`)) === String(wbBefore + 1));
    await clickSetItem(/错题本/);
    await sleep(250);
    const book = await evalJs(`(() => {
        const el = Array.from(document.querySelectorAll('.wr-modal-backdrop'))
            .find(x => /错题本/.test(x.textContent) && x.getClientRects().length);
        if (!el) return null;
        return {
            rows: el.querySelectorAll('.lt-wrong-item').length,
            label: el.querySelector('.lt-wrong-label').textContent.trim(),
            scroll: getComputedStyle(el.querySelector('.lt-wrong-list')).overflowY
        };
    })()`);
    check('错题本弹窗列出该对词（最新一条在最前）', !!book && book.rows === wbBefore + 1
        && book.label === it1.word + ' → ' + it1.match, book && `${book.rows} 行 ${book.label}`);
    check('列表独立滚动（条目多了不会把按钮顶出去）', !!book && book.scroll === 'auto', book && book.scroll);
    await shot('09-wrong-book');
    await evalJs(`(() => {
        const b = Array.from(document.querySelectorAll('.wr-modal-backdrop button'))
            .find(x => /清空错题本/.test(x.textContent) && x.getClientRects().length);
        if (b) b.click();
        return true;
    })()`);
    await sleep(200);
    check('清空错题本后归零', (await evalJs(
        `Alpine.$data(document.querySelector('.wr-root')).wrongCount`)) === 0);
    await evalJs(`Alpine.$data(document.querySelector('.wr-root')).closeWrongBook()`);
    await sleep(150);

    /* ==================== W12 刷新保留 ==================== */
    console.log('\n— W12 刷新保留 —');
    await send('Page.reload');
    await sleep(900);
    await waitFor('刷新后初始化', async () => !!(await evalJs(
        `!!(window.Alpine && Alpine.$data(document.querySelector('.wr-root')).ready
          && document.querySelectorAll('.wr-col-word .wr-item').length)`)), 20000, 150);
    s2 = await state();
    check('刷新后仍停在上次打开的学段（初中）', s2.stageId === 'junior', s2.stageId);
    check('小学第 1 关的通过记录还在', s2.progress && s2.progress.primary
        && s2.progress.primary.done.indexOf(0) >= 0,
        JSON.stringify(s2.progress.primary || {}));
    check('小学页签显示 1/30（按学段关数动态渲染）', (await evalJs(`document.querySelectorAll('.wr-stage-num')[0].textContent.trim()`)) === '1/30');

    /* ==================== W12b 重置进度（放在进度校验之后） ==================== */
    console.log('\n— W12b 重置进度 —');
    await evalJs(`(() => {
        const d = Alpine.$data(document.querySelector('.wr-root'));
        d.wrongStore.add('synonym|测试>校验', '测试 → 校验', '用来验证重置进度不牵连错题本');
        d.wrongItems = d.wrongStore.all();
        return d.wrongCount;
    })()`);
    check('重置前错题本里有 1 条', (await evalJs(
        `Alpine.$data(document.querySelector('.wr-root')).wrongCount`)) === 1);
    await openSettings();
    check('点【重置进度】', await clickSetItem(/重置进度/));
    await sleep(300);
    const dlg = await evalJs(`(() => {
        const el = document.querySelector('.sc-modal-backdrop');
        return el && el.getClientRects().length
            ? { text: el.textContent.replace(/\\s+/g, ' ').trim().slice(0, 80) } : null;
    })()`);
    check('弹出确认对话框（不是点一下就抹掉）', !!dlg, dlg && dlg.text);
    const antBefore = await evalJs(`localStorage.getItem('word_relation_v1_antonym')`);
    await evalJs(`(() => {
        const b = Array.from(document.querySelectorAll('.sc-modal-backdrop button'))
            .find(x => /重置/.test(x.textContent) && !/取消/.test(x.textContent));
        if (b) b.click();
        return true;
    })()`);
    await sleep(400);
    const after = await evalJs(`(() => {
        const raw = localStorage.getItem('word_relation_v1_synonym');
        const o = raw ? JSON.parse(raw) : {};
        // loadLevel(0) 会立刻写下一条 {done:[], current:0}，别断言「键没了」；
        // 不变量是「所有学段的 done 都空了」
        let doneCount = 0;
        Object.keys(o).forEach(k => { doneCount += ((o[k] && o[k].done) || []).length; });
        const other = localStorage.getItem('word_relation_v1_antonym');
        const d = Alpine.$data(document.querySelector('.wr-root'));
        return { doneCount, other, stageDone: d.stageDone(d.stageId), wrong: d.wrongCount, level: d.levelIndex };
    })()`);
    check('确认后清空本模式的进度记录并回到第 1 关',
        after.doneCount === 0 && after.stageDone === 0 && after.level === 0,
        `done=${after.doneCount} stageDone=${after.stageDone} level=${after.level}`);
    check('★ 只清掉本模式的键，反义词那份原样不动', after.other === antBefore,
        `antonym ${antBefore === null ? '原本没有' : '原样保留'}`);
    check('错题本不被「重置进度」牵连', after.wrong === 1, `wrong=${after.wrong}`);

    /* ==================== W13 移动端 ==================== */
    console.log('\n— W13 移动端 375 —');
    await send('Emulation.setDeviceMetricsOverride', {
        width: 375, height: 667, deviceScaleFactor: 2, mobile: true
    });
    await send('Page.navigate', { url: PAGE });
    await sleep(900);
    await waitFor('移动端初始化', async () => !!(await evalJs(
        `!!(window.Alpine && Alpine.$data(document.querySelector('.wr-root')).ready
          && document.querySelectorAll('.wr-col-word .wr-item').length)`)), 20000, 150);
    const mob = await evalJs(`(() => {
        const bar = document.querySelector('.wr-col-pick');
        const cs = getComputedStyle(bar);
        const r = bar.getBoundingClientRect();
        const tip = document.querySelector('.wr-tip').getBoundingClientRect();
        const board = document.querySelector('.wr-board').getBoundingClientRect();
        return {
            scrollW: document.documentElement.scrollWidth,
            pos: cs.position,
            top: Math.round(r.top), bottom: Math.round(r.bottom),
            vh: window.innerHeight,
            tiles: document.querySelectorAll('.wr-col-pick .wr-tile').length,
            tilesInView: Array.from(document.querySelectorAll('.wr-col-pick .wr-tile'))
                .filter(t => { const q = t.getBoundingClientRect(); return q.top >= 0 && q.bottom <= window.innerHeight + 1; }).length,
            tipAboveBoard: tip.bottom <= board.top + 1,
            padBottom: Math.round(parseFloat(getComputedStyle(document.querySelector('.wr-play')).paddingBottom))
        };
    })()`);
    check('375 宽无横向滚动', mob.scrollW === 375, `scrollW=${mob.scrollW}`);
    check('候选区 fixed 钉在视口底部', mob.pos === 'fixed' && Math.abs(mob.bottom - mob.vh) <= 1,
        `pos=${mob.pos} bottom=${mob.bottom} vh=${mob.vh}`);
    check('6 个候选词都在视口内（不用滚动就能点）', mob.tiles === 6 && mob.tilesInView === 6,
        `${mob.tilesInView}/${mob.tiles}`);
    check('提示条在棋盘上方（不被固定题板压住）', mob.tipAboveBoard);
    check('容器留出底部空间，最后一条题滚得出来', mob.padBottom >= 128, `padding-bottom=${mob.padBottom}px`);
    check('6 个题面都在', (await evalJs(`document.querySelectorAll('.wr-col-word .wr-item').length`)) === 6);
    await shot('04-mobile-375');

    /* ==================== W14 移动端完成页 ==================== */
    console.log('\n— W14 移动端完成页 —');
    await evalJs(`(() => {
        const d = Alpine.$data(document.querySelector('.wr-root'));
        for (const it of d.items) {
            if (it.done) continue;
            d.pickItem(it);
            d.pickTile(d.tiles.find(t => !t.used && t.word === it.match));
        }
        return true;
    })()`);
    await waitFor('移动端完成页出现', async () => (await state()).finished, 6000, 100);
    await sleep(320);
    // 造出「列表溢出」的压力：375 宽下 6 条辨析本来刚好装得下，不拉长就测不到按钮被挤走。
    // ⚠️ 必须等 Alpine 把 .wr-done 渲染出来之后再改，同一个 eval 里改会扑空。
    await evalJs(`(() => {
        const li = document.querySelector('.wr-done-list li .wr-done-note');
        if (!li) return false;
        li.textContent = '这一条辨析被刻意拉长，用来验证长列表在窄屏上只在文本区内滚动。'.repeat(4);
        return true;
    })()`);
    await sleep(120);
    const dp = await evalJs(`(() => {
        const p = document.querySelector('.wr-done');
        const list = p.querySelector('.wr-done-list');
        const acts = p.querySelector('.wr-done-actions');
        const pr = p.getBoundingClientRect(), ar = acts.getBoundingClientRect();
        return {
            overflow: getComputedStyle(p).overflow,
            listOverflowY: getComputedStyle(list).overflowY,
            listH: Math.round(list.clientHeight),
            listScrollH: Math.round(list.scrollHeight),
            actsInside: ar.bottom <= pr.bottom + 1 && ar.top >= pr.top - 1,
            actsGapBottom: Math.round(pr.bottom - ar.bottom),
            btn: !!p.querySelector('.wr-done-actions .btn'),
            barHidden: getComputedStyle(document.querySelector('.wr-col-pick')).display === 'none'
        };
    })()`);
    check('完成页整体不滚（overflow:hidden）', dp.overflow === 'hidden', dp.overflow);
    check('文本区是独立滚动容器', dp.listOverflowY === 'auto', dp.listOverflowY);
    check('小屏下列表确实溢出（这就是原来按钮被挤出去的原因）',
        dp.listScrollH > dp.listH, `clientH=${dp.listH} scrollH=${dp.listScrollH}`);
    check('★ 按钮区完整落在面板内、没被内容挤出去', dp.actsInside && dp.btn,
        `距底 ${dp.actsGapBottom}px`);
    check('通关时收起固定题板（不浮在完成页上面）', dp.barHidden);
    await shot('05-mobile-level-done');

    /* ==================== W14b 320 窄屏 ==================== */
    console.log('\n— W14b 320 窄屏 —');
    await send('Emulation.setDeviceMetricsOverride', {
        width: 320, height: 568, deviceScaleFactor: 2, mobile: true
    });
    await send('Page.navigate', { url: PAGE });
    await sleep(900);
    await waitFor('320 初始化', async () => !!(await evalJs(
        `!!(window.Alpine && Alpine.$data(document.querySelector('.wr-root')).ready
          && document.querySelectorAll('.wr-col-word .wr-item').length)`)), 20000, 150);
    const narrow = await evalJs(`(() => ({
        scrollW: document.documentElement.scrollWidth,
        tiles: document.querySelectorAll('.wr-col-pick .wr-tile').length,
        tilesInView: Array.from(document.querySelectorAll('.wr-col-pick .wr-tile'))
            .filter(t => { const q = t.getBoundingClientRect(); return q.top >= 0 && q.bottom <= window.innerHeight + 1; }).length,
        wordClipped: Array.from(document.querySelectorAll('.wr-word'))
            .filter(e => e.scrollWidth > e.clientWidth + 1).length
    }))()`);
    check('320 宽无横向滚动', narrow.scrollW === 320, `scrollW=${narrow.scrollW}`);
    check('320 宽 6 个候选词都在视口内', narrow.tiles === 6 && narrow.tilesInView === 6,
        `${narrow.tilesInView}/${narrow.tiles}`);
    check('320 宽题面文字不被截断', narrow.wordClipped === 0, `clipped=${narrow.wordClipped}`);
    await shot('05b-mobile-320');

    /* ==================== W15 教学首页 ==================== */
    console.log('\n— W15 教学首页 —');
    await send('Emulation.setDeviceMetricsOverride', {
        width: 1280, height: 940, deviceScaleFactor: 1, mobile: false
    });
    await send('Page.navigate', { url: HOME });
    await sleep(800);
    const home = await evalJs(`(() => {
        const cards = Array.from(document.querySelectorAll('main .card'));
        const tops = cards.map(c => Math.round(c.getBoundingClientRect().top));
        const rows = new Set(tops).size;
        const perRow = {};
        tops.forEach(t => { perRow[t] = (perRow[t] || 0) + 1; });
        return {
            count: cards.length,
            rows: rows,
            sizes: Object.values(perRow).join(','),
            accents: cards.map(c => c.getAttribute('data-accent')).join(','),
            links: cards.map(c => c.getAttribute('onclick') || '').join(' '),
            titles: cards.map(c => c.querySelector('.card-title').textContent.trim()).join(',')
        };
    })()`);
    check('教学首页 6 张模块卡', home.count === 6, home.titles);
    check('6 张卡排成两行、每行 3 张', home.rows === 2 && home.sizes === '3,3', `${home.rows} 行 / ${home.sizes}`);
    check('包含近义词 / 反义词两个入口',
        home.links.indexOf('/learn/synonym-match') >= 0 && home.links.indexOf('/learn/antonym-match') >= 0);
    check('两张新卡各有自己的主题色', home.accents.indexOf('teal') >= 0 && home.accents.indexOf('rose') >= 0,
        home.accents);
    await shot('06-learn-index');

    /* ==================== W16 反义词页 ==================== */
    console.log('\n— W16 反义词页 —');
    await send('Page.navigate', { url: ANTONYM_PAGE });
    await sleep(900);
    await waitFor('反义词页初始化', async () => !!(await evalJs(
        `!!(window.Alpine && Alpine.$data(document.querySelector('.wr-root')).ready
          && document.querySelectorAll('.wr-col-word .wr-item').length)`)), 20000, 150);
    const ant = await state();
    check('路径判定为反义词模式', ant.mode === 'antonym' && ant.label === '反义词');
    check('页面标题是「反义词匹配」', (await evalJs(`document.title`)).indexOf('反义词匹配') === 0,
        await evalJs(`document.title`));
    check('词库是另一份（大 ↔ 小，不是近义词那批）',
        ant.items.some(it => it.word === '大' && it.match === '小'),
        ant.items.map(it => it.word + '↔' + it.match).join(' '));
    check('反义词的辨析以「反义」开头（两套词库不会混）',
        ant.items.every(it => it.note.indexOf('反义') === 0));
    check('强调色换成玫红 #e11d48', (await evalJs(
        `getComputedStyle(document.querySelector('.wr-root')).getPropertyValue('--wr-accent').trim()`)) === '#e11d48');
    // 两个模式各用一份 localStorage：反义词页不能看到近义词的「已通过」记录。
    // 注意别断言「progress 为空」—— loadLevel 一进来就会写下 { stage: {done:[], current:0} }，
    // 空对象永远不成立；要看的是「done 里没有对方通关的关」。
    const iso = await evalJs(`(() => {
        const syn = JSON.parse(localStorage.getItem('word_relation_v1_synonym') || 'null');
        const ant = JSON.parse(localStorage.getItem('word_relation_v1_antonym') || 'null');
        return {
            synDone: !!(syn && syn.primary && syn.primary.done && syn.primary.done.indexOf(0) >= 0),
            antDone: !!(ant && ant.primary && ant.primary.done && ant.primary.done.indexOf(0) >= 0),
            antHasJunior: !!(ant && ant.junior)
        };
    })()`);
    check('★ 进度键独立：反义词页不共享近义词的通过记录',
        iso.synDone && !iso.antDone && !iso.antHasJunior, JSON.stringify(iso));
    check('反义词页也停在第 1 关、6 对', ant.levelIndex === 0 && ant.items.length === 6);
    await shot('07-desktop-antonym');

    /* ==================== W18 导出单页 HTML（就在反义词页上做） ==================== */
    console.log('\n— W18 导出单页 HTML —');
    const exp = await evalJs(`(async () => {
        const d = Alpine.$data(document.querySelector('.wr-root'));
        /* 用页面自己那份导出参数（导出按钮走的就是它） */
        window.__exp = await LearnTools.exportPage(d.exportOptions());
        const h = window.__exp.html;
        return {
            name: window.__exp.filename,
            kb: Math.round(h.length / 1024),
            scriptSrc: (h.match(/<script[^>]+src=/gi) || []).length,
            cssLink: (h.match(/<link[^>]+rel=["']?stylesheet/gi) || []).length,
            navGone: h.indexOf('id="site-nav"') < 0,
            pairs: (h.match(/"match":/g) || []).length,
            fontInline: h.indexOf('data:font/woff2;base64,') >= 0,
            modePrelude: /var WR_MODE = "antonym"/.test(h),
            titleOk: /<title>反义词匹配/.test(h)
        };
    })()`);
    check('反义词页导出的文件名带时间戳', /^antonym-match-\d{12}\.html$/.test(exp.name), exp.name);
    check('产物不是空壳（≥ 300KB）', exp.kb >= 300, exp.kb + ' KB');
    check('★ 零外链（没有 script[src] / 外链样式），导航壳已摘掉',
        exp.scriptSrc === 0 && exp.cssLink === 0 && exp.navGone,
        `script=${exp.scriptSrc} link=${exp.cssLink} navGone=${exp.navGone}`);
    check('★ 整份反义词库随文件带走（540 对）', exp.pairs === 540, `pairs=${exp.pairs}`);
    check('图标字体内联 + 标题写死为「反义词匹配」', exp.fontInline && exp.titleOk,
        `font=${exp.fontInline} title=${exp.titleOk}`);
    check('★ 产物自己写死了 WR_MODE="antonym"（file:// 下靠 pathname 判断会落回近义词）',
        exp.modePrelude);

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
            if (d && d.querySelectorAll('.wr-col-word .wr-item').length === 6) { ok = true; break; }
            await new Promise(r2 => setTimeout(r2, 100));
        }
        const d = f.contentDocument;
        const dis = sel => {
            const el = d.querySelector(sel);
            return el ? getComputedStyle(el).display : 'missing';
        };
        const out = {
            ok: ok,
            rows: d.querySelectorAll('.wr-col-word .wr-item').length,
            tiles: d.querySelectorAll('.wr-col-pick .wr-tile').length,
            stageTabs: d.querySelectorAll('.wr-stage').length,
            rootClass: d.querySelector('.wr-root').className,
            h1: d.querySelector('.wr-toolbar-title h2').textContent.trim(),
            back: dis('.lt-back'),
            exportItem: dis('.lt-set-item.is-export'),
            sourceItem: dis('.lt-set-item.is-source')
        };
        f.remove();
        URL.revokeObjectURL(url);
        return out;
    })()`);
    check('★ 导出件在浏览器里真的能开局：6 对 / 6 个候选 / 三个学段',
        live.ok && live.rows === 6 && live.tiles === 6 && live.stageTabs === 3,
        `rows=${live.rows} tiles=${live.tiles} tabs=${live.stageTabs}`);
    check('★ 导出件仍然是「反义词」模式（标题、主题类都跟着走）',
        /mode-antonym/.test(live.rootClass) && live.h1 === '反义词匹配',
        `${live.rootClass} / ${live.h1}`);
    check('★ 只在站内成立的入口被收起：返回 / 导出 / 源码',
        live.back === 'none' && live.exportItem === 'none' && live.sourceItem === 'none',
        `back=${live.back} export=${live.exportItem} source=${live.sourceItem}`);

    /* ==================== W17 运行期异常 ==================== */
    console.log('\n— W17 运行期异常 —');
    const noise = runtimeErrors.concat(consoleErrors).filter(t => t && !/favicon/i.test(t));
    check('无 JS 异常 / console.error', noise.length === 0,
        noise.slice(0, 2).join(' | '));

    /* ==================== 汇总 ==================== */
    const failed = results.filter(r => !r.ok);
    console.log('\n========================================');
    console.log(`结果：${results.length - failed.length}/${results.length} 通过`);
    if (failed.length) {
        console.log('失败项：');
        failed.forEach(f => console.log('  ✗ ' + f.name + (f.extra ? '  [' + f.extra + ']' : '')));
    }
    console.log('PROBE_RC=' + (failed.length ? 1 : 0));
    console.log('截图目录：' + SHOT_DIR);
    console.log('========================================');
    chrome.kill();
    process.exit(failed.length ? 1 : 0);
}

main().catch(async (e) => {
    console.error('探针异常终止：' + (e && e.stack || e));
    try { chrome.kill(); } catch (_) { /* ignore */ }
    console.log('PROBE_RC=1');
    process.exit(1);
});
