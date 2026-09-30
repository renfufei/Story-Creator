#!/usr/bin/env node
/**
 * 静音守门的共享加载器（Node 侧）—— 所有会拉起 Chrome 的测试脚本都用这一份，别各写各的。
 *
 * 守门脚本体在 `src/test/resources/silent-audio.js`（**单一真相**，Java 侧也从 test classpath 读同一份），
 * 这里只负责：① 把它读进来；② 提供要加到 Chrome 启动参数里的 `--mute-audio`；③ 一次调用装好注入。
 *
 * 用法（每个脚本三步，缺一不可）：
 *
 *     import { SILENT_AUDIO_FLAGS, installSilentAudio } from './lib/silent-audio.mjs';
 *
 *     const chrome = spawn(CHROME, [
 *         '--headless=new', ..., ...SILENT_AUDIO_FLAGS,   // ②
 *         '--remote-debugging-port=' + PORT, ...]);
 *
 *     await installSilentAudio(send);                     // ③ 替代原来的 await send('Page.enable')
 *                                                         //   addScriptToEvaluateOnNewDocument 对后续
 *                                                         //   每次导航/刷新都生效，用例里不用重复装
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));           // scripts/lib
export const GUARD_SOURCE_FILE = path.resolve(HERE, '../../src/test/resources/silent-audio.js');

/**
 * 要拼进 Chrome 启动参数里的静音开关。
 * `--mute-audio` 掐的是浏览器音频管线出口（媒体元素 + Web Audio）；
 * 平台 TTS 管不到，那部分靠 silent-audio.js 注入守门（见该文件头注释）。
 */
export const SILENT_AUDIO_FLAGS = ['--mute-audio'];

/** 守门脚本文本（页面注入用）。文件缺失就直接抛 —— 静音是硬需求，不能静默降级。 */
export const SILENT_AUDIO_INIT = fs.readFileSync(GUARD_SOURCE_FILE, 'utf8');

export const SILENT_AUDIO_GUARD = 'silent-audio/v1';

/**
 * 装静音守门：先 `Page.enable`，再注册到新文档注入。
 *
 * @param {(method: string, params?: object) => Promise<any>} send 脚本里的 CDP 发送函数
 */
export async function installSilentAudio(send) {
    await send('Page.enable');
    await send('Page.addScriptToEvaluateOnNewDocument', { source: SILENT_AUDIO_INIT });
    return true;
}

/** 用例里自检用：一段在页面里求值的表达式，返回守门是否生效。 */
export const AUDIO_OFF_PROBE = `(() => {
    const st = window.__smokeAudioOff;
    return {
        guard: st && st.guard,
        ttsHooked: !!(window.speechSynthesis && window.speechSynthesis.speak
            && window.speechSynthesis.speak.__smokeGuard),
        mediaHooked: !!(window.HTMLMediaElement && window.HTMLMediaElement.prototype.play
            && window.HTMLMediaElement.prototype.play.__smokeGuard),
    };
})()`;
