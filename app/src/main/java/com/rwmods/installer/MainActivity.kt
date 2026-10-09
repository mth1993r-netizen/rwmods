package com.rwmods.installer

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.Locale
import java.util.zip.ZipFile

class MainActivity : Activity() {

    private companion object {
        const val GAME_PACKAGE = "com.corrodinggames.rts"
        const val REQ_PERM = 1
        const val REQ_PICK = 2
        val MODS_DIR = File(Environment.getExternalStorageDirectory(), "rustedWarfare/mods")
    }

    private class Report(
        val errors: List<String>,
        val warnings: List<String>,
        val title: String?,
        val units: Int
    )

    private val cBg = Color.parseColor("#14181C")
    private val cCard = Color.parseColor("#222930")
    private val cAccent = Color.parseColor("#E8A33D")
    private val cMuted = Color.parseColor("#9AA5B1")

    private var pending: Uri? = null
    private var waitingForPermission = false

    private lateinit var listBox: LinearLayout
    private lateinit var permCard: LinearLayout
    private lateinit var gameInfo: TextView

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        if (savedInstanceState == null) handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        if (waitingForPermission && hasStoragePermission()) {
            waitingForPermission = false
            val p = pending
            pending = null
            if (p != null) startInstall(p)
        }
        refresh()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            waitingForPermission = false
            val p = pending
            pending = null
            if (p != null) startInstall(p)
            refresh()
        } else {
            Toast.makeText(this, "الصلاحية مطلوبة لتثبيت المودات", Toast.LENGTH_LONG).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK && resultCode == RESULT_OK) {
            val uri = data?.data
            if (uri != null) startInstall(uri)
        }
    }

    // ---------------------------------------------------------------- UI

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radiusDp: Int): GradientDrawable {
        val d = GradientDrawable()
        d.setColor(color)
        d.cornerRadius = dp(radiusDp).toFloat()
        return d
    }

    private fun lp(top: Int): LinearLayout.LayoutParams {
        val p = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        p.topMargin = dp(top)
        return p
    }

    private fun label(text: String, size: Float, color: Int, bold: Boolean): TextView {
        val t = TextView(this)
        t.text = text
        t.textSize = size
        t.setTextColor(color)
        if (bold) t.setTypeface(t.typeface, Typeface.BOLD)
        return t
    }

    private fun makeButton(text: String, filled: Boolean, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = text
        b.isAllCaps = false
        b.textSize = 16f
        b.setTextColor(if (filled) Color.parseColor("#1B1206") else cAccent)
        b.background = rounded(if (filled) cAccent else cCard, 14)
        b.setPadding(dp(16), dp(12), dp(16), dp(12))
        b.setOnClickListener { onClick() }
        return b
    }

    private fun buildUi() {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(cBg)
        root.layoutDirection = View.LAYOUT_DIRECTION_RTL
        root.setPadding(dp(16), dp(24), dp(16), dp(16))

        root.addView(label("RW Mods", 28f, cAccent, true))
        root.addView(label("ثبّت مودات Rusted Warfare بسهولة", 14f, cMuted, false))

        gameInfo = label("", 13f, cMuted, false)
        root.addView(gameInfo, lp(4))

        root.addView(makeButton("📦  اختر مود للتثبيت", true) { pickMod() }, lp(16))
        root.addView(makeButton("🎮  شغّل اللعبة", false) { launchGame() }, lp(8))

        permCard = LinearLayout(this)
        permCard.orientation = LinearLayout.VERTICAL
        permCard.background = rounded(cCard, 14)
        permCard.setPadding(dp(14), dp(12), dp(14), dp(12))
        permCard.addView(
            label("⚠️ التطبيق يحتاج صلاحية الوصول للملفات عشان يقرأ ويثبت المودات في مجلد اللعبة.", 14f, Color.WHITE, false)
        )
        permCard.addView(makeButton("منح الصلاحية", true) { requestStoragePermission() }, lp(10))
        root.addView(permCard, lp(12))

        root.addView(label("المودات المثبتة", 18f, Color.WHITE, true), lp(20))

        listBox = LinearLayout(this)
        listBox.orientation = LinearLayout.VERTICAL
        val scroll = ScrollView(this)
        scroll.addView(listBox)
        val sp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        sp.topMargin = dp(8)
        root.addView(scroll, sp)

        setContentView(root)
    }

    private fun refresh() {
        val granted = hasStoragePermission()
        permCard.visibility = if (granted) View.GONE else View.VISIBLE
        listBox.removeAllViews()

        val v = gameVersion()
        gameInfo.text = if (v != null) "نسخة اللعبة: $v" else "اللعبة غير مثبتة على الجهاز"

        if (!granted) return

        val items = (MODS_DIR.listFiles() ?: emptyArray<File>()).sortedByDescending { it.lastModified() }
        if (items.isEmpty()) {
            listBox.addView(label("ما فيه مودات مثبتة. اضغط «اختر مود للتثبيت».", 14f, cMuted, false), lp(8))
            return
        }
        for (f in items) listBox.addView(modRow(f), lp(8))
    }

    private fun modRow(f: File): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.background = rounded(cCard, 14)
        row.setPadding(dp(14), dp(10), dp(8), dp(10))

        val info = LinearLayout(this)
        info.orientation = LinearLayout.VERTICAL
        info.addView(label(f.name, 16f, Color.WHITE, true))
        info.addView(label(if (f.isDirectory) "مجلد" else fmtSize(f.length()), 12f, cMuted, false))
        row.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val del = makeButton("حذف", false) { confirmDelete(f) }
        row.addView(del)
        return row
    }

    private fun fmtSize(b: Long): String = when {
        b >= 1048576L -> String.format(Locale.US, "%.1f MB", b / 1048576.0)
        b >= 1024L -> "${b / 1024} KB"
        else -> "$b B"
    }

    private fun confirmDelete(f: File) {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
            .setTitle("حذف المود؟")
            .setMessage(f.name)
            .setPositiveButton("احذف") { _, _ ->
                val ok = f.deleteRecursively()
                Toast.makeText(this, if (ok) "تم الحذف" else "تعذّر الحذف", Toast.LENGTH_SHORT).show()
                refresh()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    // ---------------------------------------------------------------- game / permission

    private fun gameVersion(): String? = try {
        packageManager.getPackageInfo(GAME_PACKAGE, 0).versionName
    } catch (e: Exception) {
        null
    }

    private fun launchGame() {
        val launch = packageManager.getLaunchIntentForPackage(GAME_PACKAGE)
        if (launch != null) startActivity(launch)
        else Toast.makeText(this, "اللعبة غير مثبتة", Toast.LENGTH_SHORT).show()
    }

    private fun hasStoragePermission(): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestStoragePermission() {
        waitingForPermission = true
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
            Toast.makeText(this, "فعّل «السماح بالوصول لكل الملفات» ثم ارجع", Toast.LENGTH_LONG).show()
        } else {
            requestPermissions(arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE), REQ_PERM)
        }
    }

    // ---------------------------------------------------------------- intake

    private fun pickMod() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "*/*"
        startActivityForResult(i, REQ_PICK)
    }

    @Suppress("DEPRECATION")
    private fun streamUri(i: Intent): Uri? = i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)

    private fun handleIntent(i: Intent?) {
        if (i == null) return
        val uri: Uri? = when (i.action) {
            Intent.ACTION_SEND -> streamUri(i)
            Intent.ACTION_VIEW -> i.data
            else -> null
        }
        if (uri != null) startInstall(uri)
    }

    private fun displayName(uri: Uri): String {
        var name: String? = null
        if (uri.scheme == "content") {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) name = it.getString(0)
            }
        }
        val raw = name ?: uri.lastPathSegment ?: "mod.zip"
        return File(raw).name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
    }

    private fun startInstall(uri: Uri) {
        if (!hasStoragePermission()) {
            pending = uri
            requestStoragePermission()
            return
        }
        val name = displayName(uri)
        Toast.makeText(this, "جاري فحص المود…", Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val tmp = File(cacheDir, "incoming.zip")
                contentResolver.openInputStream(uri)!!.use { input ->
                    tmp.outputStream().use { input.copyTo(it, 64 * 1024) }
                }
                val report = validate(tmp)
                runOnUiThread { showReport(name, tmp, report) }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "تعذّر قراءة الملف: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    // ---------------------------------------------------------------- validation

    private fun versionKey(v: String): IntArray? {
        val m = Regex("^\\s*(\\d+)\\.(\\d+)(?:p(\\d+))?").find(v) ?: return null
        val patch = if (m.groupValues[3].isEmpty()) 999 else m.groupValues[3].toInt()
        return intArrayOf(m.groupValues[1].toInt(), m.groupValues[2].toInt(), patch)
    }

    // اللعبة تعتبر 1.15p11 أقدم من 1.15 (النسخة النهائية)
    private fun isNewer(required: String, have: String): Boolean {
        val r = versionKey(required) ?: return false
        val h = versionKey(have) ?: return false
        for (i in 0..2) {
            if (r[i] != h[i]) return r[i] > h[i]
        }
        return false
    }

    private fun validate(file: File): Report {
        val errors = ArrayList<String>()
        val warnings = ArrayList<String>()
        var title: String? = null
        var units = 0

        val zip = try {
            ZipFile(file)
        } catch (e: Exception) {
            return Report(listOf("الملف مو zip صالح أو تالف"), emptyList(), null, 0)
        }

        zip.use { z ->
            val entries = z.entries().toList()
            val baseNames = HashSet<String>()
            var hasIni = false
            var hasMap = false
            for (e in entries) {
                if (e.isDirectory) continue
                baseNames.add(e.name.substringAfterLast('/'))
                if (e.name.endsWith(".ini", true)) hasIni = true
                if (e.name.endsWith(".tmx", true)) hasMap = true
            }

            // ---- mod-info.txt
            val info = entries.firstOrNull { !it.isDirectory && it.name.substringAfterLast('/') == "mod-info.txt" }
            if (info == null) {
                warnings.add("ما لقيت mod-info.txt، اللعبة غالباً ما راح تتعرف على المود")
            } else {
                if (info.name.contains('/')) {
                    warnings.add("mod-info.txt داخل مجلد (${info.name}). الأفضل يكون في جذر الملف")
                }
                val text = z.getInputStream(info).bufferedReader().use { it.readText() }
                title = Regex("(?im)^\\s*title\\s*:\\s*(.+)$").find(text)?.groupValues?.get(1)?.trim()
                if (title.isNullOrEmpty()) warnings.add("mod-info.txt بدون title")
                val minV = Regex("(?im)^\\s*minVersion\\s*:\\s*(.+)$").find(text)?.groupValues?.get(1)?.trim()
                val have = gameVersion()
                if (minV != null && have != null && isNewer(minV, have)) {
                    errors.add("المود يتطلب نسخة $minV أو أعلى، ونسختك $have. اللعبة راح ترفضه (لو أنت صانعه غيّر minVersion)")
                }
            }

            if (!hasIni && !hasMap) {
                warnings.add("ما لقيت وحدات (.ini) ولا خرائط (.tmx) داخل الملف")
            }

            // ---- ini files
            val missing = HashSet<String>()
            for (e in entries) {
                if (e.isDirectory || !e.name.endsWith(".ini", true) || e.size > 2000000L) continue
                val text = try {
                    z.getInputStream(e).bufferedReader().use { it.readText() }
                } catch (ex: Exception) {
                    continue
                }
                val fileLabel = e.name.substringAfterLast('/')
                var section = ""
                var hasCore = false
                var hasName = false
                for (raw in text.lineSequence()) {
                    val line = raw.trim()
                    if (line.isEmpty() || line.startsWith("#")) continue
                    if (line.startsWith("[")) {
                        section = line.substringAfter('[').substringBefore(']').trim().lowercase()
                        if (section == "core") hasCore = true
                        continue
                    }
                    val idx = line.indexOf(':')
                    if (idx < 0) continue
                    val key = line.substring(0, idx).trim().lowercase()
                    val value = line.substring(idx + 1).trim()
                    if (section == "core" && key == "name" && value.isNotEmpty()) hasName = true
                    if ((key.startsWith("image") || key.startsWith("iconimage")) &&
                        !value.startsWith("SHARED:", true) &&
                        (value.endsWith(".png", true) || value.endsWith(".jpg", true) || value.endsWith(".jpeg", true))
                    ) {
                        val base = value.substringAfterLast('/')
                        if (!baseNames.contains(base) && missing.add("$fileLabel|$base")) {
                            warnings.add("$fileLabel: الصورة $base غير موجودة داخل الملف")
                        }
                    }
                }
                if (hasCore) {
                    if (hasName) units++ else warnings.add("$fileLabel: قسم [core] بدون name")
                }
            }
        }

        val shown = if (warnings.size > 8) {
            warnings.take(8) + "…و ${warnings.size - 8} تنبيهات أخرى"
        } else warnings
        return Report(errors, shown, title, units)
    }

    // ---------------------------------------------------------------- report + install

    private fun showReport(name: String, tmp: File, r: Report) {
        val sb = StringBuilder()
        if (!r.title.isNullOrEmpty()) sb.append("📦 ").append(r.title).append("\n")
        sb.append("الملف: ").append(name).append("\n")
        sb.append("عدد الوحدات: ").append(r.units).append("\n")
        if (r.errors.isEmpty() && r.warnings.isEmpty()) {
            sb.append("\n✅ الفحص سليم، ما لقيت مشاكل.")
        }
        if (r.errors.isNotEmpty()) {
            sb.append("\n❌ مشاكل:\n")
            for (m in r.errors) sb.append("• ").append(m).append("\n")
        }
        if (r.warnings.isNotEmpty()) {
            sb.append("\n⚠️ تنبيهات:\n")
            for (m in r.warnings) sb.append("• ").append(m).append("\n")
        }

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
            .setTitle(if (r.errors.isEmpty()) "جاهز للتثبيت" else "فيه مشكلة")
            .setMessage(sb.toString())
            .setPositiveButton(if (r.errors.isEmpty()) "ثبّت" else "ثبّت رغم ذلك") { _, _ -> install(name, tmp) }
            .setNegativeButton("إلغاء") { _, _ -> tmp.delete() }
            .show()
    }

    private fun install(name: String, tmp: File) {
        Thread {
            try {
                MODS_DIR.mkdirs()
                val out = File(MODS_DIR, name)
                val existed = out.exists()
                tmp.inputStream().use { input ->
                    out.outputStream().use { input.copyTo(it, 64 * 1024) }
                }
                tmp.delete()
                runOnUiThread {
                    refresh()
                    showInstalled(name, existed)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "فشل التثبيت: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun showInstalled(name: String, existed: Boolean) {
        val msg = name + (if (existed) "\n(حدّث النسخة القديمة)" else "") +
            "\n\nداخل اللعبة: Mods ← فعّل المود ← Save Selection ← Reload Mod Data"
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
            .setTitle("تم تثبيت المود ✅")
            .setMessage(msg)
            .setPositiveButton("شغّل اللعبة") { _, _ -> launchGame() }
            .setNegativeButton("تمام", null)
            .show()
    }
}
