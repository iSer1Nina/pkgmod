package com.pkgmod.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ListView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.pkgmod.R
import com.pkgmod.rule.MatchMode
import com.pkgmod.rule.ReplaceMode
import com.pkgmod.rule.Rule
import com.pkgmod.rule.RuleSet
import com.pkgmod.rule.RulesParser
import com.pkgmod.rule.RulesWriter
import java.io.File

/**
 * 规则管理：可视化增删改，保存后写入 filesDir/rules.json，返回主界面立即生效。
 */
class RuleListActivity : Activity() {

    companion object {
        private const val REQUEST_JSON_EDITOR = 2001

        private val MATCH_LABELS = arrayOf("精确匹配", "前缀匹配", "后缀匹配", "包含", "正则匹配")
        private val REPLACE_LABELS = arrayOf("固定值", "模板（可用 {pkg}）", "末位字符 +1", "正则替换（可用 $1）")
    }

    private val rules = ArrayList<Rule>()
    private var version = 1
    private lateinit var adapter: RuleAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_rule_list)

        adapter = RuleAdapter()
        val list = findViewById<ListView>(R.id.ruleList)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ -> showEditDialog(position) }
        list.setOnItemLongClickListener { _, _, position, _ ->
            confirmDelete(position)
            true
        }

        findViewById<Button>(R.id.btnAddRule).setOnClickListener { showEditDialog(-1) }
        findViewById<Button>(R.id.btnResetDefaults).setOnClickListener { loadDefaults() }
        findViewById<Button>(R.id.btnJsonEditor).setOnClickListener {
            startActivityForResult(Intent(this, RulesActivity::class.java), REQUEST_JSON_EDITOR)
        }
        findViewById<Button>(R.id.btnSaveRuleList).setOnClickListener { saveAndFinish() }

        loadRules()
    }

    private fun loadRules() {
        val parsed = runCatching { RulesParser.parse(rulesText()) }.getOrNull()
        rules.clear()
        if (parsed != null && parsed.rules.isNotEmpty()) {
            version = parsed.version
            rules.addAll(parsed.rules)
        } else {
            rules.addAll(defaultRules())
        }
        adapter.submit(rules)
    }

    private fun loadDefaults() {
        rules.clear()
        rules.addAll(defaultRules())
        adapter.submit(rules)
        Toast.makeText(this, "已载入内置规则，点「保存」生效", Toast.LENGTH_SHORT).show()
    }

    private fun saveAndFinish() {
        val json = RulesWriter.toJson(RuleSet(version, rules.toList()))
        val check = runCatching { RulesParser.parse(json) }
        if (check.isFailure) {
            Toast.makeText(this, "规则不合法：${check.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
            return
        }
        File(filesDir, "rules.json").writeText(json)
        setResult(RESULT_OK)
        finish()
    }

    private fun showEditDialog(index: Int) {
        val view = layoutInflater.inflate(R.layout.dialog_rule_edit, null)
        val nameInput = view.findViewById<EditText>(R.id.ruleNameInput)
        val matchSpinner = view.findViewById<Spinner>(R.id.matchSpinner)
        val patternInput = view.findViewById<EditText>(R.id.rulePatternInput)
        val replaceSpinner = view.findViewById<Spinner>(R.id.replaceSpinner)
        val valueInput = view.findViewById<EditText>(R.id.ruleValueInput)
        val enabledBox = view.findViewById<CheckBox>(R.id.ruleEnabledInput)

        val matchModes = MatchMode.values()
        val replaceModes = ReplaceMode.values()

        matchSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            MATCH_LABELS
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        replaceSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            REPLACE_LABELS
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        val existing = if (index >= 0) rules[index] else null
        if (existing != null) {
            nameInput.setText(existing.name)
            matchSpinner.setSelection(matchModes.indexOf(existing.match).coerceAtLeast(0))
            patternInput.setText(existing.pattern)
            replaceSpinner.setSelection(replaceModes.indexOf(existing.replace).coerceAtLeast(0))
            valueInput.setText(existing.value)
            enabledBox.isChecked = existing.enabled
        } else {
            enabledBox.isChecked = true
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (index >= 0) "编辑规则" else "新增规则")
            .setView(view)
            .setPositiveButton("保存", null)
            .setNegativeButton("取消", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val pattern = patternInput.text.toString().trim()
                if (pattern.isEmpty()) {
                    Toast.makeText(this, "匹配内容不能为空", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                val matchMode = matchModes[matchSpinner.selectedItemPosition]
                if (matchMode == MatchMode.REGEX && runCatching { Regex(pattern) }.isFailure) {
                    Toast.makeText(this, "正则表达式写错了", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                val rule = Rule(
                    name = nameInput.text.toString().trim(),
                    match = matchMode,
                    pattern = pattern,
                    replace = replaceModes[replaceSpinner.selectedItemPosition],
                    value = valueInput.text.toString().trim(),
                    enabled = enabledBox.isChecked
                )

                if (index >= 0) {
                    rules[index] = rule
                } else {
                    rules.add(rule)
                }
                adapter.submit(rules)
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun confirmDelete(position: Int) {
        val rule = rules[position]
        AlertDialog.Builder(this)
            .setTitle("删除规则")
            .setMessage(rule.name.ifEmpty { rule.pattern })
            .setPositiveButton("删除") { _, _ ->
                rules.removeAt(position)
                adapter.submit(rules)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun rulesText(): String {
        val custom = File(filesDir, "rules.json")
        if (custom.exists()) return custom.readText()
        return assets.open("default_rules.json").bufferedReader().use { it.readText() }
    }

    private fun defaultRules(): List<Rule> = runCatching {
        RulesParser.parse(assets.open("default_rules.json").bufferedReader().use { it.readText() }).rules
    }.getOrDefault(emptyList())

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_JSON_EDITOR && resultCode == RESULT_OK) {
            loadRules()
        }
    }

    private fun describe(rule: Rule): String = when (rule.replace) {
        ReplaceMode.LAST_CHAR_INC -> "末位字符 +1"
        else -> rule.value
    }

    private inner class RuleAdapter : BaseAdapter() {
        private val items = ArrayList<Rule>()

        fun submit(list: List<Rule>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        override fun getCount(): Int = items.size

        override fun getItem(position: Int): Any = items[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: layoutInflater.inflate(R.layout.item_rule, parent, false)
            val rule = items[position]
            view.findViewById<TextView>(R.id.ruleName).text =
                rule.name.ifEmpty { "规则 ${position + 1}" }
            val suffix = if (rule.enabled) "" else "  （已停用）"
            view.findViewById<TextView>(R.id.ruleDetail).text =
                "${rule.pattern}  \u2192  ${describe(rule)}$suffix"
            return view
        }
    }
}
