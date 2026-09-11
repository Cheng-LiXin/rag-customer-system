# -*- coding: utf-8 -*-
"""生成一页 A4 中文简历 PDF（抖音 AI 全栈实习定向版）。"""
import os

from reportlab.lib.pagesizes import A4
from reportlab.lib.units import mm
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.cidfonts import UnicodeCIDFont
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.pdfgen import canvas

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "简历-抖音AI全栈实习-一页.pdf")


def resolve_fonts():
    regular = bold = None
    reg_path = r"C:\Windows\Fonts\msyh.ttc"
    bold_path = r"C:\Windows\Fonts\msyhbd.ttc"
    if os.path.exists(reg_path):
        try:
            pdfmetrics.registerFont(TTFont("CJK", reg_path, subfontIndex=0))
            regular = "CJK"
        except Exception:
            pass
    if os.path.exists(bold_path):
        try:
            pdfmetrics.registerFont(TTFont("CJK-Bold", bold_path, subfontIndex=0))
            bold = "CJK-Bold"
        except Exception:
            pass
    if regular is None:
        pdfmetrics.registerFont(UnicodeCIDFont("STSong-Light"))
        regular = "STSong-Light"
    return regular, bold or regular


CJK, CJK_B = resolve_fonts()
W, H = A4
MARGIN_L = 15 * mm
MARGIN_R = 15 * mm
MARGIN_T = 14 * mm
CONTENT_W = W - MARGIN_L - MARGIN_R

INK = (0.12, 0.12, 0.14)
MUTED = (0.35, 0.35, 0.38)
ACCENT = (0.08, 0.28, 0.55)
LINE = (0.75, 0.78, 0.82)


def wrap_text(text, font, size, max_w):
    lines = []
    for para in text.split("\n"):
        if not para:
            lines.append("")
            continue
        cur = ""
        for ch in para:
            trial = cur + ch
            if pdfmetrics.stringWidth(trial, font, size) <= max_w:
                cur = trial
            else:
                if cur:
                    lines.append(cur)
                cur = ch
        if cur:
            lines.append(cur)
    return lines


def draw_section_title(c, y, title):
    c.setFillColorRGB(*ACCENT)
    c.setFont(CJK_B, 11.5)
    c.drawString(MARGIN_L, y, title)
    c.setStrokeColorRGB(*ACCENT)
    c.setLineWidth(0.9)
    c.line(MARGIN_L, y - 2.3 * mm, W - MARGIN_R, y - 2.3 * mm)
    return y - 5.8 * mm


def draw_bullet(c, y, text, size=8.8, leading=11.8, indent=3.2 * mm, bold_prefix=None):
    max_w = CONTENT_W - indent
    c.setFillColorRGB(*INK)
    if bold_prefix:
        prefix_w = pdfmetrics.stringWidth(bold_prefix, CJK_B, size)
        body_lines = wrap_text(text, CJK, size, max_w - prefix_w)
        if not body_lines:
            body_lines = [""]
        c.setFont(CJK_B, size)
        c.drawString(MARGIN_L + indent, y, bold_prefix)
        c.setFont(CJK, size)
        c.drawString(MARGIN_L + indent + prefix_w, y, body_lines[0])
        y -= leading
        for line in body_lines[1:]:
            c.drawString(MARGIN_L + indent, y, line)
            y -= leading
        return y
    lines = wrap_text(text, CJK, size, max_w)
    for line in lines:
        c.setFillColorRGB(*ACCENT)
        c.circle(MARGIN_L + indent * 0.35, y + 1.3, 0.75, fill=1, stroke=0)
        c.setFillColorRGB(*INK)
        c.setFont(CJK, size)
        c.drawString(MARGIN_L + indent, y, line)
        y -= leading
    return y


def main():
    c = canvas.Canvas(OUT, pagesize=A4)
    c.setTitle("简历-XXX-AI全栈开发实习生")
    c.setAuthor("XXX")
    y = H - MARGIN_T

    # 姓名与意向
    c.setFillColorRGB(*INK)
    c.setFont(CJK_B, 20)
    c.drawString(MARGIN_L, y - 4.5 * mm, "XXX")
    c.setFont(CJK_B, 11)
    c.setFillColorRGB(*ACCENT)
    intent = "AI 全栈开发实习生 · 抖音用户产品"
    iw = pdfmetrics.stringWidth(intent, CJK_B, 11)
    c.drawString(W - MARGIN_R - iw, y - 3.5 * mm, intent)
    y -= 8.5 * mm

    c.setFont(CJK, 9.2)
    c.setFillColorRGB(*MUTED)
    contact = "电话：1XX-XXXX-XXXX  |  邮箱：xxx@xx.com  |  北京  |  可实习 3 个月及以上  |  2027 届在校生"
    c.drawString(MARGIN_L, y, contact)
    y -= 2.8 * mm
    c.setStrokeColorRGB(*LINE)
    c.setLineWidth(0.6)
    c.line(MARGIN_L, y, W - MARGIN_R, y)
    y -= 7 * mm

    # 教育背景
    y = draw_section_title(c, y, "教育背景")
    c.setFont(CJK_B, 10.5)
    c.setFillColorRGB(*INK)
    c.drawString(MARGIN_L, y, "燕山大学　计算机科学与技术 · 本科")
    c.setFont(CJK, 9.2)
    c.setFillColorRGB(*MUTED)
    date = "2023.09 — 2027.06　专业前 15%"
    dw = pdfmetrics.stringWidth(date, CJK, 9.2)
    c.drawString(W - MARGIN_R - dw, y, date)
    y -= 5.0 * mm
    c.setFont(CJK, 9.0)
    c.setFillColorRGB(*INK)
    courses = "主修：数据结构、操作系统、计算机组成原理、计算机网络原理、数据库系统原理、软件工程、数字电子技术基础"
    for line in wrap_text(courses, CJK, 9.0, CONTENT_W):
        c.drawString(MARGIN_L, y, line)
        y -= 4.0 * mm
    y -= 2.2 * mm

    # 专业技能
    y = draw_section_title(c, y, "专业技能")
    skills = [
        ("后端（主）", "Java 17、Spring Boot 3、Spring Security + JWT、MyBatis-Plus、RESTful API、WebSocket 实时通讯、AOP / 定时任务"),
        ("大模型应用", "RAG 检索增强、PostgreSQL + pgvector、bge-m3 Embedding、抽取式生成与防幻觉约束、SSE 流式对话、结构化输出约束"),
        ("前端", "Vue 3 + TypeScript + Vite、Pinia、Vue Router、Element Plus、ECharts、Markdown 安全渲染"),
        ("数据中间件", "MySQL 8、Redis（缓存 / 排队 / 负载 / Pub/Sub）、Docker Compose"),
        ("AI Coding", "熟练使用 Claude Code 等 AI 编程工具辅助拆解、编码、联调与验收，约束生成质量"),
        ("工程基础", "数据结构与算法、操作系统、计算机网络、数据库原理；Maven / Git；多端协议对齐"),
    ]
    for label, body in skills:
        c.setFont(CJK_B, 9.0)
        c.setFillColorRGB(*ACCENT)
        c.drawString(MARGIN_L, y, label)
        lw = pdfmetrics.stringWidth(label, CJK_B, 9.0)
        c.setFont(CJK, 9.0)
        c.setFillColorRGB(*INK)
        avail = CONTENT_W - lw - 1.8 * mm
        for line in wrap_text(body, CJK, 9.0, avail):
            c.drawString(MARGIN_L + lw + 1.8 * mm, y, line)
            y -= 4.0 * mm
        y -= 0.9 * mm
    y -= 1.5 * mm

    # 项目经历
    y = draw_section_title(c, y, "项目经历")
    c.setFont(CJK_B, 10.2)
    c.setFillColorRGB(*INK)
    c.drawString(MARGIN_L, y, "基于 RAG 的智能客服系统")
    c.setFont(CJK, 9.0)
    c.setFillColorRGB(*MUTED)
    meta = "校企联合实习 · 约 2–3 个月 · 独立完成"
    mw = pdfmetrics.stringWidth(meta, CJK, 9.0)
    c.drawString(W - MARGIN_R - mw, y, meta)
    y -= 4.5 * mm
    stack = "技术栈：Spring Boot 3 · Spring AI · Vue 3+TS · MySQL · pgvector · Redis · WebSocket · JWT · SSE"
    c.setFont(CJK, 8.6)
    c.setFillColorRGB(*MUTED)
    for line in wrap_text(stack, CJK, 8.6, CONTENT_W):
        c.drawString(MARGIN_L, y, line)
        y -= 3.8 * mm
    y -= 1.2 * mm

    bullets = [
        ("智能对话 / AI 能力集成：", "实现 RAG 全链路（缓存 → pgvector Top-K → 结构化抽取生成）。将模型约束为「句子编号器」，只输出分组 JSON 与原文行号，服务端拼回原文渲染 markdown（表格、来源尾注），从机制上保证可溯源、语序稳定，支持配置降级回滚。"),
        ("命中率与工程质量：", "三档兜底（严格全量 → 收敛 Top-1 → 相似度≥0.55 宽松选句），降低宽泛问法误答；否定答案不入缓存；意图识别评分式词表（约 110 词 / 6 类），历史可批量回填。"),
        ("实时通讯与人机协同：", "Redis 在线客服 / 排队 / 负载 / 分配，最少负载分配 + 双向 WebSocket；转人工升级原 AI 会话保留上下文；会话超时自动结束并完整收尾；满意度按会话唯一键防刷。"),
        ("全栈研发与 API 设计：", "后端统一响应、JWT、RBAC、分页与归属校验、操作日志 AOP；前端对话页（SSE 打字机、参考来源）、客服工作台、管理后台；知识库支持 CSV / 多格式文档导入与批量向量化。"),
        ("AICoding 实践：", "以 AI 编程工具驱动需求拆解、编码、联调与文档沉淀；对生成代码做边界约束与人工验收，形成可演示、可回滚、可运营的交付习惯。"),
        ("数据到运营闭环：", "官网采集 → 切块去重 → LLM 小标题 → 导入向量化，沉淀约 150+ 活跃知识切片；业务库 12 张表覆盖账号 / 知识 / 会话 / 工单 / 审计统计。"),
    ]
    for prefix, body in bullets:
        y = draw_bullet(c, y, body, size=8.8, leading=11.6, indent=3.2 * mm, bold_prefix=prefix)
        y -= 0.7 * mm
    y -= 1.5 * mm

    # 荣誉与奖项
    y = draw_section_title(c, y, "荣誉与奖项")
    honors = "校级一、二、三等奖学金；校级三好学生；大学英语 CET-4（436）、CET-6（442）"
    c.setFont(CJK, 9.2)
    c.setFillColorRGB(*INK)
    for line in wrap_text(honors, CJK, 9.2, CONTENT_W):
        c.drawString(MARGIN_L, y, line)
        y -= 4.0 * mm
    y -= 2.5 * mm

    # 自我评价
    y = draw_section_title(c, y, "自我评价")
    evals = [
        "对大模型如何落到真实产品有完整实践：不止调 API，更关注检索质量、生成约束、兜底与可运营性，契合智能对话 / AI 工程方向。",
        "后端为主的全栈交付能力，熟悉数据库、缓存、实时通讯、RESTful API 等系统设计概念，愿在导师指导下拓展 Go / 移动端等方向。",
        "日常开发习惯使用 AICoding 工具提效并保持工程审查；自驱完成校企实习项目从 0 到演示全流程，文档与沟通习惯清晰。",
    ]
    for item in evals:
        y = draw_bullet(c, y, item, size=8.8, leading=11.5, indent=3.2 * mm)
        y -= 0.5 * mm

    c.setFont(CJK, 6.8)
    c.setFillColorRGB(0.55, 0.55, 0.58)
    c.drawRightString(W - MARGIN_R, 8 * mm, "投递前请替换姓名 / 电话 / 邮箱")

    print(f"bottom y={y:.1f}pt (A4 H={H:.0f})")
    if y < 8 * mm:
        print("WARNING: may overflow")
    c.showPage()
    c.save()
    print(f"Saved: {OUT}")


if __name__ == "__main__":
    main()
