#!/usr/bin/env python3
"""
记忆来源审计 —— 回答「记忆是谁写的 / 有没有被某个模型带偏」。

用法：
    python3 scripts/audit_memory_provenance.py [项目标识]

    项目标识用于匹配日志文件名（默认 Story-Creator，日志形如 Story-Creator__<hash>.log）
    例：python3 scripts/audit_memory_provenance.py VOOX_ALL

原理（两层证据）：
  1) 归因：WorkBuddy 日志里
       [ModelProvider] Sending request: agent=cli, model=X   → 实际调用的模型
       ModifyBackup ... flatName=NN.m.<hash>.<文件名>        → 写文件事件 + 全局递增序号
     按行扫描维护「最近一次模型请求」，即可给每次写入贴上模型标签。
  2) 取增量：~/.workbuddy/workspace/sessions/<会话>/modify_backup/NN.m.<hash>.<文件>
     是「写入前」的快照；相邻两版之差 = 中间那次写入的增量原文。

⚠️ 备份有 GC（文件数 > meta 数），seq 不连续时 diff 会跨多次写入 → 不可精确归因。
   本脚本会显式标注「可信/跨度大」。
"""
import os
import re
import sys
import glob
import collections
import difflib

WB = os.path.expanduser('~/.workbuddy')
LOG_ROOT = os.path.join(WB, 'logs')
SESS_ROOT = os.path.join(WB, 'workspace', 'sessions')

PROJECT = sys.argv[1] if len(sys.argv) > 1 else 'Story-Creator'

pat_ts = re.compile(r'^\[(\d{1,2}/\d{1,2}/\d{4}), (\d{1,2}:\d{2}:\d{2}) ([AP]M)')
pat_req = re.compile(r'Sending request: agent=\S+, model=([\w.:\-]+)')
pat_bk = re.compile(r'ModifyBackup \| data=\{"flatName":"(\d+)\.m\.([0-9a-f]+)\.([^"]+)"\}')
pat_bkname = re.compile(r'^(\d+)\.m\.([0-9a-f]+)\.(.+)$')


def scan_logs(project):
    """扫描日志 → (每次写入的元信息, 模型调用计数)"""
    meta = {}                       # (hash, seq) -> (日期, 时间, 模型)
    model_use = collections.Counter()
    files = sorted(glob.glob(os.path.join(LOG_ROOT, '*', f'{project}__*.log')))
    for fp in files:
        day = os.path.basename(os.path.dirname(fp))
        last = None
        try:
            fh = open(fp, encoding='utf-8', errors='ignore')
        except OSError:
            continue
        with fh:
            for ln in fh:
                m = pat_req.search(ln)
                if m:
                    last = m.group(1)
                    model_use[last] += 1
                    continue
                b = pat_bk.search(ln)
                if b:
                    ts = pat_ts.match(ln)
                    t = f'{ts.group(2)} {ts.group(3)}' if ts else '?'
                    meta[(b.group(2), int(b.group(1)))] = (day, t, last)
    return meta, model_use, len(files)


def collect_backups():
    """扫描所有会话的 modify_backup → {(hash, 文件名): [(seq, path)]}"""
    groups = collections.defaultdict(list)
    for sess in glob.glob(os.path.join(SESS_ROOT, '*')):
        bk = os.path.join(sess, 'modify_backup')
        if not os.path.isdir(bk):
            continue
        for p in glob.glob(os.path.join(bk, '*.m.*')):
            m = pat_bkname.match(os.path.basename(p))
            if m:
                groups[(m.group(2), m.group(3))].append((int(m.group(1)), p))
    for k in groups:
        groups[k].sort()
    return groups


def main():
    if not os.path.isdir(LOG_ROOT):
        print(f'找不到日志目录：{LOG_ROOT}')
        return 1

    meta, model_use, nlog = scan_logs(PROJECT)
    groups = collect_backups()

    print('=' * 86)
    print(f'项目 {PROJECT}：扫描 {nlog} 个日志文件；发现 {len(groups)} 组文件备份')
    print('=' * 86)

    # ---- A. 模型使用分布 ----
    total = sum(model_use.values())
    print('\n[A] 各模型实际调用次数（只看 Sending request，不含"可用模型清单"）')
    for mdl, n in model_use.most_common():
        tag = '   ← GLM 系列' if 'glm' in mdl.lower() else ''
        print(f'    {mdl:<34}{n:>7} 次{tag}')
    glm = sum(n for m, n in model_use.items() if 'glm' in m.lower())
    if total:
        print(f'\n    GLM 系列合计 {glm}/{total} = {glm / total * 100:.1f}%')

    # ---- B. 逐文件：时间线 + 模型写入归因 ----
    for (h, fname), items in sorted(groups.items(), key=lambda kv: -len(kv[1])):
        print('\n' + '=' * 86)
        print(f'[B] {fname}  (hash {h})   共 {len(items)} 个版本快照')
        print('=' * 86)
        seqs = [s for s, _ in items]
        glm_rows = []
        for idx, (seq, path) in enumerate(items):
            day, t, mdl = meta.get((h, seq), ('?', '?', '未捕获'))
            nline = sum(1 for _ in open(path, encoding='utf-8', errors='ignore'))
            star = ''
            if mdl and 'glm' in str(mdl).lower():
                star = '  ★GLM'
                glm_rows.append((idx, seq, day, t, mdl))
            print(f'    seq {seq:>4}  {day + " " + t:<24}{str(mdl):<26}{nline:>5} 行{star}')

        if not glm_rows:
            print('\n    ✔ 该文件没有可归因到 GLM 系列的写入')
            continue

        print(f'\n    ★ {len(glm_rows)} 次写入由 GLM 系列执行，增量如下：')
        for idx, seq, day, t, mdl in glm_rows:
            if idx + 1 >= len(items):
                print(f'\n    -- seq {seq} ({day} {t}) {mdl}：是最后一版，无"写入后"快照可比')
                continue
            nxt_seq = items[idx + 1][0]
            prev_text = open(items[idx][1], encoding='utf-8', errors='ignore').read()
            cur_text = open(items[idx + 1][1], encoding='utf-8', errors='ignore').read()
            d = list(difflib.unified_diff(prev_text.splitlines(),
                                          cur_text.splitlines(), lineterm='', n=0))
            added = [l[1:] for l in d if l.startswith('+') and not l.startswith('+++')]
            removed = [l[1:] for l in d if l.startswith('-') and not l.startswith('---')]
            # 跨度判定：相邻两版 seq 差 > 1 或时间跨度大 → diff 不纯
            impure = (nxt_seq - seq) > 1
            flag = '  ⚠️ diff 跨度不纯（中间有被 GC 的写入），不可精确归因' if impure else '  ✔ 相邻版本，归因可信'
            print(f'\n    ── seq {seq}  {day} {t}  {mdl}   +{len(added)} / -{len(removed)} 行')
            print(f'       {flag}')
            for l in added[:8]:
                print(f'         + {l[:160]}')
            if len(added) > 8:
                print(f'         ... 其余 {len(added) - 8} 行')

    print('\n' + '=' * 86)
    print('提示：以上只解决「谁写的」。判断「写得对不对」必须把增量里的技术断言')
    print('      （类名/枚举/路径/数字）拿去真实代码里核对，并注意口径差异')
    print('      （如"目录大小"≠"上传包体"）。')
    print('=' * 86)
    return 0


if __name__ == '__main__':
    sys.exit(main())
