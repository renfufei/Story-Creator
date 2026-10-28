// 单词匹配 · 主页面逻辑
// ===========================================================================
// 移植自 learn-word-match.html 里的 wordMatchApp()（Alpine 组件，约 1180 行 JS）。
//
// 三条移植原则：
//   ① **业务规则一字不改**：切关顺序、两列各自洗牌、避免与上一关排列重复、
//      「判定按释义不按 key」（同义关选哪个中文都算对）、错配只标红后选中那张、
//      自动练习的节奏与跨册衔接 —— 全部按原实现搬。
//   ② **状态只存一份**：所有权威状态放在 this._s，data 由 sync() 一次性投影出来。
//      小程序没有 Alpine 的响应式，随手改 this.data.xxx 极容易漏 setData
//      （表现是「逻辑对了但画面不动」），所以刻意不用那种写法。
//   ③ **换 API 的位置集中在 utils/**：存储、音效、提示、词库访问各一个模块。

var bank = require('../../utils/bank.js');
var store = require('../../utils/store.js');
var ui = require('../../utils/ui.js');
var snd = require('../../utils/sound.js');
/* 读音：只在「音频分包」里的这个页面被 configure（见 _redirectToAudioPack 与导出时追加的那几行）。
   主包里的同名页面不 configure，speech.play() 一律安静返回 —— 所以这个 require 不影响任何现有行为。 */
var speech = require('../../utils/speech.js');

/* 读音注入点。默认 null：主包页点读静默（speech 未 configure）。
   分包页由导出器在文件末尾赋值：
     speechBoot = function () { speech.configure('packages/<id>', __speechIndex); };
   ★ 它被 onLoad 每次调用，而不是在模块顶层直接 configure —— 见 onLoad 里的说明。 */
var speechBoot = null;

/* ---------- 常量 ---------- */
var MAX_WRONG = 300;          // 错题本上限（超出按时间淘汰最旧的）
var OK_FLASH = 500;           // 配对成功后两张卡保持闪绿的时长
var ADVANCE_DELAY = 950;      // 本关完成后自动进下一关的延迟
var FLASH_MS = 900;           // 过关提示停留时长
var WRONG_HOLD = 620;         // 错配标红时长

/* 自动练习节奏。网页版可以用 ?wmStep=&wmThink=&wmGap=&wmBookGap= 覆盖（给自动化测试压缩时间用），
   小程序没有这套 URL 传参习惯，改成常量集中在这里，要调就改这一处。 */
var AUTO_STEP = 1000;         // 每个小步骤间隔
var AUTO_THINK = 2000;        // 配对后「记一记」的思考时间
var AUTO_GAP = 4000;          // 过关后到下一关
var AUTO_BOOK_GAP = 5000;     // 一册练完到下一册
var BOOK_GAP_SEC = Math.max(1, Math.round(AUTO_BOOK_GAP / 1000));

/* 顶部进度条逐关画小格的上限。大学的两个册有 1251 / 942 关，
   逐关画会得到一堆 0.5px 的碎点，既看不出进度又白渲染上千个节点 ——
   超过这个数就换成一条按比例填充的横条。 */
var SEG_MAX = 60;

Page({

  data: {
    /* 册 / 关 */
    bookId: '', bookLabel: '', levelIndex: 0, levelCount: 0, theme: '',
    /* 棋盘 */
    leftCards: [], rightCards: [],
    /* 顶部进度 */
    segMode: true, segments: [], barPct: 0, barDone: false, doneCount: 0,
    /* 文案 / 导航 */
    hintText: '', navNextLabel: '下一关', canPrev: false, canNavNext: false,
    atBookEnd: false, hasNextBook: false,
    /* 自动练习 */
    autoMode: false, autoPaused: false, autoTag: '', autoTip: '', autoCountdown: 0,
    /* 弹层 */
    settingsOpen: false, wrongOpen: false, bookPickerOpen: false, finishedAll: false,
    /* 过关提示 */
    flash: '', flashEmoji: '',
    /* 错题本 / 音效 */
    wrongTotal: 0, wrongList: [], soundOn: true,
    /* 册次弹层 */
    bookGroups: [], totalBooks: 0, totalLevels: 0
  },

  // =========================================================================
  // 生命周期
  // =========================================================================
  onLoad: function (options) {
    /* 读音分包注入点：主包页是 null（点读永远静默），分包页由导出器赋值。
       ★ 必须在这里（每次进页面）调用，不能只在模块顶层 configure 一次 ——
       小程序模块只执行一次，第二次进入同一册的分包页不会再跑顶层代码，
       而那时 speech 的配置可能已被别的册覆盖 ⇒ 点上去没声音。 */
    if (speechBoot) speechBoot();

    /* 这一册有读音 ⇒ 真正的页面在「音频分包」里。
       微信硬规定：主包不能使用分包资源（开发者工具里能播，真机静默无声，官方社区已答复），
       所以读音必须和音频同处一个分包。这里只做一次 redirect，用户无感（首次多等分包下载）。
       换不过去（分包还没上传完 / 存储异常）就原地跑纯配对，玩法不受影响。 */
    if (this._redirectToAudioPack(options)) return;
    /* 走到这里说明本页没有读音能力（主包页）。把上一个分包页残留的册配置清掉，
       免得拿别的册的索引去查这一册的词。分包页不会走到这里（它 route 有册号）。 */
    if (!this._routeBook()) speech.reset();
    this._boot(options);
    /* 跨册换页时把「自动练习」带过来：redirect 会销毁旧页面，只能靠参数重启。 */
    if (options && String(options.auto) === '1') this.startAuto();
  },

  /* 原来的 onLoad 主体，一字未改，只是搬进 _boot —— 换页失败时还要能原地启动。 */
  _boot: function (options) {
    // 权威状态（data 只是它在视图上的投影）
    this._s = {
      bookId: '', levelIndex: 0, level: null,
      left: [], right: [],          // 卡片对象：{uid, side, key, text, mean, done, wrong, ok}
      selUid: null,
      locked: false, matched: 0, levelWrong: 0, lastResult: null,
      auto: { mode: false, paused: false, tag: '', tip: '', countdown: 0 },
      flash: '', flashEmoji: '',
      pickerOpen: false, wrongOpen: false, settingsOpen: false, finishedAll: false,
      progress: {}, autoProgress: {}, wrongBook: {},
      soundOn: true,
      stageOpen: {}, stageInit: false
    };

    /* 词库指纹变了就把旧进度作废（关卡被重新切分过，旧的「第 N 关已完成」已不对应新关卡）。
       错题本存的是单词本身，不受影响，故不清。 */
    store.ensureDataVersion(bank.signature());

    var s = this._s;
    s.progress = store.readMap(store.KEYS.progress);
    s.autoProgress = store.readMap(store.KEYS.autoProgress);
    s.wrongBook = store.readMap(store.KEYS.wrong);
    s.soundOn = store.readStr(store.KEYS.sound, '1') !== '0';

    /* 起始册：优先上次看的那一册，取不到就退回第一册 */
    var books = bank.books();
    var saved = store.readStr(store.KEYS.book, '');
    var found = false;
    for (var i = 0; i < books.length; i++) {
      if (books[i].id === saved) { found = true; break; }
    }
    var bookId = found ? saved : (books.length ? books[0].id : '');

    var totals = bank.totals();
    this.setData({ totalBooks: totals.books, totalLevels: totals.levels });

    this._buildSeq = 0;
    this._lastLayout = {};
    this._autoSeq = 0;
    this._autoStage = '';
    this._advanceTimer = null;
    this._flashTimer = null;
    this._wrongTimer = null;
    this._autoTimer = null;
    this._destroyed = false;

    /* 入口参数：从首页检索结果点【查看】进来时会带 ?bookId=..&level=..（level 是**下标**），
       这时要直达那一关，而不是沿用「回到上次那册的第一个未完成关」。
       没有参数（从首页入口正常进来）时行为与之前**完全一致**。 */
    var jump = this._parseJump(options);
    if (jump) this._applyBook(jump.bookId, false, jump.level);
    else this._applyBook(bookId, true);
  },

  /* 解析入口参数。bookId 必须真实存在——分享出去的链接可能被手改，
     或者词库改版后旧 id 消失了，这时宁可按默认册打开，也不要白屏。
     level 容错：非数字 / 负数一律当作「该册第 1 关」（-1 走的就是这条）。
     返回 null 表示「不是跳转进来的」，调用方保持原来的恢复逻辑。 */
  _parseJump: function (options) {
    if (!options || !options.bookId) return null;
    var id = String(options.bookId);
    if (!bank.book(id)) return null;
    var n = parseInt(options.level, 10);
    return { bookId: id, level: isFinite(n) ? n : -1 };
  },

  /* 当前页是不是「音频分包里的那一份」。分包页的 route 形如 packages/pep-3-1/play/play，
     主包页是 pages/play/play —— 只看前缀就能分辨，不依赖任何入口参数。 */
  _isAudioPackPage: function () {
    return !!this._routeBook();
  },

  /* 当前页所在分包是哪一册（'' = 主包页）。换册时靠它判断「要不要换页」。 */
  _routeBook: function () {
    var pages = getCurrentPages();
    var top = (pages && pages.length) ? pages[pages.length - 1] : null;
    var route = (top && top.route) || '';
    var m = /^packages\/([^/]+)\//.exec(route);
    return m ? m[1] : '';
  },

  /* 该册有没有读音：books.js 的 hasAudio 由导出器按「这册有没有 sprite.m4a」现算。 */
  _hasAudio: function (bookId) {
    var b = bookId ? bank.book(bookId) : null;
    return !!(b && b.hasAudio);
  },

  /* 目标册：与 _boot 里「起始册」的推断口径完全一致（入口参数 → 上次那册 → 第一册）。 */
  _targetBook: function (options) {
    var jump = this._parseJump(options);
    if (jump) return jump.bookId;
    var books = bank.books();
    var saved = store.readStr(store.KEYS.book, '');
    for (var i = 0; i < books.length; i++) {
      if (books[i].id === saved) return saved;
    }
    return books.length ? books[0].id : '';
  },

  /* 换到目标册的读音分包页。返回 true = 已经换走，调用方**不要再本地换册**
     （本地换册只换册号，音频与索引还是旧册的 ⇒ 点上去没声音；一个页面只能访问
     自己所在分包的资源，所以换册必须换页）。
     atLevel：下标，-1 = 该册第一个未完成关；withAuto：跨册带着自动练习过去。 */
  _goAudioPack: function (target, atLevel, withAuto, onFail) {
    if (!target || !this._hasAudio(target)) return false;
    if (this._routeBook() === target) return false;      // 已经在这一册的分包页里

    var url = '/packages/' + target + '/play/play?bookId=' + encodeURIComponent(target);
    var lv = (atLevel === undefined || atLevel === null) ? -1 : atLevel;
    if (lv >= 0) url += '&level=' + lv;
    if (withAuto) url += '&auto=1';

    var fail = onFail || function () { };
    /* 跳页前断声：redirect 是异步的，旧页的 onUnload 要等新页渲染完才会执行，
       中间那几百毫秒里正在念的词会一直响到新页上（听感就是「残音」）。 */
    speech.stop();
    wx.redirectTo({ url: url, fail: fail });
    return true;
  },

  /* 启动时：有读音就换到分包里的同名页。返回 true 表示「已经换走，onLoad 不要继续初始化」。 */
  _redirectToAudioPack: function (options) {
    /* 分包页以 route 上的册为准：入口参数丢了也不会被 store 带偏到别的册去。 */
    var target = this._routeBook() || this._targetBook(options);
    var jump = this._parseJump(options);
    var self = this;
    return this._goAudioPack(target, jump ? jump.level : -1, false, function () {
      /* 分包下载失败等情况：原地继续跑纯配对（没有读音，但玩法完整）。
         redirectTo 已经返回 true 了，所以启动流程要在这里补上。 */
      if (!self._s) self._boot(options);
    });
  },

  // 切后台/退出时停掉自动练习：否则回到前台会发现它「自己跑了一截」，
  // 而且定时器在后台会与用户重新进入后的操作交叉。
  onHide: function () {
    if (this._s && this._s.auto.mode) this._stopAuto(true);
    /* 切后台立刻停读：否则用户在微信里刷了别的页面，耳朵里还在念上一个单词。 */
    speech.stop();
  },

  onUnload: function () {
    /* 置位后 sync() 直接返回。_flashOk 里的 500ms 定时器是**未登记**的裸 setTimeout
       （语义上不该被 clearTimeout 打断，否则绿闪会被截断），页面卸载后它仍会到点触发；
       有这道守卫就不会出现「对已销毁页面 setData」的控制台告警。 */
    this._destroyed = true;
    var s = this._s;
    if (s && s.auto.mode) this._stopAuto(true);
    if (this._advanceTimer) { clearTimeout(this._advanceTimer); this._advanceTimer = null; }
    if (this._flashTimer) { clearTimeout(this._flashTimer); this._flashTimer = null; }
    if (this._wrongTimer) { clearTimeout(this._wrongTimer); this._wrongTimer = null; }
    if (this._autoTimer) { clearTimeout(this._autoTimer); this._autoTimer = null; }
    /* 释放音频实例：destroy 之后不能再用，所以只在页面真正卸载时做（切后台走 onHide 的 stop）。
       带上「本页管的是哪一册」——**不能用 _routeBook**：卸载时路由栈顶可能已经是新页了。
       换册时新旧页的生命周期先后并不保证，若配置已被新页接管，旧页就不能销毁它的实例，
       否则新页刚点下去念半句就没声了。 */
    speech.release((s && s.bookId) || '');
  },

  /* 转发给微信好友：卡片标题带上当前在练的册次，点开落在玩法页。
     别人点开是**全新进度** —— 进度与错题本按设备存在各自本机（单机玩法天然如此），
     不随分享走，也不会看到分享者的进度。 */
  onShareAppMessage: function () {
    var label = this.data.bookLabel || '单词趣味配对';
    return {
      title: '单词趣味配对 · 我在练「' + label + '」',
      path: '/pages/play/play'
    };
  },

  /* 分享到朋友圈。注意 onShareTimeline 的返回值**不能指定 path**
     （朋友圈进入固定打开当前页），只有 title / query / imageUrl 三个字段。 */
  onShareTimeline: function () {
    var label = this.data.bookLabel || '单词趣味配对';
    return {
      title: '单词趣味配对 · 练「' + label + '」'
    };
  },

  // =========================================================================
  // 状态投影：从 this._s 算出视图要的一切，一次性交给 setData
  // =========================================================================
  sync: function () {
    var s = this._s;
    if (!s || this._destroyed) return;
    var book = bank.book(s.bookId);
    var lc = bank.levelCount(s.bookId);
    var lv = s.level;
    var done = s.progress[s.bookId] || [];
    var patch = {};

    /* ---- 卡片：class 在这里算好，WXML 只负责贴上去 ---- */
    patch.leftCards = this._cardsView(s.left);
    patch.rightCards = this._cardsView(s.right);

    /* ---- 册 / 关 ---- */
    patch.bookId = s.bookId;
    patch.bookLabel = book ? book.label : '';
    patch.levelIndex = s.levelIndex;
    patch.levelCount = lc;
    patch.theme = lv ? lv.theme : '';
    patch.doneCount = done.length;

    /* ---- 顶部进度 ---- */
    var segMode = lc > 0 && lc <= SEG_MAX;
    patch.segMode = segMode;
    if (segMode) {
      var segs = [];
      for (var i = 0; i < lc; i++) {
        segs.push({
          i: i,
          cls: done.indexOf(i) >= 0 ? 'is-done' : (i === s.levelIndex ? 'is-current' : '')
        });
      }
      patch.segments = segs;
    } else {
      patch.barPct = lc ? Math.round((s.levelIndex + 1) * 100 / lc) : 0;
      patch.barDone = done.indexOf(s.levelIndex) >= 0;
    }

    /* ---- 文案 / 导航 ---- */
    var autoOn = s.auto.mode;
    var atBookEnd = !autoOn && lc > 0 && s.levelIndex >= lc - 1;
    var nextBook = this._nextBook();
    var lastLevelDone = atBookEnd && done.indexOf(s.levelIndex) >= 0;
    patch.hintText = this._hint();
    patch.canPrev = !autoOn && s.levelIndex > 0;
    patch.atBookEnd = atBookEnd;
    patch.hasNextBook = !!nextBook;
    patch.canNavNext = atBookEnd
      ? (lastLevelDone && !!nextBook)
      : (!autoOn && s.levelIndex < lc - 1);
    patch.navNextLabel = !atBookEnd ? '下一关' : (nextBook ? '下一册' : '最后一册');

    /* ---- 自动练习 ---- */
    patch.autoMode = autoOn;
    patch.autoPaused = s.auto.paused;
    patch.autoTag = s.auto.tag;
    patch.autoTip = s.auto.tip;
    patch.autoCountdown = s.auto.countdown;

    /* ---- 弹层 / 提示 ---- */
    patch.settingsOpen = s.settingsOpen;
    patch.wrongOpen = s.wrongOpen;
    patch.bookPickerOpen = s.pickerOpen;
    patch.finishedAll = s.finishedAll;
    patch.flash = s.flash;
    patch.flashEmoji = s.flashEmoji;

    /* ---- 错题本 / 音效 ---- */
    patch.wrongTotal = Object.keys(s.wrongBook).length;
    patch.soundOn = s.soundOn;

    /* ---- 只在真需要时才重算的开销较大的部分 ---- */
    if (s.pickerOpen) patch.bookGroups = this._groupsView();
    if (s.wrongOpen) patch.wrongList = this._wrongListView();

    this.setData(patch);
  },

  /* 卡片视图：cls 里的顺序有意义 —— 必须让 is-ok 排在 is-done 之后，
     因为 .wm-card.is-done 与 .wm-card.is-ok 权重相同 (0,2,0)，只靠 CSS 源码顺序胜出
     （见 play.wxss 的说明）。 */
  _cardsView: function (arr) {
    var s = this._s;
    var out = [];
    for (var i = 0; i < arr.length; i++) {
      var c = arr[i];
      var cls = '';
      if (s.selUid && s.selUid === c.uid) cls += ' is-sel';
      if (c.done) cls += ' is-done';
      if (c.ok) cls += ' is-ok';
      if (c.wrong) cls += ' is-wrong';
      out.push({ uid: c.uid, text: c.text, cls: cls });
    }
    return out;
  },

  _hint: function () {
    var s = this._s;
    if (s.auto.mode) return '自动练习中 · 可随时暂停或退出';
    if (!s.selUid) return '先点一个英文单词，再点它对应的中文';
    var c = this._findCard(s.selUid);
    if (!c) return '';
    if (c.side === 'L') return '已选中「' + c.text + '」，请在右侧找到它的中文';
    return '已选中「' + c.text + '」，请在左侧找到它的英文';
  },

  _findCard: function (uid) {
    var s = this._s;
    for (var i = 0; i < s.left.length; i++) if (s.left[i].uid === uid) return s.left[i];
    for (var j = 0; j < s.right.length; j++) if (s.right[j].uid === uid) return s.right[j];
    return null;
  },

  _nextBook: function () {
    var books = bank.books();
    var id = this._s.bookId;
    for (var i = 0; i < books.length; i++) {
      if (books[i].id === id) return (i < books.length - 1) ? books[i + 1] : null;
    }
    return null;
  },

  _groupsView: function () {
    var s = this._s;
    var gs = bank.groups();
    var out = [];
    for (var i = 0; i < gs.length; i++) {
      var g = gs[i];
      var books = [];
      for (var j = 0; j < g.books.length; j++) {
        var b = g.books[j];
        books.push({
          id: b.id,
          label: b.label,
          levelCount: b.levelCount,
          wordCount: b.wordCount,
          doneP: (s.progress[b.id] || []).length,
          doneA: (s.autoProgress[b.id] || []).length,
          isActive: b.id === s.bookId
        });
      }
      out.push({
        stage: g.stage,
        books: books,
        levelCount: g.levelCount,
        wordCount: g.wordCount,
        isOpen: !!s.stageOpen[g.stage],
        hasCurrent: !!s.bookId && bank.stageOf(s.bookId) === g.stage
      });
    }
    return out;
  },

  _wrongListView: function () {
    var book = this._s.wrongBook;
    var out = [];
    for (var k in book) {
      if (Object.prototype.hasOwnProperty.call(book, k)) out.push(book[k]);
    }
    out.sort(function (a, b) { return (b.ts || 0) - (a.ts || 0); });
    return out;
  },

  // 给 catchtap 用的空处理器：阻止点击穿透到遮罩层（等价于网页版的 @click.self）
  noop: function () { },

  // =========================================================================
  // 册 / 关
  // =========================================================================
  /* atLevel：**从检索结果进来的指定关**（下标）。给了它就直达那一关，
     不再走「回到第一个未完成关」那套 —— 用「查一下它在哪一关」进来的用户
     要的就是那一关本身，哪怕他早练过了。不传（undefined）或传 -1 时行为不变。 */
  _applyBook: function (bookId, restore, atLevel) {
    var s = this._s;
    if (s.auto.mode) this._stopAuto(true);
    s.bookId = bookId;
    store.writeStr(store.KEYS.book, bookId);
    s.finishedAll = false;

    var lc = bank.levelCount(bookId);
    var done = s.progress[bookId] || [];
    var start = 0;
    if (atLevel >= 0) {
      // 越界的下标按第 1 关处理，别让 levelAt 拿到 null 后整页空白
      start = (atLevel < lc) ? atLevel : 0;
    } else if (restore && done.length) {
      // 优先回到第一个未完成的关卡；全部完成则从头再来（可自由重玩）
      var firstUndone = -1;
      for (var i = 0; i < lc; i++) {
        if (done.indexOf(i) < 0) { firstUndone = i; break; }
      }
      start = firstUndone < 0 ? 0 : firstUndone;
    }
    s.levelIndex = Math.min(start, Math.max(0, lc - 1));
    this._loadLevel();
  },

  _loadLevel: function () {
    var s = this._s;
    if (this._advanceTimer) { clearTimeout(this._advanceTimer); this._advanceTimer = null; }
    var lv = bank.levelAt(s.bookId, s.levelIndex);
    s.level = lv;
    s.selUid = null;
    s.locked = false;
    s.matched = 0;
    s.levelWrong = 0;
    s.finishedAll = false;
    if (!lv) {
      s.left = [];
      s.right = [];
      this.sync();
      return;
    }
    this._buildCards(lv);
    this.sync();
  },

  /* 两列各自独立洗牌：① 不出现「整片一一对应」② 同一关再次进入时排列要变 */
  _buildCards: function (lv) {
    var s = this._s;
    var n = lv.pairs.length;
    /* uid 每次构建都必须不同：wx:for 靠 wx:key 复用节点时不会刷新作用域里的对象，
       沿用旧 uid 会让「上一关/下一关/重做本关/退出自动练习」之后卡片残留旧的选中样式 */
    this._buildSeq++;
    var seq = this._buildSeq;
    var enBase = [], zhBase = [];
    for (var i = 0; i < n; i++) {
      var p = lv.pairs[i];
      /* mean = 对面的释义：判定只看释义。同一关里若两个词释义相同（同义表达关），
         选哪个中文都算对 —— 两张中文卡本来就长得一模一样，用户无从区分也不该被为难。 */
      enBase.push({ uid: 'L' + i + '#' + seq, side: 'L', key: p.key, text: p.en, mean: p.zh, done: false, wrong: false, ok: false });
      zhBase.push({ uid: 'R' + i + '#' + seq, side: 'R', key: p.key, text: p.zh, mean: p.en, done: false, wrong: false, ok: false });
    }

    var sig = s.bookId + '#' + s.levelIndex;
    var lastSig = this._lastLayout[sig] || '';
    /* 序列配对册（stage=专题）锁左列：数字 one→ten、星期一到日、一月到十二月，
       英文一乱就没法「按序列背」了 —— 只打乱右列中文。
       其余册（含课内词库）依旧两列都洗。防对齐循环不用改：它比的是 pair.key，
       左列固定时只是右列在重试。 */
    var lockLeft = bank.stageOf(s.bookId) === '专题';
    var left = lockLeft ? enBase.slice() : this._shuffle(enBase.slice());
    var right = this._shuffle(zhBase.slice());
    for (var att = 0; att < 60; att++) {
      var l = lockLeft ? enBase.slice() : this._shuffle(enBase.slice());
      var r = this._shuffle(zhBase.slice());
      var aligned = this._countAligned(l, r);
      var repeat = (att < 40) && lastSig && this._layoutSig(l, r) === lastSig;
      if (aligned <= 1 && !repeat) { left = l; right = r; break; }
      if (att === 59) { left = l; right = r; }
    }
    this._lastLayout[sig] = this._layoutSig(left, right);
    s.left = left;
    s.right = right;
  },

  _countAligned: function (left, right) {
    var n = 0;
    var len = Math.min(left.length, right.length);
    for (var i = 0; i < len; i++) if (left[i].key === right[i].key) n++;
    return n;
  },

  _layoutSig: function (left, right) {
    var a = [], b = [];
    var i;
    for (i = 0; i < left.length; i++) a.push(left[i].key);
    for (i = 0; i < right.length; i++) b.push(right[i].key);
    return a.join(',') + '|' + b.join(',');
  },

  _shuffle: function (arr) {
    for (var i = arr.length - 1; i > 0; i--) {
      var j = Math.floor(Math.random() * (i + 1));
      var t = arr[i]; arr[i] = arr[j]; arr[j] = t;
    }
    return arr;
  },

  /* 「换册」统一入口：目标册有读音就换到它的分包页（同一个页面里只换册号是没有声音的
     —— 页面只能访问自己所在分包的资源），没有读音才本地换。
     返回 true = 已经换页，调用方不要再继续改本页状态。 */
  _switchBook: function (bookId, restore, atLevel) {
    var lv = restore ? -1 : ((atLevel === undefined || atLevel === null) ? -1 : atLevel);
    /* 换到有读音的册 ⇒ 换页（_goAudioPack 里会断声）；
       换到没有读音的册 ⇒ 本地换册，这里也要断声，不然旧册的词会跟着念下去。 */
    if (this._goAudioPack(bookId, lv, false)) return true;
    speech.stop();
    this._applyBook(bookId, restore, atLevel);
    return false;
  },

  nextLevel: function () {
    var s = this._s;
    if (s.auto.mode) return;
    if (s.levelIndex < bank.levelCount(s.bookId) - 1) {
      s.levelIndex++;
      this._loadLevel();
    }
  },

  prevLevel: function () {
    var s = this._s;
    if (s.auto.mode) return;
    if (!this.data.canPrev) return;
    if (s.levelIndex > 0) {
      s.levelIndex--;
      this._loadLevel();
    }
  },

  /* 导航条右侧按钮（本关 → 下一关；本册最后一关 → 下一册）
     网页版这里还能用 → 方向键触发，手机没有键盘，只留按钮。 */
  navNext: function () {
    var s = this._s;
    if (s.auto.mode) return;
    if (!this.data.canNavNext) return;
    if (this.data.atBookEnd) { this.gotoNextBook(); return; }
    this.nextLevel();
  },

  // =========================================================================
  // 交互
  // =========================================================================
  onTapLeft: function (e) { this._pick('L', e.currentTarget.dataset.i, false); },
  onTapRight: function (e) { this._pick('R', e.currentTarget.dataset.i, false); },

  _pick: function (side, idx, auto) {
    var s = this._s;
    if (s.locked) return;
    var arr = (side === 'L') ? s.left : s.right;
    var card = arr[idx];
    if (!card || card.done) return;
    if (s.auto.mode && !auto) return;      // 自动练习中不接受手动点击

    // 再次点击同一张 → 取消选中
    if (s.selUid && s.selUid === card.uid) {
      s.selUid = null;
      this.sync();
      return;
    }

    // 还没有选中 → 选中它
    if (!s.selUid) {
      s.selUid = card.uid;
      if (s.soundOn) snd.tap();
      /* 点英文卡即读（中文卡不读：读音库里只有英文）。
         没有读音的册、以及主包里的同名页面，speech 未 configure ⇒ play() 直接返回 false。
         包 try 是因为读音再出错也不能打断配对。 */
      if (side === 'L' && s.soundOn) {
        try { speech.play(card.text, s.bookId); } catch (e) { /* 忽略：看点读失败不影响玩法 */ }
      }
      this.sync();
      return;
    }

    var first = this._findCard(s.selUid);
    if (!first) {
      s.selUid = card.uid;
      this.sync();
      return;
    }

    // 同侧改选：替换，不判错
    if (first.side === card.side) {
      s.selUid = card.uid;
      /* 改选英文卡也要读：用户在左列连点几个词，本来就想逐个听听区别。 */
      if (side === 'L' && s.soundOn) {
        try { speech.play(card.text, s.bookId); } catch (e) { /* 忽略：点读失败不影响玩法 */ }
      }
      this.sync();
      return;
    }

    // 异侧：判定。card 是「后选中」的那张
    this._judge(first, card);
  },

  /* 左右两张卡是否同义：右侧中文 == 左侧词的释义
     （同侧比较退回 key，只用于布局去重）
     注意这是**严格相等**，没有分词容错 —— 所以词库里同一个同义组必须共用完全一样的中文。 */
  _sameMeaning: function (a, b) {
    if (!a || !b) return false;
    if (a.side === b.side) return a.key === b.key;
    var l = a.side === 'L' ? a : b;
    var r = a.side === 'L' ? b : a;
    return l.mean === r.text;
  },

  _judge: function (first, second) {
    var s = this._s;

    if (this._sameMeaning(first, second)) {
      first.done = true;
      second.done = true;
      s.selUid = null;
      s.matched++;
      if (s.soundOn) snd.chime(true);
      /* 配对成功的反馈：两张卡一起变浅绿并闪两下（约 .5s），到点摘掉标记，
         由 .is-done 的灰接管。done 仍是**立刻**置上的 —— 进度/计数/能否再点
         完全不受动画影响，动画只是叠在灰底之上的一层临时观感。 */
      this._flashOk(first, second);
      if (s.matched >= s.level.pairs.length) {
        this._completeLevel();
      } else {
        this.sync();
      }
      return;
    }

    // 不匹配：**后选中**的那张标红，先选中的保留，方便直接换一个候选者
    s.levelWrong++;
    this._recordWrong(first, second);
    s.locked = true;
    second.wrong = true;
    if (s.soundOn) snd.chime(false);
    this.sync();

    var self = this;
    if (this._wrongTimer) clearTimeout(this._wrongTimer);
    this._wrongTimer = setTimeout(function () {
      self._wrongTimer = null;
      second.wrong = false;
      self._s.locked = false;
      self.sync();
    }, WRONG_HOLD);
  },

  _flashOk: function (a, b) {
    a.ok = true;
    b.ok = true;
    var self = this;
    setTimeout(function () {
      // 卡片是每关重建的新对象，晚到的定时器即使命中旧对象也无害
      a.ok = false;
      b.ok = false;
      self.sync();
    }, OK_FLASH);
  },

  _completeLevel: function () {
    var s = this._s;
    var perfect = s.levelWrong === 0;
    s.lastResult = perfect;

    if (s.auto.mode) {
      // 自动练习进度独立记录，绝不写手动练习进度
      this._markAutoDone(s.levelIndex);
      this.sync();
      return;
    }
    this._markDone(s.levelIndex);
    if (s.soundOn) snd.levelUp();

    var lc = bank.levelCount(s.bookId);
    if (s.levelIndex >= lc - 1) {
      s.finishedAll = true;
      this._showFlash(perfect ? '🎉' : '👍', perfect ? '全对！本册完成' : '本册完成！');
      return;
    }

    var self = this;
    if (this._advanceTimer) clearTimeout(this._advanceTimer);
    this._advanceTimer = setTimeout(function () {
      self._advanceTimer = null;
      if (!self._s.finishedAll) self.nextLevel();
    }, ADVANCE_DELAY);
    this._showFlash(perfect ? '⭐️' : '👍', perfect ? '全对！进入下一关' : '完成！进入下一关');
  },

  _showFlash: function (emoji, text, ms) {
    var s = this._s;
    s.flashEmoji = emoji;
    s.flash = text;
    var self = this;
    if (this._flashTimer) clearTimeout(this._flashTimer);
    this._flashTimer = setTimeout(function () {
      self._flashTimer = null;
      self._s.flash = '';
      self._s.flashEmoji = '';
      self.sync();
    }, ms || FLASH_MS);
    this.sync();
  },

  // =========================================================================
  // 进度
  // =========================================================================
  _markDone: function (index) {
    var s = this._s;
    var done = s.progress[s.bookId] || (s.progress[s.bookId] = []);
    if (done.indexOf(index) < 0) done.push(index);
    store.writeMap(store.KEYS.progress, s.progress);
  },

  _markAutoDone: function (index) {
    var s = this._s;
    var done = s.autoProgress[s.bookId] || (s.autoProgress[s.bookId] = []);
    if (done.indexOf(index) < 0) done.push(index);
    store.writeMap(store.KEYS.autoProgress, s.autoProgress);
  },

  replayBook: function () {
    var s = this._s;
    s.finishedAll = false;
    s.progress[s.bookId] = [];
    store.writeMap(store.KEYS.progress, s.progress);
    s.levelIndex = 0;
    this._loadLevel();
  },

  gotoNextBook: function () {
    var nb = this._nextBook();
    if (nb) this._switchBook(nb.id, false);
  },

  closeFinish: function () {
    this._s.finishedAll = false;
    this.sync();
  },

  // =========================================================================
  // 错题本
  // =========================================================================
  _recordWrong: function (first, second) {
    var s = this._s;
    var enCard = first.side === 'L' ? first : second;
    var zhCard = first.side === 'L' ? second : first;
    if (!enCard || enCard.side !== 'L' || !zhCard || zhCard.side !== 'R') return;

    var lv = s.level;
    var correctZh = '';
    if (lv) {
      for (var i = 0; i < lv.pairs.length; i++) {
        if (lv.pairs[i].key === enCard.key) { correctZh = lv.pairs[i].zh; break; }
      }
    }
    var old = s.wrongBook[enCard.key];
    var next = {};
    for (var k in s.wrongBook) {
      if (Object.prototype.hasOwnProperty.call(s.wrongBook, k)) next[k] = s.wrongBook[k];
    }
    var book = bank.book(s.bookId);
    next[enCard.key] = {
      key: enCard.key,
      en: enCard.text,
      zh: correctZh || (old ? old.zh : ''),
      miss: zhCard.text,
      bookId: s.bookId,
      bookLabel: book ? book.label : '',
      theme: lv ? lv.theme : '',
      level: s.levelIndex,
      count: (old ? old.count : 0) + 1,
      ts: Date.now()
    };
    s.wrongBook = this._trimWrong(next);
    store.writeMap(store.KEYS.wrong, s.wrongBook);
  },

  _trimWrong: function (obj) {
    var keys = Object.keys(obj);
    if (keys.length <= MAX_WRONG) return obj;
    keys.sort(function (a, b) { return (obj[b].ts || 0) - (obj[a].ts || 0); });
    var out = {};
    for (var i = 0; i < MAX_WRONG; i++) out[keys[i]] = obj[keys[i]];
    return out;
  },

  openWrongBookFromSettings: function () {
    var s = this._s;
    s.settingsOpen = false;
    s.wrongOpen = true;
    this.sync();
  },

  closeWrongBook: function () {
    this._s.wrongOpen = false;
    this.sync();
  },

  removeWrong: function (e) {
    var key = e.currentTarget.dataset.key;
    var s = this._s;
    var next = {};
    for (var k in s.wrongBook) {
      if (Object.prototype.hasOwnProperty.call(s.wrongBook, k) && k !== key) next[k] = s.wrongBook[k];
    }
    s.wrongBook = next;
    store.writeMap(store.KEYS.wrong, s.wrongBook);
    this.sync();
  },

  clearWrong: function () {
    var s = this._s;
    if (!this.data.wrongTotal) return;
    var self = this;
    ui.confirm({
      title: '清空错题本',
      message: '将清空错题本里的 ' + this.data.wrongTotal + ' 个单词，确定吗？',
      confirmText: '清空',
      danger: true
    }).then(function (ok) {
      if (!ok) return;
      self._s.wrongBook = {};
      store.writeMap(store.KEYS.wrong, {});
      self.sync();
      ui.success('错题本已清空');
    });
  },

  gotoWrong: function (e) {
    var key = e.currentTarget.dataset.key;
    var s = this._s;
    var w = s.wrongBook[key];
    if (!w) return;
    s.wrongOpen = false;
    if (s.auto.mode) this._stopAuto(true);
    if (w.bookId !== s.bookId) {
      /* 有读音的册要换页，并把错题所在那关一起带过去（否则换过去还得自己找关）。 */
      var lvOk = (w.level >= 0 && w.level < bank.levelCount(w.bookId)) ? w.level : -1;
      this._switchBook(w.bookId, false, lvOk);
    }
    if (w.level >= 0 && w.level < bank.levelCount(w.bookId)) {
      s.levelIndex = w.level;
      this._loadLevel();
    } else {
      this.sync();
    }
  },

  // =========================================================================
  // 设置面板
  // =========================================================================
  openSettings: function () {
    this._s.settingsOpen = true;
    // 顺便预热音频上下文：用户点「音效」或下一张卡片时就能立刻出声，
    // 不用等第一次合成的启动开销。
    if (this._s.soundOn) snd.resume();
    this.sync();
  },

  closeSettings: function () {
    this._s.settingsOpen = false;
    this.sync();
  },

  restartLevelFromSettings: function () {
    this._s.settingsOpen = false;
    this._loadLevel();
  },

  resetProgressFromSettings: function () {
    this._s.settingsOpen = false;
    this.resetProgress();
  },

  toggleSound: function () {
    var s = this._s;
    s.soundOn = !s.soundOn;
    store.writeStr(store.KEYS.sound, s.soundOn ? '1' : '0');
    if (s.soundOn) snd.tap();
    this.sync();
  },

  resetProgress: function () {
    var s = this._s;
    var kind = s.auto.mode ? '自动练习' : '手动练习';
    var book = bank.book(s.bookId);
    var label = book ? book.label : '';
    var self = this;
    ui.confirm({
      title: '重置进度',
      message: '将清空「' + label + '」的' + kind + '进度记录，确定吗？',
      confirmText: '重置',
      danger: true
    }).then(function (ok) {
      if (!ok) return;
      var st = self._s;
      if (st.auto.mode) {
        st.autoProgress[st.bookId] = [];
        store.writeMap(store.KEYS.autoProgress, st.autoProgress);
      } else {
        st.progress[st.bookId] = [];
        store.writeMap(store.KEYS.progress, st.progress);
      }
      if (self._advanceTimer) { clearTimeout(self._advanceTimer); self._advanceTimer = null; }
      st.levelIndex = 0;
      self._loadLevel();
      ui.success('已重置「' + label + '」的' + kind + '进度');
    });
  },

  // =========================================================================
  // 册次弹层
  // =========================================================================
  openBookPicker: function () {
    var s = this._s;
    if (!s.stageInit) {
      // 首次打开：只展开当前册所在学段，其余（例如学小学时的初中 / 高中）全部折叠
      this._openStageOnly(bank.stageOf(s.bookId));
      s.stageInit = true;
    }
    s.pickerOpen = true;
    this.sync();
  },

  closeBookPicker: function () {
    this._s.pickerOpen = false;
    this.sync();
  },

  /* 手风琴式：同时只展开一个学段 —— 这样「小学 / 初中 / 高中 / 大学」四个标题
     永远都在可视区内，展开出来的册次列表自己内部滚动。
     （多开会把后面的学段标题顶出滚动区，用户就看不到还有别的学段。） */
  _openStageOnly: function (stage) {
    var gs = bank.groups();
    var open = {};
    for (var i = 0; i < gs.length; i++) open[gs[i].stage] = (gs[i].stage === stage);
    this._s.stageOpen = open;
  },

  toggleStage: function (e) {
    var stage = e.currentTarget.dataset.stage;
    var s = this._s;
    this._openStageOnly(s.stageOpen[stage] ? null : stage);
    this.sync();
  },

  pickBook: function (e) {
    var id = e.currentTarget.dataset.id;
    var s = this._s;
    s.pickerOpen = false;
    if (id !== s.bookId) {
      /* 换到有读音的册 ⇒ 换页（否则音频与索引还停在旧册，点上去没声音）。 */
      if (this._switchBook(id, true)) return;
    }
    // 换册后，下次打开弹层时新册所在学段必须是展开的
    this._openStageOnly(bank.stageOf(id));
    this.sync();
  },

  // =========================================================================
  // 自动练习
  // =========================================================================
  onAutoToggle: function () {
    if (this._s.auto.mode) this._stopAuto(false);
    else this.startAuto();
  },

  stopAutoTap: function () { this._stopAuto(false); },

  startAuto: function () {
    var s = this._s;
    if (s.auto.mode) return;
    s.auto.mode = true;
    s.auto.paused = false;
    s.finishedAll = false;
    s.levelIndex = 0;               // 每次开启都从第 1 关开始
    this._loadLevel();
    this._autoSeq++;
    var token = this._autoSeq;
    this._autoStage = 'start';
    this._setAutoTip('开始练习', '先看英文，再找中文');
    s.auto.countdown = 0;
    this.sync();

    var self = this;
    this._autoLoop(token).catch(function () { self._stopAuto(true); });
  },

  /* 状态条文案：短标签固定可见 + 详情（单词等）过长时省略号截断，状态条永远只有一行 */
  _setAutoTip: function (tag, tip) {
    var s = this._s;
    s.auto.tag = tag;
    s.auto.tip = tip;
    this.sync();
  },

  _autoLoop: async function (token) {
    var self = this;
    var s = this._s;

    while (s.auto.mode && this._autoSeq === token) {
      var lv = s.level;
      if (!lv || !lv.pairs.length) return;

      /* 一个单词一个单词地模拟「点英文 → 看一眼 → 点中文 → 思考 2 秒」。
         网页版这一步里穿插了朗读（speakThenWait），小程序首版不做朗读
         （没有原生 TTS，见迁移文档 §3.1），所以那一步改成等长的「默读」停顿，
         保留原有节奏感，不作弊把间隔压短。 */
      while (s.auto.mode && this._autoSeq === token) {
        var remain = [];
        var i;
        for (i = 0; i < s.left.length; i++) if (!s.left[i].done) remain.push(s.left[i]);
        if (!remain.length) break;

        var enCard = remain[0];
        var enIdx = s.left.indexOf(enCard);
        var zhIdx = -1;
        for (i = 0; i < s.right.length; i++) {
          if (!s.right[i].done && s.right[i].text === enCard.mean) { zhIdx = i; break; }
        }
        // 理论上不该出现（同义关里至少有一张未完成的中文卡配得上），
        // 但真出现了就退出本关，免得 while 死循环
        if (zhIdx < 0) break;

        this._autoStage = 'pick-en';
        this._setAutoTip('看英文', enCard.text);
        if (!(await this._autoSleep(AUTO_STEP, token))) return;

        this._pick('L', enIdx, true);                    // 选中英文

        this._autoStage = 'look';
        this._setAutoTip('默读一遍', enCard.text);
        if (!(await this._autoSleep(AUTO_STEP, token))) return;

        this._autoStage = 'pick-zh';
        this._setAutoTip('找出中文', enCard.text);
        if (!(await this._autoSleep(AUTO_STEP, token))) return;

        this._pick('R', zhIdx, true);                    // 配对成功
        if (s.matched >= lv.pairs.length) break;         // 本关收尾交给下面的过关节奏

        this._autoStage = 'think';
        this._setAutoTip('记一记', (s.right[zhIdx] ? s.right[zhIdx].text : '') + ' = ' + enCard.text);
        if (!(await this._autoSleep(AUTO_THINK, token))) return;
      }

      if (!s.auto.mode || this._autoSeq !== token) return;

      /* 本册练完：先给 5 秒记忆 + 跳转提示，再自动接着练下一册（最后一册才收尾） */
      var lc = bank.levelCount(s.bookId);
      if (s.levelIndex >= lc - 1) {
        var nextBook = this._nextBook();
        if (nextBook) {
          var nextLabel = nextBook.label;
          this._autoStage = 'book-gap';
          this._setAutoTip('本册完成', BOOK_GAP_SEC + ' 秒后进入下一册');
          this._showFlash('📚', '本册练完！即将进入「' + nextLabel + '」', AUTO_BOOK_GAP);
          if (!(await this._autoSleep(AUTO_BOOK_GAP, token))) return;

          this._enterBookInAuto(nextBook.id);
          this._autoStage = 'book-start';
          this._setAutoTip('开始练习', '「' + nextLabel + '」第 1 关');
          if (!(await this._autoSleep(AUTO_STEP, token))) return;
          continue;
        }
        this._autoStage = 'finish';
        this._setAutoTip('全部完成', '共 ' + bank.books().length + ' 册，太棒了！');
        this._showFlash('🏆', '全部 ' + bank.books().length + ' 册都练完啦！', 2600);
        await this._autoSleep(1500, token);
        this._stopAuto(true);
        ui.success('全部 ' + bank.books().length + ' 册自动练习完成');
        return;
      }

      /* 本关完成：额外给 4 秒思考与记忆 */
      this._autoStage = 'gap';
      this._setAutoTip('本关完成', '即将进入下一关');
      this._showFlash('✅', '本关完成！即将进入下一关', AUTO_GAP);
      if (!(await this._autoSleep(AUTO_GAP, token))) return;

      s.levelIndex++;
      this._loadLevel();
    }
  },

  /* 跨册：只换册并重建关卡，不动 autoMode / autoSeq。
     网页版这里必须先 await 大学词库（按需加载），否则 levels 是空的、
     下一轮取不到关卡会**静默停住**（autoMode 还开着，界面却不动）。
     小程序把词库全内置了，那一层异步不存在了 —— 但函数刻意保留，
     因为「跨册只换册不重启自动练习」这个语义是必须守住的。 */
  _enterBookInAuto: function (bookId) {
    var s = this._s;
    if (this._advanceTimer) { clearTimeout(this._advanceTimer); this._advanceTimer = null; }
    /* 先把「当前册」落盘：换页后新页面从 store 读起始册，读到的必须是这一册。 */
    store.writeStr(store.KEYS.book, bookId);
    /* 目标册有读音 ⇒ 换到它的分包页，并把自动练习带过去（redirect 会销毁本页，
       所以自动练习只能靠参数在新页重启；本页的 _autoSeq 会因 onUnload 里的 _stopAuto 失效，
       调用方的 await 随即收到 false 退出，不会在已销毁的页面上继续跑）。 */
    if (this._goAudioPack(bookId, 0, true)) return;
    s.bookId = bookId;
    s.levelIndex = 0;
    s.finishedAll = false;
    this._loadLevel();
  },

  /* 可中断、可暂停的等待：返回 false 表示节奏已被打断（退出/切关） */
  _autoSleep: function (ms, token) {
    var self = this;
    return new Promise(function (resolve) {
      var remain = ms;
      var tick = ms > 1000 ? 200 : 50;
      self._s.auto.countdown = Math.ceil(ms / 1000);
      self.setData({ autoCountdown: self._s.auto.countdown });
      var step = function () {
        var s = self._s;
        if (!s.auto.mode || self._autoSeq !== token) {
          self._autoTimer = null;
          resolve(false);
          return;
        }
        if (s.auto.paused) { self._autoTimer = setTimeout(step, 150); return; }
        remain -= tick;
        s.auto.countdown = Math.max(0, Math.ceil(remain / 1000));
        // 只推倒计时这一个字段：整屏 setData 在 200ms 一次的频率下太浪费
        self.setData({ autoCountdown: s.auto.countdown });
        if (remain <= 0) { self._autoTimer = null; resolve(true); return; }
        self._autoTimer = setTimeout(step, tick);
      };
      self._autoTimer = setTimeout(step, tick);
    });
  },

  toggleAutoPause: function () {
    var s = this._s;
    if (!s.auto.mode) return;
    s.auto.paused = !s.auto.paused;
    this.sync();
  },

  _stopAuto: function (silent) {
    var s = this._s;
    if (!s.auto.mode) {
      s.auto.tag = ''; s.auto.tip = ''; s.auto.countdown = 0;
      this.sync();
      return;
    }
    s.auto.mode = false;
    this._autoSeq++;
    s.auto.paused = false;
    if (this._autoTimer) { clearTimeout(this._autoTimer); this._autoTimer = null; }
    s.auto.tag = ''; s.auto.tip = ''; s.auto.countdown = 0;
    this._loadLevel();          // 回到练习模式的干净状态
    if (!silent) ui.info('已退出自动练习');
  }
});
