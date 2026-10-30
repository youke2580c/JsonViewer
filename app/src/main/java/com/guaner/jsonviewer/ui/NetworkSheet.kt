package com.guaner.jsonviewer.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.guaner.jsonviewer.R
import com.guaner.jsonviewer.data.AppSettings
import com.guaner.jsonviewer.data.UaProfile
import com.guaner.jsonviewer.data.UaProfiles
import com.guaner.jsonviewer.data.UrlHistory
import com.guaner.jsonviewer.databinding.SheetNetworkBinding
import com.guaner.jsonviewer.net.HeaderJson
import com.guaner.jsonviewer.net.HeaderParseResult
import com.guaner.jsonviewer.net.HttpEngine
import com.guaner.jsonviewer.net.HttpHeader
import com.guaner.jsonviewer.net.HttpRequest
import com.guaner.jsonviewer.net.HttpResponse
import com.guaner.jsonviewer.theme.ThemeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 网络请求面板（底部弹窗）。
 *
 * 流程：填链接（或从链接历史选一条）→ 选 GET/POST、按需加请求头与请求体 →
 * 发送 → 预览状态码与响应体 → 满意再「导入到代码」写进编辑器。
 *
 * 请求全部走 [HttpEngine]（HttpURLConnection），自定义头原样透传，
 * 所以 Referer / Cookie / Origin 这类反爬相关的头都能自己控制。
 */
class NetworkSheet : BottomSheetDialogFragment() {

    companion object {
        const val TAG = "NetworkSheet"
        private const val PREVIEW_CHARS = 80_000
    }

    /** 响应体导入回调（交给 Activity 写进编辑器，顺带走导入过滤/历史那套逻辑） */
    var onImport: ((String) -> Unit)? = null

    private lateinit var binding: SheetNetworkBinding
    private var method = "GET"
    private var lastBody: String = ""
    private val rows = ArrayList<HeaderRow>()

    /** 默认头策略：与面板上的三个 chip 一一对应，发送前读这里的值 */
    private var sendReferer: Boolean = true
    private var contentType: String = AppSettings.CT_AUTO

    /** 正在用 JSON 批量重建请求头行，期间忽略输入框变化回调 */
    private var fillingHeaders = false

    private class HeaderRow(val root: LinearLayout, val name: EditText, val value: EditText)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = SheetNetworkBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        sendReferer = AppSettings.sendReferer
        contentType = AppSettings.contentType
        applyTheme()
        setupChips()
        bindActions()
        addHeaderRow("", "")
        addHeaderRow("Referer", "")
        renderMethod()
        renderHeaderDefaults()
        updateImportEnabled()
    }

    // ---------- 主题 ----------

    private fun applyTheme() {
        val colors = ThemeManager.activeColors
        val density = resources.displayMetrics.density
        (dialog as? com.google.android.material.bottomsheet.BottomSheetDialog)?.let { d ->
            d.setOnShowListener {
                d.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
                    ?.setBackgroundColor(Color.TRANSPARENT)
            }
        }
        binding.sheetRoot.background = GradientDrawable().apply {
            val r = 18 * density
            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            setColor(colors.surface)
        }
        binding.sheetTitle.setTextColor(colors.text)
        binding.sheetHint.setTextColor(colors.gutterText)
        binding.headerLabel.setTextColor(colors.text)
        binding.bodyLabel.setTextColor(colors.text)
        listOf(binding.addHeaderButton, binding.headerJsonButton).forEach { btn ->
            btn.setTextColor(colors.accent)
            btn.background = GradientDrawable().apply {
                cornerRadius = 15 * density
                setColor(Color.TRANSPARENT)
                setStroke(dp(1), colors.gutter)
            }
        }
        listOf(binding.urlInput, binding.bodyInput).forEach { e ->
            e.setTextColor(colors.text)
            e.setHintTextColor(colors.gutterText)
            e.backgroundTintList = android.content.res.ColorStateList.valueOf(colors.accent)
        }
        binding.historyButton.setColorFilter(colors.accent)
        binding.sendButton.backgroundTintList =
            android.content.res.ColorStateList.valueOf(colors.accent)
        binding.sendButton.setTextColor(ThemeManager.contrastText(colors.accent))
        binding.importButton.backgroundTintList =
            android.content.res.ColorStateList.valueOf(colors.accent)
        binding.importButton.setTextColor(ThemeManager.contrastText(colors.accent))
        binding.copyButton.setTextColor(colors.accent)
        binding.statusText.setTextColor(colors.gutterText)
        binding.responseText.setTextColor(colors.text)
        styleChip(binding.chipGet, method == "GET")
        styleChip(binding.chipPost, method == "POST")
        listOf(binding.uaChip, binding.refererChip, binding.typeChip).forEach { stylePill(it) }
        rows.forEach { styleRow(it) }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun styleChip(tv: TextView, selected: Boolean) {
        val colors = ThemeManager.activeColors
        tv.background = GradientDrawable().apply {
            cornerRadius = 15 * resources.displayMetrics.density
            if (selected) {
                setColor(colors.accent)
            } else {
                setColor(Color.TRANSPARENT)
                setStroke(dp(1), colors.gutter)
            }
        }
        tv.setTextColor(if (selected) ThemeManager.contrastText(colors.accent) else colors.text)
    }

    /** 描边药丸（未选中态）：默认头 chips 用这个，和可切换的 GET/POST 有区别 */
    private fun stylePill(tv: TextView) {
        val colors = ThemeManager.activeColors
        tv.background = GradientDrawable().apply {
            cornerRadius = 15 * resources.displayMetrics.density
            setColor(Color.TRANSPARENT)
            setStroke(dp(1), colors.gutter)
        }
        tv.setTextColor(colors.text)
    }

    private fun styleRow(row: HeaderRow) {
        val colors = ThemeManager.activeColors
        listOf(row.name, row.value).forEach { e ->
            e.setTextColor(colors.text)
            e.setHintTextColor(colors.gutterText)
            e.backgroundTintList = android.content.res.ColorStateList.valueOf(colors.gutter)
        }
        row.value.hint = "值"
        row.name.hint = "名称（如 Referer）"
    }

    // ---------- 交互 ----------

    private fun setupChips() {
        binding.chipGet.setOnClickListener { method = "GET"; renderMethod() }
        binding.chipPost.setOnClickListener { method = "POST"; renderMethod() }
        styleChip(binding.chipGet, true)
        styleChip(binding.chipPost, false)
    }

    private fun renderMethod() {
        binding.bodyGroup.visibility = if (method == "POST") View.VISIBLE else View.GONE
        binding.typeChip.visibility = if (method == "POST") View.VISIBLE else View.GONE
        styleChip(binding.chipGet, method == "GET")
        styleChip(binding.chipPost, method == "POST")
    }

    /** 默认头三个 chip 的文案：UA 名称 / Referer 是否自动 / Content-Type 摘要 */
    private fun renderHeaderDefaults() {
        binding.uaChip.text = "UA：${UaProfiles.activeLabel}"
        binding.refererChip.text = if (sendReferer) "Referer：自动" else "Referer：不携带"
        binding.typeChip.text = "类型：${contentTypeLabel()}"
    }

    private fun contentTypeLabel(): String = when (contentType) {
        AppSettings.CT_AUTO -> "自动"
        "" -> "不发送"
        else -> contentType.substringBefore(';').trim().ifBlank { "自定义" }
    }

    private fun bindActions() {
        binding.addHeaderButton.setOnClickListener {
            if (rows.size >= 40) {
                toast("请求头最多 40 条")
                return@setOnClickListener
            }
            addHeaderRow("", "")
        }
        binding.headerJsonButton.setOnClickListener { fillHeadersFromJson() }
        binding.sendButton.setOnClickListener { send() }
        binding.importButton.setOnClickListener {
            if (lastBody.isBlank()) {
                toast("响应为空，没有可导入的内容")
                return@setOnClickListener
            }
            onImport?.invoke(lastBody)
            dismiss()
        }
        binding.copyButton.setOnClickListener {
            if (lastBody.isBlank()) {
                toast("响应为空，没有可复制的内容")
                return@setOnClickListener
            }
            copyToClipboard(lastBody)
        }
        binding.historyButton.setOnClickListener { pickFromHistory() }
        binding.uaChip.setOnClickListener { showUaPicker() }
        binding.refererChip.setOnClickListener { showRefererPicker() }
        binding.typeChip.setOnClickListener { showContentTypePicker() }
    }

    // ---------- 默认头：UA / Referer / Content-Type ----------

    /**
     * UA 选择器：内置项 + 自定义项 + 「不携带」，点一下即切换；
     * 自定义项可删除，底部可以新增（名称 + UA 值两个输入框）。
     */
    private fun showUaPicker() {
        val context = requireContext()
        val colors = ThemeManager.activeColors
        val dialog = ThemedDialog.create(context)
        val box = ThemedDialog.container(context)

        box.addView(ThemedDialog.title(context, "内置 User-Agent"))
        box.addView(
            ThemedDialog.body(
                context,
                "请求头里没手动填 UA 时用它。很多站点会按 UA 分版，电脑 UA 常能拿到完整页面；也可以选「不携带」，只发你自己填的头。"
            ),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        )

        val rowsBox = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        fun addRow(
            key: String,
            label: String,
            subtitle: String,
            selected: Boolean,
            deletableName: String?
        ) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(9), 0, dp(9))
            }
            val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(TextView(context).apply {
                text = (if (selected) "✓ " else "") + label
                textSize = 14f
                setTextColor(if (selected) colors.accent else colors.text)
                if (selected) setTypeface(typeface, Typeface.BOLD)
            })
            texts.addView(TextView(context).apply {
                text = subtitle
                textSize = 11f
                setTextColor(colors.gutterText)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            if (deletableName != null) {
                row.addView(TextView(context).apply {
                    text = "删除"
                    textSize = 12f
                    setTextColor(ThemedDialog.dangerColor())
                    setPadding(dp(12), dp(4), 0, dp(4))
                    setOnClickListener {
                        UaProfiles.removeCustom(deletableName)
                        renderHeaderDefaults()
                        dialog.dismiss()
                        toast("已删除「$deletableName」")
                        showUaPicker()
                    }
                })
            }

            row.setOnClickListener {
                if (key == UaProfiles.NONE) UaProfiles.selectNone() else UaProfiles.select(key)
                renderHeaderDefaults()
                dialog.dismiss()
                toast("UA 已切换为「$label」")
            }
            rowsBox.addView(row)
        }

        val activeName = UaProfiles.activeName
        UaProfiles.all.forEach { profile ->
            addRow(
                key = profile.name,
                label = profile.name,
                subtitle = UaProfiles.summary(profile.value),
                selected = profile.name == activeName,
                deletableName = if (profile.custom) profile.name else null
            )
        }
        addRow(
            key = UaProfiles.NONE,
            label = "不携带 UA",
            subtitle = "请求头里也没有 User-Agent 时，一个 UA 都不发",
            selected = activeName == UaProfiles.NONE,
            deletableName = null
        )

        // 条目多时限制高度，避免弹窗顶到屏幕外面
        if (UaProfiles.all.size + 1 > 5) {
            val scroll = ScrollView(context).apply { addView(rowsBox) }
            box.addView(scroll, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(260)
            ).apply { topMargin = dp(4) })
        } else {
            box.addView(rowsBox, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) })
        }

        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(2), 0, 0)
        }
        actions.addView(ThemedDialog.button(context, "新增自定义", colors.accent) {
            ThemedDialog.inputTwo(
                context,
                "新增 User-Agent",
                label1 = "名称（面板上显示）",
                hint1 = "如：iPhone Safari",
                label2 = "User-Agent 值",
                hint2 = "粘贴完整的 UA 字符串"
            ) { name, value ->
                if (value.isBlank()) {
                    toast("请填写 UA 值")
                    false
                } else {
                    val added = UaProfiles.addCustom(name, value)
                    UaProfiles.select(added.name)
                    renderHeaderDefaults()
                    dialog.dismiss()
                    toast("已新增「${added.name}」并切换")
                    showUaPicker()
                    true
                }
            }
        })
        actions.addView(ThemedDialog.button(context, "关闭", colors.gutterText) { dialog.dismiss() })
        box.addView(actions)

        ThemedDialog.show(dialog, box, 340)
    }

    private fun showRefererPicker() {
        val items = listOf(
            "自动补：链接的站点根" + if (sendReferer) "（当前）" else "",
            "不携带 Referer" + if (!sendReferer) "（当前）" else ""
        )
        ThemedDialog.options(requireContext(), "Referer", items) { index ->
            sendReferer = index == 0
            AppSettings.sendReferer = sendReferer
            renderHeaderDefaults()
            toast(if (sendReferer) "没填 Referer 时自动补站点根" else "不携带 Referer")
        }
    }

    private fun showContentTypePicker() {
        val presets = listOf(
            AppSettings.CT_AUTO to "自动（按请求体判断）",
            "application/json; charset=utf-8" to "JSON",
            "application/x-www-form-urlencoded; charset=utf-8" to "表单",
            "text/plain; charset=utf-8" to "纯文本",
            "" to "不发送 Content-Type"
        )
        val items = presets.map { (value, label) ->
            label + if (contentType == value) "（当前）" else ""
        } + "自定义…"
        ThemedDialog.options(requireContext(), "Content-Type", items) { index ->
            if (index <= presets.lastIndex) {
                contentType = presets[index].first
                AppSettings.contentType = contentType
                renderHeaderDefaults()
                toast("Content-Type：${contentTypeLabel()}")
            } else {
                ThemedDialog.input(
                    requireContext(),
                    "自定义 Content-Type",
                    contentType.takeIf { it != AppSettings.CT_AUTO && it.isNotBlank() }
                        ?: "application/json; charset=utf-8"
                ) { value ->
                    if (value.isBlank()) {
                        toast("请填写 Content-Type")
                        false
                    } else {
                        contentType = value.trim()
                        AppSettings.contentType = contentType
                        renderHeaderDefaults()
                        true
                    }
                }
            }
        }
    }

    /**
     * 「JSON 填入」：把一段 JSON 请求头解析成多行。
     * 支持对象、[{name,value}] 数组、[["名称","值"]] 二元组、["名称: 值"]，
     * 以及每行一条「名称: 值」的纯文本；剪贴板里正好是 JSON 时自动预填。
     */
    private fun fillHeadersFromJson() {
        ThemedDialog.multiline(
            requireContext(),
            "粘贴 JSON 请求头",
            "支持 {\"名称\":\"值\"}、[{\"name\":\"…\",\"value\":\"…\"}]、[[\"名称\",\"值\"]]，也支持每行一条「名称: 值」。填入后替换当前全部请求头。",
            initial = clipboardJsonCandidate()
        ) { raw ->
            when (val result = HeaderJson.parse(raw)) {
                is HeaderParseResult.Error -> {
                    toast(result.message)
                    false
                }
                is HeaderParseResult.Ok -> {
                    applyHeaders(result.headers)
                    val skipped = if (result.skipped > 0) "，跳过 ${result.skipped} 条无法识别" else ""
                    toast("已填入 ${result.headers.size} 条请求头$skipped")
                    true
                }
            }
        }
    }

    /** 长度够、首字符 { [ 且尾字符 } ] 才算「可能是 JSON」，避免输入过程中反复解析 */
    private fun looksLikeJson(text: String): Boolean =
        text.length >= 4 &&
            (text.startsWith("{") || text.startsWith("[")) &&
            (text.endsWith("}") || text.endsWith("]"))

    /** 用解析结果整体替换请求头行，末尾留一行空行方便继续手动添加 */
    private fun applyHeaders(list: List<HttpHeader>) {
        rows.forEach { binding.headerContainer.removeView(it.root) }
        rows.clear()
        list.take(40).forEach { addHeaderRow(it.name, it.value) }
        if (rows.size < 40) addHeaderRow("", "")
    }

    /** 剪贴板里像 JSON 就直接预填，省一次粘贴 */
    private fun clipboardJsonCandidate(): String {
        return try {
            val cm = requireContext()
                .getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val text = cm?.primaryClip
                ?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)
                ?.coerceToText(requireContext())
                ?.toString()
                ?.trim()
                .orEmpty()
            if (text.length in 4..20_000 && (text.startsWith("{") || text.startsWith("["))) text else ""
        } catch (e: Exception) {
            ""
        }
    }

    /** 动态添加一行请求头：名称 + 值 + 删除 */
    private fun addHeaderRow(name: String, value: String) {
        val colors = ThemeManager.activeColors
        val context = requireContext()
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(2), 0, dp(2))
        }
        val nameInput = EditText(context).apply {
            setText(name)
            hint = "名称"
            textSize = 12f
            inputType = InputType.TYPE_CLASS_TEXT
            maxLines = 1
            setPadding(dp(4), 0, dp(4), 0)
        }
        val valueInput = EditText(context).apply {
            setText(value)
            hint = "值"
            textSize = 12f
            inputType = InputType.TYPE_CLASS_TEXT
            maxLines = 1
            setPadding(dp(4), 0, dp(4), 0)
        }
        val delete = ImageButton(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setImageResource(R.drawable.ic_clear)
            setColorFilter(colors.gutterText)
            scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(6), dp(6), dp(6), dp(6))
            isFocusable = false
        }
        row.addView(nameInput, LinearLayout.LayoutParams(0, dp(38), 1f))
        row.addView(valueInput, LinearLayout.LayoutParams(0, dp(38), 1.35f))
        row.addView(delete, LinearLayout.LayoutParams(dp(30), dp(30)))

        // 直接往「名称」框粘贴 JSON 也能自动展开成多行（HTTP 头名不允许 { [，
        // 所以这里不会误伤正常输入）；显式的「JSON 填入」按钮提供同样的能力。
        nameInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) {
                if (fillingHeaders) return
                val text = s?.toString()?.trim().orEmpty()
                if (!looksLikeJson(text)) return
                val result = HeaderJson.parse(text)
                if (result !is HeaderParseResult.Ok) return
                fillingHeaders = true
                binding.headerContainer.post {
                    fillingHeaders = false
                    if (!isAdded) return@post
                    applyHeaders(result.headers)
                    val skipped = if (result.skipped > 0) "，跳过 ${result.skipped} 条" else ""
                    toast("已从 JSON 填入 ${result.headers.size} 条请求头$skipped")
                }
            }
        })

        val item = HeaderRow(row, nameInput, valueInput)
        delete.setOnClickListener {
            rows.remove(item)
            binding.headerContainer.removeView(row)
            if (rows.isEmpty()) addHeaderRow("", "")
        }
        rows.add(item)
        binding.headerContainer.addView(row)
        styleRow(item)
    }

    private fun collectHeaders(): List<HttpHeader> = rows.mapNotNull { row ->
        val name = row.name.text?.toString()?.trim().orEmpty()
        if (name.isEmpty()) return@mapNotNull null
        HttpHeader(name, row.value.text?.toString().orEmpty())
    }

    private fun send() {
        val url = binding.urlInput.text?.toString()?.trim().orEmpty()
        if (url.isEmpty()) {
            toast("请输入链接")
            return
        }
        val body = if (method == "POST") binding.bodyInput.text?.toString().orEmpty() else null
        val request = HttpRequest(
            method = method,
            url = url,
            headers = collectHeaders(),
            body = body,
            userAgent = UaProfiles.active?.value,
            sendReferer = sendReferer,
            contentType = contentType.takeIf { it != AppSettings.CT_AUTO }
        )

        binding.sendButton.isEnabled = false
        binding.sendButton.text = "请求中…"
        binding.statusText.setTextColor(ThemeManager.activeColors.gutterText)
        binding.statusText.text = "请求中…"

        lifecycleScope.launch {
            val result = try {
                withContext(Dispatchers.IO) { HttpEngine.execute(request) }
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                // OOM / StackOverflow 这类 Error 也要兜住：网络面板不该把进程带走
                HttpEngine.failWith("请求失败：${e.javaClass.simpleName}")
            }
            if (!isAdded) return@launch
            binding.sendButton.isEnabled = true
            binding.sendButton.text = "发送"
            showResult(url, result)
        }
    }

    private fun showResult(url: String, result: HttpResponse) {
        val colors = ThemeManager.activeColors
        if (result.error != null) {
            binding.statusText.setTextColor(ThemedDialog.dangerColor())
            binding.statusText.text = "请求失败：${result.error}（${result.durationMs} ms）"
            binding.responseText.setTextColor(colors.gutterText)
            binding.responseText.text = "请检查链接、网络，或尝试补充请求头（部分站点要求 Referer / Cookie）。"
            lastBody = ""
            updateImportEnabled()
            return
        }
        val ok = result.code in 200..299
        binding.statusText.setTextColor(if (ok) colors.accent else ThemedDialog.dangerColor())
        binding.statusText.text = buildString {
            append("${result.code} ${result.message}".trim())
            append(" · ${result.durationMs} ms")
            append(" · ${formatSize(result.byteSize)}")
            append(" · ${result.headers.count()} 个响应头")
        }
        lastBody = result.body
        val preview = if (result.body.length > PREVIEW_CHARS) {
            result.body.take(PREVIEW_CHARS) + "\n\n…（预览已截断，导入时仍是完整内容）"
        } else {
            result.body
        }
        binding.responseText.setTextColor(colors.text)
        binding.responseText.text = preview.ifBlank { "（空响应）" }
        binding.responseScroll.scrollTo(0, 0)
        updateImportEnabled()

        // 请求真正发出去了才记入链接历史：连方法、请求头、请求体一起存，
        // 下次点历史就能还原整次请求（拼错/域名不通的不进历史）。
        UrlHistory.add(
            UrlHistory.Entry(
                url = HttpEngine.normalizeUrl(url),
                method = method,
                headers = collectHeaders().filter { it.name.isNotBlank() && it.value.isNotBlank() },
                body = if (method == "POST") binding.bodyInput.text?.toString().orEmpty() else "",
                uaName = UaProfiles.activeName,
                sendReferer = sendReferer,
                contentType = contentType
            )
        )
    }

    private fun updateImportEnabled() {
        val enabled = lastBody.isNotBlank()
        binding.importButton.isEnabled = enabled
        binding.importButton.alpha = if (enabled) 1f else 0.5f
    }

    private fun pickFromHistory() {
        val activity = activity as? FragmentActivity ?: return
        val currentUrl = binding.urlInput.text?.toString()?.trim().orEmpty()
        HistoryDialog.show(
            activity,
            title = "链接历史",
            store = UrlHistory.store,
            emptyHint = "还没有访问过的链接",
            previewLength = 58,
            label = { raw -> UrlHistory.decode(raw).label() },
            currentMatcher = { raw -> currentUrl.isNotEmpty() && UrlHistory.decode(raw).url == currentUrl }
        ) { text, _ ->
            applyEntry(UrlHistory.decode(text))
        }
    }

    /** 一键还原一条历史：链接、方法、请求体、请求头、UA / Referer / Content-Type 全部填回去 */
    private fun applyEntry(entry: UrlHistory.Entry) {
        binding.urlInput.setText(entry.url)
        binding.urlInput.setSelection(entry.url.length)
        method = if (entry.method == "POST") "POST" else "GET"
        renderMethod()
        binding.bodyInput.setText(entry.body)
        applyHeaders(entry.headers)
        if (entry.uaName.isNotBlank()) UaProfiles.select(entry.uaName)
        sendReferer = entry.sendReferer
        contentType = entry.contentType
        renderHeaderDefaults()
        val headerCount = entry.headers.count { it.name.isNotBlank() && it.value.isNotBlank() }
        toast("已填入该次请求：${entry.method} · $headerCount 条请求头" +
            if (entry.body.isNotBlank()) " · 已带请求体" else "")
    }

    private fun formatSize(bytes: Int): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024f)
        else -> String.format("%.1f MB", bytes / (1024f * 1024f))
    }

    private fun copyToClipboard(text: String) {
        val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("响应体", text))
        toast("响应已复制")
    }

    private fun toast(msg: String) {
        // 用可空 context：请求返回时面板可能已经关闭/回收，这时静默丢弃即可
        val ctx = context ?: return
        Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
    }
}
