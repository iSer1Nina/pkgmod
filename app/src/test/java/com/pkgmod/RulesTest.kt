package com.pkgmod

import com.pkgmod.rule.MatchMode
import com.pkgmod.rule.ReplaceMode
import com.pkgmod.rule.Rule
import com.pkgmod.rule.RuleEngine
import com.pkgmod.rule.RuleSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 规则引擎测试：匹配方式、替换方式、顺序与失效目标。
 * 不依赖 org.json，直接构造 Rule 对象。
 */
class RulesTest {

    private fun engine(vararg rules: Rule): RuleEngine =
        RuleEngine(RuleSet(version = 1, rules = rules.toList()))

    @Test
    fun exactMatchWithFixedTarget() {
        val engine = engine(
            Rule(
                name = "固定映射",
                match = MatchMode.EXACT,
                pattern = "com.aligames.kuang.kybc",
                replace = ReplaceMode.FIXED,
                value = "com.aligames.kuang.kybd"
            )
        )
        assertEquals("com.aligames.kuang.kybd", engine.targetOf("com.aligames.kuang.kybc")?.target)
        assertNull(engine.targetOf("com.aligames.kuang.kybd"))
        assertNull(engine.targetOf("com.other.app"))
    }

    @Test
    fun lastCharIncrementTurnsCIntoD() {
        val engine = engine(
            Rule(
                name = "末位 +1",
                match = MatchMode.REGEX,
                pattern = "^com\\.aligames\\.kuang\\.kyb[a-z]$",
                replace = ReplaceMode.LAST_CHAR_INC
            )
        )
        assertEquals("com.aligames.kuang.kybd", engine.targetOf("com.aligames.kuang.kybc")?.target)
        assertEquals("com.aligames.kuang.kybz", engine.targetOf("com.aligames.kuang.kyby")?.target)
        assertNull("正则不匹配时不应命中", engine.targetOf("com.aligames.kuang.kyb1"))
    }

    @Test
    fun lastCharIncrementWrapsZToA() {
        val engine = engine(
            Rule(match = MatchMode.EXACT, pattern = "com.demo.zz", replace = ReplaceMode.LAST_CHAR_INC)
        )
        assertEquals("com.demo.za", engine.targetOf("com.demo.zz")?.target)
    }

    @Test
    fun templateCanReuseOriginalPackage() {
        val engine = engine(
            Rule(
                match = MatchMode.PREFIX,
                pattern = "com.old.",
                replace = ReplaceMode.TEMPLATE,
                value = "{pkg}.mod"
            )
        )
        assertEquals("com.old.game.mod", engine.targetOf("com.old.game")?.target)
    }

    @Test
    fun regexReplaceUsesCaptureGroup() {
        val engine = engine(
            Rule(
                match = MatchMode.REGEX,
                pattern = "^com\\.channel\\.(\\w+)$",
                replace = ReplaceMode.REGEX_REPLACE,
                value = "com.new.\$1"
            )
        )
        assertEquals("com.new.beta", engine.targetOf("com.channel.beta")?.target)
    }

    @Test
    fun firstEnabledMatchWins() {
        val engine = engine(
            Rule(name = "停用", enabled = false, match = MatchMode.EXACT, pattern = "com.a.b", replace = ReplaceMode.FIXED, value = "com.disabled.one"),
            Rule(name = "第一条", match = MatchMode.EXACT, pattern = "com.a.b", replace = ReplaceMode.FIXED, value = "com.first.one"),
            Rule(name = "第二条", match = MatchMode.EXACT, pattern = "com.a.b", replace = ReplaceMode.FIXED, value = "com.second.one")
        )
        val match = engine.targetOf("com.a.b")
        assertEquals("com.first.one", match?.target)
        assertEquals("第一条", match?.rule?.name)
    }

    @Test
    fun invalidTargetFallsThroughToNextRule() {
        val engine = engine(
            Rule(name = "非法", match = MatchMode.EXACT, pattern = "com.a.b", replace = ReplaceMode.FIXED, value = "1bad"),
            Rule(name = "合法", match = MatchMode.EXACT, pattern = "com.a.b", replace = ReplaceMode.FIXED, value = "com.good.one")
        )
        assertEquals("com.good.one", engine.targetOf("com.a.b")?.target)
    }

    @Test
    fun unchangedTargetIsIgnored() {
        val engine = engine(
            Rule(match = MatchMode.EXACT, pattern = "com.a.b", replace = ReplaceMode.FIXED, value = "com.a.b")
        )
        assertNull(engine.targetOf("com.a.b"))
    }

    @Test
    fun suffixAndContainsModesWork() {
        val suffix = engine(
            Rule(match = MatchMode.SUFFIX, pattern = ".kybc", replace = ReplaceMode.TEMPLATE, value = "{pkg}.x")
        )
        assertEquals("com.demo.kybc.x", suffix.targetOf("com.demo.kybc")?.target)

        val contains = engine(
            Rule(match = MatchMode.CONTAINS, pattern = "aligames", replace = ReplaceMode.FIXED, value = "com.replaced.app")
        )
        assertEquals("com.replaced.app", contains.targetOf("com.aligames.kuang.kybc")?.target)
    }
}
