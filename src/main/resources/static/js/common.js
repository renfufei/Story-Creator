/**
 * Story-Creator 前端公共库（无框架依赖，配合 Bootstrap 5 使用）
 *
 * 提供：
 *   SC.api      —— Ajax 客户端（GET/POST/PUT/DELETE，自动 JSON 编解码与错误提示）
 *   SC.fmt      —— 格式化工具（时间、字数、字数区间）
 *   SC.escape   —— HTML 转义（所有动态文本渲染前必须调用，防 XSS / 破版）
 *   SC.toast    —— 轻提示
 *   SC.confirm  —— 确认对话框（Promise）
 *   SC.skeleton —— 加载骨架 / 空状态 / 错误态
 *   SC.qs       —— 简易 DOM 查询
 *   SC.params   —— URL 查询参数
 *
 * 约定：
 *   1) 每个静态页先引 /js/common.js，再引 /js/nav.js，最后写页面自身的脚本。
 *   2) 所有后端请求走 /api/**，返回 JSON。
 */
(function (global) {
    'use strict';

    var SC = global.SC || (global.SC = {});

    /* ============================ DOM 小工具 ============================ */

    SC.qs = function (sel, root) { return (root || document).querySelector(sel); };
    SC.qsa = function (sel, root) { return Array.prototype.slice.call((root || document).querySelectorAll(sel)); };

    SC.params = function () {
        var out = {};
        var search = global.location.search || '';
        if (search.charAt(0) === '?') search = search.substring(1);
        if (!search) return out;
        search.split('&').forEach(function (kv) {
            if (!kv) return;
            var i = kv.indexOf('=');
            var k = i < 0 ? kv : kv.substring(0, i);
            var v = i < 0 ? '' : kv.substring(i + 1);
            out[decodeURIComponent(k)] = decodeURIComponent(v.replace(/\+/g, ' '));
        });
        return out;
    };

    /* ============================ 格式化 ============================ */

    var HTML_ESCAPE = { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' };

    /** HTML 转义，渲染任何后端文本前都要调用 */
    SC.escape = function (s) {
        if (s === null || s === undefined) return '';
        return String(s).replace(/[&<>"']/g, function (c) { return HTML_ESCAPE[c]; });
    };

    /** 保留换行的转义（用于 pre-wrap 容器） */
    SC.escapeNl = function (s) { return SC.escape(s).replace(/\n/g, '<br>'); };

    var pad = function (n) { return n < 10 ? '0' + n : '' + n; };

    SC.fmt = {
        /** 后端 LocalDateTime / Instant 字符串 -> yyyy-MM-dd HH:mm */
        datetime: function (v) {
            if (!v) return '';
            var d = new Date(typeof v === 'string' && !/[TZ]/.test(v) ? v.replace(' ', 'T') : v);
            if (isNaN(d.getTime())) return String(v);
            return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate()) +
                ' ' + pad(d.getHours()) + ':' + pad(d.getMinutes());
        },
        date: function (v) {
            var s = SC.fmt.datetime(v);
            return s ? s.substring(0, 10) : '';
        },
        /** 超过 1 万显示「x.x万」 */
        words: function (n) {
            n = Number(n) || 0;
            if (n >= 10000) {
                var wan = n / 10000;
                return (n % 10000 === 0 ? Math.round(wan) : wan.toFixed(1)) + '万';
            }
            return String(n);
        },
        /** 文本摘要 */
        abbreviate: function (s, len) {
            s = (s === null || s === undefined) ? '' : String(s);
            len = len || 80;
            return s.length > len ? s.substring(0, len) + '…' : s;
        }
    };

    /* ============================ Toast ============================ */

    var toastHost = null;
    function ensureToastHost() {
        if (toastHost) return toastHost;
        toastHost = document.createElement('div');
        toastHost.className = 'sc-toast-host';
        document.body.appendChild(toastHost);
        return toastHost;
    }

    /**
     * SC.toast.show(message, type, ms)
     * type: info | success | warning | danger
     */
    SC.toast = {
        show: function (message, type, ms) {
            var el = document.createElement('div');
            el.className = 'sc-toast alert alert-' + (type || 'info') + ' shadow-sm';
            el.setAttribute('role', 'alert');
            el.textContent = message;
            ensureToastHost().appendChild(el);
            setTimeout(function () {
                el.classList.add('sc-toast-out');
                setTimeout(function () { el.remove(); }, 300);
            }, ms || 2600);
            return el;
        },
        success: function (m, ms) { return SC.toast.show(m, 'success', ms); },
        error: function (m, ms) { return SC.toast.show(m, 'danger', ms || 4000); },
        warn: function (m, ms) { return SC.toast.show(m, 'warning', ms); },
        info: function (m, ms) { return SC.toast.show(m, 'info', ms); }
    };

    /* ============================ Confirm ============================ */

    /**
     * SC.confirm({title, message, confirmText, cancelText, danger})
     * @returns Promise<boolean>
     */
    SC.confirm = function (opts) {
        if (typeof opts === 'string') opts = { message: opts };
        opts = opts || {};
        return new Promise(function (resolve) {
            var wrap = document.createElement('div');
            wrap.className = 'sc-modal-backdrop';
            wrap.innerHTML =
                '<div class="sc-modal card shadow-lg">' +
                '  <div class="card-body">' +
                '    <h6 class="card-title mb-2">' + SC.escape(opts.title || '请确认') + '</h6>' +
                '    <p class="text-muted mb-4" style="white-space:pre-wrap">' + SC.escape(opts.message || '') + '</p>' +
                '    <div class="d-flex justify-content-end gap-2">' +
                '      <button type="button" class="btn btn-sm btn-outline-secondary" data-act="cancel">' +
                SC.escape(opts.cancelText || '取消') + '</button>' +
                '      <button type="button" class="btn btn-sm ' + (opts.danger ? 'btn-danger' : 'btn-primary') + '" data-act="ok">' +
                SC.escape(opts.confirmText || '确定') + '</button>' +
                '    </div>' +
                '  </div>' +
                '</div>';
            var done = function (v) { wrap.remove(); document.removeEventListener('keydown', onKey); resolve(v); };
            function onKey(e) { if (e.key === 'Escape') done(false); }
            wrap.addEventListener('click', function (e) {
                var act = e.target.getAttribute && e.target.getAttribute('data-act');
                if (act === 'ok') done(true);
                else if (act === 'cancel' || e.target === wrap) done(false);
            });
            document.addEventListener('keydown', onKey);
            document.body.appendChild(wrap);
            var ok = wrap.querySelector('[data-act="ok"]');
            if (ok) ok.focus();
        });
    };

    /** SC.prompt({title, message, value, placeholder}) -> Promise<string|null> */
    SC.prompt = function (opts) {
        if (typeof opts === 'string') opts = { title: opts };
        opts = opts || {};
        return new Promise(function (resolve) {
            var wrap = document.createElement('div');
            wrap.className = 'sc-modal-backdrop';
            wrap.innerHTML =
                '<div class="sc-modal card shadow-lg">' +
                '  <div class="card-body">' +
                '    <h6 class="card-title mb-2">' + SC.escape(opts.title || '请输入') + '</h6>' +
                (opts.message ? '<p class="text-muted small mb-2">' + SC.escape(opts.message) + '</p>' : '') +
                '    <input type="text" class="form-control form-control-sm mb-3" data-act="input" value="' +
                SC.escape(opts.value || '') + '" placeholder="' + SC.escape(opts.placeholder || '') + '">' +
                '    <div class="d-flex justify-content-end gap-2">' +
                '      <button type="button" class="btn btn-sm btn-outline-secondary" data-act="cancel">取消</button>' +
                '      <button type="button" class="btn btn-sm btn-primary" data-act="ok">确定</button>' +
                '    </div>' +
                '  </div>' +
                '</div>';
            var input = wrap.querySelector('[data-act="input"]');
            var done = function (v) { wrap.remove(); document.removeEventListener('keydown', onKey); resolve(v); };
            function submit() { var v = input.value.trim(); done(v ? v : null); }
            function onKey(e) {
                if (e.key === 'Escape') done(null);
                else if (e.key === 'Enter') submit();
            }
            wrap.addEventListener('click', function (e) {
                var act = e.target.getAttribute && e.target.getAttribute('data-act');
                if (act === 'ok') submit();
                else if (act === 'cancel' || e.target === wrap) done(null);
            });
            document.addEventListener('keydown', onKey);
            document.body.appendChild(wrap);
            input.focus();
            input.select();
        });
    };

    /* ============================ 状态块（加载/空/错误） ============================ */

    SC.skeleton = {
        /** 加载中骨架，rows 行 */
        loading: function (rows) {
            rows = rows || 3;
            var s = '<div class="sc-loading">';
            for (var i = 0; i < rows; i++) s += '<div class="sc-loading-row"></div>';
            return s + '</div>';
        },
        empty: function (text, icon) {
            return '<div class="text-center py-5 text-muted">' +
                '<i class="bi ' + (icon || 'bi-inbox') + '" style="font-size:3rem"></i>' +
                '<p class="mt-3 mb-0">' + SC.escape(text || '暂无数据') + '</p></div>';
        },
        error: function (text) {
            return '<div class="alert alert-danger">' + SC.escape(text || '加载失败') + '</div>';
        }
    };

    /* ============================ Ajax ============================ */

    function buildUrl(url) { return url; }

    async function request(method, url, body, opts) {
        opts = opts || {};
        var init = { method: method, credentials: 'same-origin', headers: {} };
        if (body !== undefined && body !== null) {
            if (body instanceof FormData) {
                init.body = body;                       // 让浏览器自动设置 boundary
            } else if (typeof body === 'string' && opts.form) {
                init.headers['Content-Type'] = 'application/x-www-form-urlencoded';
                init.body = body;
            } else {
                init.headers['Content-Type'] = 'application/json';
                init.body = JSON.stringify(body);
            }
        }
        var res;
        try {
            res = await fetch(buildUrl(url), init);
        } catch (e) {
            if (!opts.silent) SC.toast.error('网络请求失败，请检查服务是否已启动');
            throw e;
        }
        if (!res.ok) {
            var msg = res.status + ' ' + res.statusText;
            try {
                var ct = res.headers.get('content-type') || '';
                if (ct.indexOf('application/json') >= 0) {
                    var j = await res.json();
                    msg = j.message || j.error || j.msg || msg;
                } else {
                    var t = await res.text();
                    if (t) msg = t.length > 300 ? t.substring(0, 300) + '…' : t;
                }
            } catch (ignore) { /* 保留默认 msg */ }
            if (!opts.silent) SC.toast.error(msg);
            var err = new Error(msg);
            err.status = res.status;
            throw err;
        }
        if (res.status === 204) return null;
        var ct2 = res.headers.get('content-type') || '';
        if (ct2.indexOf('application/json') >= 0) return res.json();
        return res.text();
    }

    function withQuery(url, params) {
        if (!params) return url;
        var parts = [];
        Object.keys(params).forEach(function (k) {
            var v = params[k];
            if (v === null || v === undefined || v === '') return;
            parts.push(encodeURIComponent(k) + '=' + encodeURIComponent(v));
        });
        if (!parts.length) return url;
        return url + (url.indexOf('?') >= 0 ? '&' : '?') + parts.join('&');
    }

    /** 对象 -> application/x-www-form-urlencoded 字符串 */
    function toForm(obj) {
        return Object.keys(obj || {}).map(function (k) {
            return encodeURIComponent(k) + '=' + encodeURIComponent(obj[k] === null || obj[k] === undefined ? '' : obj[k]);
        }).join('&');
    }

    SC.api = {
        get: function (url, params, opts) { return request('GET', withQuery(url, params), null, opts); },
        del: function (url, opts) { return request('DELETE', url, null, opts); },
        post: function (url, body, opts) { return request('POST', url, body, opts); },
        put: function (url, body, opts) { return request('PUT', url, body, opts); },
        /** 表单方式 POST（部分老接口用 @RequestParam） */
        postForm: function (url, obj, opts) {
            return request('POST', url, toForm(obj), Object.assign({ form: true }, opts || {}));
        },
        request: request,
        withQuery: withQuery,
        toForm: toForm
    };

    /* ============================ 事件绑定小助手 ============================ */

    /** 事件委托：SC.on(root, 'click', '.btn-del', handler) */
    SC.on = function (root, type, selector, handler) {
        (root || document).addEventListener(type, function (e) {
            var t = e.target.closest(selector);
            if (t && (root || document).contains(t)) handler.call(t, e, t);
        });
    };

})(window);
