# 单词趣味配对 · 微信小程序源码

这个压缩包由 Story-Creator 的「单词匹配」页导出（`/learn/word-match` → 设置 → 导出小程序源码）。
包里的词库是导出那一刻按后端切关算法现算的，与网页版完全一致。

## 里面有什么

```
app.js / app.json / app.wxss     小程序入口与全局样式
pages/index/                     检索首页（按册次、按单词查）
pages/play/                      配对玩法页
utils/                           词库访问、检索、音效、进度存储
data/books.js                    全部册次的元信息（id / 名称 / 学段 / 关数 / 词数）
data/levels-<学段>.js            各学段全部关卡（每关：主题 + 单词对）
```

`data/` 是**生成物**，每次导出都是当前词库的最新快照；不要手改，改词库请改 Story-Creator 里的词库源
（`src/main/resources/learn/*.json` 与对应的切关逻辑）。

## 怎么跑起来

1. 解压，得到 `word-pair/` 目录。
2. 打开微信开发者工具 → 导入项目 → 选择这个目录。
3. `project.config.json` 里的 `appid` 是空的：
   - 有自己的小程序，填自己的 AppID；
   - 只想本地看效果，导入时选「测试号」即可。
4. 无需后端：进度、错题本全部存在本机（`wx.setStorageSync`），不依赖任何服务器。

## 发布前

- 小程序名称与类目需要和实际内容一致（本项目用的是「工具 - 信息查询」类目）。
- 主包体积上限 2048 KB，本包词库约 750 KB，不需要分包。
- 上传、提交审核、发布都在微信公众平台/开发者工具里完成。

## 想改玩法

改 Story-Creator 仓库里的 `src/main/resources/miniprogram-template/`，然后重新导出。
这份源码是模板 + 数据的产物，直接在本包里改会在下次导出时被覆盖。
