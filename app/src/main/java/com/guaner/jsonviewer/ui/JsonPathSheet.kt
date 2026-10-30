package com.guaner.jsonviewer.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.guaner.jsonviewer.databinding.ItemPathHitBinding
import com.guaner.jsonviewer.databinding.SheetJsonpathBinding
import com.guaner.jsonviewer.json.JsonPathEngine
import com.guaner.jsonviewer.theme.ThemeManager
import com.guaner.jsonviewer.json.PathHit
import com.guaner.jsonviewer.json.QueryResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * JSONPath 查询面板（底部弹窗）：
 * 输入表达式 -> 展示命中路径与值 -> 点击某条结果在表单树中定位。
 */
class JsonPathSheet : BottomSheetDialogFragment() {

    companion object {
        const val TAG = "JsonPathSheet"

        // 大 JSON 不走 Bundle（超过约 1MB 会抛 TransactionTooLargeException），用静态持有
        private var pendingJson: String = ""

        private val EXAMPLES = listOf(
            "$.store.book[*].author",
            "$..price",
            "$.store.book[?(@.price < 10)]",
            "$.store.book[0:2]",
            "$.store.bicycle.color"
        )

        fun newInstance(json: String): JsonPathSheet {
            pendingJson = json
            return JsonPathSheet()
        }
    }

    /** 点击某条命中结果：把路径回传给 Activity 去定位 */
    var onLocate: ((String) -> Unit)? = null
    /** 复制全部命中值（JSON 数组形式） */
    var onCopyAll: ((String) -> Unit)? = null

    private var json: String = ""
    private lateinit var binding: SheetJsonpathBinding
    private val hits = ArrayList<PathHit>()
    private lateinit var adapter: HitAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        json = pendingJson
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = SheetJsonpathBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        applyTheme()

        adapter = HitAdapter(ThemeManager.activeColors) { hit ->
            onLocate?.invoke(hit.normalized)
            dismiss()
        }
        binding.results.layoutManager =
            androidx.recyclerview.widget.LinearLayoutManager(requireContext())
        binding.results.adapter = adapter

        val colors = ThemeManager.activeColors
        for (example in EXAMPLES) {
            val chip = TextView(requireContext()).apply {
                text = example
                textSize = 11f
                setTextColor(colors.accent)
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 16 * resources.displayMetrics.density
                    setColor(colors.gutter)
                }
                val pad = (10 * resources.displayMetrics.density).toInt()
                setPadding(pad * 2, pad, pad * 2, pad)
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.marginEnd = pad * 2
                layoutParams = lp
                setOnClickListener {
                    binding.exprInput.setText(example)
                    binding.exprInput.setSelection(example.length)
                }
            }
            binding.chipRow.addView(chip)
        }

        binding.runButton.setOnClickListener { query() }
        binding.exprInput.setOnEditorActionListener { _, _, _ ->
            query()
            true
        }
        binding.copyAllButton.setOnClickListener {
            if (hits.isEmpty()) {
                toast("没有可复制的结果")
            } else {
                // 总长度上限 200K 字符：5000 条 × 每条最多 100K 的拼接会 OOM
                val sb = StringBuilder("[")
                var copied = 0
                for (hit in hits) {
                    if (sb.length >= 200_000) break
                    if (copied > 0) sb.append(',')
                    sb.append(hit.full)
                    copied++
                }
                sb.append(']')
                val text = if (copied < hits.size) {
                    sb.toString().dropLast(1) + ",\"…（仅复制前 $copied 条，共 ${hits.size} 条）\"]"
                } else {
                    sb.toString()
                }
                onCopyAll?.invoke(text)
            }
        }
    }

    /** 弹窗内所有颜色都跟随运行时主题（BottomSheet 默认取自主题资源，换肤不生效） */
    private fun applyTheme() {
        val colors = ThemeManager.activeColors
        val density = resources.displayMetrics.density
        // 底部弹窗背景：顶部圆角、surface 色
        (dialog as? com.google.android.material.bottomsheet.BottomSheetDialog)?.let { d ->
            d.setOnShowListener {
                d.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
                    ?.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            }
        }
        binding.root.background = android.graphics.drawable.GradientDrawable().apply {
            val r = 18 * density
            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            setColor(colors.surface)
        }
        binding.sheetTitle.setTextColor(colors.text)
        binding.sheetHint.setTextColor(colors.gutterText)
        binding.exprInput.setTextColor(colors.text)
        binding.exprInput.setHintTextColor(colors.gutterText)
        binding.exprLayout.setBoxStrokeColor(colors.accent)
        binding.exprLayout.defaultHintTextColor =
            android.content.res.ColorStateList.valueOf(colors.gutterText)
        binding.runButton.backgroundTintList =
            android.content.res.ColorStateList.valueOf(colors.accent)
        binding.runButton.setTextColor(ThemeManager.contrastText(colors.accent))
        binding.copyAllButton.setTextColor(colors.accent)
        binding.errorText.setTextColor(ThemedDialog.dangerColor())
        binding.resultCount.setTextColor(colors.gutterText)
    }

    private fun query() {
        val expr = binding.exprInput.text?.toString().orEmpty()
        if (expr.isBlank()) {
            binding.errorText.visibility = View.VISIBLE
            binding.errorText.text = "请输入 JSONPath 表达式"
            return
        }
        binding.errorText.visibility = View.GONE
        binding.runButton.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            // 查询可能因为极端表达式/超深嵌套抛 Error（Jayway 内部递归），
            // 必须在协程里兜住——否则未捕获异常会直接让应用崩溃
            val result = try {
                withContext(Dispatchers.Default) { JsonPathEngine.query(json, expr) }
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                QueryResult.Err("查询失败：${e.javaClass.simpleName}${e.message?.let { "（$it）" } ?: ""}")
            }
            binding.runButton.isEnabled = true
            when (result) {
                is QueryResult.Err -> {
                    binding.errorText.visibility = View.VISIBLE
                    binding.errorText.text = result.message
                    adapter.submit(emptyList())
                    binding.resultCount.text = ""
                }
                is QueryResult.Ok -> {
                    adapter.submit(result.hits)
                    binding.resultCount.text = when {
                        result.hits.isEmpty() -> "无匹配结果"
                        result.truncated ->
                            "共 ${result.totalCount} 个匹配，仅显示前 ${result.hits.size} 个，点击结果定位"
                        else -> "共 ${result.hits.size} 个匹配，点击结果定位"
                    }
                    if (result.hits.isEmpty()) {
                        binding.errorText.visibility = View.VISIBLE
                        binding.errorText.text = "没有节点匹配该表达式"
                    }
                }
            }
        }
    }

    private fun toast(msg: String) {
        val ctx = context ?: return
        Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
    }

    private class HitAdapter(
        val colors: com.guaner.jsonviewer.theme.ThemeColors,
        val onClick: (PathHit) -> Unit
    ) :
        RecyclerView.Adapter<HitAdapter.VH>() {

        private val items = ArrayList<PathHit>()

        fun submit(list: List<PathHit>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val binding = ItemPathHitBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return VH(binding)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val hit = items[position]
            val b = holder.binding
            b.hitIndex.text = "#${position + 1}"
            b.hitIndex.setTextColor(colors.gutterText)
            b.hitPath.text = hit.path
            b.hitPath.setTextColor(colors.jsonKey)
            b.hitKind.text = hit.kind
            b.hitKind.setTextColor(colors.gutterText)
            b.hitValue.text = hit.preview
            b.hitValue.setTextColor(colors.text)
            b.root.setOnClickListener { onClick(hit) }
        }

        override fun getItemCount(): Int = items.size

        class VH(val binding: ItemPathHitBinding) : RecyclerView.ViewHolder(binding.root)
    }
}
