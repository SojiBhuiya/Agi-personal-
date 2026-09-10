package com.agi.assistant.ui

import android.content.Context
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.TextView
import com.agi.assistant.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One row in the transcript. */
class ChatItem(val kind: Kind, var text: String, val meta: String = "", val success: Boolean = true) {
    enum class Kind { USER, ASSISTANT, TOOL, STATUS, ERROR }
    val time: Long = System.currentTimeMillis()

    companion object {
        fun user(t: String) = ChatItem(Kind.USER, t)
        fun assistant(t: String) = ChatItem(Kind.ASSISTANT, t)
        fun tool(name: String, output: String, ok: Boolean) = ChatItem(Kind.TOOL, output, name.replace('_', ' '), ok)
        fun status(t: String) = ChatItem(Kind.STATUS, t)
        fun error(t: String) = ChatItem(Kind.ERROR, t)
    }
}

class ChatAdapter(private val context: Context) : BaseAdapter() {
    private val items = ArrayList<ChatItem>()
    private val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())

    fun add(item: ChatItem) { items += item; notifyDataSetChanged() }
    fun remove(item: ChatItem) { items.remove(item); notifyDataSetChanged() }
    fun clear() { items.clear(); notifyDataSetChanged() }
    fun insertBefore(anchor: ChatItem, item: ChatItem) {
        val i = items.indexOf(anchor)
        if (i < 0) items += item else items.add(i, item)
        notifyDataSetChanged()
    }

    override fun getCount() = items.size
    override fun getItem(position: Int) = items[position]
    override fun getItemId(position: Int) = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.item_message, parent, false)
        val row = view.findViewById<LinearLayout>(R.id.row)
        val bubble = view.findViewById<TextView>(R.id.bubble)
        val meta = view.findViewById<TextView>(R.id.meta)
        val item = items[position]

        bubble.text = item.text
        bubble.textSize = 15f
        bubble.setTextColor(context.getColor(R.color.text))
        meta.visibility = View.VISIBLE
        when (item.kind) {
            ChatItem.Kind.USER -> {
                row.gravity = Gravity.END
                bubble.setBackgroundResource(R.drawable.bg_bubble_user)
                meta.text = fmt.format(Date(item.time))
            }
            ChatItem.Kind.ASSISTANT -> {
                row.gravity = Gravity.START
                bubble.setBackgroundResource(R.drawable.bg_bubble_assistant)
                meta.text = "Assistant • " + fmt.format(Date(item.time))
            }
            ChatItem.Kind.TOOL -> {
                row.gravity = Gravity.START
                bubble.setBackgroundResource(if (item.success) R.drawable.bg_bubble_tool else R.drawable.bg_bubble_tool_fail)
                bubble.textSize = 13f
                bubble.setTextColor(context.getColor(if (item.success) R.color.accent else R.color.danger))
                meta.text = (if (item.success) "✓ " else "✗ ") + item.meta
            }
            ChatItem.Kind.STATUS -> {
                row.gravity = Gravity.START
                bubble.background = null
                bubble.textSize = 13f
                bubble.setTextColor(context.getColor(R.color.text_muted))
                meta.visibility = View.GONE
            }
            ChatItem.Kind.ERROR -> {
                row.gravity = Gravity.START
                bubble.setBackgroundResource(R.drawable.bg_bubble_tool_fail)
                bubble.textSize = 13f
                bubble.setTextColor(context.getColor(R.color.warning))
                meta.text = "Notice"
            }
        }
        return view
    }
}
