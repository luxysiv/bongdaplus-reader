package vn.bongdaplus.reader.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import vn.bongdaplus.reader.data.AuthManager
import vn.bongdaplus.reader.data.BongDaPlusScraper
import vn.bongdaplus.reader.data.LoginResult

/**
 * Đăng nhập NATIVE bằng Email/Mật khẩu qua OkHttp (không WebView):
 * GET form lấy antiforgery token -> POST credentials -> handshake site.
 * Google/Apple (OAuth bắt buộc trình duyệt) đi đường WebView riêng.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NativeLoginScreen(
    auth: AuthManager,
    onBack: () -> Unit,
    onDone: () -> Unit,
    onOAuth: () -> Unit,
    onRegister: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var showPass by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    var msgOk by remember { mutableStateOf(false) }
    var pendingName by remember { mutableStateOf<String?>(null) }

    fun doLogin() {
        if (busy) return
        if (!email.contains("@") || pass.length < 4) {
            msg = "Nhập đúng email và mật khẩu (tối thiểu 4 ký tự)."
            msgOk = false
            return
        }
        busy = true; msg = "Đang đăng nhập…"; msgOk = false
        scope.launch {
            val r = try { BongDaPlusScraper.loginMember(email, pass) }
            catch (_: Exception) { LoginResult.NetworkError }
            when (r) {
                is LoginResult.Ok -> {
                    if (r.siteSession) {
                        auth.markLoggedIn(r.name)
                        msg = null; pendingName = null
                        onDone()
                    } else {
                        // Login member OK nhưng site chưa nhận phiên
                        pendingName = r.name
                        msg = "Đã đăng nhập (${r.name.ifBlank { "member" }}), " +
                            "nhưng phiên site chưa đồng bộ — bình luận có thể báo thiếu login."
                        msgOk = false
                    }
                }
                is LoginResult.Invalid -> { msg = r.message; msgOk = false }
                LoginResult.NetworkError -> { msg = "Lỗi mạng, thử lại sau."; msgOk = false }
            }
            busy = false
        }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Đăng nhập", fontWeight = FontWeight.Bold) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Về") } }
        )
    }) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("⚽", style = MaterialTheme.typography.displayMedium)
            Spacer(Modifier.height(4.dp))
            Text("Bóng Đá Plus", fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = email, onValueChange = { email = it },
                label = { Text("Email") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                leadingIcon = { Icon(Icons.Default.Email, null) },
                modifier = Modifier.fillMaxWidth(), enabled = !busy
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = pass, onValueChange = { pass = it },
                label = { Text("Mật khẩu") }, singleLine = true,
                visualTransformation = if (showPass) VisualTransformation.None
                else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                leadingIcon = { Icon(Icons.Default.Lock, null) },
                trailingIcon = {
                    IconButton(onClick = { showPass = !showPass }) {
                        Icon(if (showPass) Icons.Default.VisibilityOff else Icons.Default.Visibility, null)
                    }
                },
                modifier = Modifier.fillMaxWidth(), enabled = !busy
            )
            Spacer(Modifier.height(14.dp))
            Button(onClick = ::doLogin, enabled = !busy,
                modifier = Modifier.fillMaxWidth()) {
                Text(if (busy) "Đang đăng nhập…" else "Đăng nhập")
            }
            if (pendingName != null) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            auth.markLoggedIn(pendingName.orEmpty())
                            onDone()
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Cứ vào app") }
            }
            msg?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, style = MaterialTheme.typography.bodySmall,
                    color = if (msgOk) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                HorizontalDivider(Modifier.weight(1f))
                Text("  hoặc  ", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline)
                HorizontalDivider(Modifier.weight(1f))
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onOAuth, modifier = Modifier.fillMaxWidth()) {
                Text("🔑 Google / Apple (mở web)")
            }
            Spacer(Modifier.weight(1f))
            Text("Chưa có tài khoản? Đăng ký",
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable(onClick = onRegister))
        }
    }
}
