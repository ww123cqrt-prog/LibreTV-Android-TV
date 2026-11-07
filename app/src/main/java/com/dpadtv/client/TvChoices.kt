package com.dpadtv.client

import android.app.Activity
import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView

/** Explicit TV sizing avoids relying on a manufacturer's AlertDialog list measurement. */
fun Activity.showTvChoices(title: String, choices: List<String>, current: Int, onPick: (Int) -> Unit): Dialog {
    val density = resources.displayMetrics.density
    fun dp(value: Int) = (value * density).toInt()
    val dialog = Dialog(this, R.style.AppDialog)
    val colors = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_selected), intArrayOf()),
        intArrayOf(0xFF111C20.toInt(), 0xFFEDF0F6.toInt()))
    val body = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(24), dp(24), dp(24), dp(20))
        background = GradientDrawable().apply {
            setColor(0xFF151922.toInt()); cornerRadius = dp(18).toFloat(); setStroke(dp(1), 0xFF334155.toInt())
        }
        addView(TextView(context).apply {
            text = title; textSize = 22f; setTextColor(0xFFEDF0F6.toInt()); maxLines = 2
            setPadding(0, 0, 0, dp(16))
        })
    }
    val rowHeight = dp(52)
    val list = ListView(this).apply {
        tag = "tv-choice-list"
        divider = ColorDrawable(Color.TRANSPARENT); dividerHeight = dp(4)
        isFocusableInTouchMode = true
        setSelector(ColorDrawable(Color.TRANSPARENT))
        adapter = object : BaseAdapter() {
            override fun getCount() = choices.size
            override fun getItem(position: Int) = choices[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                ((convertView as? TextView) ?: TextView(context)).apply {
                    text = choices[position]; textSize = 18f; setTextColor(colors)
                    contentDescription = choices[position] + if (position == current) "，当前选项" else ""
                    gravity = Gravity.CENTER_VERTICAL; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                    setPadding(dp(16), 0, dp(16), 0); setBackgroundResource(R.drawable.tv_choice_bg)
                    setCompoundDrawablesRelative(null, null, if (position == current) getDrawable(R.drawable.ic_check)?.apply {
                        setTintList(colors); setBounds(0, 0, dp(22), dp(22))
                    } else null, null)
                    layoutParams = AbsListView.LayoutParams(-1, rowHeight)
                }
        }
        setOnItemClickListener { _, _, position, _ -> dialog.dismiss(); onPick(position) }
    }
    val maxListHeight = (resources.displayMetrics.heightPixels * .82f).toInt() - dp(120)
    body.addView(list, LinearLayout.LayoutParams(-1,
        minOf(choices.size * (rowHeight + dp(4)), maxListHeight).coerceAtLeast(rowHeight)))
    body.addView(TextView(this).apply {
        text = "上下选择  ·  确认应用  ·  返回取消"; textSize = 13f; setTextColor(0xFFA6B3C6.toInt())
        setPadding(0, dp(14), 0, 0)
    })
    dialog.setContentView(body)
    dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    dialog.window?.setDimAmount(.65f)
    dialog.show()
    dialog.window?.setLayout(minOf(dp(560), (resources.displayMetrics.widthPixels * .84f).toInt()), -2)
    @Suppress("DEPRECATION")
    dialog.window?.decorView?.systemUiVisibility = window.decorView.systemUiVisibility
    list.requestFocus(); list.setSelection(current.coerceAtLeast(0))
    return dialog
}
