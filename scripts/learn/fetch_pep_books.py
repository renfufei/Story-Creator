#!/usr/bin/env python3
"""下载 kajweb/dict 的人教版 PEP 各册 zip 并解出 JSONL 到 /tmp/pep_books/。"""
import json, os, subprocess, zipfile, io, sys

BASE = "https://raw.githubusercontent.com/kajweb/dict/master/book/"
OUT = "/tmp/pep_books"
os.makedirs(OUT, exist_ok=True)
zips = json.load(open("/tmp/pep_dl/zips.json"))

ok, fail = [], []
for bid, name in zips.items():
    dest = os.path.join(OUT, bid + ".jsonl")
    if os.path.exists(dest) and os.path.getsize(dest) > 1000:
        ok.append(bid); continue
    url = BASE + name
    try:
        raw = subprocess.run(["curl", "-sL", "--max-time", "90", url],
                             capture_output=True, check=True).stdout
        if not raw:
            fail.append((bid, "empty")); continue
        zf = zipfile.ZipFile(io.BytesIO(raw))
        members = [m for m in zf.namelist() if m.lower().endswith(".jsonl")]
        if not members:
            members = [m for m in zf.namelist() if not m.endswith("/")]
        if not members:
            fail.append((bid, "no member")); continue
        data = zf.read(members[0])
        with open(dest, "wb") as f:
            f.write(data)
        ok.append(bid)
    except Exception as e:
        fail.append((bid, repr(e)[:80]))

print("下载成功", len(ok), "失败", len(fail))
for b in fail: print("  FAIL", b)
# 行数统计
for bid in sorted(os.listdir(OUT)):
    p = os.path.join(OUT, bid)
    with open(p, "rb") as f:
        n = sum(1 for _ in f)
    print("  %-22s %5d 行  %8d bytes" % (bid, n, os.path.getsize(p)))
