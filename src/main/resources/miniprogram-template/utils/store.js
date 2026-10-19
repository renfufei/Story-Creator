// 本机存储适配层
// ---------------------------------------------------------------------------
// 网页版用的是 localStorage：字符串进出、取不到返回 null。
// 小程序用的是 wx.setStorageSync / getStorageSync：**可以直接存对象**，
// 但取不到时返回的是空字符串 ''（不是 null）—— 直接 JSON.parse('') 会抛异常，
// 直接当对象用会拿到 ''.xxx === undefined 这种静默错误。
// 所以统一收在这一层，上层只管拿对象、拿字符串，不关心小程序的怪癖。

var KEYS = {
  progress: 'word_match_progress_v2',        // 手动练习进度 { bookId: [已完成的关卡下标] }
  autoProgress: 'word_match_auto_progress_v2', // 自动练习进度（与手动练习进度不共享）
  wrong: 'word_match_wrong_v1',              // 错题本 { key: {...} }
  book: 'word_match_book_v1',                // 上次看哪一册
  sound: 'word_match_sound_v1',              // 音效开关 '1' / '0'
  dataSig: 'word_match_data_sig_v1'          // 词库指纹，用于词库变更后作废旧进度
};

function readRaw(key) {
  try {
    var v = wx.getStorageSync(key);
    return (v === '' || v === null || v === undefined) ? null : v;
  } catch (e) {
    return null;
  }
}

function writeRaw(key, val) {
  try {
    wx.setStorageSync(key, val);
    return true;
  } catch (e) {
    // 存储写满等极端情况：不影响本次游玩，只是下次进来会丢进度
    return false;
  }
}

function removeRaw(key) {
  try {
    wx.removeStorageSync(key);
  } catch (e) {
    /* 忽略 */
  }
}

// 读一个「映射」型存储（进度 / 自动进度 / 错题本），永远是对象
function readMap(key) {
  var v = readRaw(key);
  if (!v) return {};
  if (typeof v === 'string') {
    try {
      var obj = JSON.parse(v);
      return (obj && typeof obj === 'object') ? obj : {};
    } catch (e) {
      return {};
    }
  }
  return (typeof v === 'object') ? v : {};
}

function writeMap(key, obj) {
  return writeRaw(key, obj || {});
}

// 读字符串（音效开关）
function readStr(key, dft) {
  var v = readRaw(key);
  return (typeof v === 'string') ? v : (v === null ? dft : String(v));
}

function writeStr(key, val) {
  return writeRaw(key, String(val));
}

// 读数组（进度里每个册对应一个「已完成的关卡下标」数组）
function readList(key) {
  var v = readRaw(key);
  if (!v) return [];
  if (Array.isArray(v)) return v;
  if (typeof v === 'string') {
    try {
      var arr = JSON.parse(v);
      return Array.isArray(arr) ? arr : [];
    } catch (e) {
      return [];
    }
  }
  return [];
}

// 词库指纹变了 ⇒ 关卡被重新切分过，旧的「第 N 关已完成」已经不对应新关卡了。
// 这正是网页版当年做 v1→v2 迁移要解决的问题，这里改成按指纹自动判定，
// 以后词库再扩容不用再手工加一次迁移。
// 错题本存的是**单词本身**，不受关卡切分影响，因此不清理。
function ensureDataVersion(sig) {
  var old = readStr(KEYS.dataSig, '');
  if (old === sig) return false;
  removeRaw(KEYS.progress);
  removeRaw(KEYS.autoProgress);
  writeStr(KEYS.dataSig, sig);
  return old !== '';   // 返回 true 表示「确实作废过一次旧数据」（首次安装时为 false）
}

module.exports = {
  KEYS: KEYS,
  readMap: readMap,
  writeMap: writeMap,
  readStr: readStr,
  writeStr: writeStr,
  readList: readList,
  removeRaw: removeRaw,
  ensureDataVersion: ensureDataVersion
};
