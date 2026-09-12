#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
逐字保真断言：验证「答案的每一句都逐字来自被检索到的知识片段」。

这是本项目抽取式回答的**核心不变式** —— 模型只被允许挑选句子编号与起小标题，
从不准造句。批次 A2 引入行内引用标记 [n] 后必须证明它没破坏这条不变式
（标记只在句末追加，句元原文零改动）。

为什么用「子串校验」而不是「开关前后 A/B 比对」：
  A/B 需要为改一个配置重启后端，且两次运行的检索结果可能不同（检索本身无随机性，
  但缓存/排序细节仍需人工确认）。子串校验是**更强**的断言 —— 它直接证明答案
  没有改写、没有幻觉；一旦模型越界造句，这里一定会红。

允许的非原文内容（不算违规）：
  - `**小标题**` 行：本就要模型概括，属展示层，不是事实句
  - `参考文件：` 尾注：来自 knowledge_chunk 的 source_url/source_title，不是片段正文
  - markdown 语法标记：`- `、表格的 `|` 与 `|---|` 分隔行、行内引用 `[n]`

用法：
  D:\\Program\\Python\\Anaconda3\\envs\\myenv\\python.exe tools\\eval\\verify_verbatim.py
  ... --set tools\\eval\\golden_set.csv --limit 20 --threshold 1.0

退出码：0 = 全部题目 100% 保真；1 = 有题目出现非原文内容（打印具体句子）。
"""

import argparse
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from eval_common import DEFAULT_BASE_URL, ask, is_fallback, read_golden, strip_citations  # noqa: E402

# 表格分隔行（|---|---|）与纯语法行
SEP_ROW = re.compile(r"^[\s|:\-]+$")
# 小标题行：**xxx**
TITLE_LINE = re.compile(r"^\*\*.+\*\*$")
# 参考文件尾注起始
FOOTER_START = re.compile(r"^参考文件[:：]")


def norm(text):
    """归一化用于子串比较：去掉行内引用、所有空白、表格竖线。

    去掉竖线是必要的：源片段里的表格行可能写作 `a | b`，而渲染层会补成 `| a | b |`，
    两者内容一致但字面不同，归一化后才可比。
    """
    t = strip_citations(text or "")
    t = t.replace("|", "")
    t = re.sub(r"\s+", "", t)
    return t


def norm_loose(text):
    """更宽松的归一化：连标点分隔符也去掉。

    用来容忍**渲染层的展示变换**——最典型的是「单行表格退化成 `标签：内容`」
    （`ChatServiceImpl.toInlineTableRow`）：源片段里是 `| 百分制成绩 | 等级制成绩 | 绩点 |`，
    渲染后成了 `百分制成绩：等级制成绩；绩点`。标点是渲染层补的，事实内容一字未改。

    这类差异单独计为「格式差异」并在报告里列出，**不算保真失败**、也不掩盖真正的改写：
    真正被改写/幻觉的句子，去掉标点后依然对不上原文。
    """
    t = strip_citations(text or "")
    t = re.sub(r"[\s|：；、，,;:]+", "", t)
    return t


def answer_segments(answer):
    """把答案拆成「应当逐字来自原文」的片段，剔除允许模型生成的展示层内容。"""
    body = answer or ""
    # 先砍掉参考文件尾注（其内容来自知识库另一列，不是片段正文）
    lines = body.split("\n")
    kept = []
    for line in lines:
        if FOOTER_START.match(line.strip()):
            break
        kept.append(line)

    segments = []
    for line in kept:
        s = line.strip()
        if not s:
            continue
        if TITLE_LINE.match(s):
            continue          # **小标题** 允许模型生成
        if SEP_ROW.match(s):
            continue          # 表格分隔行是渲染层补的
        s = re.sub(r"^[-*+]\s+", "", s)   # 去掉列表符号
        s = re.sub(r"^\|\s*", "", s)
        n = norm(s)
        if n:
            segments.append((s, n))
    return segments


def verify_one(base_url, row, timeout, retries=2):
    """拉一次问答做校验。网络抖动很常见，而这是红线检查 ——
    单次抖动不该让整轮变红，故带重试（默认 2 次）。"""
    last = None
    for attempt in range(retries + 1):
        try:
            data, _ = ask(base_url, row["question"], no_cache=True, timeout=timeout)
            last = data
            break
        except Exception as e:
            last = None
            if attempt == retries:
                raise
            time.sleep(1.0 * (attempt + 1))
    if last is None:
        raise RuntimeError("请求失败")
    data = last
    answer = data.get("answer") or ""
    sources = data.get("sources") or []
    corpus = norm("\n".join((s.get("content") or "") for s in sources))

    if is_fallback(answer) or data.get("rejected") or not sources:
        # 兜底/拒答没有实质答案句，无从校验保真（也谈不上改写）
        return {"id": row["id"], "skipped": True, "total": 0, "bad": [], "reason": "兜底/拒答/无来源"}

    corpus_loose = norm_loose("\n".join((s.get("content") or "") for s in sources))
    bad = []
    soft = []
    segs = answer_segments(answer)
    for original, n in segs:
        if n in corpus:
            continue
        # 严格比对没过：再试「连标点也去掉」的宽松比对。
        # 过了 → 是渲染层的展示变换（如单行表格退化），记入 soft 而不是失败。
        if norm_loose(original) in corpus_loose:
            soft.append(original)
            continue
        bad.append(original)
    return {"id": row["id"], "skipped": False, "total": len(segs),
            "bad": bad, "soft": soft, "reason": ""}


def main():
    ap = argparse.ArgumentParser(description="逐字保真断言")
    ap.add_argument("--set", default=os.path.join(os.path.dirname(os.path.abspath(__file__)), "golden_set.csv"))
    ap.add_argument("--base-url", default=DEFAULT_BASE_URL)
    ap.add_argument("--limit", type=int, default=0)
    ap.add_argument("--timeout", type=int, default=120)
    ap.add_argument("--threshold", type=float, default=1.0, help="要求的最低保真比例（默认 1.0 = 不许有非原文句）")
    args = ap.parse_args()

    rows = read_golden(args.set)
    if args.limit:
        rows = rows[: args.limit]

    print("逐字保真校验：%d 题  目标=%s" % (len(rows), args.base_url))
    total_segs = 0
    total_bad = 0
    total_soft = 0
    failed = []
    for i, row in enumerate(rows, 1):
        try:
            r = verify_one(args.base_url, row, args.timeout)
        except Exception as e:
            print("  [%2d/%2d] %-6s ERROR %s" % (i, len(rows), row["id"], str(e)[:80]))
            failed.append(row["id"])
            continue
        if r["skipped"]:
            print("  [%2d/%2d] %-6s 跳过（%s）" % (i, len(rows), r["id"], r["reason"]))
            continue
        total_segs += r["total"]
        total_bad += len(r["bad"])
        total_soft += len(r.get("soft") or [])
        mark = "OK" if not r["bad"] else "非原文 %d/%d" % (len(r["bad"]), r["total"])
        if r.get("soft"):
            mark += "（含 %d 条格式差异）" % len(r["soft"])
        print("  [%2d/%2d] %-6s %s" % (i, len(rows), r["id"], mark))
        if r["bad"]:
            failed.append(r["id"])
            for b in r["bad"][:3]:
                print("            非原文句： %s" % b[:100])

    print("-" * 68)
    fidelity = 1.0 - (total_bad / total_segs) if total_segs else 1.0
    print("校验片段 %d 个，其中非原文 %d 个" % (total_segs, total_bad))
    if total_soft:
        print("另有 %d 条「格式差异」（渲染层变换，如单行表格退化成“标签：内容”，事实内容未改）"
              % total_soft)
    print("逐字保真率 = %.4f   阈值 = %.2f" % (fidelity, args.threshold))
    if failed:
        print("未通过题号：%s" % ", ".join(failed))
    ok = fidelity >= args.threshold and not failed
    print("结论：%s" % ("通过 —— 答案未出现任何非原文内容" if ok else "不通过 —— 存在改写/幻觉，需要排查"))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
