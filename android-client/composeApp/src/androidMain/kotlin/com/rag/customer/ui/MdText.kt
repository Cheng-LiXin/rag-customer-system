package com.rag.customer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 轻量 Markdown 渲染（镜像 mp MdText.vue）：支持整行 **小标题**、- / 1. 列表、
 * 竖线表格（首行表头 + 自动去 |---| 分隔行）、普通段落 + 行内 **加粗**；
 * markdown 链接去外壳只留文字；分隔线忽略。
 */
private data class Seg(val s: String, val b: Boolean)

private sealed class Blk {
    class H(val segs: List<Seg>) : Blk()
    class Li(val segs: List<Seg>) : Blk()
    class P(val segs: List<Seg>) : Blk()
    class Table(val header: List<String>, val rows: List<List<String>>) : Blk()
}

private fun stripLinks(raw: String): String =
    Regex("\\[([^]]+)]\\([^)]*\\)").replace(raw) { it.groupValues[1] }

/** 行内解析：**加粗**。 */
private fun inline(raw: String): List<Seg> {
    val text = stripLinks(raw)
    val segs = ArrayList<Seg>()
    val re = Regex("\\*\\*([^*]+)\\*\\*")
    var last = 0
    for (m in re.findAll(text)) {
        if (m.range.first > last) segs.add(Seg(text.substring(last, m.range.first), false))
        segs.add(Seg(m.groupValues[1], true))
        last = m.range.last + 1
    }
    if (last < text.length) segs.add(Seg(text.substring(last), false))
    return segs
}

private fun splitCells(line: String): List<String> {
    val parts = line.split("|").toMutableList()
    if (parts.isNotEmpty() && parts.first().trim().isEmpty()) parts.removeAt(0)
    if (parts.isNotEmpty() && parts.last().trim().isEmpty()) parts.removeAt(parts.lastIndex)
    return parts.map { it.trim() }
}

private fun isTableLine(l: String): Boolean {
    if (!l.contains('|')) return false
    return splitCells(l).size >= 2
}

private fun parseBlocks(text: String): List<Blk> {
    val lines = text.split(Regex("\\r?\\n"))
    val out = ArrayList<Blk>()
    val para = StringBuilder()
    var tbl: MutableList<List<String>>? = null

    fun flushTable() {
        val t = tbl ?: return
        val data = t.filter { cells -> cells.any { cell -> !Regex("^-{2,}$").matches(cell) } }
        if (data.isNotEmpty()) {
            out.add(Blk.Table(data.first(), data.drop(1)))
        }
        tbl = null
    }

    fun flushPara() {
        if (para.isNotEmpty()) {
            val segs = inline(para.toString())
            if (segs.any { it.s.isNotBlank() }) out.add(Blk.P(segs))
            para.clear()
        }
    }

    for (raw in lines) {
        val t = raw.trimEnd().trim()
        if (t.isEmpty()) {
            flushPara()
            continue
        }
        if (isTableLine(t)) {
            flushPara()
            if (tbl == null) tbl = ArrayList()
            tbl!!.add(splitCells(t))
            continue
        } else if (tbl != null) {
            flushTable()
        }

        val h = Regex("^\\*\\*([^*]+)\\*\\*$").find(t)
        if (h != null) {
            flushPara()
            out.add(Blk.H(listOf(Seg(h.groupValues[1].trim(), true))))
            continue
        }

        var content: String? = null
        val dash = Regex("^[-–•]\\s*(.*)$").find(t)
        if (dash != null) content = dash.groupValues[1]
        else {
            val num = Regex("^\\d+[.．、]\\s*(.*)$").find(t)
            if (num != null) content = num.groupValues[1]
        }
        if (content != null) {
            flushPara()
            if (content.isNotBlank()) out.add(Blk.Li(inline(content)))
            continue
        }

        if (Regex("^-{3,}$").matches(t) || Regex("^=+$").matches(t)) {
            flushPara()
            continue
        }
        para.append(t)
    }
    flushPara()
    if (tbl != null) flushTable()
    return out
}

private fun blockAnnotated(segs: List<Seg>, color: Color, bold: Boolean): AnnotatedString =
    buildAnnotatedString {
        segs.forEach { seg ->
            val st = SpanStyle(
                color = color,
                fontWeight = if (seg.b || bold) FontWeight.Bold else FontWeight.Normal,
            )
            append(AnnotatedString(seg.s, st))
        }
    }

@Composable
fun MdText(
    text: String,
    modifier: Modifier = Modifier,
    textColor: Color = Color(0xFF2B2F3A),
) {
    val bodyStyle = androidx.compose.ui.text.TextStyle(
        fontSize = 15.sp,
        lineHeight = 22.sp,
        color = textColor,
    )
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        for (blk in parseBlocks(text)) {
            when (blk) {
                is Blk.H -> BasicText(
                    text = blockAnnotated(blk.segs, textColor, bold = true),
                    modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
                    style = bodyStyle.copy(fontSize = 15.sp, fontWeight = FontWeight.Bold),
                )

                is Blk.Li -> Row(modifier = Modifier.fillMaxWidth()) {
                    BasicText(
                        text = AnnotatedString("•  "),
                        modifier = Modifier.padding(end = 2.dp),
                        style = bodyStyle.copy(color = Color(0xFF3482FF), fontWeight = FontWeight.Bold),
                    )
                    BasicText(
                        text = blockAnnotated(blk.segs, textColor, bold = false),
                        modifier = Modifier.weight(1f),
                        style = bodyStyle,
                    )
                }

                is Blk.P -> BasicText(
                    text = blockAnnotated(blk.segs, textColor, bold = false),
                    style = bodyStyle,
                )

                is Blk.Table -> MdTable(blk)
            }
        }
    }
}

@Composable
private fun MdTable(blk: Blk.Table) {
    val border = Color(0xFFE0E0E0)
    val alt = Color(0xFFEAF2FF)
    val brand = Color(0xFF3482FF)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 2.dp)
            .background(color = border),
    ) {
        // 表头
        Row(modifier = Modifier.background(color = brand)) {
            blk.header.forEach { c ->
                BasicText(
                    text = AnnotatedString(c),
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    style = androidx.compose.ui.text.TextStyle(
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                    ),
                    maxLines = Int.MAX_VALUE,
                    overflow = TextOverflow.Visible,
                )
            }
        }
        // 数据行
        blk.rows.forEachIndexed { rIdx, row ->
            Row(modifier = Modifier.background(color = if (rIdx % 2 == 1) alt else Color.White)) {
                val maxCols = maxOf(blk.header.size, row.size)
                for (ci in 0 until maxCols) {
                    val cell = row.getOrElse(ci) { "" }
                    BasicText(
                        text = AnnotatedString(cell),
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp, vertical = 5.dp),
                        style = androidx.compose.ui.text.TextStyle(
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                            color = Color(0xFF2B2F3A),
                        ),
                        maxLines = Int.MAX_VALUE,
                        overflow = TextOverflow.Visible,
                    )
                }
            }
            if (rIdx != blk.rows.lastIndex) {
                Spacer(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(color = border),
                )
            }
        }
    }
}
