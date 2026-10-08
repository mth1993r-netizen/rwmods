package com.rwmods.installer

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
import android.provider.Settings
import android.widget.Toast
import java.io.File

class MainActivity : Activity() {

    private companion object {
        const val GAME_PACKAGE = "com.corrodinggames.rts"
        val MODS_DIR = File(Environment.getExternalStorageDirectory(), "rustedWarfare/mods")
        const val REQ_PERM = 1
    }

    private var pending: Uri? = null
    private var waitingForPermission = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(i: Intent) {
        val uri: Uri? = if (i.action == Intent.ACTION_SEND) {
            @Suppress("DEPRECATION")
            i.getParcelableExtra(Intent.EXTRA_STREAM)
        } else i.data

        if (uri == null) {
            Toast.makeText(this, "افتح ملف المود (.rwmod) أو شاركه مع هذا التطبيق", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        pending = uri
        proceed()
    }

    private fun hasStoragePermission(): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED

    private fun proceed() {
        if (!hasStoragePermission()) {
            waitingForPermission = true
            if (Build.VERSION.SDK_INT >= 30) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
                Toast.makeText(this, "فعّل «السماح بالوصول لكل الملفات» ثم ارجع", Toast.LENGTH_LONG).show()
            } else {
                requestPermissions(arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE), REQ_PERM)
            }
            return
        }
        waitingForPermission = false
        install(pending ?: return)
    }

    override fun onResume() {
        super.onResume()
        if (waitingForPermission && hasStoragePermission()) proceed()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED) proceed()
        else { Toast.makeText(this, "الصلاحية مطلوبة لتثبيت المود", Toast.LENGTH_LONG).show(); finish() }
    }

    private fun displayName(uri: Uri): String {
        var name: String? = null
        if (uri.scheme == "content") {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) name = it.getString(0)
            }
        }
        val raw = name ?: uri.lastPathSegment ?: "mod.rwmod"
        return File(raw).name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
    }

    private fun install(uri: Uri) {
        val name = displayName(uri)
        Thread {
            try {
                MODS_DIR.mkdirs()
                val out = File(MODS_DIR, name)
                contentResolver.openInputStream(uri)!!.use { input ->
                    out.outputStream().use { input.copyTo(it, 64 * 1024) }
                }
                runOnUiThread { showDone(name) }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "فشل التثبيت: ${e.message}", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }.start()
    }

    private fun showDone(name: String) {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
            .setTitle("تم تثبيت المود ✅")
            .setMessage("$name\n\nتبي تدخل اللعبة؟")
            .setPositiveButton("تشغيل اللعبة") { _, _ ->
                val launch = packageManager.getLaunchIntentForPackage(GAME_PACKAGE)
                if (launch != null) startActivity(launch)
                else Toast.makeText(this, "اللعبة غير مثبتة", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("لا", null)
            .setOnDismissListener { finish() }
            .show()
    }
}
