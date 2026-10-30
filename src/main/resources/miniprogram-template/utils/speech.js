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
//
// ★★ 为什么每次点读都新建实例，而不是同一册复用一个：
//   真机上同一实例的「stop → 设 startTime → play」是**异步下发**的三条命令，
//   交错顺序不受我们控制，由此产生过两类真机病：
//   ① 上一次的 stop 排到本次 play 之后 ⇒ 音频刚起就被掐掉，**不报错**，
//      表现是「点了没声音，再点别的词、回头再点这个又好了」；
//   ② 残留的播放位置 / paused / currentTime 读数和现实对不上，纠偏与判停
//      全建立在错读数上，越纠越乱（表现为「念一半掐断重念」）。
//   补 play 的看门狗救不了 ①（paused / currentTime 的更新时序同样不可信，
//   实测会把正在播的词在半路掐断重播）。新实例没有历史状态，两类病都没有土壤；
//   分包文件在本机，重建的开销只是解码头，几十毫秒，点读无感。
//
// ★★ 锁屏 / 系统中断（真机病灶三）：
//   锁屏几分钟再解锁后「点哪个词都没声、换册才恢复」有两个来源：
//   ① 中断期间底层报一次错触发 onError —— onError 里若把 conf 永久置空，
//     本页所有点读就地静音，直到换册重新 configure（正是体验版实测症状）；
//   ② 音频会话被打断后没恢复，新实例 play 静默失败。
//   对策：onError 只停当前段、**绝不封 conf**；接官方 wx.onAudioInterruptionBegin/End
//   （Begin 停播、End 销毁实例）；再挂 wx.onAppHide/onAppShow，后台超过 3 秒回来
//   就 revive()——销毁僵尸实例并用 0 音量探针把音频会话戳醒。见 hookInterruption/revive。

/* 轮询间隔。80ms 对耳朵是连续的；比 onTimeUpdate 的 ~250ms 还密。 */
var TICK_MS = 80;

/* 判停提前量：比词尾早 20ms，避免把下一个词的起音带进来。 */
var TAIL_PAD = 0.02;

/* 硬超时余量：词长之外再给 1.5s，无论如何都要停（兜底，不参与正常路径）。 */
var HARD_PAD_MS = 1500;

/* 纠偏阈值：首帧位置**早于**词起点超过这个量，才认定 startTime 没生效、音频从更靠前
   的位置开播。★ 只认「早于」，不认「晚于」—— 见 tick 里的说明。 */
var DRIFT = 0.5;

/* 当前配置：{ root, meta, map }。null = 本页没有读音能力。 */
var conf = null;

/* 当前（最多一个）实例。点一次词换一个：见文件头「为什么每次点读都新建实例」。 */
var audio = null;

/* 上面那个实例当前加载的是哪个文件。 */
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

/* 系统音频中断（锁屏 / 来电 / 其他应用抢占）与前后台切换的钩子。★ 只挂一次：
   模块是单例，configure 每页都会调，重复注册会让一次中断触发 N 遍回调。 */
var interruptionHooked = false;

/* 上一次切到后台的时刻。0 = 当前在前台（或从未离开过）。 */
var hiddenAt = 0;

function hookInterruption() {
    if (interruptionHooked) return;
    interruptionHooked = true;
    /* obeyMuteSwitch 从基础库 2.3.0 起要经这个接口统一设置（实例上的属性已不生效）。 */
    try { wx.setInnerAudioOption({ obeyMuteSwitch: false }); } catch (e) { /* 老基础库没有 */ }
    try {
        if (typeof wx.onAudioInterruptionBegin === 'function') {
            wx.onAudioInterruptionBegin(function () { stop(); });
        }
        if (typeof wx.onAudioInterruptionEnd === 'function') {
            /* 中断结束：实例多半已被打坏（再 play 会静默失败），销毁掉，
               让下一次点读走全新上下文。 */
            wx.onAudioInterruptionEnd(function () { halt(); dropAudio(); });
        }
        if (typeof wx.onAppHide === 'function') {
            wx.onAppHide(function () { stop(); hiddenAt = Date.now(); });
        }
        if (typeof wx.onAppShow === 'function') {
            wx.onAppShow(function () {
                if (hiddenAt && Date.now() - hiddenAt > 3000) revive();
                hiddenAt = 0;
            });
        }
    } catch (e) { /* 老基础库没有这组 API：退化为「下一次点读重建实例」 */ }
}

/* 后台回来（解锁 / 切回小程序）时的自愈。锁屏几分钟后音频会话常被打断，
   此时新实例 play 也会静默失败 —— 先销毁僵尸实例，再用一个 0 音量的探针
   播放把会话「戳醒」：探针不出声、几百毫秒后自毁，用户下一次点读照常
   走全新实例。 */
function revive() {
    if (!conf) return false;
    stop();
    dropAudio();
    poke(0);
    return true;
}

/* 0 音量探针：只做「让音频会话重新活过来」这一件事。
   真机上锁屏回来的恢复可能要一两秒，试一次不一定够 —— 没收到 onPlay 回执
   就隔 700ms 再来一次，最多 3 次；拿到了就到此为止（探针只播 600ms 就销毁）。 */
function poke(tries) {
    if (!conf) return;
    var probe;
    try { probe = wx.createInnerAudioContext(); } catch (e) { return; }

    var alive = false, done = false;
    var finish = function () {
        if (done) return;
        done = true;
        try { probe.destroy(); } catch (e) { /* 已销毁 */ }
    };

    probe.src = '/' + conf.root + '/audio/sprite.m4a';
    probe.volume = 0;            /* 只戳会话，不出声 */
    if (typeof probe.onPlay === 'function') {
        probe.onPlay(function () { alive = true; });   /* 回执 = 会话真的活了 */
    }
    if (typeof probe.onError === 'function') {
        probe.onError(finish);
    }
    try { probe.play(); } catch (e) { /* 下一轮再试 */ }

    setTimeout(function () {
        finish();
        if (!alive && tries < 2 && conf) {
            setTimeout(function () { poke(tries + 1); }, 700);
        }
    }, 600);
}

/**
 * 把读音能力接到当前页面。
 *
 * @param {string} root 分包根目录（如 'packages/pep-3-1'），音频路径为 /root/audio/sprite.m4a
 * @param {object} meta 整册索引（由 audio/index.js require 进来）
 * @returns {boolean} 是否配置成功
 */
function configure(root, meta) {
    hookInterruption();
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

/* 每次点读都换一个全新实例（旧的当场销毁）。为什么不复用见文件头注释 ——
   一句话：真机上同一实例的 stop / startTime / play 是异步交错的命令，
   复用等于把「念一半掐断重念」「点了没声」两个病根留在身边。 */
function freshAudio(url) {
    if (audio) dropAudio();          // 旧实例连着它的播放位置、排队中的命令一起丢掉

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
    /* 兜底：极端环境下没有定时器能力时才用 onTimeUpdate（见 play 里的 catch）。
       正常路径靠 setInterval 轮询，这个回调在 watch 存在时直接跳过，不重复跑。 */
    if (typeof audio.onTimeUpdate === 'function') {
        audio.onTimeUpdate(function () {
            if (cur && cur.watch === null) tick(cur);
        });
    }
    audio.onError(function () {
        /* 音频缺失 / 解码失败 / 系统中断错误：停掉这一段就好。
           ★★ 绝不能把 conf 永久置空 —— 锁屏后真机会偶发报一次系统错误，
           一旦置空，本页所有点读就地静音，直到换册重新 configure 才恢复
           （体验版实测：「锁屏回来点哪个都没声，换册就好」正是这个病灶）。
           下一次点读本来就会重建全新实例，自然完成重试；真缺音频的册
           每次都安静失败，也不影响配对玩法。 */
        halt();
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
function tick(myCur) {
    /* 段归属：轮询只服务「建它时那一段」。切册 / 换页时旧页可能留下一个来不及清掉的
       interval（新旧页的 onLoad / onUnload 先后并无保证），若它也来 tick 新段，就会
       多出一套判停、多几拍补 play ⇒ 偶发「重播」「忽然没声」。对不上就自尽。
       ⚠️ myCur 只认带 jumped 字段的段对象：没有定时器能力时退化成 onTimeUpdate(tick)，
       那时第一个参数是事件对象，必须按「当前段」处理。 */
    var owner = (myCur && typeof myCur === 'object' && 'jumped' in myCur) ? myCur : null;
    if (owner && owner !== cur) {
        if (owner.watch !== null && owner.watch !== undefined) {
            try { clearInterval(owner.watch); } catch (e) { /* 忽略 */ }
        }
        owner.watch = null;
        return;
    }

    var c = cur;
    if (!c || !audio) return;

    var ct = Number(audio.currentTime);
    if (!isFinite(ct) || ct < 0) ct = 0;
    var now = Date.now();

    if (c.base === null) {
        /* 还没起播（解码 / 缓冲中，新实例也要给几十毫秒）：
           **绝不能在这里把基准定成 0** —— 否则 startTime 一生效，currentTime 从词
           起点开始涨，而基准还停在 0，elapsed 立刻像「已播完」⇒ 词还没出声就被判停。
           ★ 这里不做任何补救动作（不补 play）：真机 paused / currentTime
           的更新时序都不可信，靠它们当触发条件只会把正在播的词掐断重念。 */
        if (ct <= 0.001) {
            if (now - c.t0 > c.dur * 1000 + 6000) halt();   // 迟迟不出声，别留着空转
            return;
        }
        if (ct < c.start - DRIFT) {
            /* ct 明显早于词起点 —— 两种可能，**绝不在一拍之内动手**：
               (a) startTime 真没生效：音频在播更靠前的内容，ct 会**每拍稳定递增**
                   （约 +80ms/拍）却迟迟追不上起点 ⇒ 连续两拍确认后 seek 一次拉回；
               (b) 解码刚起、读数还没跟上：音频其实已从词起点开播，currentTime 先报
                   一个很小的残留值、随后**跳变**到真实位置（≥ 起点）。此时 seek 等于
                   把正在播的词拉回起点**重念一遍** —— 新下载册的前 1~2 个词
                   「念到一半又从头念」正是它（首播要现解码，读数节节滞后；
                   册越大、解码越久，越容易撞上）。
               区分依据只有一条：ct 是否**稳定递增**。(b) 是小值原地踏步后跳变，
               (a) 是每拍都涨。误判 (b) 的代价是重播，比漏一次纠偏严重得多，
               所以要求连续两拍（≈240ms）都在涨才动手。 */
            if (c.prevCt !== null && ct > c.prevCt + 0.02) c.grow++; else c.grow = 0;
            c.prevCt = ct;
            if (c.grow >= 2 && c.start > DRIFT) {
                /* 连续两拍都稳定上涨（≈ 240ms 都还在起点之前）⇒ 判定 startTime 没生效 */
                c.jumped = true;
                try { audio.seek(c.start); } catch (e) { /* 不支持 seek 就让它自然播完 */ }
                c.base = c.start;
                c.tick0 = now;
            } else if (now - c.t0 > c.dur * 1000 + 6000) {
                halt();   // 一直追不上也收尾
            }
            return;
        }
        /* 基准取哪儿，取决于「这一拍是不是真的晚了」：
           · 这一拍来得晚（ct 已越过起点）⇒ 音频确实是从起点开始播的，基准取**起点**；
             取 ct 会把判停整体推后 (ct-start) 秒，词尾串到下一个词上。
           · ct 就在起点附近 ⇒ 直接取 ct，绝对 / 相对两种 currentTime 口径都算得对。
           ★ 反方向（ct 比起点晚）绝不 seek：那是**第一次采样来得晚**（切册后首屏
           setData 卡主线程），不是 startTime 没生效；seek 回起点等于重念一遍。 */
        c.base = (ct > c.start + DRIFT) ? c.start : ct;
        c.tick0 = now;
        return;
    }

    var elapsed = ct - c.base;
    if (elapsed >= c.dur - TAIL_PAD) { halt(); return; }

    /* 硬超时：从「真的起播」算起，词长之外再给 1.5s。
       seek 不生效、currentTime 卡死等异常路径全兜在这里。 */
    if (now - c.tick0 > c.dur * 1000 + HARD_PAD_MS) halt();
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

    /* 先停掉上一段（含它的计时器），否则连点两个词会有两套轮询同时判停。 */
    halt();

    /* 每次点读都换新实例：旧实例（连同它队列里可能还没执行完的 stop / seek 命令）
       当场销毁 —— 这正是「点了没声（play 被上一次的 stop 吞掉）」和
       「念一半掐断重念（错读数触发误纠偏）」的病根。 */
    var a = freshAudio(url);
    var mySeq = ++seq;

    /* 全新实例 + startTime：文件是本机缓存，起播就是从词起点开始。 */
    a.startTime = start;

    cur = {
        start: start,
        end: end,
        dur: end - start,
        base: null,
        t0: Date.now(),
        tick0: null,
        jumped: false,
        /* 上一拍的 currentTime / 连续上涨的拍数：用来区分「startTime 没生效（读数
           每拍稳定递增）」和「解码期读数滞后（小值原地踏步后跳变）」，见 tick。 */
        prevCt: null,
        grow: 0,
        seq: mySeq,
        watch: null
    };
    try {
        a.play();
    } catch (e) {
        cur = null;
        return false;
    }
    /* 起轮询。setInterval 的第一次回调在 TICK_MS 之后，正好用来取首帧基准。
       把「这一段」作为参数传进去：旧段残留的轮询再跑到时能认出自己已经过期。 */
    var mine = cur;
    try {
        mine.watch = setInterval(function () { tick(mine); }, TICK_MS);
    } catch (e) {
        /* 极端情况下没有定时器能力：退化成「靠 onTimeUpdate 尽力而为」
           （监听在 freshAudio 里注册，条件见那里的说明）。 */
        mine.watch = null;
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
    /* 后台回来时戳醒音频会话（onAppShow 里自动调；也留给页面手动兜底）。 */
    revive: revive,
    /* 调试出口：探针用它读「当前在播哪一段」，页面不要用。 */
    _state: function () { return cur; }
};
