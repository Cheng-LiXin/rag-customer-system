#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
拒答阈值扫描：找出「超纲拒答率」与「正常题误拒率」之间的最优切点。

## 为什么是**离线**扫描而不是反复改配置重启
拒答规则是纯函数：`最高相似度 < 阈值 → 拒答`。而每次跑分都把每题的最高相似度
（`top_score`）记进了 CSV，所以对任意候选阈值 T，只需数一数
「top_score < T 的题有多少」就能精确复现该阈值下的拒答行为 ——
不必为了换一个阈值重启一次后端、再跑一遍 150 题。

（注意：本脚本只模拟**拒答决策**本身；它不重新评估「没被拒的那些题答案质量如何」。
 那部分由 run_eval.py 的兜底率/逐字保真断言独立守着。）

## 为什么需要扫描而不是照抄文档的 0.65
文档建议「最高相似度 < 0.65 → 未答」，但那是通用 RAG 的经验值，与本库的实际分布未必吻合。
本项目实测：正常题的最高相似度下限与超纲题的上限之间存在一条分离带，
0.65 落在带外会把一批正常题误拒 —— 这会直接打回此前费力修好的命中率。

用法：
  & $PY tools\\eval\\threshold_sweep.py --csv tools\\eval\\results\\xxx_ans-vector.csv
"""

import argparse
import csv
import glob
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from eval_common import fmt  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))


def latest_answer_csv():
    files = sorted(glob.glob(os.path.join(HERE, "results", "*_ans-*.csv")))
    if files:
        return files[-1]
    files = sorted(glob.glob(os.path.join(HERE, "results", "*.csv")))
    return files[-1] if files else None


def load(path):
    rows = []
    with open(path, "r", encoding="utf-8-sig", newline="") as f:
        for r in csv.DictReader(f):
            if r.get("status") != "ok":
                continue
            raw = (r.get("top_score") or "").strip()
            if not raw:
                continue
            try:
                score = float(raw)
            except ValueError:
                continue
            rows.append({"id": r.get("id"), "type": r.get("type"),
                         "expect": r.get("expect"), "score": score})
    return rows


def sweep(rows, lo, hi, step):
    normals = [r for r in rows if r["expect"] == "answer"]
    rejects = [r for r in rows if r["expect"] == "reject"]
    out = []
    t = lo
    while t <= hi + 1e-9:
        fr = sum(1 for r in normals if r["score"] < t) / len(normals) if normals else 0.0
        rr = sum(1 for r in rejects if r["score"] < t) / len(rejects) if rejects else 0.0
        out.append((round(t, 3), fr, rr))
        t += step
    return out, normals, rejects


def main():
    ap = argparse.ArgumentParser(description="拒答阈值扫描")
    ap.add_argument("--csv", default=None, help="答案路径跑分结果（默认取 results 下最新的 ans-*）")
    ap.add_argument("--lo", type=float, default=0.40)
    ap.add_argument("--hi", type=float, default=0.80)
    ap.add_argument("--step", type=float, default=0.01)
    ap.add_argument("--doc-threshold", type=float, default=0.65, help="文档建议值，用于对照")
    args = ap.parse_args()

    path = args.csv or latest_answer_csv()
    if not path or not os.path.exists(path):
        print("找不到跑分结果 CSV")
        return 1

    rows = load(path)
    if not rows:
        print("CSV 里没有可用的 top_score（需要答案路径的跑分结果）")
        return 1

    normals = [r for r in rows if r["expect"] == "answer"]
    rejects = [r for r in rows if r["expect"] == "reject"]
    print("扫描对象：%s" % os.path.basename(path))
    print("常规题 %d，超纲题 %d" % (len(normals), len(rejects)))

    n_scores = sorted(r["score"] for r in normals)
    r_scores = sorted(r["score"] for r in rejects)
    if n_scores and r_scores:
        print()
        print("实测分数分布：")
        print("  常规题：min %.4f  p05 %.4f  中位 %.4f  max %.4f" % (
            n_scores[0], n_scores[max(0, len(n_scores) // 20)], n_scores[len(n_scores) // 2], n_scores[-1]))
        print("  超纲题：min %.4f  中位 %.4f  max %.4f" % (
            r_scores[0], r_scores[len(r_scores) // 2], r_scores[-1]))
        print("  → 分离带：超纲上限 %.4f  ←→  常规下限 %.4f" % (r_scores[-1], n_scores[0]))

    table, _, _ = sweep(rows, args.lo, args.hi, args.step)
    print()
    print("  阈值T   超纲拒答率   正常题误拒率   判定")
    print("  " + "-" * 50)
    best = None
    for t, fr, rr in table:
        # 选点策略：先保证误拒率 = 0，再在此前提下取超纲拒答率最高者
        ok = "OK" if fr <= 0.0 else ("误拒 %d 题" % round(fr * (len(normals))))
        if fr <= 0.0 and (best is None or rr > best[2]):
            best = (t, fr, rr)
        mark = ""
        if abs(t - args.doc_threshold) < 1e-9:
            mark = "  ← 文档建议值"
        print("  %.3f   %.4f       %.4f        %s%s" % (t, rr, fr, ok, mark))

    print()
    if best:
        t, fr, rr = best
        print("推荐阈值 = %.3f  （该点：正常题误拒率 %.4f，超纲拒答率 %.4f）" % (t, fr, rr))
    doc_fr = next((fr for tt, fr, _ in table if abs(tt - args.doc_threshold) < 1e-9), None)
    doc_rr = next((rr for tt, _, rr in table if abs(tt - args.doc_threshold) < 1e-9), None)
    if doc_fr is not None:
        print("文档建议 0.65 的代价：正常题误拒率 %.4f（约 %d 道正常题被拒），超纲拒答率 %.4f" % (
            doc_fr, round(doc_fr * len(normals)), doc_rr))
        if best and doc_fr > best[1]:
            print("→ 结论：照抄 0.65 会打回命中率，定稿应取推荐值 %.3f。" % best[0])
    return 0


if __name__ == "__main__":
    sys.exit(main())
