package com.quanly.thuchi

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class TransactionItem(
    val id: Long,
    val date: String,
    val isIncome: Boolean,
    val category: String,
    val amount: Long,
    val payment: String,
    val desc: String,
    val note: String
)

private const val STORAGE_FILE = "transactions_data.json"

fun formatVnd(amount: Long): String {
    val nf = NumberFormat.getNumberInstance(Locale("vi", "VN"))
    return "${nf.format(amount)} ₫"
}

fun loadTransactions(context: Context): List<TransactionItem> {
    return try {
        val file = File(context.filesDir, STORAGE_FILE)
        if (!file.exists()) return emptyList()
        val arr = JSONArray(file.readText(Charsets.UTF_8))
        (0 until arr.length()).mapNotNull { i ->
            val obj = arr.optJSONObject(i) ?: return@mapNotNull null
            val amount = obj.optLong("amount", 0L)
            val desc = obj.optString("desc", "").trim()
            if (amount <= 0L || desc.isEmpty()) return@mapNotNull null
            TransactionItem(
                id = obj.optLong("id", System.currentTimeMillis() + i),
                date = obj.optString("date", todayStr()),
                isIncome = obj.optBoolean("isIncome", false),
                category = obj.optString("category", "Khác"),
                amount = amount,
                payment = obj.optString("payment", "Chuyển khoản"),
                desc = desc,
                note = obj.optString("note", "")
            )
        }
    } catch (_: Exception) {
        emptyList()
    }
}

fun saveTransactions(context: Context, list: List<TransactionItem>) {
    try {
        val arr = JSONArray()
        list.forEach { item ->
            val obj = JSONObject().apply {
                put("id", item.id)
                put("date", item.date)
                put("isIncome", item.isIncome)
                put("category", item.category)
                put("amount", item.amount)
                put("payment", item.payment)
                put("desc", item.desc)
                put("note", item.note)
            }
            arr.put(obj)
        }
        File(context.filesDir, STORAGE_FILE).writeText(arr.toString(2), Charsets.UTF_8)
    } catch (_: Exception) {
    }
}

fun todayStr(): String = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())

fun toCsv(list: List<TransactionItem>): String {
    val sb = StringBuilder("\uFEFFNgay,Loai,DanhMuc,SoTien,HinhThuc,DienGiai,GhiChu\n")
    list.forEach { t ->
        val typeStr = if (t.isIncome) "income" else "expense"
        val safeDesc = t.desc.replace("\"", "\"\"")
        val safeNote = t.note.replace("\"", "\"\"")
        sb.append("${t.date},$typeStr,${t.category},${t.amount},${t.payment},\"$safeDesc\",\"$safeNote\"\n")
    }
    return sb.toString()
}

fun parseImportedText(raw: String): List<TransactionItem> {
    val trimmed = raw.removePrefix("\uFEFF").trim()
    if (trimmed.isEmpty()) return emptyList()

    if (trimmed.startsWith("[") || trimmed.startsWith("{")) {
        val arr = if (trimmed.startsWith("[")) JSONArray(trimmed)
        else JSONObject(trimmed).optJSONArray("transactions") ?: JSONArray()
        return (0 until arr.length()).mapNotNull { i ->
            val obj = arr.optJSONObject(i) ?: return@mapNotNull null
            val rawAmt = obj.opt("amount")?.toString() ?: "0"
            val amt = rawAmt.replace(Regex("[^0-9-]"), "").toLongOrNull()?.let { kotlin.math.abs(it) } ?: 0L
            val desc = (obj.optString("desc", "").ifBlank { obj.optString("dienGiai", "") }).trim()
            if (amt <= 0L || desc.isEmpty()) return@mapNotNull null
            val isInc = when {
                obj.has("isIncome") -> obj.optBoolean("isIncome", false)
                else -> obj.optString("type", "expense") == "income"
            }
            TransactionItem(
                id = obj.optLong("id", System.currentTimeMillis() + i),
                date = obj.optString("date", todayStr()),
                isIncome = isInc,
                category = obj.optString("category", "Khác"),
                amount = amt,
                payment = obj.optString("payment", "Chuyển khoản"),
                desc = desc,
                note = obj.optString("note", "")
            )
        }
    }

    return trimmed.lines()
        .filter { it.isNotBlank() }
        .dropWhile { it.lowercase(Locale.getDefault()).startsWith("ngay,") }
        .mapIndexedNotNull { idx, line ->
            val parts = line.split(",")
            if (parts.size < 6) return@mapIndexedNotNull null
            val amt = parts[3].replace(Regex("[^0-9-]"), "").toLongOrNull()?.let { kotlin.math.abs(it) } ?: 0L
            val desc = parts[5].trim().trim('"')
            if (amt <= 0L || desc.isEmpty()) return@mapIndexedNotNull null
            TransactionItem(
                id = System.currentTimeMillis() + idx,
                date = parts[0].trim(),
                isIncome = parts[1].trim() == "income",
                category = parts[2].trim(),
                amount = amt,
                payment = parts[4].trim(),
                desc = desc,
                note = parts.getOrElse(6) { "" }.trim().trim('"')
            )
        }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Color(0xFF0D8A53),
                    background = Color(0xFFF4F7F6),
                    surface = Color.White
                )
            ) {
                FinanceAppScreen()
            }
        }
    }
}

@Composable
fun FinanceAppScreen() {
    val context = LocalContext.current
    var transactions by remember { mutableStateOf(loadTransactions(context)) }
    var statusText by remember { mutableStateOf("Dữ liệu lưu cục bộ trên máy (${STORAGE_FILE})") }

    var isIncome by remember { mutableStateOf(false) }
    var category by remember { mutableStateOf("Ăn uống") }
    var amountInput by remember { mutableStateOf("") }
    var payment by remember { mutableStateOf("Chuyển khoản") }
    var descInput by remember { mutableStateOf("") }
    var noteInput by remember { mutableStateOf("") }

    var pendingExportText by remember { mutableStateOf("") }

    val openFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                val content = context.contentResolver.openInputStream(uri)
                    ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                val imported = parseImportedText(content)
                if (imported.isEmpty()) {
                    statusText = "Không tìm thấy giao dịch hợp lệ trong file."
                } else {
                    transactions = imported + transactions
                    saveTransactions(context, transactions)
                    statusText = "Đã nhập ${imported.size} giao dịch từ file."
                }
            } catch (_: Exception) {
                statusText = "Lỗi khi đọc file sao lưu."
            }
        }
    }

    val createFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*")
    ) { uri: Uri? ->
        if (uri != null && pendingExportText.isNotEmpty()) {
            try {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(pendingExportText.toByteArray(Charsets.UTF_8))
                }
                statusText = "Đã xuất file sao lưu thành công."
            } catch (_: Exception) {
                statusText = "Lỗi khi ghi file xuất."
            } finally {
                pendingExportText = ""
            }
        }
    }

    val totalIncome = transactions.filter { it.isIncome }.sumOf { it.amount }
    val totalExpense = transactions.filter { !it.isIncome }.sumOf { it.amount }
    val balance = totalIncome - totalExpense
    val savingRate = if (totalIncome > 0L) ((balance.toDouble() / totalIncome.toDouble()) * 100.0).coerceAtLeast(0.0) else 0.0

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF4F7F6))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("Quản Lý Thu Chi Hằng Ngày", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0F172A))
            Text(statusText, fontSize = 12.sp, color = Color(0xFF0D8A53), fontWeight = FontWeight.Medium)
        }

        item {
            KpiCard("TỔNG THU NHẬP", formatVnd(totalIncome), Color(0xFF0D8A53), Color.White)
        }
        item {
            KpiCard("TỔNG CHI TIÊU", formatVnd(totalExpense), Color(0xFFE11D48), Color.White)
        }
        item {
            KpiCard("SỐ DƯ HIỆN TẠI", formatVnd(balance), Color.White, Color(0xFF0D8A53))
        }
        item {
            KpiCard("TỶ LỆ TÍCH LŨY", String.format(Locale("vi", "VN"), "%.1f%%", savingRate), Color(0xFF0F172A), Color.White)
        }

        item {
            Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Thêm Giao Dịch Mới", fontWeight = FontWeight.Bold, fontSize = 16.sp)

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { isIncome = false; category = "Ăn uống" },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (!isIncome) Color(0xFFE11D48) else Color(0xFFF1F5F9),
                                contentColor = if (!isIncome) Color.White else Color(0xFF475569)
                            ),
                            modifier = Modifier.weight(1f)
                        ) { Text("Chi tiêu (-)") }

                        Button(
                            onClick = { isIncome = true; category = "Lương" },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isIncome) Color(0xFF0D8A53) else Color(0xFFF1F5F9),
                                contentColor = if (isIncome) Color.White else Color(0xFF475569)
                            ),
                            modifier = Modifier.weight(1f)
                        ) { Text("Thu nhập (+)") }
                    }

                    OutlinedTextField(
                        value = category,
                        onValueChange = { category = it },
                        label = { Text("Danh mục") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = amountInput,
                        onValueChange = { amountInput = it },
                        label = { Text("Số tiền (VNĐ)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = payment,
                        onValueChange = { payment = it },
                        label = { Text("Hình thức thanh toán") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = descInput,
                        onValueChange = { descInput = it },
                        label = { Text("Diễn giải chi tiết") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = noteInput,
                        onValueChange = { noteInput = it },
                        label = { Text("Ghi chú (tùy chọn)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Button(
                        onClick = {
                            val amt = amountInput.replace(Regex("[^0-9]"), "").toLongOrNull() ?: 0L
                            val desc = descInput.trim()
                            if (amt <= 0L || desc.isEmpty()) {
                                statusText = "Nhập Số tiền (>0) và Diễn giải trước khi lưu."
                                return@Button
                            }
                            val item = TransactionItem(
                                id = System.currentTimeMillis(),
                                date = todayStr(),
                                isIncome = isIncome,
                                category = category.trim().ifBlank { "Khác" },
                                amount = amt,
                                payment = payment.trim().ifBlank { "Chuyển khoản" },
                                desc = desc,
                                note = noteInput.trim()
                            )
                            transactions = listOf(item) + transactions
                            saveTransactions(context, transactions)
                            amountInput = ""
                            descInput = ""
                            noteInput = ""
                            statusText = "Đã lưu giao dịch vào bộ nhớ máy."
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("+ Lưu giao dịch") }
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { openFileLauncher.launch(arrayOf("*/*")) },
                    modifier = Modifier.weight(1f)
                ) { Text("↑ Nhập file") }

                OutlinedButton(
                    onClick = {
                        pendingExportText = toCsv(transactions)
                        createFileLauncher.launch("thu-chi.csv")
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("↓ Xuất CSV") }

                OutlinedButton(
                    onClick = {
                        val arr = JSONArray()
                        transactions.forEach { t ->
                            arr.put(JSONObject().apply {
                                put("id", t.id)
                                put("date", t.date)
                                put("type", if (t.isIncome) "income" else "expense")
                                put("category", t.category)
                                put("amount", t.amount)
                                put("payment", t.payment)
                                put("desc", t.desc)
                                put("note", t.note)
                            })
                        }
                        pendingExportText = arr.toString(2)
                        createFileLauncher.launch("thu-chi.json")
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("JSON") }
            }
        }

        items(transactions, key = { it.id }) { item ->
            Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(item.desc, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text("${item.date} • ${item.category} • ${item.payment}", fontSize = 11.sp, color = Color(0xFF64748B))
                    }
                    Text(
                        text = "${if (item.isIncome) "+" else "-"}${formatVnd(item.amount)}",
                        fontWeight = FontWeight.Bold,
                        color = if (item.isIncome) Color(0xFF0D8A53) else Color(0xFFE11D48)
                    )
                    TextButton(onClick = {
                        transactions = transactions.filterNot { it.id == item.id }
                        saveTransactions(context, transactions)
                        statusText = "Đã xóa 1 giao dịch."
                    }) { Text("✕", color = Color(0xFF94A3B8)) }
                }
            }
        }
    }
}

@Composable
fun KpiCard(title: String, value: String, valueColor: Color, bgColor: Color) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = bgColor),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = if (bgColor == Color.White) Color(0xFF64748B) else Color.White.copy(alpha = 0.85f))
            Text(value, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = valueColor, modifier = Modifier.padding(top = 4.dp))
        }
    }
}
