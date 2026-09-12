#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
知识库近重复检测（只读分析）。

## 为什么要做
评估阶段实测发现：同一份文件被导入了两次（《学业预警工作实施细则》本体 + 印发它的通知，
通知里含全文），检索会同时召回两版 —— 答案里同一句话出现两遍，其中一版还是折行的碎片。
这类"通知 + 附件全文"的重复在公文类知识库里很常见，靠肉眼翻标题看不出来。

## 检测方法
把每个片段归一化（去空白/标点/全角转半角）后切成**字符 4-gram**，两两算
**包含度** = |A∩B| / min(|A|,|B|)。用包含度而不是 Jaccard：
一份长文被切成多片后与另一份的对应片，长度相近但措辞略有出入，
Jaccard 会被"各自独有的内容"稀释，包含度更能反映"一个片段是否基本被另一个覆盖"。

## 为什么走 API 而不是直连 MySQL
删除重复片段**必须走 `DELETE /api/knowledge/chunk/{id}`**，因为只有它会同步删 PGVector 里的
向量。直连数据库删行会留下**幽灵向量**：检索仍能命中一个库里已不存在的 chunk_id，
而任何界面都看不出问题。既然删除要走 API，检测也一并走，省一个 MySQL 客户端依赖。

用法：
  # 只分析（默认）
  & $PY tools\\kb-dedupe\\find_duplicates.py

  # 分析并把建议的删除清单落盘，人工复核后再执行
  & $PY tools\\kb-dedupe\\find_duplicates.py --review-csv tools\\kb-dedupe\\duplicates.csv

  # 执行删除（★ 会真的删片段与向量，务必先看过 review CSV）
  & $PY tools\\kb-dedupe\\find_duplicates.py --apply --review-csv tools\\kb-dedupe\\duplicates.csv
"""

import argparse
import csv
import os
import re
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "eval"))

from eval_common import http_json, login  # noqa: E402

GRAM = 4


def normalize(text):
    """全角转半角 + 去标点空白 + 小写"""
    if not text:
        return ""
    sb = []
    for c in text:
        if c == "　":
            sb.append(" ")
        elif "！" <= c <= "～":
            sb.append(chr(ord(c) - 0xFEE0))
        else:
            sb.append(c)
    return re.sub(r"[\s\W_]+", "", "".join(sb).lower(), flags=re.UNICODE)


def shingles(text, n=GRAM):
    t = normalize(text)
    if len(t) < n:
        return set([t]) if t else set()
    return {t[i:i + n] for i in range(len(t) - n + 1)}


def containment(a, b):
    if not a or not b:
        return 0.0
    inter = len(a & b)
    return inter / min(len(a), len(b))


def fetch_all_chunks(base_url, token, page_size=500):
    out = []
    page = 1
    while True:
        url = "%s/api/knowledge/chunk/page?pageNum=%d&pageSize=%d" % (base_url, page, page_size)
        body = http_json(url, headers={"Authorization": "Bearer %s" % token})
        if body.get("code") != 200:
            raise RuntimeError("拉取片段失败: %s" % body.get("message"))
        data = body.get("data") or {}
        records = data.get("records") or []
        out.extend(records)
        if len(out) >= (data.get("total") or 0) or not records:
            break
        page += 1
    return out


def load_gold_ids(golden_path):
    """读评估集里所有 gold_chunk_ids —— 这些片段**绝不能删**。

    为什么必须有这道闸：评估集是衡量系统质量的标尺，删掉它引用的片段会让
    Recall 直接崩掉，而且崩得莫名其妙（没人会想到是"去重"干的）。
    实测就踩过：工具曾建议删 id=207（9-05 特意新建的「学科建设」锚点片段，
    评估集 N04 的 gold）与 id=111（M23 的 gold）—— 两处都是错的。
    """
    ids = set()
    if not golden_path or not os.path.exists(golden_path):
        return ids
    with open(golden_path, "r", encoding="utf-8-sig", newline="") as f:
        for row in csv.DictReader(f):
            for part in re.split(r"[,;\s]+", (row.get("gold_chunk_ids") or "").strip()):
                if part.isdigit():
                    ids.add(int(part))
    return ids


def suggest_keep(group, chunks, protected):
    """
    建议保留哪一条，优先级从高到低：
      0) **被评估集 gold 引用**的（删了标尺就废了）
      1) 内容更长的（长的通常包含短的，反之不成立）
      2) source_url 非空的（能回溯官方来源）
      3) id 较小的（先导入的，通常已被历史会话/缓存引用）
    """
    def key(i):
        c = chunks[i]
        cid = c.get("id") or 0
        return (0 if cid in protected else 1,
                -len(normalize(c.get("content") or "")),
                0 if (c.get("sourceUrl") or "").strip() else 1,
                cid)
    return sorted(group, key=key)[0]


def main():
    ap = argparse.ArgumentParser(description="知识库近重复检测")
    ap.add_argument("--base-url", default="http://127.0.0.1:8081")
    ap.add_argument("--admin-user", default="admin")
    ap.add_argument("--admin-pass",
                    default=os.environ.get("DEMO_ADMIN_PASSWORD", "Admin@Ysu2026"))
    ap.add_argument("--containment", type=float, default=0.70,
                    help="片段级包含度阈值：达到即认为两个片段是同内容（默认 0.70）")
    ap.add_argument("--doc-coverage", type=float, default=0.60,
                    help="文档级同源判定：A 至少这么大比例的片段能在 B 里找到近重复（默认 0.60）")
    ap.add_argument("--backup", default=None,
                    help="删除前把待删片段的完整内容备份到该文件（强烈建议）")
    ap.add_argument("--review-csv", default=None, help="把建议清单写到该 CSV 供人工复核")
    ap.add_argument("--apply", action="store_true",
                    help="★ 真的执行删除（走 DELETE 接口，连带删向量）")
    ap.add_argument("--golden",
                    default=os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                                         "eval", "golden_set.csv"),
                    help="评估集路径：其中的 gold_chunk_ids 一律受保护、绝不删除")
    ap.add_argument("--no-gold-protect", action="store_true",
                    help="关闭评估集保护（不推荐，仅在你确认要删 gold 片段时用）")
    args = ap.parse_args()

    token = login(args.base_url, args.admin_user, args.admin_pass)
    chunks = fetch_all_chunks(args.base_url, token)
    print("拉取到活跃片段 %d 条" % len(chunks))

    protected = set() if args.no_gold_protect else load_gold_ids(args.golden)
    if protected:
        print("评估集保护：%d 个 gold 片段被引用，不会被删除" % len(protected))

    sig = {c["id"]: shingles(c.get("content") or "") for c in chunks}
    by_id = {c["id"]: c for c in chunks}

    # ---- 按「源文档」分组。无 source_title 的片段各自成组，避免拿样板短文本互相比对 ----
    docs = {}
    for c in chunks:
        src = (c.get("sourceTitle") or "").strip()
        key = src if src else "__orphan_%s" % c["id"]
        docs.setdefault(key, []).append(c["id"])
    docs = {k: v for k, v in docs.items() if len(v) >= 2}   # 单片段不算"文档"
    print("参与比对的源文档 %d 份\n" % len(docs))

    # ---- 文档级同源判定 ----
    # 只看片段级包含度会大量误报：同一领域的两个文档也共享大量术语（实测把
    # 「学科实力/ESI」和「学位授权点」判成重复）。真正同源的信号是
    # **A 的大多数片段都能在 B 里找到近重复**，而不是某一对片段像。
    def doc_coverage(ids_a, ids_b):
        hit = 0
        for a in ids_a:
            best = 0.0
            for b in ids_b:
                la, lb = len(sig[a]), len(sig[b])
                if la and lb and max(la, lb) / max(1, min(la, lb)) > 2.5:
                    continue
                ct = containment(sig[a], sig[b])
                if ct > best:
                    best = ct
                    if best >= args.containment:
                        break
            if best >= args.containment:
                hit += 1
        return hit / len(ids_a)

    keys = list(docs.keys())
    dup_pairs = []
    for i in range(len(keys)):
        for j in range(i + 1, len(keys)):
            a, b = keys[i], keys[j]
            ca = doc_coverage(docs[a], docs[b])
            cb = doc_coverage(docs[b], docs[a])
            if ca >= args.doc_coverage and cb >= args.doc_coverage:
                dup_pairs.append((a, b, ca, cb))

    if not dup_pairs:
        print("未发现同源文档，知识库没有整篇重复。")
        return 0

    print("发现同源文档对 %d 组：\n" % len(dup_pairs))
    proposals = []
    for gi, (a, b, ca, cb) in enumerate(dup_pairs, 1):
        ids_a, ids_b = docs[a], docs[b]
        len_a = sum(len(normalize(by_id[i].get("content") or "")) for i in ids_a)
        len_b = sum(len(normalize(by_id[i].get("content") or "")) for i in ids_b)
        # 保留哪一份：优先选「噪声更少」的那份 —— 实测通知版含大量折行插入的空格，
        # 正是答案里出现半截话条目的根源。噪声用「中文之间的孤立空格数」度量。
        def noise(ids):
            return sum(len(re.findall(r"[一-鿿]\s[一-鿿]", by_id[i].get("content") or ""))
                       for i in ids)
        na, nb = noise(ids_a), noise(ids_b)
        keep_doc, drop_doc = (a, b) if (na, -len_a) <= (nb, -len_b) else (b, a)
        keep_ids, drop_ids = docs[keep_doc], docs[drop_doc]
        print("组 %d：两份同源文档（互相覆盖率 %.2f / %.2f）" % (gi, ca, cb))
        print("  【保留】%s  —— %d 片，共 %d 字，折行噪声 %d 处，ids=%s"
              % (keep_doc, len(keep_ids), len_a if keep_doc == a else len_b, na if keep_doc == a else nb, keep_ids))
        print("  【删除】%s  —— %d 片，共 %d 字，折行噪声 %d 处，ids=%s"
              % (drop_doc, len(drop_ids), len_b if drop_doc == a else len_a, nb if drop_doc == a else na, drop_ids))
        hit_protected = [i for i in drop_ids if i in protected]
        if hit_protected:
            print("  ⚠ 待删片段里有 %d 个被评估集 gold 引用：%s" % (len(hit_protected), hit_protected))
            print("     → 删除后必须把 golden_set.csv 里的这些 id 改成保留版的对应 id（见下方映射建议）")
        print()
        for i in drop_ids:
            c = by_id[i]
            proposals.append({
                "group": gi, "keep_doc": keep_doc, "drop_doc": drop_doc,
                "delete_id": i, "delete_title": c.get("title"),
                "delete_source_title": c.get("sourceTitle"),
                "delete_len": len(normalize(c.get("content") or "")),
                "gold_protected": "是" if i in protected else "",
                "delete_content_preview": (c.get("content") or "")[:120].replace("\n", " "),
            })

    if args.review_csv:
        os.makedirs(os.path.dirname(os.path.abspath(args.review_csv)), exist_ok=True)
        with open(args.review_csv, "w", encoding="utf-8-sig", newline="") as f:
            w = csv.DictWriter(f, fieldnames=list(proposals[0].keys()))
            w.writeheader()
            for p in proposals:
                w.writerow(p)
        print("待删清单已写入：%s（共 %d 条）" % (args.review_csv, len(proposals)))

    if not args.apply:
        print("\n（未执行删除。确认无误后加 --apply 重跑；建议先用 --backup 备份待删内容）")
        return 0

    if args.backup:
        os.makedirs(os.path.dirname(os.path.abspath(args.backup)), exist_ok=True)
        with open(args.backup, "w", encoding="utf-8") as f:
            for p in proposals:
                c = by_id[p["delete_id"]]
                f.write("=== id=%s | %s | %s ===\n%s\n\n" % (
                    c["id"], c.get("sourceTitle"), c.get("title"), c.get("content") or ""))
        print("已备份待删片段的完整内容到：%s" % args.backup)

    print("\n开始删除 %d 条重复片段（走 DELETE 接口，会同步删向量）..." % len(proposals))
    ok = 0
    for p in proposals:
        try:
            # 必须显式 DELETE：不传 method 会被推断成 GET，而带 body 时又会变 POST，
            # 两种情况都打不到删除路由上（后端会兜底成含糊的「系统繁忙」）
            body = http_json("%s/api/knowledge/chunk/%s" % (args.base_url, p["delete_id"]),
                             headers={"Authorization": "Bearer %s" % token}, method="DELETE")
            if body.get("code") == 200:
                ok += 1
            else:
                print("  id=%s 删除失败：%s" % (p["delete_id"], body.get("message")))
        except Exception as e:
            print("  id=%s 删除异常：%s" % (p["delete_id"], str(e)[:100]))
    print("已删除 %d / %d 条" % (ok, len(proposals)))
    print("\n★ 后续必做：")
    print("  1) 若上面提示有 gold 片段被删，先改 tools/eval/golden_set.csv 里的 id")
    print("  2) 重跑验收：pwsh tools\\verify_all.ps1")
    return 0


if __name__ == "__main__":
    sys.exit(main())
