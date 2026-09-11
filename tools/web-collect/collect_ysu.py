#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
燕山大学官网公开页面 → Markdown 知识源 采集器（纯 Python 标准库，礼貌限速）。

设计：
  - 面向 RAG 客服系统的知识库导入流水线：抓到的文章写成 Markdown，
    放到 out/<栏目>/ 下，后续交给 tools/kb-import/kb_csv_builder.ps1 切块成导入 CSV。
  - 只抓 .ysu.edu.cn 及同源公开文章页，正文取 TRS CMS 的
    <div class="v_news_content"> 容器；自动拼接多页长文（docid_N.htm 分页）。
  - 不做全站爬取：按 config.json 的 sections 精抓（栏目列表页 + 指定文章），
    每请求间隔 ~1s，含重试；微信(weixin)/外链一律跳过。

用法：
  python collect_ysu.py                 # 默认读同目录 config.json，输出到 ./out
  python collect_ysu.py --fresh         # 忽略已抓记录，全部重抓
  python collect_ysu.py --output D:\\tmp\\kb_out
"""
from __future__ import annotations

import argparse
import csv
import json
import os
import random
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime
from html.parser import HTMLParser

HERE = os.path.dirname(os.path.abspath(__file__))

# 正文容器的 class token（TRS WCM 常见命名；刻意不含泛化的 content/article，
# 避免命中包住整页导航的外层容器）
_CONTENT_CLASS = {"v_news_content", "article_content", "articleContent",
                  "xl_content", "vsb_content", "TRS_Editor"}


def log(msg: str) -> None:
    print(f"[{datetime.now():%H:%M:%S}] {msg}", flush=True)


def make_opener(cfg: dict) -> urllib.request.OpenerDirector:
    headers = {"User-Agent": cfg.get("user_agent", "Mozilla/5.0"), "Accept-Language": "zh-CN,zh;q=0.9"}
    opener = urllib.request.build_opener(urllib.request.HTTPRedirectHandler())
    opener.addheaders = list(headers.items())
    return opener


def fetch(url: str, cfg: dict, opener: urllib.request.OpenerDirector) -> str:
    """带重试的抓取，返回解码后的 HTML 文本。"""
    last = None
    for attempt in range(cfg.get("retries", 2) + 1):
        try:
            req = urllib.request.Request(url)
            with opener.open(req, timeout=cfg.get("timeout", 30)) as r:
                raw = r.read()
            for enc in ("utf-8-sig", "utf-8", "gb18030"):
                try:
                    return raw.decode(enc)
                except (UnicodeDecodeError, LookupError):
                    continue
            return raw.decode("gb18030", "ignore")
        except Exception as e:  # noqa: BLE001 网络层错误统一重试
            last = e
            time.sleep(0.8 * (attempt + 1))
    raise RuntimeError(f"抓取失败 {url}: {last!r}")


def clean_title(title: str) -> str:
    """去掉 <title> 里的站名后缀，如“-燕山大学”“-招生网”。"""
    t = re.sub(r"\s+", " ", (title or "").strip())
    # 只在末尾站名片段时裁掉：优先已知站名（含“燕山大学+站名”复合），其次短小且无中文标点的末段
    t = re.sub(r"\s*[-—–_|·]\s*(?:燕山大学[^，。？、；：\s]{0,10}|招生网|迎新网|就业网|新闻网|首页)\s*$", "", t)
    if not t:
        t = re.sub(r"\s*[-—–_|·]\s*[^，。？、；：]{1,8}\s*$", "", title or "").strip()
    return re.sub(r"\s+", " ", t).strip() or "未命名文章"


def sanitize_filename(name: str, limit: int = 60) -> str:
    name = re.sub(r'[\\/:*?"<>|\r\n\t]', "", name).strip().rstrip(". ")
    name = re.sub(r"\s+", " ", name)
    return name[:limit] or "article"


class ContentExtractor(HTMLParser):
    """定位正文容器并转成“段落列表 + 分页链接”。

    产出：
      paras   -> list[str]，每段一个元素（已在结构上换行）；
      title   -> 页标题（站名后缀已裁）；
      pages   -> set[int]，当前文章的分页页码（docid_N.htm 的 N）。
    """

    # 块级结束即“另起一段”的标签
    BLOCK_END = {
        "p", "div", "li", "tr", "h1", "h2", "h3", "h4", "h5", "h6",
        "blockquote", "ul", "ol", "table",
    }

    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.title = ""
        self._in_title = False
        self._title_buf = []
        # 正文状态
        self.active = False
        self.depth = 0
        self._skip = 0  # script/style 深度
        self._heading = ""      # 当前行前缀（#、- 等）
        self._line = []         # 当前行 inline 片段
        self._cur = []          # 当前段落（若干行）
        self.paras = []
        self.all_links = []
        self._a_stack = []
        # 表格状态
        self._in_td = False
        self._td_buf = []
        self._row = []

    # ---------- 基础工具 ----------
    def _flush_line(self):
        txt = "".join(self._line).strip()
        if self._heading and txt:
            txt = self._heading + txt
        if txt:
            self._cur.append(txt)
        self._line = []
        self._heading = ""

    def _flush_para(self):
        self._flush_line()
        if self._cur:
            self.paras.append("\n".join(self._cur))
        self._cur = []

    def _txt(self, data: str) -> str:
        return re.sub(r"\s+", " ", data)

    # ---------- 头（title） ----------
    def handle_starttag(self, tag, attrs):
        d = dict(attrs)
        if tag == "title":
            self._in_title = True
            self._title_buf = []
        if tag == "a":
            # 无论是否在正文容器内都记录链接，供分页判定用
            self._a_stack.append(d.get("href", ""))
        if not self.active:
            # 未激活：在整页里寻找正文容器（div 带已知 content class / id）
            if self._skip == 0 and tag == "div":
                a = d.get("class", "") or ""
                cid = d.get("id", "") or ""
                if set(re.split(r"\s+", a.strip())) & _CONTENT_CLASS \
                        or cid == "vsb_content" or cid.startswith("vsb_content_"):
                    self.active = True
                    self.depth = 1
            return
        if self._skip:
            return
        if tag in ("script", "style"):
            self._skip += 1
            return
        if tag == "div":
            self.depth += 1
        low = tag.lower()
        if low in ("h1", "h2", "h3", "h4", "h5", "h6"):
            self._flush_line()
            self._heading = "#" * int(low[1]) + " "
        elif low == "li":
            self._flush_line()
            self._heading = "- "
        elif low == "br":
            self._flush_line()
        elif low in ("td", "th"):
            self._flush_line()
            self._in_td = True
            self._td_buf = []

    def handle_data(self, data):
        if self._in_title:
            self._title_buf.append(data)
        if not self.active or self._skip:
            return
        t = self._txt(data)
        if self._in_td:
            self._td_buf.append(t)
        elif t:
            self._line.append(t)

    def handle_endtag(self, tag):
        if tag == "a" and self._a_stack:
            self.all_links.append(self._a_stack.pop())
        if tag == "title":
            self._in_title = False
            self.title = clean_title("".join(self._title_buf))
        if not self.active or self._skip:
            if tag in ("script", "style") and self._skip > 0:
                self._skip -= 1
            return
        low = tag.lower()
        if tag == "div":
            self.depth -= 1
            if self.depth <= 0:
                # 容器闭合
                self._flush_para()
                self.active = False
                self.depth = 0
                return
        if low == "td" or low == "th":
            self._row.append("".join(self._td_buf).strip())
            self._td_buf = []
            self._in_td = False
        elif low == "tr":
            if self._row:
                self._cur.append(" | ".join(self._row))
                self._row = []
        elif low in self.BLOCK_END:
            self._flush_para()

    def body_text(self) -> str:
        return "\n\n".join(self.paras).strip()


def extract(url: str, html: str) -> dict:
    p = ContentExtractor()
    try:
        p.feed(html)
        p.close()
    except Exception:  # 解析到一半坏标签也能拿到已收集内容
        pass
    # 分页判定：本页路径里所有指向“同名 docid_N.htm”的链接（N>=2）
    stem = os.path.splitext(os.path.basename(urllib.parse.urlparse(url).path))[0]
    pages = set()
    for href in p.all_links:
        m = re.fullmatch(stem + r"_(\d+)\.htm", os.path.basename(urllib.parse.urlparse(href).path))
        if m and int(m.group(1)) >= 2:
            pages.add(int(m.group(1)))
    return {"title": p.title, "body": p.body_text(), "pages": sorted(pages)}


def resolve(url: str, href: str) -> str:
    return urllib.parse.urljoin(url, href.strip())


def polite_sleep(cfg: dict) -> None:
    lo, hi = cfg.get("delay", [0.8, 1.4])
    time.sleep(random.uniform(lo, hi))


def collect_article(url: str, cfg: dict, opener: urllib.request.OpenerDirector, out_dir: str,
                    idx: int, state: dict) -> dict | None:
    """抓取一篇文章（含分页拼接），写成 md。返回记录或 None(跳过/无正文)。"""
    # 幂等：已抓过且非 --fresh
    if url in state:
        log(f"跳过已抓 {url} -> {state[url]}")
        return None

    html = fetch(url, cfg, opener)
    info = extract(url, html)
    body = info["body"]
    # 分页拼接 docid_2.htm / docid_3.htm ...
    cur_stem = os.path.splitext(os.path.basename(urlparse_path(url)))[0]
    extra = ""
    for n in info["pages"]:
        page_url = resolve(url, f"{cur_stem}_{n}.htm")
        try:
            ph = fetch(page_url, cfg, opener)
            pbody = extract(page_url, ph)["body"]
            if pbody:
                extra += "\n\n" + pbody
            polite_sleep(cfg)
        except Exception as e:  # 单页失败不阻断
            log(f"  分页 {page_url} 失败: {e}")
    if extra:
        body = body + extra

    body = re.sub(r"\n{3,}", "\n\n", body).strip()
    if len(body) < cfg.get("min_body_chars", 120):
        log(f"跳过过短/空文 {url}（正文 {len(body)} 字）")
        return None

    title = info["title"] or sanitize_filename(cur_stem)
    fname = f"{idx:02d}_{sanitize_filename(title)}.md"
    fpath = os.path.join(out_dir, fname)
    with open(fpath, "w", encoding="utf-8") as f:
        f.write(f"# {title}\n\n{body}\n")
    state[url] = fname
    log(f"[{title}] {len(body):>6} 字 -> {os.path.relpath(fpath, HERE)}")
    return {"title": title, "file": fname, "url": url, "chars": len(body)}


def urlparse_path(url: str) -> str:
    return urllib.parse.urlparse(url).path


def collect_list_articles(list_url: str, cfg: dict, opener, section_out: str,
                          state: dict, records: list, sec_id: str, sec_cat: int) -> None:
    """从栏目列表页解析 info/\d+/\d+.htm 文章链接并逐篇抓取。"""
    html = fetch(list_url, cfg, opener)

    class ListParser(HTMLParser):
        def __init__(self):
            super().__init__()
            self.links = []
            self._cur = None

        def handle_starttag(self, tag, attrs):
            if tag == "a":
                self._cur = {"href": dict(attrs).get("href", ""), "text": []}

        def handle_data(self, d):
            if self._cur is not None:
                self._cur["text"].append(d)

        def handle_endtag(self, tag):
            if tag == "a" and self._cur is not None:
                href = self._cur["href"].strip()
                text = re.sub(r"\s+", " ", "".join(self._cur["text"])).strip()
                if href and text:
                    self.links.append((href, text))
                self._cur = None

    lp = ListParser()
    lp.feed(html)
    articles = []
    for href, text in lp.links:
        if re.search(r"/?info/\d+/\d+\.htm", href) and "weixin" not in href.lower():
            abs_url = resolve(list_url, href)
            articles.append((abs_url, text))
    # 去重保序
    seen, order = set(), []
    for u, t in articles:
        if u not in seen:
            seen.add(u)
            order.append((u, t))
    cap = cfg.get("max_articles_per_list", 12)
    order = order[:cap]
    log(f"{sec_id}: 栏目页解析到 {len(order)} 篇文章，开始抓取")
    for i, (u, t) in enumerate(order, 1):
        try:
            rec = collect_article(u, cfg, opener, section_out, i, state)
            if rec:
                rec["section"] = sec_id
                rec["category"] = sec_cat
                records.append(rec)
        except Exception as e:  # 单篇失败不阻断后续
            log(f"  文章失败 {u}: {e}")
        polite_sleep(cfg)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--config", default=os.path.join(HERE, "config.json"))
    ap.add_argument("--output", default=os.path.join(HERE, "out"))
    ap.add_argument("--fresh", action="store_true", help="忽略已抓记录全部重抓")
    args = ap.parse_args()

    with open(args.config, encoding="utf-8") as f:
        cfg = json.load(f)

    out_root = args.output
    os.makedirs(out_root, exist_ok=True)
    opener = make_opener(cfg)
    state_path = os.path.join(out_root, ".state.json")
    state = {}
    if not args.fresh and os.path.exists(state_path):
        with open(state_path, encoding="utf-8") as f:
            state = json.load(f)

    all_records = []
    try:
        for sec in cfg["sections"]:
            sec_id = sec["id"]
            sec_out = os.path.join(out_root, sec_id)
            os.makedirs(sec_out, exist_ok=True)
            sec_cat = sec.get("category", 0)
            log(f"== 栏目「{sec_id}」(分类ID {sec_cat}) ==")
            seeds = sec.get("seed_articles") or []
            list_url = sec.get("list_url")
            if list_url:
                collect_list_articles(list_url, cfg, opener, sec_out, state, all_records,
                                      sec_id, sec_cat)
            for i, u in enumerate(seeds, 1):
                try:
                    rec = collect_article(u, cfg, opener, sec_out, i, state)
                    if rec:
                        rec["section"] = sec_id
                        rec["category"] = sec_cat
                        all_records.append(rec)
                except Exception as e:
                    log(f"  文章失败 {u}: {e}")
                polite_sleep(cfg)
    finally:
        with open(state_path, "w", encoding="utf-8") as f:
            json.dump(state, f, ensure_ascii=False, indent=2)

    # 来源清单 CSV（UTF-8 BOM，Excel 友好）
    stamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    man_path = os.path.join(out_root, f"manifest_{stamp}.csv")
    with open(man_path, "w", encoding="utf-8-sig", newline="") as f:
        w = csv.writer(f)
        w.writerow(["栏目", "分类ID", "文章标题", "字数", "来源URL", "本地文件"])
        for r in all_records:
            w.writerow([r["section"], r["category"], r["title"], r["chars"], r["url"], r["file"]])
    log(f"完成：共抓取 {len(all_records)} 篇文章")
    log(f"来源清单: {man_path}")
    log(f"Markdown 输出: {os.path.abspath(out_root)}")


if __name__ == "__main__":
    main()
