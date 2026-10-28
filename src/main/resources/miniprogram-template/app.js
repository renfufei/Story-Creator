// 单词趣味配对 · 微信小程序
// 词库来自 Story-Creator 的 /learn/word-match：小学 / 初中 / 高中 / 专题 / 大学 5 个学段，
// 册数以 data/books.js 为准（导出时按后端现算，这里的数字会过期，别照着改逻辑）。
// 进度与错题本全部存在本机（wx.setStorageSync），不依赖任何后端。
App({
  globalData: {
    // 首屏要用的册元信息在 data/books.js，这里不再重复加载
  },

  onLaunch: function () {
    // 允许在静音键按下时也播放提示音（音效是配对反馈的主要部分）
    if (wx.setInnerAudioOption) {
      try {
        wx.setInnerAudioOption({ obeyMuteSwitch: false, mixWithOther: true });
      } catch (e) {
        // 旧版本基础库不支持该参数，忽略即可，不影响玩法
      }
    }
  }
});
