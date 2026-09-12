#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Faithfulness（忠实度）裁判：用 DeepSeek 判断「答案里的每个论断是否都能被召回片段支撑」。

## 为什么必须用 LLM 判
Faithfulness 问的是「答案有没有超出资料」，本质是语义蕴含判断，字符串匹配答不了
（本项目已经另有一道更强的**结构性**校验 —— `verify_verbatim.py` 断言答案每句都是
召回片段的逐字子串。两者互补：逐字保真保证「没改写」，Faithfulness 保证「没说资料之外的话」。）

## 判分口径
- 把答案拆成独立论断，逐条判 supported / unsupported
- `faithfulness = supported / 总论断数`
- **拒答与兜底不计入分母**（它们没有实质论断，按拒答率单独统计）
- 判分前先剥掉行内引用标记 `[n]`（既便于判分，也顺带证明这个标记不破坏语义）
- `**小标题**` 行按指示**不计入论断** —— 那本就是模型生成的概括，不是事实句

## 成本
按「答案 + 来源」的 MD5 缓存判分结果（`results/judge_cache.json`），重跑几乎零成本。

用法：
  $env:DEEPSEEK_API_KEY="sk-..."
  & $PY tools\\eval\\judge_faithfulness.py --answers tools\\eval\\results\\xxx_answers.json
"""

import argparse
import csv
import hashlib
import json
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from eval_common import is_fallback, strip_citations, write_csv  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
CACHE_PATH = os.path.join(HERE, "results", "judge_cache.json")
DEEPSEEK_URL = "https://api.deepseek.com/chat/completions"

SYSTEM_PROMPT = """你是严格的答案校验员。用户会给你【参考资料】与一段【回答】。
请把【回答】拆成若干条独立的事实论断，逐条判断它是否能被【参考资料】直接支撑。

判定规则：
1. 论断在资料里有依据即算 supported。允许同义改写，允许由资料直接推出的结论。
2. 资料里没有的、被夸大的、靠常识补的、或与其他资料冲突的，一律算 false。
3. 不要因为论断「看起来合理」就判 true，必须有资料依据。
4. 以 ** 包裹的小标题行是概括性标题，**不计入论断**，直接忽略。
5. 参考文献列表、链接行不计入论断。

只输出一个 JSON 对象，格式：
{"claims":[{"text":"论断原文","supported":true,"evidence":"支撑它的资料原文片段（没有则空串）"}]}
不要输出其他任何文字。"""


def load_cache():
    if os.path.exists(CACHE_PATH):
        try:
            with open(CACHE_PATH, "r", encoding="utf-8") as f:
                return json.load(f)
        except Exception:
            return {}
    return {}


def save_cache(cache):
    os.makedirs(os.path.dirname(CACHE_PATH), exist_ok=True)
    with open(CACHE_PATH, "w", encoding="utf-8") as f:
        json.dump(cache, f, ensure_ascii=False)


def judge_one(api_key, question, sources_text, answer, timeout=120):
    """返回 (faithfulness, claims)。异常抛给调用方。"""
    import urllib.request

    user = (
        "【参考资料】\n" + (sources_text or "（无）") +
        "\n\n【用户问题】\n" + question +
        "\n\n【回答】\n" + answer
    )
    payload = {
        "model": "deepseek-chat",
        "temperature": 0,
        "response_format": {"type": "json_object"},
        "messages": [
            {"role": "system", "content": SYSTEM_PROMPT},
            {"role": "user", "content": user},
        ],
    }
    req = urllib.request.Request(
        DEEPSEEK_URL,
        data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
        headers={"Content-Type": "application/json", "Authorization": "Bearer %s" % api_key},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        body = json.loads(resp.read().decode("utf-8", "replace"))
    content = body["choices"][0]["message"]["content"]
    content = re.sub(r"^```(?:json)?\s*", "", content.strip())
    content = re.sub(r"\s*```$", "", content)
    data = json.loads(content)
    claims = data.get("claims") or []
    if not claims:
        return None, []
    supported = sum(1 for c in claims if c.get("supported"))
    return supported / len(claims), claims


def main():
    ap = argparse.ArgumentParser(description="Faithfulness 裁判")
    ap.add_argument("--answers", required=True, help="run_eval.py --dump-json 产出的 JSON")
    ap.add_argument("--out", default=None, help="输出 CSV（默认与输入同名的 _faith.csv）")
    ap.add_argument("--api-key", default=os.environ.get("DEEPSEEK_API_KEY", ""))
    ap.add_argument("--limit", type=int, default=0)
    ap.add_argument("--timeout", type=int, default=120)
    args = ap.parse_args()

    if not args.api_key:
        print("缺少 DEEPSEEK_API_KEY（环境变量或 --api-key）")
        return 1
    if not os.path.exists(args.answers):
        print("找不到答案文件：%s" % args.answers)
        return 1

    with open(args.answers, "r", encoding="utf-8") as f:
        data = json.load(f)

    cache = load_cache()
    items = list(data.items())
    if args.limit:
        items = items[: args.limit]

    rows = []
    scored = []
    skipped = 0
    cached_n = 0
    for i, (qid, item) in enumerate(items, 1):
        answer = item.get("answer") or ""
        sources = item.get("sources") or []
        if item.get("rejected") or is_fallback(answer) or not sources:
            skipped += 1
            rows.append({"id": qid, "type": item.get("expect", ""), "status": "skipped",
                         "faithfulness": "", "n_claims": "", "n_unsupported": "",
                         "unsupported": "", "note": "拒答/兜底/无来源，不计入分母"})
            continue

        sources_text = "\n\n".join(
            "【片段%s】%s" % (s.get("index", "?"), s.get("content") or "") for s in sources)
        clean_answer = strip_citations(answer)
        key = hashlib.md5((clean_answer + "||" + sources_text).encode("utf-8")).hexdigest()

        if key in cache:
            score, claims = cache[key]["score"], cache[key]["claims"]
            cached_n += 1
        else:
            try:
                score, claims = judge_one(args.api_key, item.get("question", ""),
                                          sources_text, clean_answer, args.timeout)
                if score is None:
                    rows.append({"id": qid, "type": item.get("expect", ""), "status": "empty",
                                 "faithfulness": "", "n_claims": 0, "n_unsupported": 0,
                                 "unsupported": "", "note": "模型未拆出论断"})
                    continue
                cache[key] = {"score": score, "claims": claims}
            except Exception as e:
                rows.append({"id": qid, "type": item.get("expect", ""), "status": "error",
                             "faithfulness": "", "n_claims": "", "n_unsupported": "",
                             "unsupported": "", "note": str(e)[:120]})
                print("  [%3d/%3d] %-5s ERROR %s" % (i, len(items), qid, str(e)[:80]))
                continue

        unsupported = [c.get("text", "") for c in claims if not c.get("supported")]
        scored.append(score)
        rows.append({
            "id": qid, "type": item.get("expect", ""), "status": "ok",
            "faithfulness": round(score, 4), "n_claims": len(claims),
            "n_unsupported": len(unsupported),
            "unsupported": " | ".join(t[:80] for t in unsupported[:3]),
            "note": "",
        })
        if i % 10 == 0:
            save_cache(cache)
        print("  [%3d/%3d] %-5s faith=%.3f  claims=%d  不支持=%d" % (
            i, len(items), qid, score, len(claims), len(unsupported)))

    save_cache(cache)

    out = args.out or args.answers.replace("_answers.json", "_faith.csv")
    write_csv(out, rows, ["id", "type", "status", "faithfulness", "n_claims",
                          "n_unsupported", "unsupported", "note"])

    print()
    print("=" * 68)
    print("判分题数 %d（跳过 %d，命中缓存 %d）" % (len(items), skipped, cached_n))
    if scored:
        avg = sum(scored) / len(scored)
        perfect = sum(1 for s in scored if s >= 0.999)
        print("Faithfulness 均值 = %.4f   （目标 > 0.85）" % avg)
        print("完全忠实（=1.0）的题：%d / %d" % (perfect, len(scored)))
        print("最低的 5 题：")
        for r in sorted([r for r in rows if r["status"] == "ok"],
                        key=lambda x: x["faithfulness"])[:5]:
            print("  %-5s %.3f  不支持 %d 条：%s" % (
                r["id"], r["faithfulness"], r["n_unsupported"], r["unsupported"][:70]))
    else:
        print("没有可判分的题")
    print("=" * 68)
    print("明细已写入：%s" % out)
    return 0


if __name__ == "__main__":
    sys.exit(main())
