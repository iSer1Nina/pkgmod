package com.pkgmod.ui

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import com.pkgmod.R
import com.pkgmod.rule.RulesParser
import java.io.File

class RulesActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_rules)

        val editor = findViewById<EditText>(R.id.rulesText)
        editor.setText(currentRules())

        findViewById<Button>(R.id.btnSaveRules).setOnClickListener {
            val text = editor.text.toString()
            runCatching { RulesParser.parse(text) }
                .onSuccess { parsed ->
                    File(filesDir, "rules.json").writeText(text)
                    Toast.makeText(this, "已保存 ${parsed.rules.size} 条规则", Toast.LENGTH_SHORT).show()
                    setResult(RESULT_OK)
                    finish()
                }
                .onFailure { error ->
                    Toast.makeText(this, "JSON 有误：${error.message}", Toast.LENGTH_LONG).show()
                }
        }

        findViewById<Button>(R.id.btnResetRules).setOnClickListener {
            editor.setText(defaultRules())
            Toast.makeText(this, "已载入内置规则，点保存生效", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnCancelRules).setOnClickListener { finish() }
    }

    private fun currentRules(): String {
        val custom = File(filesDir, "rules.json")
        return if (custom.exists()) custom.readText() else defaultRules()
    }

    private fun defaultRules(): String =
        assets.open("default_rules.json").bufferedReader().use { it.readText() }
}
