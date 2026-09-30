/**
 * 冒烟测试的「静音守门」—— 所有会拉起浏览器的测试共用这一份（Node 探针 + Java 端到端测试）。
 *
 * 为什么必须有它：Chrome 的 `--mute-audio` 只掐浏览器音频管线里的输出（媒体元素、Web Audio），
 * 而 `speechSynthesis` 走的是**操作系统的语音合成**（macOS 上是 AVSpeechSynthesizer）——
 * 无头浏览器照样会从音箱把单词念出来。本项目单词页默认 `soundOn: true`，
 * 冒烟时点两下卡片，整台电脑就开始念英文；乘法页在没有预生成音频时也会降级到本地朗读。
 *
 * 注入方式：由 `Page.addScriptToEvaluateOnNewDocument` 在**页面脚本之前**执行，
 * 对每次导航与刷新都生效 —— 装一次就够，用例里不用记着重新装，更不靠人记着静音。
 *
 * 三道处理：
 *   ① TTS：`speechSynthesis.speak` 换成记录器（记文本 + 异步回调 `onend`，绝不碰原生引擎）；
 *   ② 媒体元素：`play()` 之前强制 `muted = true` / `volume = 0`，仍然走原生 `play()`
 *      ⇒ `onended` 与 `play()` 的 Promise 语义不变，用例照样能等"播完"；
 *   ③ Web Audio：**不插桩** —— 输出已被 `--mute-audio` 在音频管线入口掐掉，
 *      去改 AudioNode 图反而会让"音效是否播放"之类的断言失真。
 *
 * 与本文件配套的启动参数是 `--mute-audio`（Node 侧见 scripts/lib/silent-audio.mjs 的
 * SILENT_AUDIO_FLAGS，Java 侧见 HeadlessChrome 的启动参数列表），两者缺一不可。
 *
 * ⚠️ 本脚本会在**每一个被测页面**上跑，自己抛错会污染"无未捕获 JS 错误"这类断言，
 * 所以整体 try/catch，且只往外暴露一个 `window.__smokeAudioOff` 供用例自检。
 */
(() => {
    try {
        if (window.__smokeAudioOff) return true;
        const state = { guard: 'silent-audio/v1', spoken: [], playCalls: 0, tagMuted: 0 };
        Object.defineProperty(window, '__smokeAudioOff', { value: state, configurable: true });

        /* ① 平台语音：整条链路的唯一出口就是 speechSynthesis.speak，
              换成记录器后原生引擎再也不会被调用。onend 必须异步回调，
              否则页面里"念完再继续"的逻辑会被同步回调打乱节奏（甚至栈溢出）。 */
        const synth = window.speechSynthesis;
        if (synth) {
            const speak = function (utterance) {
                try { state.spoken.push(String((utterance && utterance.text) || '')); } catch (e) { /* ignore */ }
                setTimeout(function () {
                    try { if (utterance && typeof utterance.onend === 'function') utterance.onend(); } catch (e) { /* ignore */ }
                }, 60);
            };
            speak.__smokeGuard = true;
            try { synth.speak = speak; } catch (e) { /* 只读属性就放弃，下面的 muted 仍兜底 */ }
            const noop = function () {};
            ['cancel', 'pause', 'resume'].forEach(function (k) {
                try { synth[k] = noop; } catch (e) { /* ignore */ }
            });
        }

        /* ② 媒体元素：<audio>/<video> 与 new Audio() 都走 HTMLMediaElement.play()，
              在委托原生实现之前先压上 muted + volume=0。 */
        const mediaProto = window.HTMLMediaElement && window.HTMLMediaElement.prototype;
        if (mediaProto && typeof mediaProto.play === 'function') {
            const nativePlay = mediaProto.play;
            const guardedPlay = function () {
                try { this.muted = true; this.volume = 0; state.playCalls++; } catch (e) { /* ignore */ }
                return nativePlay.apply(this, arguments);
            };
            guardedPlay.__smokeGuard = true;
            mediaProto.play = guardedPlay;
        }
        // 静态写死在 HTML 里的 <audio>/<video> 标签也一并压上（有的页面直接改属性、不调 play 封装）
        const muteTags = function () {
            try {
                document.querySelectorAll('audio,video').forEach(function (el) {
                    el.muted = true;
                    el.volume = 0;
                    state.tagMuted++;
                });
            } catch (e) { /* ignore */ }
        };
        if (document.readyState === 'loading') {
            document.addEventListener('DOMContentLoaded', muteTags, { once: true });
        } else {
            muteTags();
        }
        return true;
    } catch (e) {
        return false;   // 守门脚本自己绝不能把被测页面搞崩
    }
})();
