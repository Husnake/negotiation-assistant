package com.example.negotiation

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
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
import com.example.negotiation.service.WebSocketManager
import com.example.negotiation.ui.theme.NegotiationTheme
import kotlinx.coroutines.launch
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var wsManager: WebSocketManager

    private val transcripts = mutableStateListOf<String>()
    private val adviceData = mutableStateOf<AdviceData?>(null)
    private val isRecording = mutableStateOf(false)
    private val connectionStatus = mutableStateOf("未连接")
    private val serverIp = mutableStateOf("")
    private val isRecognizing = mutableStateOf(false)

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startRealtimeRecognition()
        } else {
            connectionStatus.value = "需要录音权限"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        initSpeechRecognizer()

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
                        isRecognizing = isRecognizing.value,
                        connectionStatus = connectionStatus.value,
                        onConnect = { connectToServer(serverIp.value) },
                        onToggleRecord = { toggleRecording() }
                    )
                }
            }
        }
    }

    private fun initSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            connectionStatus.value = "设备不支持语音识别，请安装 Google 语音搜索"
            return
        }

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    isRecognizing.value = true
                }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {
                    isRecognizing.value = false
                }
                override fun onError(error: Int) {
                    isRecognizing.value = false
                    val msg = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH -> "没识别到语音，请再说一遍"
                        SpeechRecognizer.ERROR_NETWORK -> "网络错误"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "权限不足"
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "说话超时"
                        else -> "识别错误: $error"
                    }
                    if (error != SpeechRecognizer.ERROR_CLIENT) {
                        connectionStatus.value = msg
                    }
                    // 如果还在录音状态，自动重启识别
                    if (isRecording.value) {
                        restartRecognition()
                    }
                }
                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.get(0) ?: ""
                    if (text.isNotBlank()) {
                        wsManager.sendText(text)
                    }
                    // 连续识别：如果还在录音状态，重新启动
                    if (isRecording.value) {
                        restartRecognition()
                    }
                }
                override fun onPartialResults(partialResults: Bundle?) {
                    // 可选：实时显示部分结果
                }
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
    }

    private fun connectToServer(ip: String) {
        if (ip.isBlank()) {
            connectionStatus.value = "请输入IP地址"
            return
        }
        val url = if (ip.startsWith("ws://") || ip.startsWith("wss://")) ip else "ws://$ip:8000/ws"
        wsManager.connect(url)
    }

    private fun toggleRecording() {
        if (isRecording.value) {
            stopRealtimeRecognition()
        } else {
            if (connectionStatus.value != "已连接") {
                connectionStatus.value = "请先连接后端"
                return
            }
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startRealtimeRecognition() {
        if (!::speechRecognizer.isInitialized) {
            connectionStatus.value = "语音识别未初始化"
            return
        }
        isRecording.value = true
        startListening()
    }

    private fun startListening() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        speechRecognizer.startListening(intent)
    }

    private fun restartRecognition() {
        if (!isRecording.value) return
        speechRecognizer.stopListening()
        startListening()
    }

    private fun stopRealtimeRecognition() {
        isRecording.value = false
        isRecognizing.value = false
        if (::speechRecognizer.isInitialized) {
            speechRecognizer.stopListening()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopRealtimeRecognition()
        if (::speechRecognizer.isInitialized) {
            speechRecognizer.destroy()
        }
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
    isRecognizing: Boolean,
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
        Text(
            text = "谈判助理",
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = serverIp,
                onValueChange = onServerIpChange,
                label = { Text("后端地址 (如: wss://xxx.trycloudflare.com/ws)") },
                modifier = Modifier.weight(1f),
                singleLine = true
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(onClick = onConnect) {
                Text("连接")
            }
        }

        Text(
            text = "状态: $connectionStatus${if (isRecognizing) " (识别中...)" else ""}",
            fontSize = 13.sp,
            color = when (connectionStatus) {
                "已连接" -> Color(0xFF4CAF50)
                "连接中..." -> Color(0xFFFF9800)
                else -> Color.Gray
            },
            modifier = Modifier.padding(vertical = 4.dp)
        )

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
                text = if (isRecording) "⏹️ 停止实时分析" else "🎤 开始实时分析",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
