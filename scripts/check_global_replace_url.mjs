#!/usr/bin/env node
/**
 * 探针：全局替换的**参数编码**（前端这一侧）—— 「替换为」留空 = 删除。
 *
 * 为什么单独钉这个：后端已经改成用 `request.getParameterValues` 读原始值
 * （`@RequestParam List<String>` 会把空值转成空集合 ⇒ 后面所有值前移错位），
 * 但前端如果把 `null` 直接 `append` 进 URLSearchParams，会被字符串化成**字面量 "null"** ——
 * 正文里就真的出现四个字母 null。这两头任何一头松了，用户都会看到「删不掉 / 多出 null」。
 *
 * 做法：proofreading.js 里的 `workflowProofreadingMixin()` 是纯工厂函数（不带 DOM 依赖），
 * 直接在 Node 的 vm 里跑，stub 掉 EventSource 捕获 URL —— 不启服务、不开浏览器。
 *
 * 用法：node scripts/check_global_replace_url.mjs
 */
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const MIXIN = path.join(ROOT, 'src/main/resources/static/js/workflow/proofreading.js');

const results = [];
const check = (name, ok, extra = '') => {
    results.push({ name, ok: !!ok });
    console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${extra ? '  [' + extra + ']' : ''}`);
};

/** 载入 mixin，返回 { make, urls, alerts } */
function load() {
    const urls = [];
    const alerts = [];
    class FakeEventSource {
        constructor(url) { urls.push(url); this.listeners = {}; }
        addEventListener() {}
        close() {}
    }
    const ctx = {
        URLSearchParams,
        EventSource: FakeEventSource,
        alert: (m) => alerts.push(m),
        console,
        setTimeout,
    };
    vm.createContext(ctx);
    vm.runInContext(fs.readFileSync(MIXIN, 'utf8'), ctx, { filename: MIXIN });
    const make = (pairs, projectId = 77) => Object.assign({}, ctx.workflowProofreadingMixin(), {
        projectId,
        replacePairs: pairs,
        globalReplacing: false,
        globalReplaceLog: [],
        loadChapterList() {}, loadCharacterList() {}, loadProofreadData() {},
        currentStep: 'CHAPTER_WRITING',
    });
    return { make, urls, alerts };
}

// ---------------------------------------------------------------- 1. null / 留空
{
    const { make, urls } = load();
    const inst = make([
        { oldText: '张三', newText: null },       // 右边是 null（脚本/自动填充可能给这个）
        { oldText: '李四', newText: '' },         // 右边空串（用户清空输入框就是这个）
        { oldText: '王五', newText: undefined },  // undefined 同理
        { oldText: '   ', newText: 'X' },         // 左边纯空白 ⇒ 这行无效，不发
        { oldText: '', newText: 'Y' },            // 左边空 ⇒ 不发
    ]);
    inst.executeGlobalReplace();
    const url = urls[0] || '';
    const q = url.includes('?') ? url.slice(url.indexOf('?') + 1) : '';

    check('发起了请求', urls.length === 1, url ? url.slice(0, 90) : '没发');
    check('★右边为 null/undefined 时，发的是空值而不是字面量 "null"/"undefined"',
        /newTexts=(&|$)/.test(q) && !/newTexts=(null|undefined)/.test(q), q);
    check('三对有效替换全部按顺序发出（oldTexts 出现 3 次）',
        (q.match(/(^|&)oldTexts=/g) || []).length === 3, q);
    check('左边为空/纯空白的行不参与（不会把 "" 当查找词）',
        !/(^|&)oldTexts=(&|$)/.test(q), q);
    check('中文按 UTF-8 百分号编码（服务端 getParameterValues 能解回原文）',
        q.includes('oldTexts=%E5%BC%A0%E4%B8%89') && q.includes('newTexts=&'), q);
    check('oldTexts 与 newTexts 交替出现、条数一致（不错位的前提）',
        (q.match(/(^|&)oldTexts=/g) || []).length === (q.match(/(^|&)newTexts=/g) || []).length, q);
}

// ---------------------------------------------------------------- 2. 全空 ⇒ 不请求
{
    const { make, urls, alerts } = load();
    const inst = make([{ oldText: '', newText: '' }, { oldText: '  ', newText: 'x' }]);
    inst.executeGlobalReplace();
    check('一对有效替换都没有时：不发请求、给出提示', urls.length === 0 && alerts.length === 1,
        `requests=${urls.length} alerts=${alerts.join('|')}`);
}

// ---------------------------------------------------------------- 3. 正常替换不受影响
{
    const { make, urls } = load();
    const inst = make([{ oldText: 'AAA', newText: 'BBB' }]);
    inst.executeGlobalReplace();
    const url = urls[0] || '';
    check('正常填写的替换对仍原样发出', /oldTexts=AAA&newTexts=BBB/.test(url), url);
    check('请求打在当前项目上', url.startsWith('/projects/77/global-replace?'), url.slice(0, 60));
}

// ---------------------------------------------------------------- 4. 7 个页面的输入框提示
{
    const PAGES = [
        'src/main/resources/static/pages/workflow.html',
        'src/main/resources/static/pages/workflow/chapters.html',
        'src/main/resources/static/pages/workflow/polishing.html',
        'src/main/resources/static/pages/workflow/proofreading.html',
        'src/main/resources/static/pages/workflow/characters.html',
        'src/main/resources/static/pages/workflow/world-building.html',
        'src/main/resources/static/pages/workflow/outline.html',
    ];
    const bad = [];
    for (const rel of PAGES) {
        const html = fs.readFileSync(path.join(ROOT, rel), 'utf8');
        const modal = html.indexOf('id="globalReplaceModal"');
        const seg = modal < 0 ? '' : html.slice(modal, modal + 3000);
        if (!seg.includes('placeholder="替换为...（留空 = 删除）"')) bad.push(rel.split('/').pop());
    }
    check('7 个带「全局替换」弹层的页面都提示「留空 = 删除」', bad.length === 0, bad.join(' / '));
}

const pass = results.filter(r => r.ok).length;
console.log(`\n===== ${pass}/${results.length} 通过 =====`);
process.exit(pass === results.length ? 0 : 1);
