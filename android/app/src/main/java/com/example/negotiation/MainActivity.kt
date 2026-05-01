package com.example.negotiation

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.negotiation.service.AudioRecorder
import com.example.negotiation.service.WebSocketManager
import com.example.negotiation.ui.theme.NegotiationTheme
import kotlinx.coroutines.launch
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    private lateinit var audioRecorder: AudioRecorder
    private lateinit var wsManager: WebSocketManager

    private val transcripts = mutableStateListOf<String>()
    private val adviceData = mutableStateOf<AdviceData?>(null)
    private val isRecording = mutableStateOf(false)
    private val connectionStatus = mutableStateOf("未连接")
    private val serverIp = mutableStateOf("")

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startRecording()
        } else {
            connectionStatus.value = "需要录音权限"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        audioRecorder = AudioRecorder { audioData ->
            wsManager.sendAudio(audioData)
        }

        wsManager = WebSocketManager(
            onTranscript = { text ->
                transcripts.add(text)
            },
            onAdvice = { json ->
                adviceData.value = parseAdvice(json)
            },
            onStatusChange = { status ->
                connectionStatus.value = status
            }
        )

        setContent {
            NegotiationTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(
                        serverIp = serverIp.value,
                        onServerIpChange = { serverIp.value = it },
                        transcripts = transcripts,
                        advice = adviceData.value,
                        isRecording = isRecording.value,
                        connectionStatus = connectionStatus.value,
                        onConnect = { connectToServer(serverIp.value) },
                        onToggleRecord = { toggleRecording() }
                    )
                }
            }
        }
    }

    private fun connectToServer(ip: String) {
        if (ip.isBlank()) {
            connectionStatus.value = "请输入IP地址"
            return
        }
        val url = if (ip.startsWith("ws://")) ip else "ws://$ip:8000/ws"
        wsManager.connect(url)
    }

    private fun toggleRecording() {
        if (isRecording.value) {
            stopRecording()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startRecording() {
        if (connectionStatus.value != "已连接") {
            connectionStatus.value = "请先连接后端"
            return
        }
        audioRecorder.start()
        isRecording.value = true
    }

    private fun stopRecording() {
        audioRecorder.stop()
        isRecording.value = false
    }

    override fun onDestroy() {
        super.onDestroy()
        audioRecorder.stop()
        wsManager.disconnect()
    }

    private fun parseAdvice(json: String): AdviceData {
        val obj = JSONObject(json)
        return AdviceData(
            opponentMind = obj.optString("opponent_mind", ""),
            keySignal = obj.optString("key_signal", ""),
            advice = obj.optString("advice", ""),
            risk = obj.optString("risk", ""),
            pricePosition = obj.optString("price_position", "")
        )
    }
}

data class AdviceData(
    val opponentMind: String,
    val keySignal: String,
    val advice: String,
    val risk: String,
    val pricePosition: String
)

@Composable
fun MainScreen(
    serverIp: String,
    onServerIpChange: (String) -> Unit,
    transcripts: List<String>,
    advice: AdviceData?,
    isRecording: Boolean,
    connectionStatus: String,
    onConnect: () -> Unit,
    onToggleRecord: () -> Unit
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(transcripts.size) {
        if (transcripts.isNotEmpty()) {
            scope.launch {
                listState.animateScrollToItem(transcripts.size - 1)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // 标题
        Text(
            text = "谈判助理",
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        // 服务器配置
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = serverIp,
                onValueChange = onServerIpChange,
                label = { Text("后端IP (如: 192.168.1.5)") },
                modifier = Modifier.weight(1f),
                singleLine = true
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(onClick = onConnect) {
                Text("连接")
            }
        }

        // 状态
        Text(
            text = "状态: $connectionStatus",
            fontSize = 13.sp,
            color = when (connectionStatus) {
                "已连接" -> Color(0xFF4CAF50)
                "连接中..." -> Color(0xFFFF9800)
                else -> Color.Gray
            },
            modifier = Modifier.padding(vertical = 4.dp)
        )

        // 建议卡片（核心！）
        advice?.let {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "💡 ${it.advice}",
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    if (it.opponentMind.isNotBlank()) {
                        Text("🧠 对方心理: ${it.opponentMind}", fontSize = 13.sp)
                    }
                    if (it.keySignal.isNotBlank()) {
                        Text("🔑 关键信号: ${it.keySignal}", fontSize = 13.sp)
                    }
                    if (it.risk.isNotBlank()) {
                        Text("⚠️ 风险: ${it.risk}", fontSize = 13.sp, color = Color(0xFFE65100))
                    }
                    if (it.pricePosition.isNotBlank()) {
                        Text(
                            "📊 价格态势: ${it.pricePosition}",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // 转写记录
        Text(
            text = "实时转写 (${transcripts.size} 条)",
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(vertical = 6.dp)
        )

        LazyColumn(
            modifier = Modifier.weight(1f),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(transcripts) { text ->
                Text(
                    text = text,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Color(0xFFF5F5F5),
                            shape = MaterialTheme.shapes.small
                        )
                        .padding(10.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 录音按钮
        Button(
            onClick = onToggleRecord,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isRecording) Color(0xFFE53935) else MaterialTheme.colorScheme.primary
            )
        ) {
            Text(
                text = if (isRecording) "⏹️ 停止录音" else "🎤 开始实时分析",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
