#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
add_subheadings.py —— 给知识切片自动生成「小标题」，输出「小标题——源标题」格式。

用途：
  kb_csv_builder.ps1 按段落切块后，同一源文的多个片段标题相同（都是源文标题/章节标题），
  后台知识列表里难以区分。本脚本对每条内容调 DeepSeek 生成一个 6~14 字的概括小标题，
  把该片段的标题改写为「小标题——源标题」，并把干净的「源标题」单独写入第 6 列，
  供热门知识 Top10 按源文聚合时取标题（避免被小标题污染）。

输入： kb_csv_builder.ps1 的输出（表头 标题,分类ID,内容,关键词,来源链接）
清单： web-collect 的 manifest_*.csv（列 栏目,分类ID,文章标题,字数,来源URL,本地文件）
输出： 6 列 CSV（标题,分类ID,内容,关键词,来源链接,源标题），UTF-8(BOM)，可直接导入。

依赖： 仅标准库 + 环境变量 DEEPSEEK_API_KEY（或 --key）。
缓存： 按内容 MD5 缓存已生成的小标题（--cache 指定的 JSON），重复运行不重复计费。

用法：
  python add_subheadings.py --csv knowledge_import.csv --manifest ../web-collect/out/manifest_*.csv \
      --output knowledge_import_subtitled.csv
"""
import argparse
import csv
import hashlib
import json
import os
import re
import sys
import time
import urllib.request

SEP = '——'  # 小标题与源标题之间的分隔符


def sanitize(raw, max_len=18):
    """清洗模型输出的小标题：去引号/标点/换行/序号，去掉会干扰分隔符的字符。"""
    t = (raw or '').replace('\n', ' ').replace('\r', ' ').strip()
    t = t.strip('"\'“”‘’《》〈〉【】[]()（）「」『』·—–- ')
    t = re.sub(r'^(小标题|标题|总结|摘要|主题|内容|问答)[:：\s]*', '', t)
    t = re.sub(r'^\d+\s*[.、)）:：]\s*', '', t)
    t = re.sub(r'[*_`#~>]', '', t)
    t = t.replace(SEP, '—').replace('|', '·')
    t = re.sub(r'[。．，、；：！？,.;:!?·—\-—\s]+$', '', t)
    t = t.strip()
    if len(t) > max_len:
        t = t[:max_len]
    return t


def fallback_subheading(content, max_len=14):
    """模型失败时的确定性兜底：取正文前几个有效行拼出短标题。"""
    lines = [l.strip() for l in (content or '').splitlines()]
    kept = [l for l in lines
            if l and len(l) > 1 and not re.fullmatch(r'[一二三四五六七八九十百千0-9]+', l)]
    joined = ' '.join(kept)
    joined = re.sub(r'[*_`#~>]', '', joined).replace(SEP, '—').replace('|', '·')
    joined = re.sub(r'[。．，、；：！？,.;:!?·\s]+$', '', joined)
    if not joined:
        return '知识内容'
    s = sanitize(joined, max_len)
    return s if len(s) >= 2 else '知识内容'


def chat(messages, model, key, max_tokens=24):
    url = 'https://api.deepseek.com/chat/completions'
    payload = {
        'model': model,
        'messages': messages,
        'temperature': 0.0,
        'max_tokens': max_tokens,
        'stream': False,
    }
    req = urllib.request.Request(
        url,
        data=json.dumps(payload).encode('utf-8'),
        headers={'Content-Type': 'application/json', 'Authorization': 'Bearer ' + key},
    )
    with urllib.request.urlopen(req, timeout=60) as r:
        return json.loads(r.read().decode('utf-8'))


def summarize(content, model, key, cache):
    h = hashlib.md5(content.encode('utf-8')).hexdigest()
    if h in cache and cache[h]:
        return cache[h]

    prompt = ('给下面这段知识内容起一个 6～14 字的简短小标题，准确概括主题。'
              '只输出小标题本身，不要书名号、引号、标点、换行、序号或解释。\n\n'
              '内容：\n' + content[:900])
    title = ''
    for attempt in range(3):
        try:
            resp = chat([{'role': 'user', 'content': prompt}], model, key)
            title = sanitize(resp['choices'][0]['message']['content'])
            if len(title) >= 2:
                break
            title = ''
        except Exception:
            title = ''
            time.sleep(1.0 + attempt)
    if len(title) < 2:
        title = fallback_subheading(content)
    cache[h] = title
    return title


def load_cache(path):
    if path and os.path.exists(path):
        try:
            with open(path, encoding='utf-8') as f:
                return json.load(f)
        except Exception:
            return {}
    return {}


def save_cache(path, cache):
    if not path:
        return
    tmp = path + '.tmp'
    with open(tmp, 'w', encoding='utf-8') as f:
        json.dump(cache, f, ensure_ascii=False, indent=2)
    os.replace(tmp, path)


def main():
    ap = argparse.ArgumentParser(description='为知识切片生成小标题，输出「小标题——源标题」CSV')
    ap.add_argument('--csv', required=True, help='kb_csv_builder 输出的 CSV（5 列）')
    ap.add_argument('--manifest', help='web-collect 的 manifest_*.csv（来源URL->文章标题）')
    ap.add_argument('--output', required=True, help='输出 CSV 路径（6 列）')
    ap.add_argument('--model', default='deepseek-chat', help='DeepSeek 模型（默认 deepseek-chat）')
    ap.add_argument('--key', default='', help='DeepSeek API key（默认取环境变量 DEEPSEEK_API_KEY）')
    ap.add_argument('--cache', default='', help='小标题缓存 JSON 路径（默认 <output>.cache.json）')
    ap.add_argument('--limit', type=int, default=0, help='只处理前 N 行（预览用）')
    ap.add_argument('--min-chars', type=int, default=0, help='丢弃内容少于该字数的片段（如页脚噪声），默认 0 不丢')
    args = ap.parse_args()

    key = args.key or os.environ.get('DEEPSEEK_API_KEY', '')
    if not key:
        print('缺少 DEEPSEEK_API_KEY：用 --key 或设置环境变量 DEEPSEEK_API_KEY', file=sys.stderr)
        sys.exit(2)

    # 清单：来源URL -> 文章标题
    url2title = {}
    if args.manifest and os.path.exists(args.manifest):
        with open(args.manifest, encoding='utf-8-sig') as f:
            for row in csv.DictReader(f):
                url = (row.get('来源URL') or '').strip()
                title = (row.get('文章标题') or '').strip()
                if url and title:
                    url2title[url] = title
        print(f'载入清单 {len(url2title)} 条（来源URL -> 文章标题）')

    cache_path = args.cache or (args.output + '.cache.json')
    cache = load_cache(cache_path)

    with open(args.csv, encoding='utf-8-sig') as f:
        reader = csv.DictReader(f)
        out_rows = []
        n_summarized = 0
        n_passthrough = 0
        n_skipped = 0
        for i, row in enumerate(reader):
            title = (row.get('标题') or '').strip()
            content = row.get('内容') or ''
            src_url = (row.get('来源链接') or '').strip()

            if args.min_chars and len(content.strip()) < args.min_chars:
                n_skipped += 1
                if args.limit and i + 1 >= args.limit:
                    break
                continue

            source_title = url2title.get(src_url, '')
            if source_title:
                sub = summarize(content, args.model, key, cache)
                new_title = f'{sub}{SEP}{source_title}'
                n_summarized += 1
            else:
                # 无来源链接（手写）或清单里没有：不改标题，源标题留空
                new_title = title
                source_title = ''
                n_passthrough += 1

            out_rows.append([
                new_title,
                (row.get('分类ID') or '').strip(),
                content,
                (row.get('关键词') or '').strip(),
                src_url,
                source_title,
            ])
            if (i + 1) % 5 == 0:
                save_cache(cache_path, cache)
            if args.limit and i + 1 >= args.limit:
                break
            time.sleep(0.3)

    with open(args.output, 'w', encoding='utf-8-sig', newline='') as f:
        w = csv.writer(f)
        w.writerow(['标题', '分类ID', '内容', '关键词', '来源链接', '源标题'])
        w.writerows(out_rows)

    save_cache(cache_path, cache)
    print(f'完成：生成小标题 {n_summarized} 条，原样保留 {n_passthrough} 条，丢弃 {n_skipped} 条 -> {args.output}')
    print(f'缓存：{cache_path}')


if __name__ == '__main__':
    main()
