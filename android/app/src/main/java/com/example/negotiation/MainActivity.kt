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
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import com.iflytek.cloud.*
import com.iflytek.cloud.SpeechConstant as XFConstant
import android.util.Log

class MainActivity : ComponentActivity() {
    private var speechRecognizer: SpeechRecognizer? = null
    private lateinit var wsManager: WebSocketManager

    private val transcripts = mutableStateListOf<String>()
    private val adviceData = mutableStateOf<AdviceData?>(null)
    private val isRecording = mutableStateOf(false)
    private val connectionStatus = mutableStateOf("未连接")
    private val serverIp = mutableStateOf("")
    private val isRecognizing = mutableStateOf(false)
    private val xfStatus = mutableStateOf("")

    companion object {
        private const val TAG = "MainActivity"
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

        initXunfeiRecognizer()

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
                        xfStatus = xfStatus.value,
                        onConnect = { connectToServer(serverIp.value) },
                        onToggleRecord = { toggleRecording() }
                    )
                }
            }
        }

        // APP 启动时自动连接后端
        serverIp.value = "wss://suffering-oliver-infinite-endorsed.trycloudflare.com/ws"
        connectToServer(serverIp.value)
    }

    private fun initXunfeiRecognizer() {
        speechRecognizer = SpeechRecognizer.createRecognizer(this, InitListener { code ->
            if (code != ErrorCode.SUCCESS) {
                val msg = "讯飞初始化失败，错误码：$code"
                Log.e(TAG, msg)
                xfStatus.value = msg
            } else {
                Log.i(TAG, "讯飞初始化成功")
                xfStatus.value = "讯飞已就绪"
            }
        })

        speechRecognizer?.let { recognizer ->
            // 设置听写参数
            recognizer.setParameter(XFConstant.DOMAIN, "iat")          // 听写引擎
            recognizer.setParameter(XFConstant.LANGUAGE, "zh_cn")      // 中文
            recognizer.setParameter(XFConstant.ACCENT, "mandarin")     // 普通话
            recognizer.setParameter(XFConstant.SAMPLE_RATE, "16000")   // 16k 采样率
            recognizer.setParameter(XFConstant.RESULT_TYPE, "json")    // 返回 json
            recognizer.setParameter(XFConstant.VAD_BOS, "4000")        // 前端静音超时（毫秒）
            recognizer.setParameter(XFConstant.VAD_EOS, "1000")        // 后端静音超时
            recognizer.setParameter(XFConstant.ASR_PTT, "1")           // 有标点
            recognizer.setParameter(XFConstant.ASR_WBEST, "1")         // 最佳结果数
            recognizer.setParameter(XFConstant.ENGINE_TYPE, XFConstant.TYPE_CLOUD) // 云端引擎
        }
    }

    private val recognizerListener = object : RecognizerListener {
        override fun onBeginOfSpeech() {
            isRecognizing.value = true
            xfStatus.value = "请说话..."
        }

        override fun onEndOfSpeech() {
            isRecognizing.value = false
            xfStatus.value = "识别中..."
        }

        override fun onResult(results: RecognizerResult?, isLast: Boolean) {
            results?.let {
                val text = parseXunfeiResult(it.resultString)
                if (text.isNotBlank()) {
                    wsManager.sendText(text)
                }
            }
            if (isLast) {
                xfStatus.value = ""
                // 连续识别：如果还在录音状态，重新启动
                if (isRecording.value) {
                    restartRecognition()
                }
            }
        }

        override fun onError(error: SpeechError?) {
            isRecognizing.value = false
            error?.let {
                val msg = when (it.errorCode) {
                    10118 -> "说话时间太短"
                    10119 -> "说话时间太长"
                    10200 -> "网络异常"
                    10407 -> "appid 无效（请确认 appid 与 SDK 匹配）"
                    14002 -> "没有权限"
                    else -> "识别错误(${it.errorCode})：${it.errorDescription}"
                }
                xfStatus.value = msg
                Log.e(TAG, "讯飞错误: $msg")
                // 非致命错误时自动重启
                if (isRecording.value && it.errorCode != 10407) {
                    restartRecognition()
                }
            }
        }

        override fun onVolumeChanged(volume: Int, data: ByteArray?) {
            // 音量变化回调，可用于显示音量波形
        }

        override fun onEvent(eventType: Int, arg1: Int, arg2: Int, obj: Bundle?) {
            // 事件回调
        }
    }

    private fun parseXunfeiResult(jsonStr: String): String {
        return try {
            val json = JSONObject(jsonStr)
            val ws = json.getJSONArray("ws")
            val sb = StringBuilder()
            for (i in 0 until ws.length()) {
                val wordObj = ws.getJSONObject(i)
                val cw = wordObj.getJSONArray("cw")
                for (j in 0 until cw.length()) {
                    val item = cw.getJSONObject(j)
                    sb.append(item.optString("w", ""))
                }
            }
            sb.toString()
        } catch (e: Exception) {
            Log.e(TAG, "解析讯飞结果失败: ${e.message}")
            ""
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
        if (speechRecognizer == null) {
            connectionStatus.value = "语音识别未初始化"
            return
        }
        isRecording.value = true
        speechRecognizer?.startListening(recognizerListener)
    }

    private fun restartRecognition() {
        if (!isRecording.value) return
        speechRecognizer?.stopListening()
        speechRecognizer?.startListening(recognizerListener)
    }

    private fun stopRealtimeRecognition() {
        isRecording.value = false
        isRecognizing.value = false
        xfStatus.value = ""
        speechRecognizer?.stopListening()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopRealtimeRecognition()
        speechRecognizer?.destroy()
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
    xfStatus: String,
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
                },
                modifier = Modifier.padding(end = 4.dp)
            )
            TextButton(onClick = { showServerInput = !showServerInput }) {
                Text("设置", fontSize = 12.sp, color = Color.Gray)
            }
        }

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

        Text(
            text = if (xfStatus.isNotBlank()) xfStatus else connectionStatus,
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
                    containerColor = Color(0xFFFFF8E1)
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "💡 ${it.advice}",
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp,
                        color = Color(0xFF5D4037)
                    )
                    Spacer(modifier = Modifier.height(8.dp))

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

                    if (it.keySignal.isNotBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("🔑 ${it.keySignal}", fontSize = 12.sp, color = Color(0xFF757575))
                    }

                    if (it.risk.isNotBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("⚠️ ${it.risk}", fontSize = 12.sp, color = Color(0xFFC62828))
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
