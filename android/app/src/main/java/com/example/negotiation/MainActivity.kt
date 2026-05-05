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
import com.example.negotiation.service.WebSocketManager
import com.example.negotiation.ui.theme.NegotiationTheme
import com.example.negotiation.util.XfyunJsonParser
import com.iflytek.cloud.*
import kotlinx.coroutines.launch
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    private var xfyunRecognizer: SpeechRecognizer? = null
    private lateinit var wsManager: WebSocketManager

    private val transcripts = mutableStateListOf<String>()
    private val adviceData = mutableStateOf<AdviceData?>(null)
    private val isRecording = mutableStateOf(false)
    private val connectionStatus = mutableStateOf("连接中...")
    private val serverIp = mutableStateOf("")
    private val isRecognizing = mutableStateOf(false)

    companion object {
        private const val TAG = "NegotiationAssistant"
        private const val AUTO_CONNECT_URL = "wss://suffering-oliver-infinite-endorsed.trycloudflare.com/ws"
    }

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

        // 讯飞 SDK 初始化
        SpeechUtility.createUtility(this, SpeechConstant.APPID + "=a0d2a24e")
        initXfyunRecognizer()

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

        // APP 启动时自动连接后端
        serverIp.value = AUTO_CONNECT_URL
        connectToServer(AUTO_CONNECT_URL)
    }

    private fun initXfyunRecognizer() {
        xfyunRecognizer = SpeechRecognizer.createRecognizer(this) { code ->
            if (code != ErrorCode.SUCCESS) {
                connectionStatus.value = "讯飞语音初始化失败: $code"
            }
        }
    }

    private fun connectToServer(ip: String) {
        if (ip.isBlank()) {
            connectionStatus.value = "请输入后端地址"
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
        val recognizer = xfyunRecognizer ?: run {
            connectionStatus.value = "语音识别未初始化"
            return
        }
        isRecording.value = true
        startListening(recognizer)
    }

    private fun startListening(recognizer: SpeechRecognizer) {
        isRecognizing.value = true

        recognizer.setParameter(SpeechConstant.DOMAIN, "iat")
        recognizer.setParameter(SpeechConstant.LANGUAGE, "zh_cn")
        recognizer.setParameter(SpeechConstant.ACCENT, "mandarin")
        recognizer.setParameter(SpeechConstant.RESULT_TYPE, "json")
        recognizer.setParameter(SpeechConstant.ASR_PTT, "1")
        recognizer.setParameter(SpeechConstant.VAD_BOS, "4000")
        recognizer.setParameter(SpeechConstant.VAD_EOS, "1000")

        val ret = recognizer.startListening(object : RecognizerListener {
            override fun onVolumeChanged(volume: Int, data: ByteArray?) {}
            override fun onBeginOfSpeech() {}
            override fun onEndOfSpeech() {
                isRecognizing.value = false
            }
            override fun onResult(results: RecognizerResult?, isLast: Boolean) {
                val json = results?.resultString ?: ""
                val text = XfyunJsonParser.parseResult(json)
                if (text.isNotBlank()) {
                    transcripts.add(text)
                    wsManager.sendText(text)
                }
                if (isRecording.value && isLast) {
                    startListening(recognizer)
                }
            }
            override fun onError(error: SpeechError?) {
                isRecognizing.value = false
                val msg = error?.errorDescription ?: "识别错误"
                connectionStatus.value = msg
                if (isRecording.value) {
                    startListening(recognizer)
                }
            }
            override fun onEvent(eventType: Int, arg1: Int, arg2: Int, obj: Bundle?) {}
        })
        if (ret != ErrorCode.SUCCESS) {
            connectionStatus.value = "启动识别失败: $ret"
        }
    }

    private fun stopRealtimeRecognition() {
        isRecording.value = false
        isRecognizing.value = false
        xfyunRecognizer?.stopListening()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopRealtimeRecognition()
        xfyunRecognizer?.destroy()
        wsManager.disconnect()
    }

    private fun parseAdvice(json: String): AdviceData {
        val obj = JSONObject(json)
        return AdviceData(
            opponentMind = obj.optString("opponent_mind", ""),
            keySignal = obj.optString("key_signal", ""),
            advice = obj.optString("advice", ""),
            suggestedScript = obj.optString("suggested_script", ""),
            risk = obj.optString("risk", ""),
            pricePosition = obj.optString("price_position", "")
        )
    }
}

data class AdviceData(
    val opponentMind: String,
    val keySignal: String,
    val advice: String,
    val suggestedScript: String,
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
    var showServerInput by remember { mutableStateOf(false) }

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
        // 顶部栏：标题 + 连接状态 + 设置
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "谈判助理",
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = when (connectionStatus) {
                    "已连接" -> "●"
                    "连接中..." -> "○"
                    else -> "✕"
                },
                fontSize = 18.sp,
                color = when (connectionStatus) {
                    "已连接" -> Color(0xFF4CAF50)
                    "连接中..." -> Color(0xFFFF9800)
                    else -> Color(0xFFE53935)
                }
            )
            TextButton(onClick = { showServerInput = !showServerInput }) {
                Text("设置", fontSize = 12.sp, color = Color.Gray)
            }
        }

        // 后端地址输入（默认隐藏）
        if (showServerInput) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = serverIp,
                    onValueChange = onServerIpChange,
                    label = { Text("后端地址") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                Spacer(modifier = Modifier.width(8.dp))
                Button(onClick = onConnect) {
                    Text("连接")
                }
            }
        }

        // 状态栏
        Text(
            text = connectionStatus,
            fontSize = 13.sp,
            color = when (connectionStatus) {
                "已连接" -> Color(0xFF4CAF50)
                "连接中..." -> Color(0xFFFF9800)
                else -> Color.Gray
            },
            modifier = Modifier.padding(vertical = 4.dp)
        )

        // 建议卡片
        advice?.let {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFFFFF8E1)
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    // 核心策略
                    Text(
                        text = "💡 ${it.advice}",
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp,
                        color = Color(0xFF5D4037)
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    // 推荐话术（绿色高亮）
                    if (it.suggestedScript.isNotBlank()) {
                        Surface(
                            color = Color(0xFFE8F5E9),
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "🗣️ ${it.suggestedScript}",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color(0xFF1B5E20),
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    // 价格态势 + 对方心理
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        if (it.pricePosition.isNotBlank()) {
                            Text(
                                "📊 ${it.pricePosition}",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFE65100)
                            )
                        }
                        if (it.opponentMind.isNotBlank()) {
                            Text(
                                "🧠 对方: ${it.opponentMind}",
                                fontSize = 13.sp,
                                color = Color.Gray
                            )
                        }
                    }

                    // 关键信号
                    if (it.keySignal.isNotBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("🔑 ${it.keySignal}", fontSize = 12.sp, color = Color(0xFF757575))
                    }

                    // 风险提示
                    if (it.risk.isNotBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("⚠️ ${it.risk}", fontSize = 12.sp, color = Color(0xFFC62828))
                    }
                }
            }
        }

        // 实时转写标题
        Text(
            text = "实时转写 (${transcripts.size} 条)",
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(vertical = 6.dp)
        )

        // 转写列表
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
                text = if (isRecording) "⏹️ 停止实时分析" else "🎤 开始实时分析",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
