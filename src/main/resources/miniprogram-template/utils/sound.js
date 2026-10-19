// 音效：WebAudio 振荡器 → 小程序 WebAudio
// ---------------------------------------------------------------------------
// 网页版不用音频文件，而是用 AudioContext 现场合成两个正弦音（配对成功：660Hz→880Hz；
// 不匹配：200Hz→150Hz 三角波）。好处是零资源体积、零加载延迟。
//
// 小程序从基础库 2.19.0 起提供 wx.createWebAudioContext()，接口与浏览器 AudioContext
// 基本一致（createOscillator / createGain / currentTime / destination / resume），
// 所以这套合成代码可以几乎原样搬过来 —— 仍然不需要任何 mp3 资源。
//
// 降级策略：低版本基础库上 createWebAudioContext 不存在，或者创建/播放抛异常时，
// 一律静默失败（返回 null / 空操作）。**配对过程绝不因为音效而中断**。

var ctx = null;
var ctxTried = false;

function ensureCtx() {
  if (ctx) return ctx;
  if (ctxTried) return null;          // 试过一次失败就不反复试了
  ctxTried = true;
  if (typeof wx.createWebAudioContext !== 'function') return null;
  try {
    ctx = wx.createWebAudioContext();
  } catch (e) {
    ctx = null;
  }
  return ctx;
}

// 与网页版 tone() 一一对应：frequency / 起始偏移 / 时长 / 波形 / 音量
// 音量包络用指数斜坡做「快起慢落」，避免爆音
function tone(freq, startAt, duration, type, gain) {
  var c = ensureCtx();
  if (!c) return;
  try {
    var osc = c.createOscillator();
    var g = c.createGain();
    osc.type = type || 'sine';
    osc.frequency.value = freq;
    var t0 = c.currentTime + startAt;
    g.gain.setValueAtTime(0.0001, t0);
    g.gain.exponentialRampToValueAtTime(gain || 0.09, t0 + 0.015);
    g.gain.exponentialRampToValueAtTime(0.0001, t0 + duration);
    osc.connect(g);
    g.connect(c.destination);
    osc.start(t0);
    osc.stop(t0 + duration + 0.02);
  } catch (e) {
    /* 忽略：音效失败不影响玩法 */
  }
}

// 部分机型首次使用前需要 resume()（与浏览器「自动播放策略」同一类问题）
function resume() {
  var c = ensureCtx();
  if (!c) return;
  try {
    if (c.state === 'suspended' && c.resume) c.resume();
  } catch (e) {
    /* 忽略 */
  }
}

// 配对成功 / 失败的和弦
function chime(ok) {
  resume();
  if (ok) {
    tone(660, 0, 0.12, 'sine', 0.08);
    tone(880, 0.09, 0.16, 'sine', 0.08);
  } else {
    tone(200, 0, 0.16, 'triangle', 0.06);
    tone(150, 0.12, 0.18, 'triangle', 0.06);
  }
}

// 选中卡片的轻微「嗒」
function tap() {
  resume();
  tone(520, 0, 0.06, 'sine', 0.05);
}

// 切关 / 完成本关的短促上扬音（网页版没有，小程序里补一个，替代原来朗读带来的「节奏感」）
function levelUp() {
  resume();
  tone(620, 0, 0.10, 'sine', 0.07);
  tone(830, 0.08, 0.12, 'sine', 0.07);
  tone(1040, 0.17, 0.16, 'sine', 0.07);
}

module.exports = {
  chime: chime,
  tap: tap,
  levelUp: levelUp,
  resume: resume,
  // 基础库不支持 WebAudio 时为 false，页面可据此把音效开关置为不可用
  available: function () { return typeof wx.createWebAudioContext === 'function'; }
};
