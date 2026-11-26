package com.pkgmod.ui

import android.app.Activity
import android.content.pm.ApplicationInfo
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView
import com.pkgmod.R

data class AppRow(
    val label: String,
    val pkg: String,
    val target: String?,
    val ruleName: String?,
    val scope: Set<String> = emptySet(),
    val info: ApplicationInfo? = null,
    val targetSdk: Int = 0
)

class AppListAdapter(private val activity: Activity) : BaseAdapter() {

    private val items = ArrayList<AppRow>()

    fun submit(rows: List<AppRow>) {
        items.clear()
        items.addAll(rows)
        notifyDataSetChanged()
    }

    fun rowAt(position: Int): AppRow = items[position]

    override fun getCount(): Int = items.size

    override fun getItem(position: Int): Any = items[position]

    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: activity.layoutInflater.inflate(R.layout.item_app, parent, false)
        val row = items[position]

        view.findViewById<TextView>(R.id.appLabel).text = row.label
        view.findViewById<TextView>(R.id.appPkg).text = row.pkg

        val badge = view.findViewById<TextView>(R.id.appBadge)
        val target = row.target
        if (target == null) {
            badge.text = ""
        } else {
            val suffix = if (row.ruleName.isNullOrEmpty()) "" else "  · ${row.ruleName}"
            val warn = if (row.targetSdk in 1..22) "  \u26a0 targetSdk=${row.targetSdk}，Android 14+ 可能拒绝安装" else ""
            badge.text = "\u2192 $target$suffix$warn"
        }
        return view
    }
}
