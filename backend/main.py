import os
import asyncio
import tempfile
import wave
import json
from datetime import datetime
from fastapi import FastAPI, WebSocket
from fastapi.middleware.cors import CORSMiddleware
import openai
from dotenv import load_dotenv

load_dotenv()

app = FastAPI()
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

client = openai.AsyncOpenAI(api_key=os.getenv("OPENAI_API_KEY"))


class NegotiationSession:
    def __init__(self):
        self.transcript = []
        self.analysis_count = 0
        self.last_advice = ""
        self.context_summary = ""


sessions = {}

TRIGGER_WORDS = [
    "价格", "便宜", "贵", "底价", "考虑", "急", "贷款", "全款",
    "再想想", "商量", "优惠", "降价", "加钱", "税费", "首付",
    "周期", "付款", "定金", "违约", "中介费", "佣金"
]


async def analyze_dialogue(session: NegotiationSession):
    """调用 GPT-4o 分析谈判对话"""
    recent = "\n".join([
        f"{'买方' if i % 2 == 0 else '卖方/中介'}: {t}"
        for i, t in enumerate(session.transcript[-18:])
    ])

    prompt = f"""你是一位有20年经验的房产谈判专家。请分析以下二手房谈判对话，给出简短、可执行的建议。

当前谈判摘要：{session.context_summary or '刚开始谈判'}
最近对话记录：
{recent}

请严格输出JSON格式，不要markdown：
{{
    "opponent_mind": "对方当前心理状态（15字以内）",
    "key_signal": "发现的关键信号",
    "advice": "具体建议（50字以内，直接说怎么做）",
    "risk": "风险提示",
    "price_position": "价格态势：僵持/让步/逼单/犹豫/松口"
}}"""

    try:
        response = await client.chat.completions.create(
            model="gpt-4o",
            messages=[{"role": "user", "content": prompt}],
            response_format={"type": "json_object"},
            temperature=0.6,
            max_tokens=400
        )
        result = response.choices[0].message.content

        # 更新上下文摘要（每5次分析更新一次）
        session.analysis_count += 1
        if session.analysis_count % 5 == 0:
            await update_summary(session)

        return result
    except Exception as e:
        return json.dumps({"error": str(e)})


async def update_summary(session: NegotiationSession):
    """定期更新谈判状态摘要"""
    full = "\n".join(session.transcript[-30:])
    prompt = f"""总结以下房产谈判的核心状态（50字以内）：
{full}"""
    try:
        resp = await client.chat.completions.create(
            model="gpt-4o-mini",
            messages=[{"role": "user", "content": prompt}],
            max_tokens=100
        )
        session.context_summary = resp.choices[0].message.content.strip()
    except:
        pass


@app.websocket("/ws")
async def websocket_endpoint(websocket: WebSocket):
    await websocket.accept()
    session = NegotiationSession()
    session_id = id(session)
    sessions[session_id] = session

    print(f"[{datetime.now()}] 客户端连接: {session_id}")

    try:
        while True:
            # 接收二进制音频数据
            audio_bytes = await websocket.receive_bytes()

            if len(audio_bytes) < 1000:
                continue  # 忽略过小片段

            # 保存为 wav
            with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as tmp:
                with wave.open(tmp.name, 'wb') as wav:
                    wav.setnchannels(1)
                    wav.setsampwidth(2)
                    wav.setframerate(16000)
                    wav.writeframes(audio_bytes)
                tmp_path = tmp.name

            # Whisper 转写
            try:
                with open(tmp_path, 'rb') as audio_file:
                    transcript = await client.audio.transcriptions.create(
                        model="whisper-1",
                        file=audio_file,
                        language="zh"
                    )

                text = transcript.text.strip()
                if not text:
                    continue

                session.transcript.append(text)
                print(f"[转写] {text}")

                # 发送转写结果给客户端
                await websocket.send_json({
                    "type": "transcript",
                    "text": text,
                    "timestamp": datetime.now().isoformat()
                })

                # 触发分析：每3句，或包含关键词
                should_analyze = (
                    len(session.transcript) % 3 == 0 or
                    any(w in text for w in TRIGGER_WORDS)
                ) and len(session.transcript) >= 2

                if should_analyze:
                    advice_json = await analyze_dialogue(session)
                    print(f"[建议] {advice_json[:100]}...")
                    await websocket.send_json({
                        "type": "advice",
                        "content": advice_json,
                        "timestamp": datetime.now().isoformat()
                    })

            except Exception as e:
                print(f"处理错误: {e}")
                await websocket.send_json({
                    "type": "error",
                    "message": str(e)
                })
            finally:
                if os.path.exists(tmp_path):
                    os.unlink(tmp_path)

    except Exception as e:
        print(f"连接断开: {e}")
    finally:
        sessions.pop(session_id, None)
        print(f"[{datetime.now()}] 客户端断开: {session_id}")


if __name__ == "__main__":
    import uvicorn
    print("启动谈判助理后端...")
    print("请确保环境变量 OPENAI_API_KEY 已设置")
    uvicorn.run(app, host="0.0.0.0", port=8000)
