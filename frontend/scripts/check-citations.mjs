/**
 * 行内引用渲染回归检查（前端唯一一处专门测试，因为这是最容易静默出错的一环）。
 *
 * 背景：后端在答案正文的每个原文句尾追加 [n]，前端用 marked 的 inline 扩展渲染成
 * 可点击的 <sup class="cite" data-cite="n">。这层若出错**不会报错、只会静默少东西**，
 * 必须用断言锁住。本脚本已抓到过一个真实缺陷：GFM 表格会丢弃超出表头列数的单元格，
 * 所以表格的引用号必须**另起一段**，接在最后一行末尾会被直接吃掉。
 *
 * 跑法：cd frontend && node scripts/check-citations.mjs
 * 退出码：0 全部通过；1 有断言失败。
 */
import { marked } from 'marked'

// 与 src/components/MarkdownText.vue 中注册的扩展保持一致
marked.use({
  extensions: [
    {
      name: 'cite',
      level: 'inline',
      start: (src) => src.indexOf('['),
      tokenizer(src) {
        const m = /^\[(\d{1,2})\]/.exec(src)
        if (m) return { type: 'cite', raw: m[0], n: Number(m[1]) }
      },
      renderer(token) {
        return `<sup class="cite" data-cite="${token.n}">${token.n}</sup>`
      }
    }
  ]
})

const render = (src) => String(marked.parse(src, { gfm: true, breaks: true, async: false }))

let failed = 0
function check(name, cond, detail) {
  if (cond) {
    console.log(`  PASS  ${name}`)
  } else {
    failed++
    console.log(`  FAIL  ${name}`)
    if (detail) console.log(`        ${detail}`)
  }
}

console.log('行内引用渲染回归检查')

// 1) 列表项里的句尾引用
const prose = render('- 学院设置：燕山大学设有研究生院和19个直属学院。 [1]')
check('列表句尾 [1] 渲染为可点击角标', prose.includes('<sup class="cite" data-cite="1">1</sup>'), prose)
check('引用标记未吞掉正文', prose.includes('设有研究生院和19个直属学院'))

// 2) 表格后的引用必须另起一段（这是曾经的缺陷）
const table = render(
  '报到时间：2026年8月27日-28日。 [2]\n\n' +
  '| 时间 | 报到学院 |\n| --- | --- |\n| 8月27日 | 材料学院 |\n| 8月28日 | 机械学院 |\n\n' +
  '[2]'
)
check('表格后的 [2] 未被 GFM 丢弃', table.includes('<sup class="cite" data-cite="2">2</sup>'), table)
const bodyRows = [...table.matchAll(/<tr>([\s\S]*?)<\/tr>/g)].map((m) => (m[1].match(/<td>/g) || []).length)
check('表格每行仍是 2 列（引用号没被当成多余单元格）', bodyRows.every((n) => n === 0 || n === 2), `列数=${bodyRows}`)

// 3) 反面：非数字方括号不得被误当作引用
const negative = render('这是 [12] 号引用，而 [abc] 不是，数组 [1,2] 也不是')
check('[abc] 不产生角标', !negative.includes('data-cite="abc"'), negative)
check('正文里的 [abc] 原样保留', negative.includes('[abc]'))

// 4) 参考文件尾注里的 markdown 链接不受影响
const footer = render('参考文件：\n- [学校简介](https://www.ysu.edu.cn/xxgk/xxjj.htm)')
check('尾注链接仍渲染为 <a>', footer.includes('<a href="https://www.ysu.edu.cn/xxgk/xxjj.htm">'), footer)

console.log(failed === 0 ? '\n全部通过' : `\n${failed} 项失败`)
process.exit(failed === 0 ? 0 : 1)
