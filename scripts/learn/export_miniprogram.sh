#!/bin/bash
# 从正在运行的服务导出「单词趣味配对」小程序工程，落到 word-match-miniprogram/。
#
# 为什么要这个脚本：小程序工程是**生成物** —— 页面代码来自仓库里的模板
# （src/main/resources/miniprogram-template/），词库由后端的切关算法现算。
# 所以换机器不用带 920KB 的源码，clone + 起服务 + 跑一次本脚本即可。
#
# 用法：
#   ./scripts/learn/export_miniprogram.sh              # 默认 http://localhost:1888
#   BASE=http://localhost:8080 ./scripts/learn/export_miniprogram.sh
#   ./scripts/learn/export_miniprogram.sh /tmp/mp-out  # 导到别处（不动现有目录）
set -euo pipefail

BASE="${BASE:-http://localhost:1888}"
OUT="${1:-word-match-miniprogram}"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

echo "拉取 $BASE/learn/word-match/miniprogram.zip ..."
if ! curl -fsS "$BASE/learn/word-match/miniprogram.zip" -o "$TMP/p.zip"; then
    echo "导出失败：确认服务在 $BASE 上跑着（./start_web.sh）" >&2
    exit 1
fi
echo "包大小：$(du -h "$TMP/p.zip" | cut -f1)"

unzip -q "$TMP/p.zip" -d "$TMP/x"
if [ ! -d "$TMP/x/word-pair" ]; then
    echo "包结构异常：解压后没有 word-pair/ 目录" >&2
    exit 1
fi

rm -rf "$OUT"
cp -R "$TMP/x/word-pair" "$OUT"
echo "已生成 ${OUT}（$(find "${OUT}" -type f | wc -l | tr -d ' ') 个文件，$(du -sh "${OUT}" | cut -f1)）"
echo "下一步：微信开发者工具导入该目录；appid 留空则可选测试号。"
