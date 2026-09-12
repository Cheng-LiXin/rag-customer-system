#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
量化评估公共工具（纯标准库，无第三方依赖，与 tools/web-collect/collect_ysu.py 风格一致）。

本机 `python` 是商店占位符，请用全路径调用：
  D:\\Program\\Python\\Anaconda3\\envs\\myenv\\python.exe tools\\eval\\run_eval.py ...

约定：
- 后端返回体统一为 Result 包裹：{"code":200,"message":"...","data":{...}}；code!=200 视为业务错误。
- /api/chat/** 在 SecurityConfig 里是 permitAll，故评估脚本无需 JWT。
"""

import csv
import hashlib
import json
import re
import statistics
import time
import urllib.error
import urllib.parse
import urllib.request

# 与 ChatServiceImpl.NO_CONTENT_MSG 保持逐字一致 —— 兜底率是评估的红线指标，
# 一旦后端改了文案而这里没同步，评估会静默失真，故集中定义在此。
NO_CONTENT_MSG = "资料中没有相关内容，建议换个问法或补充关键词。"

# 行内引用标记 [n]（批次 A2 引入）。判分与逐字比对前都要先剥掉它。
CITE_RE = re.compile(r"\[\d{1,2}\]")

DEFAULT_BASE_URL = "http://127.0.0.1:8080"

GOLDEN_FIELDS = [
    "id", "type", "question", "gold_chunk_ids", "gold_source_urls",
    "gold_keywords", "expect", "notes",
]


# ---------------------------------------------------------------- HTTP

def http_json(url, payload=None, timeout=120, headers=None, method=None):
    """发一个 JSON 请求并返回解析后的 body（dict）。HTTP/业务错误抛出 RuntimeError。

    method 省略时按「有 payload 就 POST、没有就 GET」推断。**要发 DELETE 必须显式传
    method='DELETE'** —— 否则会带着 payload 走 POST，打到不存在的路由上，
    然后被后端兜底成一句含糊的「系统繁忙」，很难查（踩过）。
    """
    data = None
    hdrs = {"Accept": "application/json"}
    if headers:
        hdrs.update(headers)
    if method is None:
        method = "POST" if payload is not None else "GET"
    if payload is not None:
        data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        hdrs["Content-Type"] = "application/json; charset=utf-8"
    req = urllib.request.Request(url, data=data, headers=hdrs, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            body = resp.read().decode("utf-8", errors="replace")
    except urllib.error.HTTPError as e:
        raise RuntimeError("HTTP %s: %s" % (e.code, e.read().decode("utf-8", errors="replace")[:300]))
    except Exception as e:  # URLError / timeout
        raise RuntimeError("请求失败 %s: %s" % (url, e))
    try:
        return json.loads(body)
    except json.JSONDecodeError:
        raise RuntimeError("响应不是 JSON: %s" % body[:300])


def ask(base_url, question, conversation_id=None, no_cache=True, timeout=120, extra_headers=None):
    """调用 POST /api/chat/ask，返回 (data, wall_ms)。data 为 Result.data（ChatAnswer）。"""
    url = "%s/api/chat/ask" % base_url.rstrip("/")
    if no_cache:
        url += "?noCache=true"
    payload = {"message": question}
    if conversation_id is not None:
        payload["conversationId"] = conversation_id
    t0 = time.time()
    body = http_json(url, payload, timeout=timeout, headers=extra_headers)
    wall_ms = int((time.time() - t0) * 1000)
    if body.get("code") != 200:
        raise RuntimeError("业务错误 code=%s message=%s" % (body.get("code"), body.get("message")))
    return body.get("data") or {}, wall_ms


def login(base_url, username, password, timeout=30):
    """管理员登录，返回 JWT。检索预览接口是 ADMIN 权限，需要它。"""
    body = http_json("%s/api/auth/login" % base_url.rstrip("/"),
                     {"username": username, "password": password}, timeout=timeout)
    if body.get("code") != 200:
        raise RuntimeError("登录失败: %s" % body.get("message"))
    token = (body.get("data") or {}).get("token")
    if not token:
        raise RuntimeError("登录响应里没有 token")
    return token


def retrieve_preview(base_url, token, query, top_k=5, mode=None, timeout=60):
    """调用检索预览接口，直接拿**检索层**的 top-K 结果。

    为什么要单独走这个接口：/api/chat/ask 返回的 sources 是喂给答案生成器的片段数
    （rag.top-k=3），而 Recall@5 是**检索层**指标。两者刻意解耦 ——
    为凑指标把答案路径的 top-k 提到 5，会把多余片段塞进抽取池、污染已调好的答案质量。

    返回 list[dict]，每项含 chunkId / score / title / sourceUrl。
    """
    url = "%s/api/knowledge/chunk/retrieve-preview?q=%s&topK=%d" % (
        base_url.rstrip("/"), urllib.parse.quote(query), top_k)
    if mode:
        url += "&mode=%s" % mode
    body = http_json(url, headers={"Authorization": "Bearer %s" % token}, timeout=timeout)
    if body.get("code") != 200:
        raise RuntimeError("检索预览失败: %s" % body.get("message"))
    return body.get("data") or []


# ---------------------------------------------------------------- 文本

def strip_citations(text):
    """剥掉行内引用标记 [n]，用于 Faithfulness 判分与逐字保真比对。"""
    return CITE_RE.sub("", text or "")


def is_fallback(answer):
    """是否为「没有内容」兜底（比较前剥掉引用标记，避免标记影响判定）。"""
    return strip_citations(answer).strip() == NO_CONTENT_MSG


def answer_hash(text):
    """答案内容的 MD5（剥标记后），用于 LLM 判分缓存，避免重跑重复计费。"""
    return hashlib.md5(strip_citations(text).strip().encode("utf-8")).hexdigest()


# ---------------------------------------------------------------- 数据集

def read_golden(path):
    """读 Golden Set CSV。表头固定为 GOLDEN_FIELDS，返回 list[dict]。"""
    rows = []
    with open(path, "r", encoding="utf-8-sig", newline="") as f:
        reader = csv.DictReader(f)
        for raw in reader:
            q = (raw.get("question") or "").strip()
            if not q or q.startswith("#"):
                continue  # 允许用 # 开头行做注释
            rows.append({k: (raw.get(k) or "").strip() for k in GOLDEN_FIELDS})
    return rows


def write_golden(path, rows):
    with open(path, "w", encoding="utf-8", newline="") as f:
        w = csv.DictWriter(f, fieldnames=GOLDEN_FIELDS)
        w.writeheader()
        for r in rows:
            w.writerow({k: r.get(k, "") for k in GOLDEN_FIELDS})


def parse_ids(value):
    """把 '1,2,3' 或 '1 2 3' 或 '1;2' 解析成 int 列表。"""
    if not value:
        return []
    parts = re.split(r"[,;\s]+", value.strip())
    out = []
    for p in parts:
        if p.isdigit():
            out.append(int(p))
    return out


def write_csv(path, rows, fieldnames):
    with open(path, "w", encoding="utf-8-sig", newline="") as f:
        w = csv.DictWriter(f, fieldnames=fieldnames)
        w.writeheader()
        for r in rows:
            w.writerow({k: r.get(k, "") for k in fieldnames})


# ---------------------------------------------------------------- 指标

def pct(values, p):
    """百分位数（p 取 0~100）。样本为空返回 None。"""
    vals = sorted(v for v in values if v is not None)
    if not vals:
        return None
    if len(vals) == 1:
        return vals[0]
    k = (len(vals) - 1) * (p / 100.0)
    lo = int(k)
    hi = min(lo + 1, len(vals) - 1)
    return vals[lo] + (vals[hi] - vals[lo]) * (k - lo)


def mean(values):
    vals = [v for v in values if v is not None]
    return statistics.fmean(vals) if vals else None


def rate(hit, total):
    return (hit / total) if total else 0.0


def fmt(x, nd=4):
    return "-" if x is None else ("%.*f" % (nd, x))
