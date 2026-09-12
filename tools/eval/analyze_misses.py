#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
漏召回分析：把跑分结果里「gold 没进 top-K」的题挑出来，连同**实际召回了什么**一起打印。

为什么需要这个工具：
  跑分只告诉你 Recall 是多少，不告诉你**为什么**没召回。而漏召回有两类完全不同的原因：
    ① 标注错了 —— 我标的 gold 片段其实答不了这个问题，真正该答的那个没标；
    ② 检索真的漏了 —— 标注没错，是检索没把它带上来。
  这两类的处理方式完全相反（改标注 vs 改检索/加数据）。本工具把实际召回列表摆出来，
  对着看就知道该改哪边。

用法：
  D:\\Program\\Python\\Anaconda3\\envs\\myenv\\python.exe tools\\eval\\analyze_misses.py
  ... --csv tools\\eval\\results\\20260912-xxxx_verify.csv --top 20
  ... --type multihop       # 只看多跳题（默认全部）
"""

import argparse
import csv
import glob
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from eval_common import parse_ids, read_golden  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))


def latest_csv():
    files = sorted(glob.glob(os.path.join(HERE, "results", "*.csv")))
    return files[-1] if files else None


def load_run(path):
    rows = {}
    with open(path, "r", encoding="utf-8-sig", newline="") as f:
        for r in csv.DictReader(f):
            rows[(r.get("id") or "").strip()] = r
    return rows


def main():
    ap = argparse.ArgumentParser(description="漏召回分析")
    ap.add_argument("--csv", default=None, help="跑分结果 CSV（默认取 results 下最新一个）")
    ap.add_argument("--set", default=os.path.join(HERE, "golden_set.csv"))
    ap.add_argument("--type", default="", help="只看某类型：normal / multihop")
    ap.add_argument("--top", type=int, default=0, help="最多打印多少条（0=全部）")
    args = ap.parse_args()

    csv_path = args.csv or latest_csv()
    if not csv_path or not os.path.exists(csv_path):
        print("找不到跑分结果 CSV，先跑 run_eval.py")
        return 1
    print("分析对象：%s" % os.path.basename(csv_path))

    run = load_run(csv_path)
    golden = {r["id"]: r for r in read_golden(args.set)}

    # 用 gold 的标题信息帮人判断，故再读一次 golden（含 notes）
    misses = []
    partial = []
    for qid, g in golden.items():
        if args.type and g["type"] != args.type:
            continue
        gold = parse_ids(g["gold_chunk_ids"])
        if not gold:
            continue
        r = run.get(qid)
        if not r or r.get("status") != "ok":
            continue
        # 答案路径的 CSV 列叫 source_chunk_ids（喂给生成器的片段），
        # 检索路径的列叫 retrieved_chunk_ids（检索层的 top-K）。两个都要认，
        # 否则拿检索层的 CSV 跑分析会得到「全部未召回」的假象。
        got = parse_ids(r.get("retrieved_chunk_ids") or r.get("source_chunk_ids") or "")
        hit = [x for x in gold if x in got]
        if not hit:
            misses.append((qid, g, gold, got))
        elif len(hit) < len(gold):
            partial.append((qid, g, gold, got, hit))

    total = sum(1 for qid, g in golden.items()
                if (not args.type or g["type"] == args.type) and parse_ids(g["gold_chunk_ids"]))
    print("有标注的题：%d；完全未召回：%d；部分未召回：%d" % (total, len(misses), len(partial)))
    print()

    if misses:
        print("=" * 78)
        print("完全未召回（gold 一个都没进 top-K）")
        print("=" * 78)
        for i, (qid, g, gold, got) in enumerate(misses):
            if args.top and i >= args.top:
                print("  ...（其余略）")
                break
            print("%-5s [%s] %s" % (qid, g["type"], g["question"]))
            print("       期望 gold : %s   %s" % (gold, g["gold_keywords"]))
            print("       实际召回  : %s" % (got or "（无来源）"))
            print("       备注      : %s" % g["notes"])
            print()

    if partial:
        print("=" * 78)
        print("部分未召回（多跳题只命中了一部分来源）")
        print("=" * 78)
        for i, (qid, g, gold, got, hit) in enumerate(partial):
            if args.top and i >= args.top:
                print("  ...（其余略）")
                break
            print("%-5s [%s] %s" % (qid, g["type"], g["question"]))
            print("       命中 %s / 缺 %s" % (hit, [x for x in gold if x not in got]))
            print()

    print("提示：判断漏召回属于哪一类 ——")
    print("  · 若「实际召回」里的片段确实能回答该问题 → 是**标注错了**，把它补进 gold_chunk_ids")
    print("  · 若确实都答不上 → 是**检索真的漏了**，考虑补数据（给权威句加全称主语）或换检索策略")
    return 0


if __name__ == "__main__":
    sys.exit(main())
