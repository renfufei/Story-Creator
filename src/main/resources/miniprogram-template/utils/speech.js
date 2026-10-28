// 单词读音：播放「整册 sprite」里的某一段
// ---------------------------------------------------------------------------
// 为什么是整册一个音频文件，而不是每个词一个 mp3：
//   · 一册 64~155 个词，逐词一个文件 ⇒ 一册就要几十上百次下载，小程序里又慢又费流量；
//   · 整册压成一条 sprite（AAC，约 0.5~1.4 MB），配一份「词 → 起止秒」的索引，
//     播放时跳转到对应区间即可 —— 一册只下载 1 次，第二次点词是零延迟。
//
// 为什么这个模块默认「什么都不做」：
//   音频文件在**分包**里（主包 2 MB 上限放不下），而微信规定**主包不能使用分包资源**
//   （开发者工具里能播，真机静默无声，官方社区已明确答复）。
//   所以只有「音频分包里的那个玩法页」才调用 configure() 把索引交进来；
//   主包里的同名页面不 configure，play() 直接返回 —— 于是同一个玩法页文件，
//   在主包是「纯配对」，在分包里多一个点读能力，行为差异只由 configure 决定。
//
// 索引格式（导出时由 sprite.json 转成 js 模块）：
//   { sampleRate: 24000, words: [["ruler", 0.12, 1.912], ...] }   // 词, 起, 止（秒）
//
// ★ 停止判定为什么不用 onTimeUpdate：
//   真机上 onTimeUpdate 不保证持续回调 —— 实测在 seek 之后，部分基础库会让这个
//   回调停摆（也有机型本来就只有零星几次）。靠它判「播到词尾就停」的话，一旦回调
//   不再触发，条件永远不成立，音频就顺着整册一直往下念（点第一词能把 134 秒念完）。
//   ⇒ 改为 setInterval 主动轮询 currentTime，回调只当锦上添花，不参与判定。
//   ⇒ 并且时间基准取「首帧读到的位置」而不是词起点：真机上 currentTime 到底是
//      「文件绝对位置」还是「相对本次播放起点」两种口径都见过，用首帧当零点，
//      两种口径都算得对。

/* 轮询间隔。80ms 对耳朵是连续的；比 onTimeUpdate 的 ~250ms 还密。 */
var TICK_MS = 80;

/* 判停提前量：比词尾早 20ms，避免把下一个词的起音带进来。 */
var TAIL_PAD = 0.02;

/* 硬超时余量：词长之外再给 1.5s，无论如何都要停（兜底，不参与正常路径）。 */
var HARD_PAD_MS = 1500;

/* 纠偏阈值：首帧位置与词起点差超过这个量，说明 startTime 没生效、音频从 0 开始播。 */
var DRIFT = 0.5;

/* 当前配置：{ root, meta, map }。null = 本页没有读音能力。 */
var conf = null;

/* 播放上下文。同一册（同一个 sprite 文件）只建一个、反复复用 —— 新建实例要重新解码，
   点读的手感会明显变差。但**换了文件（换册）必须重建**，原因见 ensureAudio。 */
var audio = null;

/* 上面那个实例当前加载的是哪个文件。换册后 src 变了就重建，避免播放位置残留。 */
var audioSrc = '';

/* 正在播的那一段：{ start, end, dur, base, t0, jumped, watch }。null = 没在播。 */
var cur = null;

/* 播放序号：连点两个词时，前一段的收尾逻辑不能把后一段掐掉。 */
var seq = 0;

/* 把词表摊成小写 → 区间 的字典，避免每次点读都遍历几十上百条。 */
function buildMap(meta) {
    var map = {};
    var words = (meta && meta.words) || [];
    for (var i = 0; i < words.length; i++) {
        var row = words[i];
        if (!row || row.length < 3) continue;
        var key = String(row[0]).toLowerCase();
        if (!map[key]) map[key] = row;
    }
    return map;
}

/**
 * 把读音能力接到当前页面。
 *
 * @param {string} root 分包根目录（如 'packages/pep-3-1'），音频路径为 /root/audio/sprite.m4a
 * @param {object} meta 整册索引（由 audio/index.js require 进来）
 * @returns {boolean} 是否配置成功
 */
function configure(root, meta) {
    if (!root || !meta || !meta.words || !meta.words.length) return false;
    var id = String(root).split('/').pop();      // 'packages/pep-3-2' → 'pep-3-2'
    conf = { root: String(root), bookId: id, meta: meta, map: buildMap(meta) };
    return true;
}

/** 当前配置的是哪一册（'' = 没有读音能力）。换册后页面拿它判断要不要换页。 */
function bookId() {
    return conf ? conf.bookId : '';
}

/** 丢掉读音能力并停声。换册 / 离开读音页时调用，避免拿旧册索引去查新册的词。 */
function reset() {
    halt();
    /* 配置都清了，实例留着只会带着旧册的播放位置 —— 一起丢掉。 */
    dropAudio();
    conf = null;
}

/** 本页是否有读音能力（给页面决定要不要画喇叭、要不要提示用）。 */
function enabled() {
    return !!conf;
}

/** 词对应的区间；词不在索引里（比如本册词表被改过）返回 null。 */
function lookup(word) {
    if (!conf) return null;
    var key = String(word == null ? '' : word).toLowerCase();
    return conf.map[key] || null;
}

/* 收尾：清计时器 + 停音频。**只把 cur 置空是不够的** ——
   置空只让后面的判定失效，声音还在播。 */
function halt() {
    var c = cur;
    cur = null;
    if (c && c.watch !== null && c.watch !== undefined) {
        try { clearInterval(c.watch); } catch (e) { /* 忽略 */ }
    }
    if (audio) {
        try { audio.stop(); } catch (e) { /* 首次调用 / 已停止，忽略 */ }
    }
}

/* 惰性建实例 + 只注册一次监听。
   这里的监听只做兜底：真正判停的是 play() 里起的轮询（见文件头注释）。

   ★ 为什么换文件要重建，而不是在旧实例上改 src：
   真机上「stop → 改 src → 改 startTime → play」这一串命令是异步下发的，startTime
   有时会被上一次的 stop / seek 覆盖，起播位置落在**上一次的播放点**上。耳朵里就是
   「先念半句上一个词（残音），再从头念一遍这个词」—— 换册后第一次点读最明显，
   因为文件换了、解码也要重来，时序更乱。
   同文件复用没有这个问题，还省掉一次解码，所以只在 url 变化时重建。 */
function ensureAudio(url) {
    if (audio && audioSrc === url) return audio;
    if (audio) dropAudio();          // 换文件：旧实例连着它的播放位置一起丢掉

    audio = wx.createInnerAudioContext();
    audioSrc = url;
    audio.src = url;
    /* 静音键按下时也出声：点读是用户主动点的，课堂上开着静音也希望能听。
       与 utils/sound.js 的音效保持同一策略。 */
    audio.obeyMuteSwitch = false;

    /* 整册播到文件末尾（索引与实际音频不一致时的极端情况）：收尾，别留着。 */
    if (typeof audio.onEnded === 'function') {
        audio.onEnded(function () { if (cur) halt(); });
    }
    audio.onError(function () {
        /* 音频缺失 / 解码失败：本页就此放弃读音，但绝不影响配对玩法。 */
        halt();
        conf = null;
    });

    return audio;
}

/* 销毁实例。destroy 之后不能再 play，所以只在这里、以及 release 里调。 */
function dropAudio() {
    if (!audio) return;
    try { audio.destroy(); } catch (e) { /* 旧基础库没有 destroy */ }
    audio = null;
    audioSrc = '';
}

/* 一次轮询判定。cur 里的 base 是「首帧读到的位置」，相当于给 currentTime
   定了个零点 —— 于是不管它报的是文件绝对位置还是本次播放的相对位置，
   elapsed 都是「这一段已经播了多久」。 */
function tick() {
    var c = cur;
    if (!c || !audio) return;

    var ct = Number(audio.currentTime);
    if (!isFinite(ct) || ct < 0) ct = 0;

    if (c.base === null) {
        /* 还没起播（解码 / 首次下载慢）：**绝不能在这里把基准定成 0**。
           否则 startTime 一生效，currentTime 会从词起点开始涨，而基准还停在 0，
           elapsed 立刻就像「已经播完」⇒ 词还没出声就被判停。 */
        if (ct <= 0.001) {
            if (Date.now() - c.t0 > c.dur * 1000 + 6000) halt();   // 起播都没成功，别留着空转
            return;
        }
        c.base = ct;
        c.tick0 = Date.now();
        /* startTime 没生效 ⇒ 音频从 0 开始播（表现为「点第 40 个词，先把前面念一遍」）。
           纠偏一次就够了；之后 base 用词起点，仍按同一套 elapsed 逻辑走。 */
        if (!c.jumped && c.start > DRIFT && Math.abs(ct - c.start) > DRIFT) {
            c.jumped = true;
            try { audio.seek(c.start); } catch (e) { /* 不支持 seek 就让它自然播完 */ }
            c.base = c.start;
        }
        return;
    }

    var elapsed = ct - c.base;
    if (elapsed >= c.dur - TAIL_PAD) { halt(); return; }

    /* 硬超时：从「真的起播」算起，词长之外再给 1.5s。
       seek 不生效、currentTime 卡死等异常路径全兜在这里。 */
    if (Date.now() - c.tick0 > c.dur * 1000 + HARD_PAD_MS) halt();
}

/**
 * 读一个英文词。没配置、词不在索引、区间非法 —— 都安静返回。
 *
 * @param {string} word 英文词（大小写不敏感）
 * @param {string} [bookId] 当前界面上是哪一册。**给了就必须与已配置的册一致** ——
 *        换册后页面若没换到该册的分包，索引与音频都对不上，宁可不出声也不能念错册
 *        （3-1 的索引查 3-2 的词大多查不到；偶尔同名同词却会播到别的册的录音）。
 * @param {boolean} [restart] 保留参数位（当前恒为「同一个词再点也重播」）
 */
function play(word, bookId, restart) {
    if (!conf) return false;
    if (bookId && conf.bookId !== String(bookId)) return false;
    var entry = lookup(word);
    if (!entry) return false;

    var start = Number(entry[1]);
    var end = Number(entry[2]);
    if (!isFinite(start) || !isFinite(end) || end <= start) return false;

    var url = '/' + conf.root + '/audio/sprite.m4a';

    /* 先停掉上一段（含它的计时器），否则连点两个词会有两套轮询同时判停。
       ★ 顺序：先 halt 再取实例 —— 取实例时可能销毁重建，先 halt 才不会漏停旧实例。 */
    halt();

    var a = ensureAudio(url);
    var mySeq = ++seq;

    /* 起点。同一实例上「stop → 改 startTime → play」就是让「跳着播不同词」稳定的组合；
       换文件的情形由 ensureAudio 重建实例兜住（那里有为什么不能只改 src 的说明）。 */
    a.startTime = start;

    cur = {
        start: start,
        end: end,
        dur: end - start,
        base: null,
        t0: Date.now(),
        tick0: null,
        jumped: false,
        seq: mySeq,
        watch: null
    };
    try {
        a.play();
    } catch (e) {
        cur = null;
        return false;
    }
    /* 起轮询。setInterval 的第一次回调在 TICK_MS 之后，正好用来取首帧基准。 */
    try {
        cur.watch = setInterval(tick, TICK_MS);
    } catch (e) {
        /* 极端情况下没有定时器能力：退化成「靠 onTimeUpdate 尽力而为」。 */
        cur.watch = null;
        if (typeof a.onTimeUpdate === 'function') a.onTimeUpdate(tick);
    }
    return true;
}

/** 立刻停止（切关、退出页面、关掉音效时调用）。 */
function stop() {
    seq++;
    halt();
}

/**
 * 页面卸载时彻底释放：不 destroy 会留下一个常驻音频实例。
 *
 * @param {string} [keepBookId] 正在卸载的这个页面管的是哪一册。
 *        换册时新旧页的 onLoad / onUnload 先后**并无保证**：新页可能已经 configure 好了、
 *        甚至已经开始念了，这时旧页的释放绝不能动它的实例（真机表现是「刚点下去
 *        念半句就没声了」）。给了 keepBookId 且当前配置已经不是它 ⇒ 有新页接管了，什么都不做。
 */
function release(keepBookId) {
    if (keepBookId && conf && conf.bookId !== String(keepBookId)) return;
    stop();
    dropAudio();
}

module.exports = {
    configure: configure,
    enabled: enabled,
    bookId: bookId,
    lookup: lookup,
    play: play,
    stop: stop,
    reset: reset,
    release: release,
    /* 调试出口：探针用它读「当前在播哪一段」，页面不要用。 */
    _state: function () { return cur; }
};
