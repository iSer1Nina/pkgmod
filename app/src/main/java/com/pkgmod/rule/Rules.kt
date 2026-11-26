package com.pkgmod.rule

import org.json.JSONObject
import java.util.regex.Pattern

enum class MatchMode(val key: String) {
    EXACT("exact"),
    PREFIX("prefix"),
    SUFFIX("suffix"),
    CONTAINS("contains"),
    REGEX("regex");

    companion object {
        fun from(value: String): MatchMode =
            values().firstOrNull { it.key.equals(value, ignoreCase = true) } ?: EXACT
    }
}

enum class ReplaceMode(val key: String) {
    FIXED("fixed"),
    TEMPLATE("template"),
    LAST_CHAR_INC("lastCharInc"),
    REGEX_REPLACE("regexReplace");

    companion object {
        fun from(value: String): ReplaceMode =
            values().firstOrNull { it.key.equals(value, ignoreCase = true) } ?: FIXED
    }
}

data class Rule(
    val name: String = "",
    val match: MatchMode = MatchMode.EXACT,
    val pattern: String = "",
    val replace: ReplaceMode = ReplaceMode.FIXED,
    val value: String = "",
    val enabled: Boolean = true,
    val scope: Set<String> = setOf("manifest", "arsc")
) {
    companion object {
        val DEFAULT_SCOPE = setOf("manifest", "arsc")
    }
}

data class RuleSet(
    val version: Int = 1,
    val rules: List<Rule> = emptyList()
)

data class MatchResult(
    val rule: Rule,
    val target: String
)

object RulesParser {

    fun parse(text: String): RuleSet {
        val root = JSONObject(text)
        val version = root.optInt("version", 1)
        val array = root.optJSONArray("rules") ?: return RuleSet(version, emptyList())

        val rules = ArrayList<Rule>(array.length())
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val pattern = item.optString("pattern", "")
            if (pattern.isEmpty()) continue

            rules.add(
                Rule(
                    name = item.optString("name", ""),
                    match = MatchMode.from(item.optString("match", "exact")),
                    pattern = pattern,
                    replace = ReplaceMode.from(item.optString("replace", "fixed")),
                    value = item.optString("value", ""),
                    enabled = item.optBoolean("enabled", true),
                    scope = parseScope(item.optString("scope", "manifest,arsc"))
                )
            )
        }
        return RuleSet(version, rules)
    }

    private fun parseScope(raw: String): Set<String> =
        raw.split(',')
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .toSet()
            .ifEmpty { Rule.DEFAULT_SCOPE }
}

class RuleEngine(val ruleSet: RuleSet) {

    private val validPackage = Regex("^[a-zA-Z_][a-zA-Z0-9_]*(\\.[a-zA-Z_][a-zA-Z0-9_]*)*$")

    fun targetOf(pkg: String): MatchResult? {
        for (rule in ruleSet.rules) {
            if (!rule.enabled) continue
            if (!matches(rule, pkg)) continue

            val target = buildTarget(rule, pkg) ?: continue
            if (target == pkg) continue
            if (!isValidPackageName(target)) continue
            return MatchResult(rule, target)
        }
        return null
    }

    fun isValidPackageName(candidate: String): Boolean {
        if (candidate.length !in 3..255) return false
        if (!candidate.contains('.')) return false
        return validPackage.matches(candidate)
    }

    private fun matches(rule: Rule, pkg: String): Boolean = when (rule.match) {
        MatchMode.EXACT -> pkg == rule.pattern
        MatchMode.PREFIX -> pkg.startsWith(rule.pattern)
        MatchMode.SUFFIX -> pkg.endsWith(rule.pattern)
        MatchMode.CONTAINS -> pkg.contains(rule.pattern)
        MatchMode.REGEX -> runCatching { Pattern.matches(rule.pattern, pkg) }.getOrDefault(false)
    }

    private fun buildTarget(rule: Rule, pkg: String): String? = when (rule.replace) {
        ReplaceMode.FIXED -> rule.value
        ReplaceMode.TEMPLATE -> rule.value.replace("{pkg}", pkg)
        ReplaceMode.LAST_CHAR_INC -> increaseLastChar(pkg)
        ReplaceMode.REGEX_REPLACE -> runCatching {
            Regex(rule.pattern).replace(pkg, rule.value)
        }.getOrNull()
    }

    private fun increaseLastChar(pkg: String): String? {
        if (pkg.isEmpty()) return null
        val last = pkg.last()
        val next = when (last) {
            in 'a'..'y' -> last + 1
            'z' -> 'a'
            in 'A'..'Y' -> last + 1
            'Z' -> 'A'
            in '0'..'8' -> last + 1
            '9' -> '0'
            else -> return null
        }
        return pkg.dropLast(1) + next
    }
}
