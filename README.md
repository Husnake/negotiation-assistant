# 谈判助理

安卓端实时房产谈判AI助手。

## 工作原理

```
手机麦克风 → 5秒音频切片 → 后端Whisper转文字 → GPT-4o分析 → 手机实时显示建议
```

## 目录结构

```
negotiation-assistant/
├── backend/          # Python后端（跑在电脑上）
│   ├── main.py
│   └── requirements.txt
└── android/          # 安卓APP
    └── app/...
```

## 后端部署（电脑端）

### 1. 安装依赖

```bash
cd backend
pip install -r requirements.txt
```

### 2. 设置OpenAI API Key

```bash
export OPENAI_API_KEY="sk-你的key"
# Windows用: set OPENAI_API_KEY=sk-你的key
```

### 3. 启动后端

```bash
python main.py
```

看到 `启动谈判助理后端...` 说明成功了。

### 4. 查看电脑IP

```bash
# Mac/Linux
ifconfig | grep "inet "

# Windows
ipconfig
```

找到内网IP，比如 `192.168.1.5`。这个IP要填到手机APP里。

### ⚠️ 防火墙
如果手机连不上，检查电脑防火墙是否放行了8000端口。

## 安卓端部署

### 方法：Android Studio新建项目 + 替换代码

1. **打开 Android Studio** → New Project → **Empty Activity** → 名称填 `NegotiationAssistant`，包名 `com.example.negotiation`，语言选 **Kotlin**，最小SDK **API 26**

2. **替换文件**：把我给你的代码文件，对应替换进新项目：
   - `build.gradle.kts` (Project) → 项目根目录
   - `settings.gradle.kts` → 项目根目录
   - `app/build.gradle.kts` → app目录
   - `app/src/main/AndroidManifest.xml`
   - `app/src/main/java/com/example/negotiation/MainActivity.kt`
   - `app/src/main/java/com/example/negotiation/service/AudioRecorder.kt`
   - `app/src/main/java/com/example/negotiation/service/WebSocketManager.kt`
   - `app/src/main/java/com/example/negotiation/ui/theme/` 下的3个文件
   - `app/src/main/res/values/strings.xml`
   - `app/src/main/res/values/themes.xml`

3. **Sync Gradle**，然后运行到手机上

### 使用步骤

1. 电脑启动后端（确保手机和电脑**同一个WiFi**）
2. 打开手机APP，输入电脑IP（如 `192.168.1.5`），点击**连接**
3. 看到状态变"已连接"后，点击**开始实时分析**
4. 把手机放桌上，正常谈判
5. 看屏幕上的实时转写和建议卡片

## 成本估算

每次1小时谈判大约消耗：
- Whisper转写：~¥2.5
- GPT-4o分析：~¥4
- **合计约 ¥6-7/小时**

## 注意事项

1. **录音合法性**：谈判录音需符合当地法律。建议提前告知对方"本次谈判我会做AI辅助记录"
2. **网络延迟**：从说话到看到建议约 3-5 秒（Whisper+LLM处理时间）
3. **电量**：持续录音+联网比较耗电，建议带充电宝
4. **麦克风**：安静环境下手机放桌上能录到双方；太吵可以外接USB-C麦克风
