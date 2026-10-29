package com.clonemaster.manager

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

data class InstalledApp(val label: String, val packageName: String, val isSystem: Boolean)
data class CloneProfile(val sourceLabel: String, val packageName: String, val cloneName: String, val iconChoice: String)
data class ExtractedApk(val fileName: String, val path: String, val packageName: String)

class MainActivity : ComponentActivity() {
    private var selectedApp by mutableStateOf<InstalledApp?>(null)
    private var refreshKey by mutableIntStateOf(0)
    private var pendingExport by mutableStateOf<File?>(null)
    private val exportPicker = registerForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.android.package-archive")) { uri: Uri? ->
        val source = pendingExport
        if (uri != null && source != null) {
            try {
                contentResolver.openOutputStream(uri)?.use { out -> FileInputStream(source).use { input -> input.copyTo(out) } }
                Toast.makeText(this, "تم تصدير ملف APK", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this, "فشل التصدير: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
        pendingExport = null
    }
    private val apkPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) Toast.makeText(this, "تم اختيار الملف. فحص وتعديل APK غير مفعّل بعد.", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    CloneMasterScreen(
                        apps = remember(refreshKey) { loadLaunchableApps() },
                        selectedApp = selectedApp,
                        profiles = loadProfiles(),
                        extracted = loadExtracted(),
                        onAppClick = { selectedApp = it },
                        onBackToList = { selectedApp = null },
                        onImportApk = { apkPicker.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream", "*/*")) },
                        onExtract = { app -> val file = extractApk(app); refreshKey++; file },
                        onExport = { file -> pendingExport = file; exportPicker.launch(file.name) },
                        onSaveProfile = { profile -> saveProfile(profile); refreshKey++ },
                        onDeleteProfile = { profile -> deleteProfile(profile); refreshKey++ },
                        onDeleteExtracted = { item -> File(item.path).delete(); refreshKey++ }
                    )
                }
            }
        }
    }

    private fun loadLaunchableApps(): List<InstalledApp> {
        val intent = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL).mapNotNull { resolve ->
            val info = resolve.activityInfo?.applicationInfo ?: return@mapNotNull null
            InstalledApp(packageManager.getApplicationLabel(info).toString(), info.packageName,
                (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0)
        }.distinctBy { it.packageName }.sortedBy { it.label.lowercase() }
    }

    private fun extractApk(app: InstalledApp): File? {
        return try {
            val info = packageManager.getApplicationInfo(app.packageName, 0)
            val dir = File(filesDir, "extracted_apks").apply { mkdirs() }
            val out = File(dir, "${safeFileName(app.label)}_${System.currentTimeMillis()}.apk")
            FileInputStream(info.sourceDir).use { input -> FileOutputStream(out).use { output -> input.copyTo(output) } }
            // Save only the base APK. Apps delivered as split APKs may not install from this file alone.
            Toast.makeText(this, "تم استخراج APK الأساسي. قد يحتاج التطبيق إلى ملفات split إضافية.", Toast.LENGTH_LONG).show()
            out
        } catch (e: Exception) {
            Toast.makeText(this, "تعذر استخراج APK: ${e.message}", Toast.LENGTH_LONG).show(); null
        }
    }
    private fun safeFileName(value: String) = value.replace(Regex("[^A-Za-z0-9._-]"), "_").take(50).ifBlank { "app" }
    private fun loadProfiles(): List<CloneProfile> {
        val raw = getSharedPreferences("cm", MODE_PRIVATE).getString("profiles", "[]") ?: "[]"
        return try { val a = JSONArray(raw); (0 until a.length()).map { val o = a.getJSONObject(it); CloneProfile(o.getString("sourceLabel"), o.getString("packageName"), o.getString("cloneName"), o.getString("iconChoice")) } } catch (_: Exception) { emptyList() }
    }
    private fun saveProfile(p: CloneProfile) {
        val list = loadProfiles().filterNot { it.packageName == p.packageName && it.cloneName == p.cloneName }.toMutableList(); list.add(p)
        val a = JSONArray(); list.forEach { a.put(JSONObject().put("sourceLabel", it.sourceLabel).put("packageName", it.packageName).put("cloneName", it.cloneName).put("iconChoice", it.iconChoice)) }
        getSharedPreferences("cm", MODE_PRIVATE).edit().putString("profiles", a.toString()).apply()
        Toast.makeText(this, "تم حفظ إعدادات النسخة فقط؛ لم يتم إنشاء APK مستقل", Toast.LENGTH_LONG).show()
    }
    private fun deleteProfile(p: CloneProfile) {
        val a = JSONArray(); loadProfiles().filterNot { it == p }.forEach { a.put(JSONObject().put("sourceLabel", it.sourceLabel).put("packageName", it.packageName).put("cloneName", it.cloneName).put("iconChoice", it.iconChoice)) }
        getSharedPreferences("cm", MODE_PRIVATE).edit().putString("profiles", a.toString()).apply()
    }
    private fun loadExtracted(): List<ExtractedApk> = File(filesDir, "extracted_apks").listFiles()?.filter { it.isFile && it.extension.equals("apk", true) }?.sortedByDescending { it.lastModified() }?.map { ExtractedApk(it.name, it.absolutePath, "") } ?: emptyList()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CloneMasterScreen(
    apps: List<InstalledApp>, selectedApp: InstalledApp?, profiles: List<CloneProfile>, extracted: List<ExtractedApk>,
    onAppClick: (InstalledApp) -> Unit, onBackToList: () -> Unit, onImportApk: () -> Unit,
    onExtract: (InstalledApp) -> File?, onExport: (File) -> Unit, onSaveProfile: (CloneProfile) -> Unit,
    onDeleteProfile: (CloneProfile) -> Unit, onDeleteExtracted: (ExtractedApk) -> Unit
) {
    var showSystem by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    if (selectedApp != null) {
        var cloneName by remember(selectedApp.packageName) { mutableStateOf("${selectedApp.label} (نسخة)") }
        var iconChoice by remember(selectedApp.packageName) { mutableStateOf("الأصلية") }
        var extractedFile by remember(selectedApp.packageName) { mutableStateOf<File?>(null) }
        Scaffold(topBar = { TopAppBar(title = { Text("إعداد النسخة") }, navigationIcon = { TextButton(onClick = onBackToList) { Text("رجوع") } }) }) { padding ->
            Column(Modifier.padding(padding).fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(selectedApp.label, style = MaterialTheme.typography.headlineSmall)
                Text("معرّف الأصل: ${selectedApp.packageName}", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(value = cloneName, onValueChange = { cloneName = it }, modifier = Modifier.fillMaxWidth(), label = { Text("اسم النسخة المقترح") }, singleLine = true)
                Text("الأيقونة المقترحة")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = iconChoice == "الأصلية", onClick = { iconChoice = "الأصلية" }, label = { Text("الأصلية") })
                    FilterChip(selected = iconChoice == "Android", onClick = { iconChoice = "Android" }, label = { Text("Android") })
                }
                Button(onClick = { onSaveProfile(CloneProfile(selectedApp.label, selectedApp.packageName, cloneName.ifBlank { "${selectedApp.label} (نسخة)" }, iconChoice)) }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Save, null); Spacer(Modifier.width(8.dp)); Text("حفظ إعدادات النسخة") }
                Button(onClick = { extractedFile = onExtract(selectedApp) }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.FileOpen, null); Spacer(Modifier.width(8.dp)); Text("استخراج APK الأساسي") }
                extractedFile?.let { file -> OutlinedButton(onClick = { onExport(file) }, modifier = Modifier.fillMaxWidth()) { Text("تصدير APK إلى مجلد تختاره") } }
                Text("تنبيه: استخراج APK وحفظ الإعدادات يعملان كميزات أولية. إعادة بناء Manifest والموارد وتغيير package ID وإعادة التوقيع والتثبيت كنسخة مستقلة غير مفعّلة بعد.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
        return
    }
    val filtered = apps.filter { (showSystem || !it.isSystem) && (it.label.contains(query, true) || it.packageName.contains(query, true)) }
    Scaffold(topBar = { TopAppBar(title = { Text("Clone Master") }, navigationIcon = { Icon(Icons.Default.Android, null, Modifier.padding(start = 16.dp)) }) }) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Text("مدير التطبيقات", style = MaterialTheme.typography.headlineSmall) }
            item { Text("المرحلة الحالية: استخراج APK الأساسي وحفظ إعدادات النسخ وإدارة الملفات المحفوظة.", style = MaterialTheme.typography.bodyMedium) }
            item { OutlinedTextField(value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(), label = { Text("ابحث عن تطبيق") }, singleLine = true) }
            item { Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(checked = showSystem, onCheckedChange = { showSystem = it }); Text("إظهار تطبيقات النظام") } }
            item { OutlinedButton(onClick = onImportApk, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.FileOpen, null); Spacer(Modifier.width(8.dp)); Text("اختيار ملف APK للفحص (واجهة أولية)") } }
            item { Text("التطبيقات (${filtered.size})", style = MaterialTheme.typography.titleMedium) }
            items(filtered, key = { it.packageName }) { app -> Card(onClick = { onAppClick(app) }, modifier = Modifier.fillMaxWidth()) { Row(Modifier.padding(12.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Android, null); Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text(app.label, style = MaterialTheme.typography.titleSmall); Text(app.packageName, style = MaterialTheme.typography.bodySmall); if (app.isSystem) Text("تطبيق نظام", style = MaterialTheme.typography.labelSmall) } } } }
            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)); Text("إعدادات النسخ المحفوظة (${profiles.size})", style = MaterialTheme.typography.titleMedium) }
            if (profiles.isEmpty()) item { Text("لا توجد إعدادات محفوظة بعد.", style = MaterialTheme.typography.bodySmall) }
            items(profiles, key = { it.packageName + it.cloneName }) { p -> Card { Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(p.cloneName); Text("من: ${p.sourceLabel} · ${p.iconChoice}", style = MaterialTheme.typography.bodySmall); Text("إعدادات فقط — ليس APK مستقلاً", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }; IconButton(onClick = { onDeleteProfile(p) }) { Icon(Icons.Default.Delete, "حذف الإعداد") } } } }
            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)); Text("ملفات APK المستخرجة (${extracted.size})", style = MaterialTheme.typography.titleMedium) }
            if (extracted.isEmpty()) item { Text("لم يتم استخراج ملفات بعد.", style = MaterialTheme.typography.bodySmall) }
            items(extracted, key = { it.path }) { f -> Card { Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(f.fileName); Text("محفوظ داخل مساحة CloneMaster", style = MaterialTheme.typography.bodySmall) }; IconButton(onClick = { onExport(File(f.path)) }) { Icon(Icons.Default.FileOpen, "تصدير") }; IconButton(onClick = { onDeleteExtracted(f) }) { Icon(Icons.Default.Delete, "حذف الملف") } } } }
        }
    }
}