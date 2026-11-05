#!/usr/bin/env python3
"""生成听幕·屏 KWS 关键词文件与 App 端拼音映射表。

KWS 模型(sherpa-onnx-kws-zipformer-wenetspeech-3.3M-2024-01-01-mobile)的 tokens
是带调拼音音素(声母 + 带调韵母),keywords.txt 每行形如:

    x ià y ī zh àn @下一站

本脚本:
  1. 把触发词(汉字)转成音素序列,校验每个 token 都在模型 tokens.txt 里;
  2. 输出 keywords.txt(构建时随模型目录进 assets);
  3. 生成 App 端两个 Kotlin 数据文件(供运行时自定义触发词):
       HanziToPinyin.kt  汉字 -> TONE3 音节
       PinyinTable.kt    TONE3 音节 -> "声母 带调韵母"

用法: python3 gen_kws_keywords.py [--words-file f]
"""
import argparse
import os
import re
import sys
import unicodedata

from pypinyin import lazy_pinyin, Style

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TOKENS = os.path.join(ROOT, "models/kws-wenetspeech-mobile/tokens.txt")
OUT_KEYWORDS = os.path.join(ROOT, "models/kws-wenetspeech-mobile/keywords.txt")
OUT_HANZI = os.path.join(ROOT, "android/Tingmu/app/src/main/java/com/zcode/tingmu/HanziToPinyin.kt")
OUT_TABLE = os.path.join(ROOT, "android/Tingmu/app/src/main/java/com/zcode/tingmu/PinyinTable.kt")

# 默认触发词(乘车报站句式;都是短、高频、句首词)
DEFAULT_WORDS = ["下一站", "终点站", "请下车", "换乘", "到站", "列车进站"]

# 声母,双字母优先匹配
INITIALS = ["zh", "ch", "sh", "b", "p", "m", "f", "d", "t", "n", "l", "g", "k", "h", "j", "q", "x", "r", "z", "c", "s", "y", "w"]

# 数字调 -> 符号调(放调规则:含 a 放 a;否则 o/e;iu/ui 放末位 u;ü 放自身)
TONE_MARKS = {
    "a": "āáǎà", "e": "ēéěè", "i": "īíǐì", "o": "ōóǒò", "u": "ūúǔù", "ü": "ǖǘǚǜ",
}


def place_tone(final_base, tone):
    if not tone:
        return final_base
    n = int(tone) - 1
    if "a" in final_base:
        tgt, pos = "a", final_base.index("a")
    elif "o" in final_base:
        tgt, pos = "o", final_base.index("o")
    elif "e" in final_base:
        tgt, pos = "e", final_base.index("e")
    elif final_base.endswith("iu"):
        tgt, pos = "u", len(final_base) - 1
    elif final_base.endswith("ui"):
        tgt, pos = "i", final_base.index("i")
    elif "i" in final_base:
        tgt, pos = "i", final_base.index("i")
    elif "u" in final_base:
        tgt, pos = "u", final_base.index("u")
    elif "ü" in final_base:
        tgt, pos = "ü", final_base.index("ü")
    else:
        return final_base
    return final_base[:pos] + TONE_MARKS[tgt][n] + final_base[pos + 1:]


def syllable_pair(syl_tone3):
    """TONE3 音节(xia4) -> (声母, 带调韵母) 或 None。

    规则以官方 keywords.txt 样本回归校验:
      yi1->y ī / wen2->w én / wang4->w àng / you3->y ǒu / ai4->ài / er3->ěr
    """
    m = re.fullmatch(r"([a-zü]+)([0-5]?)", syl_tone3.replace("v", "ü"))
    if not m:
        return None
    base, tone = m.group(1), m.group(2)
    for ini in INITIALS:
        if base.startswith(ini) and len(base) > len(ini):
            return ini, place_tone(base[len(ini):], tone)
    # 零声母:正字法 y/w 前缀当声母(yi/wu/yu/yan/wang...),其余纯韵母(ai/er/ou...)
    if base[0] in ("y", "w"):
        return base[0], place_tone(base[1:], tone)
    return "", place_tone(base, tone)


def load_tokens():
    toks = set()
    with open(TOKENS, encoding="utf-8") as f:
        for line in f:
            parts = line.strip().split()
            if parts:
                toks.add(parts[0])
    return toks


def word_tokens(word):
    """汉字词 -> [声母/韵母 token...] 或 None(含非汉字/无拼音)。"""
    syls = lazy_pinyin(word, style=Style.TONE3, errors="ignore")
    if not syls:
        return None
    toks = []
    for s in syls:
        pair = syllable_pair(s)
        if pair is None:
            return None
        if pair[0]:
            toks.append(pair[0])
        toks.append(pair[1])
    return toks


def build_keywords(words, tokens):
    lines, ok_words, failed = [], [], []
    for w in words:
        toks = word_tokens(w)
        if toks is None or any(t not in tokens for t in toks):
            failed.append(w)
            continue
        lines.append(" ".join(toks) + " @" + w)
        ok_words.append(w)
    return "\n".join(lines) + "\n", ok_words, failed


def build_hanzi_table(tokens):
    """遍历 CJK 基本区,产出 汉字->音节 与 音节->(声母,韵母) 两张表。"""
    hanzi, syls = {}, {}
    for cp in range(0x4E00, 0x9FA6):
        ch = chr(cp)
        p = lazy_pinyin(ch, style=Style.TONE3, errors="ignore")
        if not p or not re.fullmatch(r"[a-z]+[0-5]?", p[0]):
            continue
        pair = syllable_pair(p[0])
        if pair is None or pair[0] not in tokens or pair[1] not in tokens:
            continue
        hanzi[ch] = p[0]
        syls[p[0]] = pair
    return hanzi, syls


def esc(s):
    return s.replace("\\", "\\\\").replace('"', '\\"')


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--words-file", help="每行一个触发词;缺省用内置默认集")
    args = ap.parse_args()

    tokens = load_tokens()
    words = DEFAULT_WORDS
    if args.words_file:
        with open(args.words_file, encoding="utf-8") as f:
            words = [ln.strip() for ln in f if ln.strip()]

    kw_text, ok_words, failed = build_keywords(words, tokens)
    if failed:
        print("!! 这些触发词无法转成音素(非汉字或音素越界):", " ".join(failed), file=sys.stderr)
        if not ok_words:
            sys.exit(1)
    with open(OUT_KEYWORDS, "w", encoding="utf-8") as f:
        f.write(kw_text)
    print("keywords.txt: %d 词 -> %s" % (len(ok_words), OUT_KEYWORDS))
    print(kw_text, end="")

    hanzi, syls = build_hanzi_table(tokens)
    print("hanzi=%d syllables=%d" % (len(hanzi), len(syls)))

    with open(OUT_HANZI, "w", encoding="utf-8") as f:
        f.write("package com.zcode.tingmu\n\n")
        f.write("/** 汉字 -> TONE3 拼音音节(自动生成 by scripts/gen_kws_keywords.py,勿手改)。 */\n")
        f.write("object HanziToPinyin {\n")
        items = ["%s%s" % (ch, syl) for ch, syl in sorted(hanzi.items())]
        write_chunked(f, items, 5000)
        f.write("    val MAP: Map<String, String> by lazy {\n")
        f.write("        ALL.split(',').mapNotNull { if (it.length >= 2) it.substring(0, 1) to it.substring(1) else null }.toMap()\n")
        f.write("    }\n")
        f.write("}\n")

    with open(OUT_TABLE, "w", encoding="utf-8") as f:
        f.write("package com.zcode.tingmu\n\n")
        f.write("/** TONE3 音节 -> \"声母 带调韵母\"(自动生成 by scripts/gen_kws_keywords.py,勿手改)。 */\n")
        f.write("object PinyinTable {\n")
        items = ["%s:%s" % (syl, (ini + " " + fin).strip()) for syl, (ini, fin) in sorted(syls.items())]
        write_chunked(f, items, 5000)
        f.write("    val MAP: Map<String, List<String>> by lazy {\n")
        f.write("        ALL.split(',').filter { it.isNotEmpty() }.associate {\n")
        f.write("            it.substringBefore(':') to it.substringAfter(':').split(' ')\n")
        f.write("        }\n")
        f.write("    }\n")
        f.write("}\n")


def write_chunked(f, items, per_chunk):
    """大表拆成多个字符串常量块(<64KB/条,避开 JVM clinit 方法上限)。

    条目内不含逗号;块尾补一个逗号,运行时 ALL.split(',') 后过滤空串。"""
    chunks = [items[i:i + per_chunk] for i in range(0, len(items), per_chunk)]
    for idx, ch in enumerate(chunks, 1):
        f.write('    private val C%d = "%s,"\n' % (idx, ",".join(esc(x) for x in ch)))
    f.write("    private val ALL get() = " + " + ".join("C%d" % i for i in range(1, len(chunks) + 1)) + "\n\n")
    print("written: %s / %s" % (OUT_HANZI, OUT_TABLE))


if __name__ == "__main__":
    main()
