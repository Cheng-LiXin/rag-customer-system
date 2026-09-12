#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Golden Set 跑分（纯标准库）。

用法（本机 python 是商店占位符，用全路径）：
  set PY=D:\\Program\\Python\\Anaconda3\\envs\\myenv\\python.exe
  %PY% tools\\eval\\run_eval.py --set tools\\eval\\golden_set.csv --tag baseline --mode stream

前置条件：
  1. 后端已启动，且以 RAG_EVAL_BYPASS=true 运行（否则 ?noCache=true 不生效，重复问题会命中
     qa:cache 导致 Recall/TTFT 全部失真）。启动示例：
       $env:RAG_EVAL_BYPASS="true"; mvn spring-boot:run
  2. 知识库已导入且 vector_status=1。

输出：tools/eval/results/<时间戳>_<tag>.csv + 终端汇总。

指标口径（务必与 docs/评估报告.md 保持一致）：
  - 兜底率 fallback_rate：expect=answer 的题里，答案等于固定兜底文案的比例（红线：不得高于基线）
  - 超纲拒答率 reject_rate：expect=reject 的题里，被判为拒答的比例（目标 > 90%）
  - 正常题误拒率 false_reject_rate：expect=answer 的题被拒答的比例（红线：≈ 0）
  - Recall@K / MRR：仅对填了 gold_chunk_ids 的题计算；K 默认 5，但受后端实际返回条数限制
    （rag.top-k=3 时最多只有 3 条来源，故 batch A 阶段该指标天然偏保守，batch D 接入
     recall-k 后再复核）
  - TTFT：stream 模式为客户端的「首个 token 事件到达」耗时（伪流式下≈全链路）；
          ask 模式取服务端 timings.ttftMs
"""

import argparse
import json
import os
import sys
import time
import urllib.parse
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from eval_common import (  # noqa: E402
    DEFAULT_BASE_URL, ask, fmt, is_fallback, login, mean, parse_ids, pct, rate,
    read_golden, retrieve_preview, strip_citations, write_csv,
)

RETRIEVAL_FIELDS = [
    "id", "type", "question", "gold_chunk_ids", "retrieved_chunk_ids",
    "recall_at_k", "full_recall", "mrr", "n_retrieved", "status", "error",
]

RESULT_FIELDS = [
    "id", "type", "expect", "question", "status", "error",
    "answer_len", "is_fallback", "is_rejected", "reject_reason",
    "top_score", "n_sources", "source_chunk_ids",
    "recall_at_k", "full_recall", "mrr", "wall_ms", "ttft_ms", "retrieve_ms", "generate_ms",
    "answer_head",
]


def ask_stream(base_url, question, no_cache=True, timeout=120):
    """GET /api/chat/stream，返回 (data, wall_ms, ttft_ms)。data 取 end 事件的内容。

    SSE 每行可能是 `data:{...}` 或裸 JSON（CLAUDE.md 明确要求兼容两种），这里统一处理。
    """
    url = "%s/api/chat/stream?message=%s" % (base_url.rstrip("/"), urllib.parse.quote(question))
    if no_cache:
        url += "&noCache=true"
    req = urllib.request.Request(url, headers={"Accept": "text/event-stream"})
    t0 = time.time()
    ttft_ms = None
    end_data = None
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        for raw in resp:
            line = raw.decode("utf-8", errors="replace").strip()
            if not line:
                continue
            if line.startswith("data:"):
                line = line[5:].strip()
            if not line or line.startswith(":"):
                continue
            try:
                evt = json.loads(line)
            except json.JSONDecodeError:
                continue
            etype = evt.get("type")
            if etype == "token" and ttft_ms is None:
                ttft_ms = int((time.time() - t0) * 1000)
            elif etype == "end":
                end_data = {k: v for k, v in evt.items() if k != "type"}
            elif etype == "error":
                raise RuntimeError("SSE error: %s" % evt.get("message"))
    wall_ms = int((time.time() - t0) * 1000)
    if end_data is None:
        raise RuntimeError("未收到 end 事件")
    return end_data, wall_ms, ttft_ms


def run_one(base_url, row, mode, no_cache, k, timeout, retrieval_mode=None):
    rec = {f: "" for f in RESULT_FIELDS}
    rec["id"] = row["id"]
    rec["type"] = row["type"]
    rec["expect"] = row["expect"]
    rec["question"] = row["question"]

    # 单次请求指定检索模式（后端需 RAG_EVAL_MODE_OVERRIDE=true 才接受该头）
    headers = {"X-Retrieval-Mode": retrieval_mode} if retrieval_mode else None

    try:
        if mode == "stream":
            data, wall_ms, ttft_ms = ask_stream(base_url, row["question"], no_cache, timeout)
            timings = data.get("timings") or {}
        else:
            data, wall_ms = ask(base_url, row["question"], no_cache=no_cache,
                                timeout=timeout, extra_headers=headers)
            timings = data.get("timings") or {}
            ttft_ms = timings.get("ttftMs")
    except Exception as e:
        rec["status"] = "error"
        rec["error"] = str(e)[:200]
        return rec

    answer = data.get("answer") or ""
    sources = data.get("sources") or []
    ids = []
    for s in sources:
        cid = str(s.get("chunkId") or "")
        if cid.isdigit():
            ids.append(int(cid))

    gold = parse_ids(row["gold_chunk_ids"])
    recall = ""
    full_recall = ""
    mrr = ""
    if gold:
        topk = ids[:k]
        hits = [g for g in gold if g in topk]
        # recall_at_k：任一 gold 进 top-K（常规口径）
        recall = 1 if hits else 0
        # full_recall：**全部** gold 都进 top-K。多跳题必须用这个口径 ——
        # 跨文档问题需要两个来源都召回才算答得全，只看"命中其一"会严重高估。
        full_recall = 1 if len(hits) == len(gold) else 0
        rank = next((i + 1 for i, cid in enumerate(ids) if cid in gold), None)
        mrr = (1.0 / rank) if rank else 0.0

    rec.update({
        "status": "ok",
        "answer_len": len(answer),
        "is_fallback": 1 if is_fallback(answer) else 0,
        "is_rejected": 1 if data.get("rejected") else 0,
        "reject_reason": data.get("rejectReason") or "",
        "top_score": data.get("maxScore") if data.get("maxScore") is not None else "",
        "n_sources": len(ids),
        "source_chunk_ids": ",".join(str(i) for i in ids),
        "recall_at_k": recall,
        "full_recall": full_recall,
        "mrr": mrr,
        "wall_ms": wall_ms,
        "ttft_ms": ttft_ms if ttft_ms is not None else "",
        "retrieve_ms": timings.get("retrieveMs", ""),
        "generate_ms": timings.get("generateMs", ""),
        "answer_head": strip_citations(answer)[:60].replace("\n", " "),
    })
    # 下划线前缀的字段不写进 CSV（write_csv 只按 RESULT_FIELDS 取值），仅用于 --dump-json
    rec["_answer"] = answer
    # content 必须带上：Faithfulness 判分要把「答案里的每个论断」逐条对回来源原文，
    # 没有证据原文就没法判。
    rec["_sources"] = [
        {"index": s.get("index"), "chunkId": s.get("chunkId"), "title": s.get("title"),
         "sourceUrl": s.get("sourceUrl"), "score": s.get("score"),
         "content": s.get("content") or ""}
        for s in sources
    ]
    return rec


def compute_metrics(rows, k):
    ok = [r for r in rows if r["status"] == "ok"]
    err = [r for r in rows if r["status"] != "ok"]
    normals = [r for r in ok if r["expect"] == "answer"]
    rejects = [r for r in ok if r["expect"] == "reject"]

    fb = sum(r["is_fallback"] for r in normals)
    fr = sum(r["is_rejected"] for r in normals)
    rr = sum(r["is_rejected"] for r in rejects)
    # 超纲题若未被显式拒答但回了兜底文案，也算「未答非所问」，单独统计以免高估拒答率
    rr_fb = sum(1 for r in rejects if r["is_rejected"] or r["is_fallback"])

    with_gold = [r for r in ok if r["recall_at_k"] != ""]
    recall = mean([r["recall_at_k"] for r in with_gold]) if with_gold else None
    mrr = mean([r["mrr"] for r in with_gold]) if with_gold else None
    # 分类型：多跳题看「两个来源是否都召回」（full_recall），常规题看「任一命中」
    by_type = {}
    for t in ("normal", "multihop"):
        rs = [r for r in ok if r["type"] == t and r["recall_at_k"] != ""]
        by_type[t] = (mean([r["recall_at_k"] for r in rs]) if rs else None, len(rs))
    multi = [r for r in ok if r["type"] == "multihop" and r["full_recall"] != ""]
    multihop_full = mean([r["full_recall"] for r in multi]) if multi else None
    ttfts = [r["ttft_ms"] for r in ok if r["ttft_ms"] != ""]
    walls = [r["wall_ms"] for r in ok]
    # 实际生效的 K：后端返回几条来源就最多只能算到几（当前 rag.top-k=3，故实际是 Recall@3）
    max_src = max([r["n_sources"] for r in ok], default=0)

    return {
        "total": len(rows), "ok": len(ok), "err": len(err),
        "n_normals": len(normals), "n_rejects": len(rejects),
        "fallback_rate": rate(fb, len(normals)), "fb": fb,
        "false_reject_rate": rate(fr, len(normals)), "fr": fr,
        "reject_rate": rate(rr, len(rejects)), "rr": rr,
        "reject_or_fallback_rate": rate(rr_fb, len(rejects)), "rr_fb": rr_fb,
        "recall": recall, "mrr": mrr, "n_gold": len(with_gold),
        "by_type": by_type, "multihop_full": multihop_full, "n_multihop": len(multi),
        "ttft_p50": pct(ttfts, 50), "ttft_p95": pct(ttfts, 95), "ttft_max": max(ttfts) if ttfts else None,
        "wall_p50": pct(walls, 50), "wall_p95": pct(walls, 95),
        "effective_k": min(k, max_src) if max_src else k, "max_sources": max_src,
        "errors": [r["id"] for r in err],
    }


def run_retrieval(rows, args):
    """检索层跑分：直接问 retriever 要 top-K，不经答案生成。

    这是 Recall@K / MRR 的**正确测量层**。答案路径的 rag.top-k=3 是「喂给抽取器的片段数」，
    两者刻意解耦（见 tools/eval/README.md）。
    """
    token = login(args.base_url, args.admin_user, args.admin_pass)
    results = []
    for i, row in enumerate(rows, 1):
        rec = {f: "" for f in RETRIEVAL_FIELDS}
        rec["id"] = row["id"]
        rec["type"] = row["type"]
        rec["question"] = row["question"]
        rec["gold_chunk_ids"] = row["gold_chunk_ids"]
        try:
            hits = retrieve_preview(args.base_url, token, row["question"], args.k, args.retrieval_mode)
            ids = []
            for h in hits:
                cid = str(h.get("chunkId") or "")
                if cid.isdigit():
                    ids.append(int(cid))
            rec["retrieved_chunk_ids"] = ",".join(str(x) for x in ids)
            rec["n_retrieved"] = len(ids)
            gold = parse_ids(row["gold_chunk_ids"])
            if gold:
                topk = ids[: args.k]
                hit = [g for g in gold if g in topk]
                rec["recall_at_k"] = 1 if hit else 0
                rec["full_recall"] = 1 if len(hit) == len(gold) else 0
                rank = next((j + 1 for j, c in enumerate(ids) if c in gold), None)
                rec["mrr"] = (1.0 / rank) if rank else 0.0
            rec["status"] = "ok"
            flag = "" if rec["recall_at_k"] != 0 else "漏召回"
            print("  [%3d/%3d] %-5s %-8s %-6s %s" % (
                i, len(rows), row["id"], row["type"], flag, row["question"][:26]))
        except Exception as e:
            rec["status"] = "error"
            rec["error"] = str(e)[:200]
            print("  [%3d/%3d] %-5s ERROR %s" % (i, len(rows), row["id"], str(e)[:80]))
        results.append(rec)
    return results


def summarize_retrieval(rows, k, mode):
    ok = [r for r in rows if r["status"] == "ok"]
    with_gold = [r for r in ok if r["recall_at_k"] != ""]
    print("=" * 68)
    print("检索模式 = %s   题数 %d（成功 %d / 失败 %d）" % (mode, len(rows), len(ok), len(rows) - len(ok)))
    print("有 gold 标注并可计算指标的题：%d" % len(with_gold))
    print("-" * 68)
    by_type = {}
    for t in ("normal", "multihop"):
        rs = [r for r in with_gold if r["type"] == t]
        if rs:
            by_type[t] = rs
            print("%-8s n=%-3d Recall@%-2d %s   MRR %s" % (
                t, len(rs), k,
                fmt(mean([r["recall_at_k"] for r in rs])),
                fmt(mean([r["mrr"] for r in rs]))))
    if with_gold:
        print("-" * 68)
        print("总体     n=%-3d Recall@%-2d %s   MRR %s" % (
            len(with_gold), k,
            fmt(mean([r["recall_at_k"] for r in with_gold])),
            fmt(mean([r["mrr"] for r in with_gold]))))
    multi = [r for r in with_gold if r["type"] == "multihop"]
    if multi:
        print("多跳全召回率 %s   (%d 题，两个来源都进 top-%d 才算)" % (
            fmt(mean([r["full_recall"] for r in multi])), len(multi), k))
    print("=" * 68)
    return {
        "recall": mean([r["recall_at_k"] for r in with_gold]) if with_gold else None,
        "mrr": mean([r["mrr"] for r in with_gold]) if with_gold else None,
        "multihop_full": mean([r["full_recall"] for r in multi]) if multi else None,
        "errors": [r["id"] for r in rows if r["status"] != "ok"],
        "mode": mode, "k_target": k,
    }


def summarize(rows, k):
    m = compute_metrics(rows, k)
    print("=" * 68)
    print("题数 %d（成功 %d / 失败 %d）" % (m["total"], m["ok"], m["err"]))
    print("常规题 %d  |  超纲题 %d" % (m["n_normals"], m["n_rejects"]))
    print("-" * 68)
    print("兜底率(常规)      %s   (%d/%d)   ← 红线：不得高于基线" % (
        fmt(m["fallback_rate"]), m["fb"], m["n_normals"]))
    print("正常题误拒率      %s   (%d/%d)   ← 红线：≈0" % (
        fmt(m["false_reject_rate"]), m["fr"], m["n_normals"]))
    print("超纲拒答率        %s   (%d/%d)   ← 目标 >90%%" % (
        fmt(m["reject_rate"]), m["rr"], m["n_rejects"]))
    print("超纲未答非所问率  %s   (%d/%d)   ← 拒答或回兜底均算" % (
        fmt(m["reject_or_fallback_rate"]), m["rr_fb"], m["n_rejects"]))
    if m["recall"] is not None:
        print("Recall@%-2d         %s   (%d 题有标注，实际 K=%d，后端最多返回 %d 条来源)" % (
            k, fmt(m["recall"]), m["n_gold"], m["effective_k"], m["max_sources"]))
        print("MRR               %s" % fmt(m["mrr"]))
        for t, label in (("normal", "常规题"), ("multihop", "多跳题")):
            v, n = m["by_type"].get(t, (None, 0))
            if n:
                print("  └ %s Recall@%-2d  %s   (%d 题)" % (label, k, fmt(v), n))
        if m["multihop_full"] is not None:
            print("多跳全召回率      %s   (%d 题)   ← 两个来源**都**进 top-K 才算" % (
                fmt(m["multihop_full"]), m["n_multihop"]))
        if m["effective_k"] < k:
            print("  ⚠ 实际 K < 目标 K：后端 rag.top-k=%d，来源只返回 %d 条。"
                  "本行实为 Recall@%d，要算真 Recall@%d 需批次 D 的 recall-k。" % (
                      m["max_sources"], m["max_sources"], m["effective_k"], k))
    else:
        print("Recall@K/MRR      -    （尚无 gold_chunk_ids 标注，batch D 补齐）")
    print("-" * 68)
    print("TTFT   p50 %sms  p95 %sms  max %sms" % (
        fmt(m["ttft_p50"], 0), fmt(m["ttft_p95"], 0), fmt(m["ttft_max"], 0)))
    print("端到端 p50 %sms  p95 %sms" % (fmt(m["wall_p50"], 0), fmt(m["wall_p95"], 0)))
    if m["errors"]:
        print("-" * 68)
        for r in rows:
            if r["status"] != "ok":
                print("  ERROR %s: %s" % (r["id"], r["error"][:120]))
    print("=" * 68)
    return m


def assert_redlines(m, args):
    """红线断言：任一条不达标即返回失败项列表（供 --assert / verify_all 使用）。"""
    problems = []
    if m["err"]:
        problems.append("有 %d 题请求失败（%s）" % (m["err"], ", ".join(m["errors"][:5])))
    if m["n_normals"] and m["false_reject_rate"] > args.max_false_reject:
        problems.append("正常题误拒率 %.4f > %.2f（防护/拒答误杀）" % (
            m["false_reject_rate"], args.max_false_reject))
    if m["n_normals"] and m["fallback_rate"] > args.max_fallback:
        problems.append("兜底率 %.4f > %.2f（命中率回退）" % (m["fallback_rate"], args.max_fallback))
    if m["n_rejects"] and m["reject_rate"] < args.min_reject:
        problems.append("超纲拒答率 %.4f < %.2f" % (m["reject_rate"], args.min_reject))
    if m["recall"] is not None and m["recall"] < args.min_recall:
        problems.append("Recall@%d %.4f < %.2f" % (m["k_target"], m["recall"], args.min_recall))

    print("--- 红线断言 ---")
    if problems:
        for p in problems:
            print("  [FAIL] %s" % p)
    else:
        print("  [PASS] 全部红线达标")
    return problems


def main():
    ap = argparse.ArgumentParser(description="Golden Set 跑分")
    ap.add_argument("--set", default=os.path.join(os.path.dirname(os.path.abspath(__file__)), "golden_set.csv"))
    ap.add_argument("--tag", default="run")
    ap.add_argument("--base-url", default=DEFAULT_BASE_URL)
    ap.add_argument("--mode", choices=["ask", "stream"], default="ask")
    ap.add_argument("--k", type=int, default=5, help="Recall@K 的 K（受后端实际返回条数限制）")
    ap.add_argument("--limit", type=int, default=0, help="只跑前 N 条（0=全部）")
    ap.add_argument("--no-cache", action="store_true", default=True, help="旁路 qa:cache（默认开）")
    ap.add_argument("--use-cache", dest="no_cache", action="store_false")
    ap.add_argument("--timeout", type=int, default=120)
    ap.add_argument("--sleep", type=float, default=0.0, help="每题之间的间隔秒数（默认 0）")
    ap.add_argument("--dump-json", action="store_true",
                    help="额外把每题完整答案与来源写入 results/<tag>_answers.json（人工抽检 / 开关前后 A/B 比对用）")
    ap.add_argument("--assert", dest="assert_redlines", action="store_true",
                    help="跑完做红线断言，任一不达标则退出码 1（tools/verify_all.ps1 依赖此行为）")
    ap.add_argument("--min-recall", type=float, default=0.85, help="Recall@K 下限")
    ap.add_argument("--min-reject", type=float, default=0.90, help="超纲拒答率下限")
    ap.add_argument("--max-false-reject", type=float, default=0.05, help="正常题误拒率上限")
    ap.add_argument("--max-fallback", type=float, default=0.15, help="常规题兜底率上限")
    ap.add_argument("--retrieval", action="store_true",
                    help="检索层跑分：直接量 retriever 的 top-K（Recall@K/MRR 的正确测量层），不看答案质量")
    ap.add_argument("--retrieval-mode", default=None,
                    help="检索模式：vector / bm25 / rrf / rerank（不传则用后端配置）")
    # 检索预览接口是 ADMIN 权限，需要登录。口令可用环境变量覆盖 ——
    # 改了演示账号口令后，别忘了一并更新这里/环境变量，否则 --retrieval 会静默登录失败。
    ap.add_argument("--admin-user", default=os.environ.get("EVAL_ADMIN_USER", "admin"))
    ap.add_argument("--admin-pass", default=os.environ.get("EVAL_ADMIN_PASSWORD", "Admin@Ysu2026"))
    args = ap.parse_args()

    rows = read_golden(args.set)
    if args.limit:
        rows = rows[: args.limit]
    if not rows:
        print("Golden Set 为空：%s" % args.set)
        return 1

    # ---------------- 检索层跑分（Recall@K / MRR 的正确测量层）----------------
    if args.retrieval:
        print("检索层跑分：%d 题  k=%d  mode=%s  目标=%s" % (
            len(rows), args.k, args.retrieval_mode or "(后端默认)", args.base_url))
        rresults = run_retrieval(rows, args)
        out_dir = os.path.join(os.path.dirname(os.path.abspath(__file__)), "results")
        os.makedirs(out_dir, exist_ok=True)
        stamp = time.strftime("%Y%m%d-%H%M%S")
        suffix = args.retrieval_mode or "default"
        rout = os.path.join(out_dir, "%s_retrieval-%s_%s.csv" % (stamp, suffix, args.tag))
        write_csv(rout, rresults, RETRIEVAL_FIELDS)
        rmetrics = summarize_retrieval(rresults, args.k, suffix)
        print("结果已写入：%s" % rout)
        if args.assert_redlines and rmetrics["recall"] is not None and rmetrics["recall"] < args.min_recall:
            print("  [FAIL] Recall@%d %s < %.2f" % (args.k, fmt(rmetrics["recall"]), args.min_recall))
            return 1
        return 0 if not rmetrics["errors"] else 2

    print("跑分开始：%d 题  模式=%s  旁路缓存=%s  目标=%s" % (len(rows), args.mode, args.no_cache, args.base_url))
    results = []
    t0 = time.time()
    for i, row in enumerate(rows, 1):
        rec = run_one(args.base_url, row, args.mode, args.no_cache, args.k, args.timeout,
                      args.retrieval_mode)
        results.append(rec)
        flag = rec["status"]
        if rec["status"] == "ok":
            marks = []
            if rec["is_rejected"]:
                marks.append("拒答")
            if rec["is_fallback"]:
                marks.append("兜底")
            flag = "ok" + ("/" + "/".join(marks) if marks else "")
        print("  [%2d/%2d] %-8s %-6s %s" % (
            i, len(rows), rec["id"], flag, rec["question"][:30]))
        if args.sleep:
            time.sleep(args.sleep)

    out_dir = os.path.join(os.path.dirname(os.path.abspath(__file__)), "results")
    os.makedirs(out_dir, exist_ok=True)
    stamp = time.strftime("%Y%m%d-%H%M%S")
    out_path = os.path.join(out_dir, "%s_%s.csv" % (stamp, args.tag))
    write_csv(out_path, results, RESULT_FIELDS)

    if args.dump_json:
        dump_path = os.path.join(out_dir, "%s_%s_answers.json" % (stamp, args.tag))
        with open(dump_path, "w", encoding="utf-8") as f:
            json.dump(
                {r["id"]: {"question": r["question"], "expect": r["expect"],
                           "rejected": bool(r["is_rejected"]), "answer": r["_answer"],
                           "sources": r["_sources"]}
                 for r in results},
                f, ensure_ascii=False, indent=2,
            )
        print("完整答案已写入：%s" % dump_path)

    print("\n耗时 %.1fs" % (time.time() - t0))
    metrics = summarize(results, args.k)
    metrics["k_target"] = args.k
    print("结果已写入：%s" % out_path)

    if args.assert_redlines:
        print()
        return 1 if assert_redlines(metrics, args) else 0
    # 非零退出便于脚本/CI 感知失败
    return 0 if not metrics["errors"] else 2


if __name__ == "__main__":
    sys.exit(main())
