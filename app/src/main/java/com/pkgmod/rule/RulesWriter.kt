package com.pkgmod.rule

import org.json.JSONArray
import org.json.JSONObject

/** 把规则集写回 JSON（与 RulesParser 的字段一一对应）。 */
object RulesWriter {

    fun toJson(set: RuleSet): String {
        val root = JSONObject()
        root.put("version", set.version)

        val array = JSONArray()
        for (rule in set.rules) {
            val item = JSONObject()
            item.put("name", rule.name)
            item.put("match", rule.match.key)
            item.put("pattern", rule.pattern)
            item.put("replace", rule.replace.key)
            item.put("value", rule.value)
            item.put("enabled", rule.enabled)
            item.put("scope", rule.scope.joinToString(","))
            array.put(item)
        }
        root.put("rules", array)
        return root.toString(2)
    }
}
