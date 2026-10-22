/*
 * 前后对照面板的**共享实现**（扩写 / 润色 / 精修 三个页面共用）
 *
 * 为什么单独抽出来：同一套「句级差异 + 字数增量 + 左右同步滚动」原来只长在扩写页里，
 * 润色与精修页各自用的是裸 split-view（两边各滚各的、没有差异高亮）。
 * 抽成一份之后，三个页面只有数据来源不同，交互行为完全一致，也只需要维护一个 diff 算法。
 *
 * 页面里怎么用（以润色为例）：
 *   preview 对象 = Object.assign({draft, content}, SCCmp.preparePair(draft, content))
 *   标记里：opsBefore/opsAfter 走 x-for，`:class="diffClass(op.t, pair.highlight)"` 上色，
 *           `:data-anc="op.k"` 标出锚点句，`@scroll="syncScroll($event)"` 挂同步；
 *           开关是 `.sc-cmp-sync input`（同步滚动）+ `pair.highlight`（差异高亮），都默认开。
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
     *
     * 对齐上的句子两边共用同一个 **anchor 号 k**（`{t:'same', s, k}`）——
     * 它就是「左右同步滚动」的锚点：同一个 k 在两侧指的是同一句话。
     * 单边多出来的句子没有对侧对应物，`k = -1`（**负号 = 这一句不是锚点**，
     * 拿它对齐只会把两边错开）；渲染成 `data-anc` 后，读的一方只认 `k >= 0`。
     */
    function diff(beforeText, afterText) {
        var a = splitSentences(beforeText);
        var b = splitSentences(afterText);
        var plain = function (arr) {
            return arr.map(function (s) { return { t: 'same', s: s, k: -1 }; });
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
        var k = 0;              // 对齐句的锚点号（两侧同号 = 同一句）
        i = 0;
        j = 0;
        while (i < n && j < m) {
            if (normKey(a[i]) === normKey(b[j])) {
                // 纯换行 token 不当锚点：pre-wrap 下它是零宽断行，量出来的位置不可靠
                var anchor = isBlank(a[i]) ? -1 : k;
                outA.push({ t: 'same', s: a[i], k: anchor });
                outB.push({ t: 'same', s: b[j], k: anchor });
                if (anchor >= 0) k++;
                i++; j++;
            } else if (dp[i + 1][j] >= dp[i][j + 1]) {
                // 纯空白 token（换行）不算改动，否则两侧段落断行差异会被整片染红
                outA.push({ t: isBlank(a[i]) ? 'same' : 'del', s: a[i], k: -1 });
                i++;
            } else {
                outB.push({ t: isBlank(b[j]) ? 'same' : 'add', s: b[j], k: -1 });
                j++;
            }
        }
        while (i < n) outA.push({ t: isBlank(a[i]) ? 'same' : 'del', s: a[i++], k: -1 });
        while (j < m) outB.push({ t: isBlank(b[j]) ? 'same' : 'add', s: b[j++], k: -1 });
        return { before: outA, after: outB, truncated: false };
    }

    /**
     * 组装一份可供模板直接渲染的对照数据。
     * @param beforeText 前一版正文（原文 / 原稿 / 精修前）
     * @param afterText  后一版正文（扩写后 / 润色后 / 精修后）
     * @param opts       {highlight, syncScroll, beforeCount, afterCount, extra} —— 字数默认按字符串长度，
     *                   扩写页有后端给的字数（已去掉空白）就传进来覆盖。
     *                   两个开关都默认开（历史行为 + 用户明确要的默认值）。
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
            highlight: opts.highlight !== false,
            syncScroll: opts.syncScroll !== false
        };
        if (opts.extra) {
            for (var k in opts.extra) {
                if (Object.prototype.hasOwnProperty.call(opts.extra, k)) out[k] = opts.extra[k];
            }
        }
        return out;
    }

    /**
     * 差异上色的 class。
     * enabled 传 false 时一律返回空串（「差异高亮」关掉）——但句子仍然是**逐句渲染的 span**，
     * 因为锚点同步要靠这些 span 定位；关掉高亮不等于退回一整段纯文本。
     */
    function diffClass(kind, enabled) {
        if (enabled === false) return '';
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

    /* ===== 锚点同步 =====
       按「占比」同步只能保证两侧进度条差不多，读到的**不是同一处**：
       扩写/润色后正文变长，原稿滚到 50% 是第 20 段，扩写后 50% 可能已经是第 26 段了。
       既然差异表里「没改动的句子」两侧是同一句（同一个 anchor 号 k），就拿它们当锚点：
       源侧滚到某句 → 目标侧把**那一句**摆到同样的视口位置。改得越多的地方两侧位移越不一样，
       改得少的地方几乎 1:1 —— 这才是"按内容同步"。

       测量用内容坐标（`spanTop - paneTop + scrollTop`），与当前滚到哪儿无关，所以能整块缓存；
       缓存键带上 `clientWidth/scrollHeight/锚点数`，窗口缩放或内容变了自动失效。 */
    var seqCache = typeof WeakMap === 'function' ? new WeakMap() : null;

    /** 量一栏里所有锚点句的位置；返回 { sig, list:[{k,y,h}], map }（y/h 都是内容坐标） */
    function measureAnchors(pane) {
        var spans = pane.querySelectorAll('[data-anc]');
        var sig = spans.length + '|' + pane.clientWidth + '|' + pane.scrollHeight;
        var hit = seqCache ? seqCache.get(pane) : null;
        if (hit && hit.sig === sig) return hit;
        var seq = { sig: sig, list: [], map: null };
        if (spans.length) {
            // 一次读 pane 的位置，再逐个读 span（全程只读，不会反复触发重排）
            var base = pane.getBoundingClientRect().top - pane.scrollTop;
            for (var i = 0; i < spans.length; i++) {
                var k = Number(spans[i].getAttribute('data-anc'));
                if (!(k >= 0)) continue;      // 非锚点句（k = -1 或没渲染数字）一律跳过
                var r = spans[i].getBoundingClientRect();
                seq.list.push({ k: k, y: r.top - base, h: r.height });
            }
        }
        if (seqCache) seqCache.set(pane, seq);
        return seq;
    }

    /** 某个锚点在目标栏里的位置（y = 顶边、h = 高度），按 k 查 */
    function anchorPos(seq, k) {
        if (!seq.map) {
            seq.map = {};
            for (var i = 0; i < seq.list.length; i++) seq.map[seq.list[i].k] = seq.list[i];
        }
        return seq.map[k] || null;
    }

    /**
     * 源侧 scrollTop → 目标侧 scrollTop（按锚点映射，不是按百分比）。
     *
     * 拿「第一句还没完全滚出视野」的锚点当参照（它在两侧是同一句话）：
     *  ① 顶边落在这句话**里面** → 保留「从这句往下滚了多少」；
     *  ② 顶边落在这句话**上面**（中间那截是对侧没有的新增内容）→ 让同一句话停在同样的
     *     高度上，但**不许越过上一句的底边** ⇒ 读新增段落时对面停住，读完再一起走；
     *  ③ 在首句之前 / 末句之后（开头或结尾整段是新增）→ 按剩余空间等比分配，
     *     保证滚到顶、滚到底两侧仍然一起到顶、到底。
     * 没有锚点（没渲染句子 span、整篇都改过）返回 null，由调用方退回占比同步。
     */
    function anchorTarget(pane, other, scrollTop) {
        var a = measureAnchors(pane);
        var b = measureAnchors(other);
        if (!a.list.length || !b.list.length) return null;
        var first = a.list[0];
        var last = a.list[a.list.length - 1];
        var firstPos = anchorPos(b, first.k);
        var lastPos = anchorPos(b, last.k);
        if (!firstPos || !lastPos) return null;

        var srcMax = pane.scrollHeight - pane.clientHeight;
        var dstMax = other.scrollHeight - other.clientHeight;
        // 顶/底直接对顶/底：最后一句常常压不到顶（它在最后一屏里），
        // 光靠锚点映射会让「一侧到底了、另一侧还差几十像素」
        if (scrollTop <= 0) return 0;
        if (srcMax - scrollTop <= 1) return dstMax;

        // ③ 首句之前
        if (scrollTop <= first.y) {
            return first.y > 0 ? (scrollTop / first.y) * firstPos.y : firstPos.y;
        }

        // 「第一句还没完全滚出视野」的锚点 = 用户此刻正在读（或即将读到）的那一句
        var lo = 0;
        var hi = a.list.length - 1;
        var idx = -1;
        while (lo <= hi) {
            var mid = (lo + hi) >> 1;
            if (a.list[mid].y + a.list[mid].h > scrollTop) { idx = mid; hi = mid - 1; }
            else { lo = mid + 1; }
        }

        // ③ 末句之后
        if (idx < 0) {
            var srcTail = srcMax - (last.y + last.h);
            if (srcTail <= 0) return lastPos.y + lastPos.h;
            var tailFrac = Math.min(1, (scrollTop - (last.y + last.h)) / srcTail);
            return (lastPos.y + lastPos.h)
                + tailFrac * Math.max(0, dstMax - (lastPos.y + lastPos.h));
        }

        var p = a.list[idx];
        var d = anchorPos(b, p.k);
        if (!d) return null;

        // ① 顶边就在这句里：按「这句里的偏移」搬过去（同一句两侧等宽等字号，通常是 1:1）
        if (scrollTop >= p.y) {
            var ratio = (p.h > 0 && d.h > 0) ? d.h / p.h : 1;
            return d.y + (scrollTop - p.y) * ratio;
        }

        // ② 顶边在这句上面（两句之间那段是对侧没有的新增内容）
        var target = d.y - (p.y - scrollTop);
        var prev = idx > 0 ? anchorPos(b, a.list[idx - 1].k) : null;
        if (prev) target = Math.max(prev.y + prev.h, target);   // 别越过上一句的底边
        return target;
    }

    /**
     * 左右两栏**滚动同步**：优先按内容锚点，没有锚点才退回按占比。
     * 只在**真正左右并排**时同步：窄屏两栏是上下堆叠的，那时同步反而会把
     * 另一栏（用户看不见）的位置偷偷改掉，所以按实际几何位置判断，不写死断点。
     * 某侧没有可滚动空间（这一段短）也不同步，否则会把长的一侧强行拽回顶部。
     * 【同步滚动】开关就长在面板上（`.sc-cmp-sync input`），关掉就完全不管另一栏。
     * 防死循环：目标位置与当前相差 ≤1px 就不写；锚点映射本身是幂等的
     * （A→B 后再 B→A 会落回原处），链式回调自然收敛。
     */
    function syncScroll(ev) {
        var pane = ev && ev.target;
        if (!pane || !pane.classList || !pane.classList.contains('sc-cmp-pane')) return;
        var panel = pane.closest ? pane.closest('.sc-cmp') : null;
        if (!panel) return;
        // 开关：没有这个开关（单栏面板 / 写作步的正文预览）就当开着
        var toggle = panel.__scSyncToggle;
        if (toggle === undefined) {
            toggle = panel.querySelector('.sc-cmp-sync input') || null;
            panel.__scSyncToggle = toggle;
        }
        if (toggle && toggle.checked === false) return;

        var panes = panel.querySelectorAll('.sc-cmp-pane');
        if (panes.length < 2) return;
        var boxA = panes[0].getBoundingClientRect();
        var boxB = panes[1].getBoundingClientRect();
        if (Math.abs(boxA.top - boxB.top) > 4) return;   // 上下堆叠（窄屏）就各滚各的
        var other = panes[0] === pane ? panes[1] : panes[0];
        var selfMax = pane.scrollHeight - pane.clientHeight;
        var otherMax = other.scrollHeight - other.clientHeight;
        if (selfMax <= 0 || otherMax <= 0) return;

        var target = anchorTarget(pane, other, pane.scrollTop);
        if (target == null) {
            // 兜底：没有可对齐的句子（全篇重写 / 老版本渲染），至少保证同进同退
            target = Math.min(1, Math.max(0, pane.scrollTop / selfMax)) * otherMax;
        }
        target = Math.round(Math.min(otherMax, Math.max(0, target)));
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
