#!/usr/bin/env python3
"""按册批量生成「单词读音」音频。

产物结构（页面用 /audio/word-match/<册>/<slug>.<ext> 取）：
    <out>/word-match/<bookId>/<slug>.m4a       单词音频（默认 AAC 32k）
    <out>/word-match/<bookId>/index.json       该册词→文件映射 + 生成参数（排查用）
    <out>/word-match/<bookId>/sprite.m4a       [--sprite] 整册拼成一个文件
    <out>/word-match/<bookId>/sprite.json      [--sprite] 每个词在 sprite 里的起止秒数
    <out>/word-match/silence.m4a               50ms 静音，用于解锁移动端自动播放策略

两个引擎（--engine）：
    say    macOS 内置 `say`，**完全离线**。质量取决于语音档位：
           `Samantha` 是 compact 档（22.05kHz 共振峰合成，机械感明显）；
           `Samantha (Premium)` 等是神经网络档（48kHz），需先在
           「系统设置 → 辅助功能 → 朗读内容 → 系统声音 → 管理声音」下载。
    edge   微软 Edge 神经语音（需 `pip install edge-tts`，24kHz 神经合成，接近真人）。
           批量生成时是**一次性联网**，产物落地后运行期不再依赖网络。

为什么默认 m4a/AAC 而不是 mp3（2026-10-27 实测，同一批词）：
    mp3 48k = 100%，aac_at 32k = 70.5%，aac_at 24k = 53.7%。
    AAC 比 MP3 编码效率高约 30%，iOS/微信 webview 原生支持。
    ⚠️ 换扩展名必须同步页面的 AUDIO_EXT，且服务端 Content-Type 要是 audio/mp4
    （Spring 静态资源实测返回 audio/mp4、Range 返回 206，OK）。

--sprite 的作用：把整册拼成**一个**音频文件，前端一次加载、用 currentTime 定位。
    请求数从「每个词 1 次」降到「整册 1 次」，首字延迟归零。
    只适合词量小的册（小学 8 册 64~155 词，整册约 0.4~1.1 MB）；
    词量大的册（初中/高中 300~550 词）单文件更大，仍走按需单文件。

slug 规则（**必须与页面里的 wordAudioSlug() 完全一致**）：
    小写 → 非 [a-z0-9] 连续段折成 '-' → 去首尾 '-'
    "pencil box" → "pencil-box"、"I'm" → "i-m"

用法：
    python3 scripts/learn/gen_word_audio.py --book pep-3-1 --engine edge --edge-rate -10%
    python3 scripts/learn/gen_word_audio.py --book pep-3-1 pep-3-2 --engine edge --jobs 3 --sprite
    python3 scripts/learn/gen_word_audio.py --book pep-3-1 --check      # 只核对，不生成
"""
import argparse
import json
import re
import shutil
import subprocess
import sys
import tempfile
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PEP_JSON = ROOT / "src/main/resources/learn/pep-words.json"
DEFAULT_OUT = ROOT / "src/main/resources/static/audio"
SILENCE_SEC = 0.05

# sprite 里每个词前后的静音（毫秒）
#   前 pad 吸收 AAC priming 延迟与 seek 精度误差；后 pad 吸收 timeupdate 的 250ms 粒度，
#   否则「播到 end 就停」会多放出下一个词的开头。
SPRITE_HEAD_MS = 120
SPRITE_TAIL_MS = 300

# 与页面 wordAudioSlug 保持一致
SLUG_RE = re.compile(r"[^a-z0-9]+")

# 各引擎的默认参数: (默认音色, 采样率, 码率)
ENGINE_DEFAULTS = {
    "say": ("Samantha", 22050, "48k"),
    "edge": ("en-US-JennyNeural", 24000, "32k"),
}

FORMAT_EXT = {"m4a": "m4a", "mp3": "mp3"}


def slugify(word: str) -> str:
    return SLUG_RE.sub("-", word.strip().lower()).strip("-")


def load_books():
    with PEP_JSON.open(encoding="utf-8") as f:
        return json.load(f)["books"]


def uniq_words(book):
    """按首次出现顺序去重（大小写不敏感），保留原始写法用于朗读。"""
    seen, out = set(), []
    for theme in book.get("themes", []):
        for pair in theme.get("words", []):
            en = (pair[0] or "").strip()
            if not en:
                continue
            key = en.lower()
            if key in seen:
                continue
            seen.add(key)
            out.append(en)
    return out


def run(cmd, **kw):
    return subprocess.run(cmd, capture_output=True, text=True, **kw)


def probe_duration(path: Path) -> float:
    r = run(["ffprobe", "-v", "error", "-show_entries", "format=duration",
             "-of", "default=noprint_wrappers=1:nokey=1", str(path)])
    try:
        return float(r.stdout.strip())
    except ValueError:
        return 0.0


def has_encoder(name: str) -> bool:
    r = run(["ffmpeg", "-hide_banner", "-encoders"])
    return name in r.stdout


def encode(src: Path, dest: Path, sample_rate: int, bitrate: str,
           normalize: bool, fmt: str, src_pcm_rate: int | None = None) -> tuple[bool, str]:
    """统一编码到目标格式（单声道）。返回 (成功, 错误说明)。

    src_pcm_rate 非空时表示 src 是**裸 PCM**（s16le/单声道/该采样率），
    必须显式给 -f/-ar/-ac，否则 ffmpeg 无法从 .pcm 后缀推断而报
    "Invalid data found when processing input"。
    """
    cmd = ["ffmpeg", "-y", "-loglevel", "error"]
    if src_pcm_rate:
        cmd += ["-f", "s16le", "-ar", str(src_pcm_rate), "-ac", "1"]
    cmd += ["-i", str(src), "-ac", "1", "-ar", str(sample_rate)]
    if fmt == "m4a":
        # aac_at = Apple AudioToolbox，比 ffmpeg 内置 aac 质量好且严格守码率；
        # 不可用时退回内置 aac（会自行压到更低码率，体积更小但质量略降）。
        enc = "aac_at" if has_encoder("aac_at") else "aac"
        cmd += ["-c:a", enc, "-b:a", bitrate, "-f", "mp4"]
    else:
        cmd += ["-c:a", "libmp3lame", "-b:a", bitrate]
    if normalize:
        # 单词音频只有 0.5~2s，loudnorm 需要更长窗口才准；用 dynaudnorm 更稳
        cmd += ["-af", "dynaudnorm=f=150:g=9:p=0.9"]
    cmd.append(str(dest))
    r = run(cmd)
    if r.returncode != 0 or not dest.exists():
        return False, (r.stderr or "").strip()[:120]
    return True, ""


def synth_say(word: str, dest: Path, voice: str, rate: int, sample_rate: int,
              bitrate: str, normalize: bool, fmt: str) -> tuple[bool, str]:
    """say → aiff → 目标格式。"""
    with tempfile.TemporaryDirectory() as td:
        aiff = Path(td) / "t.aiff"
        r = run(["say", "-v", voice, "-r", str(rate), "-o", str(aiff), word])
        if r.returncode != 0 or not aiff.exists():
            return False, f"say 失败: {r.stderr.strip()[:120]}"
        ok, err = encode(aiff, dest, sample_rate, bitrate, normalize, fmt)
        if not ok:
            return False, f"编码失败: {err}"
    dur = probe_duration(dest)
    if dur <= 0.08:
        return False, f"时长异常 {dur:.3f}s"
    return True, f"{dur:.2f}s"


def synth_edge(word: str, dest: Path, voice: str, edge_rate: str, sample_rate: int,
               bitrate: str, normalize: bool, fmt: str) -> tuple[bool, str]:
    """edge-tts 直出 mp3 → 转目标格式。"""
    try:
        import asyncio
        import edge_tts
    except ImportError:
        return False, "未安装 edge-tts（pip install edge-tts）"

    with tempfile.TemporaryDirectory() as td:
        raw = Path(td) / "raw.mp3"
        try:
            async def go():
                await edge_tts.Communicate(word, voice, rate=edge_rate).save(str(raw))
            asyncio.run(go())
        except Exception as e:                      # 网络抖动、限流、音色名错误
            return False, f"edge 失败: {str(e)[:120]}"
        if not raw.exists() or raw.stat().st_size < 512:
            return False, "edge 返回空音频"
        ok, err = encode(raw, dest, sample_rate, bitrate, normalize, fmt)
        if not ok:
            return False, f"编码失败: {err}"
    dur = probe_duration(dest)
    if dur <= 0.08:
        return False, f"时长异常 {dur:.3f}s"
    return True, f"{dur:.2f}s"


def make_silence(dest: Path, sample_rate: int, bitrate: str, fmt: str) -> None:
    src = ["-f", "lavfi", "-i", f"anullsrc=r={sample_rate}:cl=mono", "-t", str(SILENCE_SEC)]
    if fmt == "m4a":
        cmd = ["ffmpeg", "-y", "-loglevel", "error"] + src + \
              ["-c:a", "aac_at" if has_encoder("aac_at") else "aac", "-b:a", bitrate, "-f", "mp4", str(dest)]
    else:
        cmd = ["ffmpeg", "-y", "-loglevel", "error"] + src + \
              ["-c:a", "libmp3lame", "-b:a", bitrate, str(dest)]
    run(cmd)


def decode_pcm(path: Path, sample_rate: int) -> bytes:
    """解码成 s16le / 单声道 / 指定采样率的原始 PCM。"""
    r = subprocess.run(["ffmpeg", "-v", "error", "-i", str(path),
                        "-f", "s16le", "-ac", "1", "-ar", str(sample_rate), "-"],
                       capture_output=True)
    return r.stdout


def build_sprite(bag: Path, words: list, files: dict, sample_rate: int, bitrate: str,
                 fmt: str) -> tuple[int, int]:
    """把整册拼成一个音频 + 偏移索引。返回 (词数, sprite 字节数)。

    做法：逐词解码成 PCM，在内存里按「前静音 + 词 + 后静音」拼起来，
    记录每个词首/末样本的位置（换算成秒），最后整体编码一次。
    这样偏移是**按样本精确计算**的，不依赖容器的 seek 精度。
    """
    head = int(sample_rate * SPRITE_HEAD_MS / 1000)
    tail = int(sample_rate * SPRITE_TAIL_MS / 1000)
    sil_head = b"\x00\x00" * head
    sil_tail = b"\x00\x00" * tail

    parts, entries, pos = [], [], 0
    for w in words:
        p = bag / files[w]
        if not p.exists():
            continue
        data = decode_pcm(p, sample_rate)
        n = len(data) // 2
        if n <= 0:
            continue
        start = pos + head
        end = start + n
        parts += [sil_head, data, sil_tail]
        entries.append([w, round(start / sample_rate, 3), round(end / sample_rate, 3)])
        pos = end + tail

    if not entries:
        return 0, 0

    sprite = bag / f"sprite.{FORMAT_EXT[fmt]}"
    with tempfile.TemporaryDirectory() as td:
        raw = Path(td) / "sprite.pcm"
        raw.write_bytes(b"".join(parts))
        ok, err = encode(raw, sprite, sample_rate, bitrate, False, fmt,
                         src_pcm_rate=sample_rate)
        if not ok:
            raise RuntimeError(f"sprite 编码失败: {err}")

    (bag / "sprite.json").write_text(json.dumps({
        "sampleRate": sample_rate,
        "headMs": SPRITE_HEAD_MS, "tailMs": SPRITE_TAIL_MS,
        "duration": round(pos / sample_rate, 3),
        "words": entries,
    }, ensure_ascii=False, separators=(",", ":")) + "\n", encoding="utf-8")
    return len(entries), sprite.stat().st_size


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--book", nargs="+", default=None,
                    help="册次 id，可多个；all = 全部人教版册次")
    ap.add_argument("--engine", choices=sorted(ENGINE_DEFAULTS), default="say")
    ap.add_argument("--voice", default=None,
                    help="say 传 macOS 语音名；edge 传 en-US-JennyNeural 这类名")
    ap.add_argument("--rate", type=int, default=150, help="say 的语速（词/分钟）")
    ap.add_argument("--edge-rate", default="-10%", help="edge 的语速偏移")
    ap.add_argument("--format", choices=sorted(FORMAT_EXT), default="m4a")
    ap.add_argument("--bitrate", default=None, help="留空用引擎默认")
    ap.add_argument("--sample-rate", type=int, default=None)
    ap.add_argument("--normalize", action="store_true", help="响度归一")
    ap.add_argument("--jobs", type=int, default=1, help="并发数（edge 建议 3~6）")
    ap.add_argument("--sprite", action="store_true", help="额外产出整册单文件 + 偏移索引")
    ap.add_argument("--prune", action="store_true",
                    help="生成 sprite 后删除单词文件（只适合确定只走 sprite 的册）")
    ap.add_argument("--out", default=str(DEFAULT_OUT))
    ap.add_argument("--force", action="store_true")
    ap.add_argument("--check", action="store_true", help="只核对产物")
    args = ap.parse_args()

    def_voice, def_sr, def_br = ENGINE_DEFAULTS[args.engine]
    voice = args.voice or def_voice
    sample_rate = args.sample_rate or def_sr
    bitrate = args.bitrate or def_br
    fmt = args.format
    ext = FORMAT_EXT[fmt]

    need = ["ffmpeg", "ffprobe"] + (["say"] if args.engine == "say" else [])
    for tool in need:
        if not shutil.which(tool):
            print(f"缺少命令: {tool}", file=sys.stderr)
            return 2

    books = load_books()
    want = args.book or ["pep-3-1"]
    if "all" in want:
        targets = books
    else:
        by_id = {b["id"]: b for b in books}
        missing = [x for x in want if x not in by_id]
        if missing:
            print(f"未知册次: {missing}（可用: {list(by_id)[:5]}…）", file=sys.stderr)
            return 2
        targets = [by_id[x] for x in want]

    out_root = Path(args.out) / "word-match"
    out_root.mkdir(parents=True, exist_ok=True)
    if not args.check:
        make_silence(out_root / f"silence.{ext}", sample_rate, bitrate, fmt)

    def synth(word: str, dest: Path):
        if args.engine == "say":
            return synth_say(word, dest, voice, args.rate, sample_rate, bitrate, args.normalize, fmt)
        return synth_edge(word, dest, voice, args.edge_rate, sample_rate, bitrate, args.normalize, fmt)

    total_new = total_skip = 0
    failures = []
    for book in targets:
        bid = book["id"]
        bag = out_root / bid
        bag.mkdir(parents=True, exist_ok=True)
        words = uniq_words(book)
        index = {"bookId": bid, "label": book.get("label", ""),
                 "engine": args.engine, "voice": voice,
                 "rate": args.edge_rate if args.engine == "edge" else args.rate,
                 "sampleRate": sample_rate, "bitrate": bitrate, "format": fmt,
                 "words": {}}
        done = 0

        todo = []
        for en in words:
            slug = slugify(en)
            if not slug:
                failures.append((bid, en, "slug 为空"))
                continue
            dest = bag / f"{slug}.{ext}"
            index["words"][en] = f"{slug}.{ext}"
            if args.check:
                if dest.exists() and probe_duration(dest) > 0.08:
                    done += 1
                else:
                    failures.append((bid, en, "缺失"))
                continue
            if dest.exists() and not args.force:
                total_skip += 1
                done += 1
            else:
                todo.append((en, dest))

        if todo:
            jobs = max(1, args.jobs)
            with ThreadPoolExecutor(max_workers=jobs) as pool:
                futs = {pool.submit(synth, en, dest): (en, dest) for en, dest in todo}
                for n, fut in enumerate(as_completed(futs), 1):
                    en, _dest = futs[fut]
                    try:
                        ok, msg = fut.result()
                    except Exception as e:              # 单条异常不能打断整册
                        ok, msg = False, f"{type(e).__name__}: {e}"
                    if ok:
                        total_new += 1
                        done += 1
                    else:
                        failures.append((bid, en, msg))
                    if n % 50 == 0 or n == len(futs):
                        print(f"  {bid} … {n}/{len(futs)}", flush=True)

        index["count"] = done
        size_kb = sum(f.stat().st_size for f in bag.glob(f"*.{ext}")) / 1024
        line = f"{bid} ({book.get('label','')}): {done}/{len(words)} 个 · {size_kb:.0f} KB · {args.engine}/{voice}"

        if args.sprite and not args.check and done == len(words):
            n, sbytes = build_sprite(bag, words, index["words"], sample_rate, bitrate, fmt)
            line += f" · sprite {n} 词 / {sbytes/1024:.0f} KB"
            if args.prune:
                removed = 0
                for f in bag.glob(f"*.{ext}"):
                    if f.name.startswith("sprite"):
                        continue
                    f.unlink()
                    removed += 1
                line += f" · 已删单词文件 {removed} 个"

        (bag / "index.json").write_text(
            json.dumps(index, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(line)

    if args.check:
        print(f"\n核对完成：失败 {len(failures)} 个")
    else:
        print(f"\n新生成 {total_new} 个，跳过已存在 {total_skip} 个，失败 {len(failures)} 个")
    for bid, en, msg in failures[:20]:
        print(f"  ✗ {bid} / {en}: {msg}")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
