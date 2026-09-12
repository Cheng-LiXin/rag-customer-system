#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
评估环境探针：跑分之前先确认「这次跑分的结果可信」。

检查两件事：
  1. 后端可达（/api/rag/hello）
  2. **缓存旁路是否真的生效** —— 这是最容易被忽略、也最致命的一项：
     若后端没有以 RAG_EVAL_BYPASS=true 启动，`?noCache=true` 会被忽略，
     重复问题命中 qa:cache 直接返回旧答案，于是 Recall/MRR/TTFT 全部变成上一轮的残留值，
     指标会「看起来很好但完全失真」。

探针做法：先用 noCache=false 问一次（写/读缓存），再用 noCache=true 问同一问题。
后者若仍返回 fromCache=true，说明旁路没生效。

用法：
  D:\\Program\\Python\\Anaconda3\\envs\\myenv\\python.exe tools\\eval\\probe_env.py
退出码：0 = 环境就绪；1 = 旁路未生效（跑分结果不可信）。
"""

import argparse
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from eval_common import DEFAULT_BASE_URL, ask, http_json, is_fallback  # noqa: E402

PROBE_QUESTION = "燕山大学设有哪些学院"


def main():
    ap = argparse.ArgumentParser(description="评估环境探针")
    ap.add_argument("--base-url", default=DEFAULT_BASE_URL)
    ap.add_argument("--timeout", type=int, default=60)
    args = ap.parse_args()

    url = args.base_url.rstrip("/")
    print("评估环境探针  目标=%s" % url)

    # 1) 后端可达
    try:
        body = http_json("%s/api/rag/hello" % url, timeout=10)
    except Exception as e:
        print("  [FAIL] 后端不可达：%s" % e)
        return 1
    if body.get("code") != 200:
        print("  [FAIL] /api/rag/hello 异常：%s" % body)
        return 1
    print("  [ OK ] 后端可达")

    # 2) 缓存写入（noCache=false）
    try:
        first, _ = ask(url, PROBE_QUESTION, no_cache=False, timeout=args.timeout)
    except Exception as e:
        print("  [FAIL] 首次问答失败：%s" % e)
        return 1
    print("  [ OK ] 首次问答成功（fromCache=%s，答案%s）" % (
        first.get("fromCache"), "为兜底" if is_fallback(first.get("answer") or "") else "正常"))

    # 3) 旁路探针（noCache=true）
    try:
        second, _ = ask(url, PROBE_QUESTION, no_cache=True, timeout=args.timeout)
    except Exception as e:
        print("  [FAIL] 旁路请求失败：%s" % e)
        return 1

    if second.get("fromCache"):
        print("  [FAIL] 缓存旁路**未生效**：noCache=true 仍命中缓存（fromCache=true）")
        print()
        print("  原因：后端没有以 RAG_EVAL_BYPASS=true 启动。修复：")
        print('    $env:RAG_EVAL_BYPASS="true"; mvn spring-boot:run')
        print("  不修复的后果：重复问题返回上一轮缓存答案，Recall/MRR/TTFT 全部失真。")
        return 1

    print("  [ OK ] 缓存旁路生效（noCache=true 未命中缓存）")
    print()
    print("环境就绪，可以跑 tools/eval/run_eval.py")
    return 0


if __name__ == "__main__":
    sys.exit(main())
