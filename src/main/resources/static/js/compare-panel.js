/*
 * 前后对照面板的**共享实现**（扩写 / 润色 / 精修 三个页面共用）
 *
 * 为什么单独抽出来：同一套「句级差异 + 字数增量 + 左右同步滚动」原来只长在扩写页里，
 * 润色与精修页各自用的是裸 split-view（两边各滚各的、没有差异高亮）。
 * 抽成一份之后，三个页面只有数据来源不同，交互行为完全一致，也只需要维护一个 diff 算法。
 *
 * 页面里怎么用（以润色为例）：
 *   preview 对象 = Object.assign({draft, content}, SCCmp.preparePair(draft, content))
 *   标记里：opsBefore/opsAfter 走 x-for，diffClass(op.t) 上色，syncScroll($event) 挂滚动。
 */
(function (global) {
    'use strict';

    var ENDERS = '。！？!?…';   // 中文句末标点算断句
    var LIMIT = 600;            // 句子数上限：超了退回纯文本，避免上万格 DP 卡住页面

    /**
     * 切句：句末标点断一句，换行自成一个 token（靠 CSS 的 pre-wrap 还原段落与缩进）。
     */
    function splitSentences(text) {
        var out = [];
        var buf = '';
        var s = String(text == null ? '' : text);
        for (var i = 0; i < s.length; i++) {
            var ch = s.charAt(i);
            if (ch === '\r') continue;
            if (ch === '\n') {
                if (buf) { out.push(buf); buf = ''; }
                out.push('\n');
                continue;
            }
            buf += ch;
            if (ENDERS.indexOf(ch) >= 0) { out.push(buf); buf = ''; }
        }
        if (buf) out.push(buf);
        return out;
    }

    function normKey(s) { return s.replace(/\s+/g, ''); }
    function isBlank(s) { return s.trim() === ''; }

    /**
     * 句级差异：LCS 对齐两侧句子，只给「单边多出来的句子」打标。
     * 扩写/润色通常是保留原句、增补或改写新句，这个粒度最贴近真实改动；
     * 逐字 diff 在几千字正文上既慢又吵。
     */
    function diff(beforeText, afterText) {
        var a = splitSentences(beforeText);
        var b = splitSentences(afterText);
        var plain = function (arr) {
            return arr.map(function (s) { return { t: 'same', s: s }; });
        };
        if (a.length > LIMIT || b.length > LIMIT) {
            return { before: plain(a), after: plain(b), truncated: true };
        }
        var n = a.length;
        var m = b.length;
        // dp[i][j] = a[i..] 与 b[j..] 的最长公共句数；句子数有上限，Uint16 够用
        var dp = [];
        for (var i = 0; i <= n; i++) dp.push(new Uint16Array(m + 1));
        for (i = n - 1; i >= 0; i--) {
            for (var j = m - 1; j >= 0; j--) {
                dp[i][j] = normKey(a[i]) === normKey(b[j])
                    ? dp[i + 1][j + 1] + 1
                    : Math.max(dp[i + 1][j], dp[i][j + 1]);
            }
        }
        var outA = [];
        var outB = [];
        i = 0;
        j = 0;
        while (i < n && j < m) {
            if (normKey(a[i]) === normKey(b[j])) {
                outA.push({ t: 'same', s: a[i] });
                outB.push({ t: 'same', s: b[j] });
                i++; j++;
            } else if (dp[i + 1][j] >= dp[i][j + 1]) {
                // 纯空白 token（换行）不算改动，否则两侧段落断行差异会被整片染红
                outA.push({ t: isBlank(a[i]) ? 'same' : 'del', s: a[i] });
                i++;
            } else {
                outB.push({ t: isBlank(b[j]) ? 'same' : 'add', s: b[j] });
                j++;
            }
        }
        while (i < n) outA.push({ t: isBlank(a[i]) ? 'same' : 'del', s: a[i++] });
        while (j < m) outB.push({ t: isBlank(b[j]) ? 'same' : 'add', s: b[j++] });
        return { before: outA, after: outB, truncated: false };
    }

    /**
     * 组装一份可供模板直接渲染的对照数据。
     * @param beforeText 前一版正文（原文 / 原稿 / 精修前）
     * @param afterText  后一版正文（扩写后 / 润色后 / 精修后）
     * @param opts       {highlight, beforeCount, afterCount, extra} —— 字数默认按字符串长度，
     *                   扩写页有后端给的字数（已去掉空白）就传进来覆盖。
     */
    function preparePair(beforeText, afterText, opts) {
        opts = opts || {};
        var bt = String(beforeText == null ? '' : beforeText);
        var at = String(afterText == null ? '' : afterText);
        var d = diff(bt, at);
        var out = {
            beforeText: bt,
            afterText: at,
            beforeCount: opts.beforeCount != null ? opts.beforeCount : bt.length,
            afterCount: opts.afterCount != null ? opts.afterCount : at.length,
            opsBefore: d.before,
            opsAfter: d.after,
            truncated: !!d.truncated,
            highlight: opts.highlight !== false
        };
        if (opts.extra) {
            for (var k in opts.extra) {
                if (Object.prototype.hasOwnProperty.call(opts.extra, k)) out[k] = opts.extra[k];
            }
        }
        return out;
    }

    function diffClass(kind) {
        if (kind === 'del') return 'sc-cmp-del';
        if (kind === 'add') return 'sc-cmp-add';
        return '';
    }

    /** 增量徽标：变多为绿、没变/变少为灰（与扩写页原有口径一致） */
    function deltaClass(before, after) {
        return (after || 0) > (before || 0) ? 'bg-success' : 'bg-secondary';
    }

    function deltaText(before, after) {
        var b = before || 0;
        var a = after || 0;
        if (!b) return '原文缺失';
        var pct = (a - b) / b * 100;
        return (pct >= 0 ? '+' : '') + pct.toFixed(1) + '%';
    }

    /**
     * 左右两栏**滚动同步**：按滚动占比同步，不按像素。
     * 两侧字数不一样，按占比才能"两边读到同一处"；按像素同步会在短的一侧先见底。
     * 只在**真正左右并排**时同步：窄屏两栏是上下堆叠的，那时同步反而会把
     * 另一栏（用户看不见）的位置偷偷改掉，所以按实际几何位置判断，不写死断点。
     * 某侧没有可滚动空间（这一段短）也不同步，否则会把长的一侧强行拽回顶部。
     * 防死循环：目标位置与当前相差 ≤1px 就不写，链式回调会自然收敛。
     */
    function syncScroll(ev) {
        var pane = ev && ev.target;
        if (!pane || !pane.classList || !pane.classList.contains('sc-cmp-pane')) return;
        var panel = pane.closest ? pane.closest('.sc-cmp') : null;
        if (!panel) return;
        var panes = panel.querySelectorAll('.sc-cmp-pane');
        if (panes.length < 2) return;
        var boxA = panes[0].getBoundingClientRect();
        var boxB = panes[1].getBoundingClientRect();
        if (Math.abs(boxA.top - boxB.top) > 4) return;   // 上下堆叠（窄屏）就各滚各的
        var other = panes[0] === pane ? panes[1] : panes[0];
        var selfMax = pane.scrollHeight - pane.clientHeight;
        var otherMax = other.scrollHeight - other.clientHeight;
        if (selfMax <= 0 || otherMax <= 0) return;
        var ratio = Math.min(1, Math.max(0, pane.scrollTop / selfMax));
        var target = Math.round(ratio * otherMax);
        if (Math.abs(other.scrollTop - target) <= 1) return;
        other.scrollTop = target;
    }

    global.SCCmp = {
        splitSentences: splitSentences,
        diff: diff,
        preparePair: preparePair,
        diffClass: diffClass,
        deltaClass: deltaClass,
        deltaText: deltaText,
        syncScroll: syncScroll
    };
})(window);
