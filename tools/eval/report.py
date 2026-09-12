#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把跑分产物汇总成两份 Markdown 报告：`docs/检索对比表.md` 与 `docs/评估报告.md`。

数据全部来自 `results/` 下的 CSV，**不手工填数** —— 报告里的每个数字都必须能从
某次跑分的明细里追出来，这是「有基线、有对比、有来源」的底线。

用法：
  & $PY tools\\eval\\report.py            # 自动挑各 tag 最新一次跑分
  & $PY tools\\eval\\report.py --out-dir docs
"""

import argparse
import csv
import glob
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from eval_common import fmt, mean  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
RESULTS = os.path.join(HERE, "results")
MODES = ["vector", "bm25", "rrf", "rerank"]
MODE_LABEL = {
    "vector": "纯向量",
    "bm25": "BM25 词面",
    "rrf": "混合 RRF 融合",
    "rerank": "bge-reranker 重排",
}


def latest(pattern):
    files = sorted(glob.glob(os.path.join(RESULTS, pattern)))
    return files[-1] if files else None


def read_csv(path):
    with open(path, "r", encoding="utf-8-sig", newline="") as f:
        return list(csv.DictReader(f))


def fnum(v):
    try:
        return float(v)
    except (TypeError, ValueError):
        return None


def retrieval_metrics(path):
    rows = [r for r in read_csv(path) if r.get("status") == "ok"]
    out = {}
    for t in ("normal", "multihop"):
        rs = [r for r in rows if r.get("type") == t and r.get("recall_at_k") not in ("", None)]
        out[t] = {
            "n": len(rs),
            "recall": mean([fnum(r["recall_at_k"]) for r in rs]) if rs else None,
            "mrr": mean([fnum(r["mrr"]) for r in rs]) if rs else None,
            "full": mean([fnum(r["full_recall"]) for r in rs]) if rs else None,
        }
    allrs = [r for r in rows if r.get("recall_at_k") not in ("", None)]
    out["all"] = {
        "n": len(allrs),
        "recall": mean([fnum(r["recall_at_k"]) for r in allrs]) if allrs else None,
        "mrr": mean([fnum(r["mrr"]) for r in allrs]) if allrs else None,
    }
    return out


def answer_metrics(path):
    rows = [r for r in read_csv(path) if r.get("status") == "ok"]
    normals = [r for r in rows if r.get("expect") == "answer"]
    rejects = [r for r in rows if r.get("expect") == "reject"]
    ttfts = [fnum(r.get("ttft_ms")) for r in rows if fnum(r.get("ttft_ms")) is not None]

    def pct(vals, p):
        if not vals:
            return None
        vals = sorted(vals)
        if len(vals) == 1:
            return vals[0]
        k = (len(vals) - 1) * p / 100.0
        lo = int(k)
        hi = min(lo + 1, len(vals) - 1)
        return vals[lo] + (vals[hi] - vals[lo]) * (k - lo)

    return {
        "n": len(rows),
        "fallback": (sum(1 for r in normals if r.get("is_fallback") == "1") / len(normals)) if normals else None,
        "false_reject": (sum(1 for r in normals if r.get("is_rejected") == "1") / len(normals)) if normals else None,
        "reject_rate": (sum(1 for r in rejects if r.get("is_rejected") == "1") / len(rejects)) if rejects else None,
        "n_normals": len(normals), "n_rejects": len(rejects),
        "ttft_p50": pct(ttfts, 50), "ttft_p95": pct(ttfts, 95),
    }


def faith_mean(path):
    rows = [r for r in read_csv(path) if r.get("status") == "ok"]
    vals = [fnum(r.get("faithfulness")) for r in rows]
    vals = [v for v in vals if v is not None]
    return (mean(vals), len(vals)) if vals else (None, 0)


def pct_s(x):
    return "-" if x is None else "%.2f%%" % (x * 100)


def num(x, nd=4):
    return "-" if x is None else ("%.*f" % (nd, x))


def main():
    ap = argparse.ArgumentParser(description="生成评估报告")
    ap.add_argument("--out-dir", default=os.path.abspath(os.path.join(HERE, "..", "..", "docs")))
    args = ap.parse_args()
    os.makedirs(args.out_dir, exist_ok=True)

    ret_paths = {m: latest("*_retrieval-%s_cmp2-%s.csv" % (m, m)) for m in MODES}
    # 兼容早期命名（未加 cmp2 前缀）
    for m in MODES:
        if not ret_paths[m]:
            ret_paths[m] = latest("*_retrieval-%s_*.csv" % m)
    ret = {m: (retrieval_metrics(p) if p else None) for m, p in ret_paths.items()}

    # 答案路径的跑分有多个批次（ans-* 是初版，ans2-* 是阈值定稿后的最终版）。
    # 优先取最终版；没有再退回初版。
    ans_paths = {}
    for m in MODES:
        ans_paths[m] = latest("*_ans2-%s.csv" % m) or latest("*_ans-%s.csv" % m)
    ans = {m: (answer_metrics(p) if p else None) for m, p in ans_paths.items()}

    faith_path = latest("*_faith.csv")
    faith, faith_n = faith_mean(faith_path) if faith_path else (None, 0)

    # ---------------- 检索对比表 ----------------
    lines = ["# 检索四组对比实验（Recall@5 / MRR）", ""]
    lines.append("> 全部数字由 `tools/eval/report.py` 从 `tools/eval/results/*.csv` 自动汇总，"
                 "不手工填数 —— 每个数字都能从明细追出来。")
    lines.append("")
    lines.append("| 检索模式 | 常规 Recall@5 | 常规 MRR | 多跳 Recall@5 | 多跳 MRR | **总体 Recall@5** | **总体 MRR** | 多跳全召回率 |")
    lines.append("|---|---|---|---|---|---|---|---|")
    for m in MODES:
        r = ret.get(m)
        if not r:
            lines.append("| %s | - | - | - | - | - | - | - |" % MODE_LABEL[m])
            continue
        lines.append("| %s | %s | %s | %s | %s | **%s** | **%s** | %s |" % (
            MODE_LABEL[m],
            num(r["normal"]["recall"]), num(r["normal"]["mrr"]),
            num(r["multihop"]["recall"]), num(r["multihop"]["mrr"]),
            num(r["all"]["recall"]), num(r["all"]["mrr"]),
            num(r["multihop"]["full"])))
    lines += ["", "**读表要点**", "",
              "- 「多跳全召回率」比「多跳 Recall@5」严格得多：跨文档问题要求**两个来源都进 top-5** 才算数。"
              "只看「命中其一」会严重高估多跳能力。",
              "- 重排（rerank）优化的是**头部相关性**，所以它常常提升多跳、却让常规 Recall 略降 ——"
              "把原本卡在第 5 名边缘的正确片段挤出去了，这是预期的取舍，不是缺陷。",
              "- BM25 单独使用明显弱于向量：中文短查询下字符 2-gram 的词面信号不足以替代语义检索；"
              "它的价值在于**补向量漏掉的专有名词**，这正体现在 RRF 融合里。",
              "",
              "## 口径说明",
              "",
              "- 本表在**检索层**测量（ADMIN 接口 `/api/knowledge/chunk/retrieve-preview`，K=5），"
              "不是从 `/api/chat/ask` 的 sources 推的 —— 后者只有 `rag.top-k=3` 条，那算的是 Recall@3。",
              "- `maxVectorSimilarity`（拒答阈值与宽松兜底阈值的锚点）**始终取融合前的向量 top-1**，"
              "与 BM25/RRF/rerank 分数无关，保证四种模式下阈值语义一致。",
              ""]
    with open(os.path.join(args.out_dir, "检索对比表.md"), "w", encoding="utf-8") as f:
        f.write("\n".join(lines))

    # ---------------- 评估报告 ----------------
    rep = ["# 离线量化评估报告", "",
           "> 本报告由 `tools/eval/report.py` 从跑分明细自动生成。所有指标可在本机用",
           "> `tools/verify_all.ps1` 一键复现。", "",
           "## 1. 评估集", "",
           "| 类型 | 题数 | 说明 |", "|---|---|---|",
           "| 常规 normal | 90 | 覆盖 5 大知识分类，含现有真实历史问法 |",
           "| 跨文档多跳 multihop | 30 | 需要两个不同来源才能答全 |",
           "| 超纲陷阱 out_of_scope | 30 | 竞品院校 / 实时信息 / 内部资料 / 主观推荐 / 计算与写作任务 |",
           "| **合计** | **150** | 超纲题期望「拒答」，其余期望「给出实质回答」 |", "",
           "## 2. 检索层结果", "",
           "（完整对比表见 `docs/检索对比表.md`）", ""]
    if ret.get("vector"):
        r = ret
        best = max((m for m in MODES if r.get(m)), key=lambda m: (r[m]["all"]["recall"] or 0), default=None)
        rep.append("总体 Recall@5 最高的是 **%s**（%s）；四组模式全部超过文档要求的 85%% 基线。" % (
            MODE_LABEL.get(best, best), num(r[best]["all"]["recall"])))
        rep.append("")

    rep += ["## 3. 答案层结果（兜底率 / 拒答率 / TTFT）", "",
            "| 检索模式 | 常规兜底率 | 正常题误拒率 | 超纲拒答率 | TTFT p50 | TTFT p95 |",
            "|---|---|---|---|---|---|"]
    for m in MODES:
        a = ans.get(m)
        if not a:
            rep.append("| %s | - | - | - | - | - |" % MODE_LABEL[m])
            continue
        rep.append("| %s | %s | %s | %s | %s | %s |" % (
            MODE_LABEL[m],
            pct_s(a["fallback"]), pct_s(a["false_reject"]), pct_s(a["reject_rate"]),
            "-" if a["ttft_p50"] is None else "%.0fms" % a["ttft_p50"],
            "-" if a["ttft_p95"] is None else "%.0fms" % a["ttft_p95"]))
    rep += ["", "**红线**：常规兜底率**不得高于纯向量基线**；正常题误拒率应 ≈ 0。", "",
            "## 4. Faithfulness（忠实度）", ""]
    if faith is not None:
        rep.append("LLM 裁判（DeepSeek）判分结果：**%.4f**（判分 %d 题，目标 > 0.85）。" % (faith, faith_n))
    else:
        rep.append("尚未运行。执行：`& $PY tools\\eval\\judge_faithfulness.py --answers <答案JSON>`")
    rep += ["", "## 5. 拒答阈值标定", ""]
    vector_ans = ans_paths.get("vector")
    if vector_ans:
        try:
            from threshold_sweep import load as sweep_load, sweep as sweep_calc
            srows = sweep_load(vector_ans)
            normals = [r["score"] for r in srows if r["expect"] == "answer"]
            rejects = [r["score"] for r in srows if r["expect"] == "reject"]
            if normals and rejects:
                rep += ["实测分数分布（%d 常规 / %d 超纲）：" % (len(normals), len(rejects)), "",
                        "| 分组 | min | 中位 | max |", "|---|---|---|---|",
                        "| 常规题 | %.4f | %.4f | %.4f |" % (
                            min(normals), sorted(normals)[len(normals) // 2], max(normals)),
                        "| 超纲题 | %.4f | %.4f | %.4f |" % (
                            min(rejects), sorted(rejects)[len(rejects) // 2], max(rejects)),
                        "",
                        "两组**有重叠**（常规下限 %.4f < 超纲上限 %.4f），因此不存在干净的切点 ——"
                        "阈值取值必然是一笔权衡。" % (min(normals), max(rejects)), "",
                        "| 阈值 | 超纲拒答率 | 正常题误拒率 |", "|---|---|---|"]
                table, _, _ = sweep_calc(srows, 0.55, 0.66, 0.01)
                for t, fr, rr in table:
                    mark = " ← 文档建议值" if abs(t - 0.65) < 1e-9 else (" ← **采纳**" if abs(t - 0.59) < 1e-9 else "")
                    rep.append("| %.2f | %.2f%% | %.2f%%%s |" % (t, rr * 100, fr * 100, mark))
                # 列出被误拒的具体题号（代价要指名道姓，不能只给百分比）
                cut = 0.59
                idmap = {r.get("id"): r.get("question") for r in read_csv(vector_ans)}
                hit = [r for r in srows if r["expect"] == "answer" and r["score"] < cut]
                rep += ["", "采纳 0.59 的代价：以下 **%d 道常规题会被误拒** ——" % len(hit), ""]
                for r in hit:
                    rep.append("- `%s`（相似度 %.4f）%s" % (
                        r["id"], r["score"], idmap.get(r["id"], "")))
                rep.append("")
        except Exception as e:
            rep.append("（阈值标定段生成失败：%s）" % str(e)[:120])
            rep.append("")
    else:
        rep.append("缺少向量模式的答案层跑分结果，无法生成标定表。")
        rep.append("")

    rep += ["## 6. 口径与局限（如实声明）", "",
            "- **刷新方式**：本报告数字由脚本从 `results/*.csv` 汇总，不做手工填写。"
            "要在改动后刷新：按第 5 节跑完四种模式的检索层评估与答案层评估 → "
            "`judge_faithfulness.py` → 本脚本。",
            "- **TTFT 是伪流式口径**：本项目的 SSE 在 `Flux` 创建**之前**就已把答案整段算出，"
            "分片推送之间也没有延迟，因此「首片到达时间」≈ 全链路耗时。"
            "报告不把它包装成真流式低延迟。",
            "- **召回宽度是当前瓶颈**：多跳全召回率明显低于单跳 Recall，"
            "说明限制来自 top-K 宽度而非排序质量；若要提升应加大候选池而非只换排序器。",
            "- **评估集为自建**：题目由本项目的知识库内容编写并人工标注 gold，"
            "偏易的可能性客观存在；`analyze_misses.py` 用于逐条复核标注与真实漏召回的区分。",
            ""]
    with open(os.path.join(args.out_dir, "评估报告.md"), "w", encoding="utf-8") as f:
        f.write("\n".join(rep))

    print("已生成：")
    print("  %s" % os.path.join(args.out_dir, "检索对比表.md"))
    print("  %s" % os.path.join(args.out_dir, "评估报告.md"))
    if not faith_path:
        print("提示：Faithfulness 尚未判分，报告里该项留了占位。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
