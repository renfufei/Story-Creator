// 单词趣味配对 · 首页（词库检索）
// ===========================================================================
// 这一页是小程序的**主页面**，也是「工具 - 信息查询」类目要求的主界面形态：
//   打开即是一个常驻搜索框（不点任何按钮就能输入），而不是直接进配对棋盘。
//
// 两种交互并存、共用同一份结果（同一套匹配口径，见 utils/search.js）：
//   · 输入即出 —— 边敲边在下拉里列最多 5 个单词，点一行直接去看那一关；
//   · 点「搜索」/ 键盘回车 —— 下拉收起，结果落到下方列表，每行带【查看】。
//
// 状态全部放在 data 里（本页没有需要绕开 setData 的高频状态），
// 与 play 页那套「this._s + sync()」的投影模式不同 —— 这里只有输入去抖一个定时器。

var bank = require('../../utils/bank.js');
var search = require('../../utils/search.js');
var ui = require('../../utils/ui.js');

/* 输入去抖：每敲一个字都查一次全库没必要（索引已缓存，但逐次 run 仍要过两万条）。
   140ms 是「肉眼看不出延迟、又不会每键一查」的折中。 */
var DEBOUNCE = 140;

Page({

  data: {
    q: '',            // 输入框里的原文
    stats: '',        // 「26 个词库 · 3452 组 · 20191 词」
    dropOpen: false,  // 自动下拉是否展开
    hits: [],         // 下拉里的命中行
    searched: false,  // 是否已经点过「搜索」（决定下方显示结果还是用法说明）
    resultQ: '',      // 结果对应的关键词
    results: [],      // 结果列表
    resultTip: ''     // 命中的匹配档位说明
  },

  onLoad: function () {
    this._timer = null;
    this._dead = false;

    var t = bank.totals();
    this.setData({
      stats: t.books + ' 个词库 · ' + t.levels + ' 组 · ' + t.words + ' 词'
    });

    /* 预热检索索引：第一次搜索要把 4 个学段的数据模块 require 进来再摊平两万条，
       放在首屏渲染**之后**做，用户第一次敲字时就已经就绪，不会卡一下。
       预热失败也无所谓（真搜索时会重建），所以整段吞异常。 */
    var self = this;
    setTimeout(function () {
      if (self._dead) return;
      try { search.warm(); } catch (e) { /* 预热失败不影响正常搜索 */ }
    }, 0);
  },

  /* 页面卸载后去抖定时器可能还没到点（用户敲字立刻返回上页）。
     置位后回调直接退出，避免对已销毁页面 setData。 */
  onUnload: function () {
    this._dead = true;
    if (this._timer) { clearTimeout(this._timer); this._timer = null; }
  },

  /* 转发给好友。别人点开是**全新状态**：本小程序不联网、进度与错题本按设备存本机，
     分享不携带任何数据。 */
  onShareAppMessage: function () {
    return { title: '单词趣味配对 · 英汉单词查询与配对', path: '/pages/index/index' };
  },

  /* 分享到朋友圈：返回值不能指定 path（进入固定打开当前页），只有 title / query / imageUrl。 */
  onShareTimeline: function () {
    return { title: '单词趣味配对 · 输入单词就能查它在哪个词库' };
  },

  // =========================================================================
  // 输入 / 搜索
  // =========================================================================
  onInput: function (e) {
    var v = (e && e.detail) ? e.detail.value : '';
    /* q 立刻回填（不走去抖）—— 否则「输入框里有字、data.q 还是旧值」，
       紧接着点「搜索」会拿旧词去查。 */
    this.setData({ q: v });

    var self = this;
    if (this._timer) { clearTimeout(this._timer); this._timer = null; }
    this._timer = setTimeout(function () {
      self._timer = null;
      self._refreshDrop(v);
    }, DEBOUNCE);
  },

  /* 自动下拉：输入即出。空输入直接把面板收起来，别留一个空框在那。 */
  _refreshDrop: function (v) {
    if (this._dead) return;
    var kw = String(v == null ? '' : v).trim();
    if (!kw) {
      this.setData({ dropOpen: false, hits: [] });
      return;
    }
    var res = search.run(kw);
    this.setData({ dropOpen: true, hits: res.hits });
  },

  /* 点「搜索」按钮 或 键盘回车。两个入口共用一个 handler：
     bindconfirm 会带 detail.value，bindtap 不会（detail 是坐标），所以带值就以事件为准。 */
  onSearch: function (e) {
    if (this._timer) { clearTimeout(this._timer); this._timer = null; }
    var kw = (e && e.detail && typeof e.detail.value === 'string') ? e.detail.value : this.data.q;
    kw = String(kw == null ? '' : kw).trim();

    if (!kw) {
      this.setData({ q: '', dropOpen: false, hits: [], searched: false, results: [], resultQ: '', resultTip: '' });
      ui.info('先输入要查的单词或中文意思');
      return;
    }

    var res = search.run(kw);
    /* 下拉收起：结果已经落到下方列表，面板再压着搜索结果只会挡住视线 */
    this.setData({
      q: kw,
      dropOpen: false,
      hits: [],
      searched: true,
      resultQ: kw,
      results: res.hits,
      resultTip: search.modeText(res.mode)
    });
  },

  /* 点搜索区以外的地方（页头、结果列表、说明文字）把下拉收起来 —— 等价于网页版
     的 @click.outside。不收的话，下拉会一直压在下方内容上，只有「搜索」和「清除」能关掉它。
     根容器的 bindtap 会收到所有子节点的冒泡，所以这里必须判断「当前确实是开着的」再动手，
     免得每次点结果行都白 setData 一次。 */
  closeDrop: function () {
    if (!this.data.dropOpen) return;
    this.setData({ dropOpen: false, hits: [] });
  },

  /* 给 catchtap 用的空处理器：拦冒泡但什么也不做（搜索区内部用） */
  noop: function () { },

  onClear: function () {
    if (this._timer) { clearTimeout(this._timer); this._timer = null; }
    this.setData({
      q: '', dropOpen: false, hits: [],
      searched: false, results: [], resultQ: '', resultTip: ''
    });
  },

  // =========================================================================
  // 跳转
  // =========================================================================
  onPickDrop: function (e) { this._open(this.data.hits, e.currentTarget.dataset.i); },
  onPickResult: function (e) { this._open(this.data.results, e.currentTarget.dataset.i); },

  /* 用 navigateTo（不是 redirectTo）：本页留在页面栈里，玩法页左上角有返回箭头，
     用户能直接退回搜索结果 —— 「查到了就去看一眼」正是这个用法。 */
  _open: function (list, i) {
    var row = list[i];
    if (!row) return;
    wx.navigateTo({
      url: '/pages/play/play?bookId=' + encodeURIComponent(row.bookId) + '&level=' + row.level,
      fail: function () { ui.info('打开玩法页失败，请重试'); }
    });
  },

  gotoPlay: function () {
    wx.navigateTo({
      url: '/pages/play/play',
      fail: function () { ui.info('打开玩法页失败，请重试'); }
    });
  }
});
