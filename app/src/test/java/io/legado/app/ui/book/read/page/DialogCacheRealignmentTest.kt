package io.legado.app.ui.book.read.page

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 缓存重排（DialogCacheRealignment）单元测试。
 * 覆盖 11-21 承诺的四类用例：符号改动 / 引号增删 / 重复台词 / 无变化，外加守卫类。
 */
class DialogCacheRealignmentTest {

    private fun resultsOf(vararg entries: Pair<Int, String>): JSONObject {
        val results = JSONObject()
        for ((seq, text) in entries) {
            results.put(
                seq.toString(),
                JSONObject().put("name", "角色$seq").put("gender", "男")
                    .put("age", "青年").put("dialogText", text)
            )
        }
        return results
    }

    // ========== extractQuotes：序号口径 = 每个 “ 字符都计数，” 只截断 ==========

    @org.junit.Test
    fun `extractQuotes counts every left quote char`() {
        // 4 个 “：两个完整对话 + 一个无右引号（跨行被吞）+ 一个空引号
        val content = "旁白“甲说”中间“乙说”尾“丙说“”后文"
        val quotes = DialogCacheRealignment.extractQuotes(content)
        // “甲说 → seq1，乙说 → seq2，丙说 → seq3，空引号内容为空不产出
        assertEquals(listOf(1 to "甲说", 2 to "乙说", 3 to "丙说"), quotes)
    }

    // ========== 用例1：无变化 → 不产出重排结果（调用方零写盘） ==========

    @org.junit.Test
    fun `unchanged content yields null realignment`() {
        val content = "他说“你好。”然后“再见。”"
        val old = resultsOf(1 to "你好。", 2 to "再见。")
        assertNull(DialogCacheRealignment.buildRealignedResults(content, old))
    }

    @org.junit.Test
    fun `normalize ignores punctuation and symbols`() {
        assertEquals("你好世界", DialogCacheRealignment.normalize("“你、好———世\u3000界！！”"))
        assertEquals("C22abc", DialogCacheRealignment.normalize("“C22 a-b_c”"))
        assertTrue(DialogCacheRealignment.normalize("？！。，").isEmpty())
    }

    // ========== 用例2：符号改动（字不动，符号变）→ 精确归一化命中 ==========

    @org.junit.Test
    fun `symbol-only change still maps by normalized text`() {
        // 旧正文：“你好。” → 规则把句号改成！并加省略号：新正文 “你好！…”
        val newContent = "他说“你好！…”然后“再见。”"
        val old = resultsOf(1 to "你好。", 2 to "再见。")
        val r = DialogCacheRealignment.buildRealignedResults(newContent, old)
        assertNotNull(r)
        assertEquals(2, r!!.mappedCount)
        assertTrue(r.orphans.isEmpty())
        assertEquals("角色1", r.results.getJSONObject("1").optString("name"))
        assertEquals("你好！…", r.results.getJSONObject("1").optString("dialogText"))
        assertEquals("角色2", r.results.getJSONObject("2").optString("name"))
    }

    // ========== 用例3：引号增删 → 序号平移 ==========

    @org.junit.Test
    fun `removed earlier quote shifts seqs forward`() {
        // 旧：1=术语“宣统” 2=赵志敬“杨过此事当真” 3=杨过…
        // 新规则把非对话“宣统”改成单引号 → 对话序号整体前移
        val old = resultsOf(1 to "宣统", 2 to "杨过，此事当真？", 3 to "臭道士，贼头狗脑。")
        val newContent = "开头‘宣统’的说明“杨过，此事当真？”接着“臭道士，贼头狗脑。”"
        val r = DialogCacheRealignment.buildRealignedResults(newContent, old)
        assertNotNull(r)
        // 旧1（宣统）在正文里已不是双引号台词 → 孤儿；旧2→新1、旧3→新2
        assertTrue(r!!.orphans.contains(1))
        assertEquals("角色2", r.results.getJSONObject("1").optString("name"))
        assertEquals("杨过，此事当真？", r.results.getJSONObject("1").optString("dialogText"))
        assertEquals("角色3", r.results.getJSONObject("2").optString("name"))
        assertEquals(2, r.mappedCount)
    }

    @org.junit.Test
    fun `added earlier quote shifts seqs backward`() {
        // 新正文在前面多了一个术语引号 → 旧对话全部后移一位
        val old = resultsOf(1 to "你好。", 2 to "再见。")
        val newContent = "强调“术语”开头，然后“你好。”和“再见。”"
        val r = DialogCacheRealignment.buildRealignedResults(newContent, old)
        assertNotNull(r)
        assertTrue(r!! === r)
        assertEquals("角色1", r.results.getJSONObject("2").optString("name"))
        assertEquals("角色2", r.results.getJSONObject("3").optString("name"))
        assertTrue(r!!.results.optJSONObject("1") == null || r.results.getJSONObject("1").optString("name") != "角色1")
    }

    // ========== 用例4：重复台词 → 顺序消歧，不抢同一个位置 ==========

    @org.junit.Test
    fun `duplicate dialogues map in order`() {
        // 两个“好”，按顺序分别给两个角色，不会都挤到第1个位置
        val old = resultsOf(1 to "好。", 2 to "好。", 3 to "走吧。")
        val newContent = "“好！”“好！”最后“走吧。”"
        val r = DialogCacheRealignment.buildRealignedResults(newContent, old)
        assertNotNull(r)
        assertTrue(r!! === r)
        assertEquals("角色1", r.results.getJSONObject("1").optString("name"))
        assertEquals("角色2", r.results.getJSONObject("2").optString("name"))
        assertEquals("角色3", r.results.getJSONObject("3").optString("name"))
    }

    // ========== 守卫：台词被改字 → 内插兜底或孤儿，绝不空挂 ==========

    @org.junit.Test
    fun `rewritten middle dialogue interpolates between anchors`() {
        // 旧 2 的台词被规则改了字（归一化对不上），但它夹在 1、3 两个锚点之间
        val old = resultsOf(1 to "第一句。", 2 to "被改掉的句子", 3 to "第三句。")
        val newContent = "“第一句。”“换了内容的句子”“第三句。”"
        val r = DialogCacheRealignment.buildRealignedResults(newContent, old)
        assertNotNull(r)
        assertTrue(r!! === r)
        assertEquals("角色1", r.results.getJSONObject("1").optString("name"))
        assertEquals("角色3", r.results.getJSONObject("3").optString("name"))
        // 旧2内插到新2（1、3之间唯一的空位）
        assertEquals("角色2", r.results.getJSONObject("2").optString("name"))
    }

    @org.junit.Test
    fun `no anchor orphan stays out`() {
        // 开头的条目锚丢了、前面又没有别的锚 → 孤儿，不硬塞
        val old = resultsOf(1 to "消失的句子", 2 to "第二句。")
        val newContent = "“第二句。”"
        val r = DialogCacheRealignment.buildRealignedResults(newContent, old)
        assertNotNull(r)
        assertTrue(r!!.orphans.contains(1))
        assertEquals("角色2", r.results.getJSONObject("1").optString("name"))
    }

    // ========== 11-21 15:5x 回归：孤儿绝不丢（0077 实测 9→1 的教训） ==========

    @org.junit.Test
    fun `orphans are backfilled never dropped`() {
        // 9 条缓存、正文只剩 1 句匹配 → 8 条孤儿必须全部保留在 results 里
        val old = resultsOf(1 to "看上别的男人了？", 2 to "说话！", 3 to "真以为我找不到你？",
            4 to "我其实真不是变态。", 5 to "骚东西，想我了？", 6 to "之前夸没夸过你的手指",
            7 to "会弹琴吗？", 8 to "不管会不会现在都去学琴", 9 to "你不要太过分！")
        val newContent = "正文里只有“我其实真不是变态。”这一句对得上"
        val r = DialogCacheRealignment.buildRealignedResults(newContent, old)
        assertNotNull(r)
        assertTrue(r!!.orphans.size == 8)
        // 结果条目数=旧条目数（9），一个不丢
        assertEquals(9, r.results.length())
        // 命中的那条挂到新位置且台词刷新；孤儿原样保留（名字+原台词）。
        // 注意：回填是「顺延找空位」不是「保持原键」——校验按名字集合+台词集合对账。
        val hit = r.results.getJSONObject("1")
        assertEquals("角色4", hit.optString("name"))
        assertEquals("我其实真不是变态。", hit.optString("dialogText"))
        // 9 个名字一个不少（角色1~9 全在结果里）
        val names = (1..r.results.length()).map { r.results.getJSONObject(it.toString()).optString("name") }.toSet()
        assertEquals((1..9).map { "角色$it" }.toSet(), names)
        // 每条孤儿台词原文保留（「说话！」「你不要太过分！」等 7 条非命中台词都在）
        val texts = (1..r.results.length()).map { r.results.getJSONObject(it.toString()).optString("dialogText") }.toSet()
        assertTrue(texts.contains("说话！"))
        assertTrue(texts.contains("你不要太过分！"))
        assertTrue(texts.contains("会弹琴吗？"))
    }

    @org.junit.Test
    fun `realigned result equal to current cache yields same object for idempotence`() {
        // 幂等：对「已回填过」的缓存再排一次，产出与输入全同（调用方据此跳过写盘）
        val old = resultsOf(1 to "消失的句子", 2 to "第二句。")
        val newContent = "“第二句。”"
        val first = DialogCacheRealignment.buildRealignedResults(newContent, old)
        assertNotNull(first)
        // 把第一次结果再喂回去（模拟第二次挂标注）
        val again = DialogCacheRealignment.buildRealignedResults(newContent, first!!.results)
        assertNotNull(again)
        // 全同：键集一致、每个条目 name/dialogText 一致 → 调用方 changed=false 不写盘
        assertEquals(first.results.length(), again!!.results.length())
        for (k in first.results.keys()) {
            val a = first.results.getJSONObject(k)
            val b = again.results.getJSONObject(k)
            assertEquals(a.optString("name"), b.optString("name"))
            assertEquals(a.optString("dialogText"), b.optString("dialogText"))
        }
    }

    @org.junit.Test
    fun `empty or shell cache returns null`() {
        val shell = JSONObject().put("3", JSONObject().put("name", "").put("dialogText", "x"))
        assertNull(DialogCacheRealignment.buildRealignedResults("“你好。”", shell))
        assertNull(DialogCacheRealignment.buildRealignedResults("", resultsOf(1 to "你好")))
        assertNull(DialogCacheRealignment.buildRealignedResults("没有引号的正文", resultsOf(1 to "你好")))
    }

    // ========== 大章耗时冒烟（毫秒级验证） ==========

    @org.junit.Test
    fun `large chapter alignment stays fast`() {
        val sb = StringBuilder()
        val oldEntries = ArrayList<Pair<Int, String>>()
        for (i in 1..200) {
            sb.append("旁白第").append(i).append("段他说“这是第").append(i).append("句台词内容。”")
            oldEntries.add(i to "这是第${i}句台词内容。")
        }
        val old = resultsOf(*oldEntries.toTypedArray())
        // 模拟规则改动：给每句台词加符号
        val changed = sb.toString().replace("。”", "！……”")
        val t0 = System.currentTimeMillis()
        val r = DialogCacheRealignment.buildRealignedResults(changed, old)
        val ms = System.currentTimeMillis() - t0
        assertNotNull(r)
        assertEquals(200, r!!.mappedCount)
        assertTrue(r.orphans.isEmpty())
        assertTrue("对齐耗时 ${ms}ms 超过 500ms", ms < 500)
    }
}
