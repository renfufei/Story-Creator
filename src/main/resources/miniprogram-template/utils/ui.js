// 提示 / 确认 适配层
// ---------------------------------------------------------------------------
// 网页版用的是站内公共组件 SC.toast / SC.confirm（自定义样式 + Promise）。
// 小程序里对应 wx.showToast / wx.showModal：
//   · showToast 的 icon 只有 success / error / loading / none，没有 warn，
//     所以「提醒」类统一走 icon: 'none'（否则会被画成一个对勾，语义相反）。
//   · showModal 用 success 回调，这里包成 Promise，让调用处保持 .then 写法。

function toast(title, icon, ms) {
  wx.showToast({
    title: String(title || ''),
    icon: icon || 'none',
    duration: ms || 1800,
    mask: false
  });
}

function info(title) {
  toast(title, 'none', 1800);
}

function success(title) {
  toast(title, 'success', 1800);
}

function warn(title) {
  toast(title, 'none', 2000);
}

function error(title) {
  toast(title, 'error', 2400);
}

// 返回 Promise<boolean>：true = 用户点了「确定」
function confirm(opts) {
  var o = opts || {};
  return new Promise(function (resolve) {
    wx.showModal({
      title: o.title || '确认',
      content: o.message || '',
      confirmText: o.confirmText || '确定',
      cancelText: o.cancelText || '取消',
      confirmColor: o.danger ? '#d92d20' : '#0f6cbd',
      success: function (res) { resolve(!!res.confirm); },
      fail: function () { resolve(false); }
    });
  });
}

module.exports = {
  toast: toast,
  info: info,
  success: success,
  warn: warn,
  error: error,
  confirm: confirm
};
