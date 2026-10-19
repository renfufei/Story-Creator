/* ============================================================================
   learn-tools.js —— 教学模块三个新页面（成语填空 / 近义词匹配 / 反义词匹配）
   共用的「设置」弹层行为。

   为什么抽成一份：
     三张页的【设置】面板条目几乎一致（错题本 / 重做本关 / 朗读 / 导出 / 源码 /
     重置进度），而「导出单页 HTML」有 150 行左右（取回本页 → 内联 CSS/JS/图标字体
     → 把词库就地写死），复制三遍必然各自漂移。所以**弹层结构**各页各写一份
     （几十行，Alpine 指令必须处于静态 HTML 里才能被编译），**CSS 与逻辑**放这里。

   为什么弹层不在这里用 innerHTML 注入：
     Alpine 只在启动时扫一次 DOM，注入进来的 x-show / @click 不会被编译；
     要补救就得手动 Alpine.initTree，反而会让「导出件里的执行顺序」依赖脚本位置。
     静态结构 + 共享逻辑是这里更稳的取舍。

   导出件与线上页同源：直接拿「服务端渲染出来的这份页面」做改造，不另写一套页面，
   所以导出物永远跟线上一致。
   ========================================================================== */
(function (global) {
    'use strict';

    var GITHUB = 'https://github.com/renfufei/Story-Creator';

    /* 导出时按这个清单内联；页面里其它带 src 的脚本（nav.js / bootstrap.bundle）
       在单机件里要么无用、要么是站内导航，一律丢弃。 */
    var EXPORT_CSS = ['/vendor/bootstrap/css/bootstrap.min.css',
                      '/vendor/bootstrap-icons/css/bootstrap-icons.min.css',
                      '/css/style.css',
                      '/css/app.css'];
    var EXPORT_JS = ['/js/common.js', '/js/learn-tools.js', '/vendor/alpinejs/cdn.min.js'];
    var EXPORT_FONT = '/vendor/bootstrap-icons/css/fonts/bootstrap-icons.woff2';

    /* ---------- 小工具 ---------- */

    function toast(kind, msg, ms) {
        if (global.SC && SC.toast) {
            var fn = SC.toast[kind] || SC.toast.info;
            fn.call(SC.toast, msg, ms);
            return;
        }
        console.log('[learn-tools] ' + kind + ': ' + msg);
    }

    function fetchText(url) {
        return fetch(url, { cache: 'no-store' }).then(function (r) {
            if (!r.ok) throw new Error(url + ' 取不到（HTTP ' + r.status + '）');
            return r.text();
        });
    }

    function fetchBase64(url) {
        return fetch(url, { cache: 'no-store' }).then(function (r) {
            if (!r.ok) throw new Error(url + ' 取不到（HTTP ' + r.status + '）');
            return r.arrayBuffer();
        }).then(function (buf) {
            var bytes = new Uint8Array(buf), step = 0x8000, parts = [];
            for (var i = 0; i < bytes.length; i += step) {
                parts.push(String.fromCharCode.apply(null, bytes.subarray(i, i + step)));
            }
            return btoa(parts.join(''));
        });
    }

    /* 内联脚本里出现脚本结束标记会提前结束脚本块，必须打断。
       注意连注释里都不能写那个字面量 —— 浏览器只看字符，不看它是不是注释。
       JS 字符串里 \/ 与 / 等价，所以打断后语义不变。 */
    function escapeScript(code) { return String(code).replace(/<\/script/gi, '<\\/script'); }

    /* 图标字体整份内联（130KB），但页面真正用到的图标只有二十来个：
       只裁剪 CSS 规则。图标名从页面源码里扫（含 :class 表达式里的），
       以后新增图标不用回来改这里。 */
    function pickIconCss(iconCss, sourceText, fontB64) {
        var used = {}, m, nameRe = /bi-[a-z0-9-]+/g;
        while ((m = nameRe.exec(sourceText))) used[m[0]] = true;

        var keep = [], r, ruleRe = /\.bi-([a-z0-9-]+)::before\{[^}]*\}/g;
        while ((r = ruleRe.exec(iconCss))) if (used['bi-' + r[1]]) keep.push(r[0]);

        var face = iconCss.match(/@font-face\{[^}]*\}/);
        var base = iconCss.match(/\.bi::before,[^{]*\{[^}]*\}/);
        var faceTxt = face ? face[0].replace(/src:[^}]*/,
            'src:url(data:font/woff2;base64,' + fontB64 + ') format("woff2")') : '';
        return faceTxt + (base ? base[0] : '') + keep.join('');
    }

    /* 文件名时间戳 YYYYMMDDHHmm（带时分，避免同一天多次导出互相覆盖） */
    function stampDateTime(d) {
        var p = function (x) { return (x < 10 ? '0' : '') + x; };
        return '' + d.getFullYear() + p(d.getMonth() + 1) + p(d.getDate())
            + p(d.getHours()) + p(d.getMinutes());
    }

    /* a[download] + Blob 落盘，返回字节数（用于提示体积） */
    function download(html, filename) {
        var blob = new Blob([html], { type: 'text/html;charset=utf-8' });
        var url = URL.createObjectURL(blob);
        var a = document.createElement('a');
        a.href = url;
        a.download = filename;
        a.style.display = 'none';
        document.body.appendChild(a);
        a.click();
        setTimeout(function () { URL.revokeObjectURL(url); a.remove(); }, 5000);
        return blob.size;
    }

    /* ---------- 导出为单页 HTML ----------
       opts = {
         dataVar : '主体脚本里那个全局变量名，如 __IDIOM_FILL_DATA__'  （必填）
         payload : 要写进产物的词库对象
         title   : 产物 <title>，如「成语填空」
         prefix  : 文件名前缀，如 idiom-fill
         prelude : 可选，替换脚本时先执行的一段代码（页面里别的全局变量在此重建，
                   例如近义词页的 WR_MODE / WR_LABEL）
       }
       返回 Promise<{html, filename, bytes}> */
    function exportPage(opts) {
        var o = opts || {};
        if (!o.dataVar) return Promise.reject(new Error('导出缺少 dataVar'));

        return fetchText(global.location.pathname).then(function (pageHtml) {
            return Promise.all([
                Promise.all(EXPORT_CSS.map(fetchText)),
                Promise.all(EXPORT_JS.map(fetchText)),
                fetchBase64(EXPORT_FONT)
            ]).then(function (got) {
                var cssList = got[0], jsList = got[1], fontB64 = got[2];
                var codeMap = {};
                EXPORT_JS.forEach(function (u, n) { codeMap[u] = jsList[n]; });
                if (!codeMap['/vendor/alpinejs/cdn.min.js']) {
                    throw new Error('Alpine 脚本没取到，导出会变成死页面');
                }

                var doc = new DOMParser().parseFromString(pageHtml, 'text/html');
                var head = doc.head, body = doc.body;

                /* ① 摘掉站内导航壳：单机件里没有任何站内页可去 */
                var nav = doc.getElementById('site-nav');
                if (nav) nav.remove();

                /* ② 外链 CSS + 图标字体 → 一份内联 <style>（摆在页面自带 <style> 之前，
                      保证层叠顺序与线上一致） */
                var styleEl = doc.createElement('style');
                styleEl.setAttribute('data-lt-inline', 'css');
                styleEl.textContent = '\n/* ===== 内联：Bootstrap 5 ===== */\n' + cssList[0]
                    + '\n/* ===== 内联：Bootstrap Icons（规则按用到的图标裁剪，字体是 data URI） ===== */\n'
                    + pickIconCss(cssList[1], pageHtml, fontB64)
                    + '\n/* ===== 内联：style.css ===== */\n' + cssList[2]
                    + '\n/* ===== 内联：app.css ===== */\n' + cssList[3] + '\n';
                var ownStyle = head.querySelector('style');
                head.insertBefore(styleEl, ownStyle || head.firstChild);

                var drop = doc.querySelectorAll('link[rel="stylesheet"], link[rel="icon"], link[rel="shortcut icon"]');
                for (var i = 0; i < drop.length; i++) drop[i].remove();

                /* ③ 外链 JS 就地换内联（位置不变 ⇒ 执行顺序不变） */
                var scripts = doc.querySelectorAll('script[src]');
                for (var j = 0; j < scripts.length; j++) {
                    var el = scripts[j], src = el.getAttribute('src') || '';
                    var code = codeMap[src] || null;
                    if (!code) { el.remove(); continue; }
                    var node = doc.createElement('script');
                    node.textContent = escapeScript(code);
                    el.parentNode.replaceChild(node, el);
                }

                /* ④ 词库就地写死（整库随文件带走：三个学段都只有几十 KB） */
                var json = JSON.stringify(o.payload || {})
                    .replace(/</g, '\\u003c').replace(/\u2028/g, '\\u2028').replace(/\u2029/g, '\\u2029');
                var snippet = '\n/* 词库随文件一起带走：导出件不请求任何后端接口 */\n'
                    + 'window.__LEARN_STANDALONE__ = true;\n'
                    + (o.prelude ? o.prelude + '\n' : '')
                    + 'window.' + o.dataVar + ' = ' + json + ';\n';

                var inlined = false;
                var own = doc.querySelectorAll('script:not([src])');
                for (var k = 0; k < own.length; k++) {
                    if (own[k].textContent.indexOf(o.dataVar) < 0) continue;
                    own[k].textContent = snippet;
                    inlined = true;
                    break;
                }
                if (!inlined) throw new Error('页面结构变了：找不到词库引导脚本');

                /* ⑤ 单机模式标记：CSS 据此收起「返回 / 导出 / 源码」这些只在站内成立的入口 */
                body.setAttribute('data-standalone', '1');
                body.removeAttribute('data-nav');

                var stamp = stampDateTime(new Date());
                var titleEl = doc.querySelector('title');
                if (titleEl && o.title) titleEl.textContent = o.title;

                var html = '<!DOCTYPE html>\n' + doc.documentElement.outerHTML;

                /* 自检：产物里不该再有任何「加载外部资源」的引用（导航链接不算） */
                if (/<script[^>]+src=/i.test(html)) throw new Error('导出后仍有外链脚本');
                if (/<link[^>]+rel=["']?stylesheet/i.test(html)) throw new Error('导出后仍有外链样式');

                return {
                    html: html,
                    filename: (o.prefix || 'learn') + '-' + stamp + '.html',
                    bytes: html.length
                };
            });
        });
    }

    /* ---------- 错题本存储 ----------
       localStorage 里存 [{k, label, sub, ts}]，按 k 去重（同一条只留最新一份），
       上限 200 条。key 由各页传入，两个模块互不干扰。 */
    function wrongBook(key) {
        function all() {
            try {
                var a = JSON.parse(global.localStorage.getItem(key) || '[]');
                return Object.prototype.toString.call(a) === '[object Array]' ? a : [];
            } catch (e) { return []; }
        }
        return {
            key: key,
            all: all,
            add: function (k, label, sub) {
                try {
                    var list = all().filter(function (x) { return x.k !== k; });
                    list.unshift({ k: k, label: label, sub: sub || '', ts: Date.now() });
                    if (list.length > 200) list.length = 200;
                    global.localStorage.setItem(key, JSON.stringify(list));
                } catch (e) { /* 隐身模式忽略 */ }
            },
            clear: function () {
                try { global.localStorage.removeItem(key); } catch (e) { /* ignore */ }
            }
        };
    }

    global.LearnTools = {
        github: GITHUB,
        exportPage: exportPage,
        download: download,
        wrongBook: wrongBook,
        toast: toast,
        get standalone() { return !!global.__LEARN_STANDALONE__; }
    };
})(window);
