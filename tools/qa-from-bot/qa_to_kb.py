#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
qa_to_kb.py —— 把「从现成客服机器人/人工整理得到的一问一答」转成知识库 Markdown + 来源清单，
产物可直接交给 kb_oneclick.ps1（-InputDir 指向输出目录）入库。

适用场景（docs/知识库运营手册 §用学校现成机器人扩充）：
  1. 现成机器人的官方问答 = 学生高频问题，把每个 (问题, 答案) 记成一行；
  2. 答案最好回到官网核对一遍原文，来源链接填官方 URL（入库后热门知识 Top10 可归并、可溯源）；
  3. 转换后每条变成独立 Markdown：标题=问题（一条一问、检索友好），正文=答案，按「栏目」分文件夹；
     同时生成 manifest_faq_*.csv 来源清单，kb_csv_builder 会自动回填「来源链接」列。

只依赖标准库；编码自动处理 UTF-8(BOM)/GB18030。

用法一：先生成「待填写清单」（模板），再人工逐行补答案/来源
  python qa_to_kb.py template --output 待填写.csv --category 招生问答
  # 可选：从一个问题列表（每行一个问题；行内用制表符可写「栏目<TAB>问题」）预填到模板，省得手敲
  python qa_to_kb.py template --output 待填写.csv --category 招生问答 --prefill 问题列表.txt

用法二：把填好的清单转成 Markdown + manifest（下一步交给 kb_oneclick）
  python qa_to_kb.py build --csv 待填写.csv --output kb_faq
  ./tools/kb_oneclick.ps1 -DryRun -InputDir kb_faq -CategoriesJson tools/web-collect/categories.json

清单列（表头固定，顺序可换）：
  栏目,问题,答案,来源链接
  - 栏目 = 知识分类文件夹名，须与 categories.json 里的键一致（否则该栏目导入为分类0）；
  - 问题 = 学生问法（会作为文件名与 # 标题）；
  - 答案 = 从机器人抄下、并到官网核对的回答正文（可含空行/列表，可多段）；
  - 来源链接 = 官方原文 URL（可空：仍会生成 md，只是来源链接留空，热门 Top10 退化为按标题归并）。
"""
import argparse
import csv
import io
import os
import re
import sys
import time
from collections import OrderedDict

DEFAULT_CATEGORY = '通用'   # 清单里没填「栏目」时的兜底文件夹（categories.json 无此键→该栏目导入为分类0）
HEADER = ['栏目', '问题', '答案', '来源链接']
MANIFEST_HEADER = ['栏目', '分类ID', '文章标题', '字数', '来源URL', '本地文件']
ILLEGAL_FILENAME = re.compile(r'[\\/:*?"<>|\r\n\t\x00-\x1f]')


def read_text_robust(path):
    """先按 UTF-8(BOM) 严格读，失败回退 GB18030（与仓库其它工具一致）。"""
    with open(path, 'rb') as f:
        data = f.read()
    for enc in ('utf-8-sig', 'gb18030'):
        try:
            return data.decode(enc)
        except UnicodeDecodeError:
            continue
    raise ValueError('无法识别文件编码：%s' % path)


def read_rows(path):
    """读清单 CSV → list[dict]（按表头中文名取值，顺序可换）。"""
    text = read_text_robust(path)
    text = text.lstrip('﻿')
    reader = csv.DictReader(io.StringIO(text))
    if not reader.fieldnames:
        return [], []
    rows = list(reader)
    missing = [c for c in ('问题', '答案') if c not in reader.fieldnames]
    if missing:
        raise ValueError('清单缺列：%s（应有 栏目,问题,答案,来源链接）' % '、'.join(missing))
    return rows, [c for c in HEADER if c not in reader.fieldnames]


def sanitize_filename(question, max_len=40):
    """问题 → 安全文件名片段（保留中文/数字/字母，去掉 Windows 非法字符）。"""
    s = re.sub(ILLEGAL_FILENAME, ' ', question)
    s = re.sub(r'\s+', ' ', s).strip().strip('. ')
    if not s:
        return '问答'
    if len(s) > max_len:
        # 尽量在标点/空格处截断，避免截出半个词
        cut = s[:max_len]
        cut = re.sub(r'[。，、；：！？,.;:!?\s]+[^。，、；：！？,.;:!?\s]*$', '', cut)
        s = cut or s[:max_len]
    return s


def clean_row(r):
    return {'栏目': (r.get('栏目') or '').strip(),
            '问题': (r.get('问题') or '').strip(),
            '答案': (r.get('答案') or '').strip(),
            '来源链接': (r.get('来源链接') or '').strip()}


# ---------------- mode: template ----------------
def cmd_template(args):
    rows = []
    seen = set()
    if os.path.exists(args.output):
        existing, _ = read_rows(args.output)
        rows = [clean_row(r) for r in existing if (r.get('问题') or '').strip()]
        seen = {(r['栏目'], r['问题']) for r in rows}

    added = 0
    if args.prefill:
        if not args.category and not rows:
            print('错误：--prefill 需要 --category 指定默认栏目（或问题行内用制表符自带栏目）。')
            return 1
        with open(args.prefill, encoding='utf-8-sig') as f:
            for raw in f:
                line = raw.rstrip('\r\n')
                if not line.strip() or line.lstrip().startswith('#'):
                    continue
                if '\t' in line:
                    cat, q = line.split('\t', 1)
                else:
                    cat, q = (args.category or DEFAULT_CATEGORY), line
                cat, q = cat.strip(), q.strip()
                if not q or (cat, q) in seen:
                    continue
                seen.add((cat, q))
                rows.append({'栏目': cat, '问题': q, '答案': '', '来源链接': ''})
                added += 1

    outdir = os.path.dirname(os.path.abspath(args.output))
    if outdir:
        os.makedirs(outdir, exist_ok=True)
    with open(args.output, 'w', encoding='utf-8-sig', newline='') as f:
        w = csv.DictWriter(f, fieldnames=HEADER)
        w.writeheader()
        for r in rows:
            w.writerow(r)
    print('%s：%s（共 %d 行）' % ('模板已更新' if seen else '模板已生成', args.output, len(rows)))
    if args.prefill:
        print('  从问题列表新增 %d 行' % added)
    print('下一步：用 Excel 打开清单，逐行填「答案」（从机器人抄下并到官网核对），'
          '「来源链接」填官方原文 URL，保存后再执行 build。')


# ---------------- mode: build ----------------
def cmd_build(args):
    rows, _ = read_rows(args.csv)
    rows = [clean_row(r) for r in rows if (r.get('问题') or '').strip()]
    if not rows:
        print('清单为空（没有填「问题」的行）。')
        return 1
    answered = [r for r in rows if r['答案']]
    if not answered:
        print('没有任何一行填「答案」——请先用 Excel 打开清单逐行补答案，再执行 build。')
        print('  （想预填问题行：qa_to_kb.py template --output 待填写.csv --category 招生问答 --prefill 问题.txt）')
        return 1

    # 栏目 -> [(编号, 问题, 答案, 来源)]；同栏同名去重、答案为空跳过
    groups = OrderedDict()
    no_cat = 0
    no_answer = 0
    seen_q = set()
    for r in rows:
        if not r['答案']:
            no_answer += 1
            continue
        cat = r['栏目'] or (args.default_category or DEFAULT_CATEGORY)
        if not r['栏目']:
            no_cat += 1
        key = (cat, r['问题'])
        if key in seen_q:
            print('  跳过重复行（%s / %s）' % (cat, r['问题'][:24]))
            continue
        seen_q.add(key)
        g = groups.setdefault(cat, [])
        g.append((len(g) + 1, r['问题'], r['答案'], r['来源链接']))

    items = []   # (abs_md_path, rel_name, cat, q, a, url)
    for cat, lst in groups.items():
        catdir = os.path.join(args.output, cat)
        for idx, q, a, url in lst:
            fname = '%03d_%s.md' % (idx, sanitize_filename(q))
            items.append((os.path.join(catdir, fname), fname, cat, q, a, url))

    total = len(items)
    with_url = sum(1 for it in items if it[5])
    for cat, lst in groups.items():
        print('  %-12s %d 条' % (cat, len(lst)))
    if no_answer:
        print('跳过未填「答案」的行：%d（模板里留空的占位行）' % no_answer)
    if no_cat:
        print('提醒：%d 行没填「栏目」，已归入「%s」——该文件夹须在 categories.json 有键，否则导入为分类0。'
              % (no_cat, args.default_category or DEFAULT_CATEGORY))

    if args.dry_run_only:
        print('共 %d 条（带来源链接 %d）；[--dry-run-only] 未生成任何文件。' % (total, with_url))
        return 0

    for md, fname, cat, q, a, url in items:
        os.makedirs(os.path.dirname(md), exist_ok=True)
        with open(md, 'w', encoding='utf-8', newline='\n') as f:
            f.write('# %s\n\n%s\n' % (q, a))

    mrows = [{'栏目': it[2], '分类ID': '', '文章标题': it[3], '字数': len(it[4]),
              '来源URL': it[5], '本地文件': it[1]} for it in items if it[5]]
    mpath = None
    if mrows:
        mpath = os.path.join(args.output, 'manifest_faq_%s.csv' % time.strftime('%Y%m%d_%H%M%S'))
        with open(mpath, 'w', encoding='utf-8-sig', newline='') as f:
            w = csv.DictWriter(f, fieldnames=MANIFEST_HEADER)
            w.writeheader()
            for r in mrows:
                w.writerow(r)

    print('转换完成：%d 条问答 → %s（带来源链接 %d）' % (total, os.path.abspath(args.output), with_url))
    print('来源清单：%s' % (mpath or '（所有行都没填来源链接，未生成）'))

    print('\n下一步入库（先 DryRun 预览再正式导入）：')
    print('  .\\tools\\kb_oneclick.ps1 -DryRun -InputDir "%s" -CategoriesJson .\\tools\\web-collect\\categories.json'
          % os.path.abspath(args.output))
    print('  确认切块无误后，去掉 -DryRun 正式导入（可加 -RepairStatus2 自动修复失败向量）。')
    return 0


def main():
    p = argparse.ArgumentParser(description='现成机器人/人工问答 → 知识库 Markdown（供 kb_oneclick 入库）')
    sub = p.add_subparsers(dest='cmd', required=True)

    pt = sub.add_parser('template', help='生成/扩展待填写清单（含可选 --prefill 从问题列表预填）')
    pt.add_argument('--output', required=True, help='待填写清单 CSV 路径')
    pt.add_argument('--category', default='', help='预填时默认栏目（问题行不带栏目前缀时用）')
    pt.add_argument('--prefill', default='', help='纯问题列表 txt：每行一个问题，行内制表符可写「栏目\\t问题」')
    pt.set_defaults(func=cmd_template)

    pb = sub.add_parser('build', help='把填好的清单转成 Markdown + manifest')
    pb.add_argument('--csv', required=True, help='填好的清单 CSV（栏目,问题,答案,来源链接）')
    pb.add_argument('--output', required=True, help='输出目录（每栏目一个子文件夹 + manifest_faq_*.csv）')
    pb.add_argument('--default-category', default='', help='未填「栏目」行的兜底文件夹（默认：通用）')
    pb.add_argument('--dry-run-only', action='store_true',
                    help='只打印将生成的内容统计，不落盘（先看条数/栏目分布）')
    pb.set_defaults(func=cmd_build)

    args = p.parse_args()
    try:
        sys.exit(args.func(args))
    except (ValueError, OSError) as e:
        print('错误：%s' % e)
        sys.exit(1)


if __name__ == '__main__':
    main()
