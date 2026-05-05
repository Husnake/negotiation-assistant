import os
import json
import base64
import hashlib
import time
from datetime import datetime
from fastapi import FastAPI, WebSocket
from fastapi.middleware.cors import CORSMiddleware
import openai
import requests
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

# LLM 客户端
client = openai.AsyncOpenAI(
    api_key=os.getenv("LLM_API_KEY") or os.getenv("OPENAI_API_KEY"),
    base_url=os.getenv("LLM_BASE_URL", "https://api.openai.com/v1")
)
LLM_MODEL = os.getenv("LLM_MODEL", "gpt-4o")
LLM_SUMMARY_MODEL = os.getenv("LLM_SUMMARY_MODEL", "gpt-4o-mini")

# 讯飞配置
XUNFEI_APPID = os.getenv("XUNFEI_APPID", "")
XUNFEI_APIKEY = os.getenv("XUNFEI_APIKEY", "")
XUNFEI_APISECRET = os.getenv("XUNFEI_APISECRET", "")


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


def xunfei_stt(audio_base64: str) -> str:
    """调用讯飞语音听写 HTTP API"""
    if not all([XUNFEI_APPID, XUNFEI_APIKEY, XUNFEI_APISECRET]):
        return "[错误] 讯飞 API 配置不完整"

    try:
        # 1. 构造参数 JSON 并 Base64 编码
        param = {"engine_type": "sms16k", "aue": "raw"}
        param_base64 = base64.b64encode(json.dumps(param).encode("utf-8")).decode("utf-8")

        # 2. 生成时间戳
        cur_time = str(int(time.time()))

        # 3. 生成 CheckSum: MD5(APIKey + CurTime + Param)
        checksum_str = XUNFEI_APIKEY + cur_time + param_base64
        checksum = hashlib.md5(checksum_str.encode("utf-8")).hexdigest()

        # 4. 发送请求
        headers = {
            "X-Appid": XUNFEI_APPID,
            "X-CurTime": cur_time,
            "X-Param": param_base64,
            "X-CheckSum": checksum,
            "Content-Type": "application/x-www-form-urlencoded; charset=utf-8"
        }

        # 请求体：音频数据的 base64 编码
        data = {"audio": audio_base64}

        resp = requests.post(
            "http://api.xfyun.cn/v1/service/v1/iat",
            headers=headers,
            data=data,
            timeout=10
        )
        resp.raise_for_status()

        result = resp.json()
        if result.get("code") == "0":
            return result.get("data", "").strip()
        else:
            return f"[讯飞错误] {result.get('code')}: {result.get('message', '未知错误')}"

    except requests.exceptions.RequestException as e:
        return f"[请求错误] {str(e)}"
    except Exception as e:
        return f"[异常] {str(e)}"


async def analyze_dialogue(session: NegotiationSession):
    """调用 LLM 分析谈判对话"""
    recent = "\n".join([
        f"{'买方' if i % 2 == 0 else '卖方/中介'}: {t}"
        for i, t in enumerate(session.transcript[-18:])
    ])

    prompt = f"""你是一位有20年经验的房产谈判专家。请分析以下二手房谈判对话，给出短俊、可执行的建议。

当前谈判摘要：{session.context_summary or '刚开始谈判'}
最近对话记录：
{recent}

请严格输出JSON格式，不要markdown，不要think标签：
{{
    "opponent_mind": "对方当前心理状态（10字以内）",
    "key_signal": "发现的关键信号",
    "advice": "核心策略（30字以内）",
    "suggested_script": "给对方的一句原话（50字以内，直接可以用来说的）",
    "risk": "风险提示",
    "price_position": "价格态势：僵持/让步/逼单/犹豫/松口"
}}"""

    try:
        response = await client.chat.completions.create(
            model=LLM_MODEL,
            messages=[{"role": "user", "content": prompt}],
            response_format={"type": "json_object"},
            temperature=0.6,
            max_tokens=400
        )
        result = response.choices[0].message.content

        # 清理 MiniMax 等模型的 thinking tag 污染
        import re
        result = re.sub(r'<sum>.*?</sum>', '', result, flags=re.DOTALL)
        result = re.sub(r'<think>.*?</think>', '', result, flags=re.DOTALL)

        session.analysis_count += 1
        if session.analysis_count % 5 == 0:
            await update_summary(session)

        return result.strip()
    except Exception as e:
        return json.dumps({"error": str(e)})


async def update_summary(session: NegotiationSession):
    """定期更新谈判状态摘要"""
    full = "\n".join(session.transcript[-30:])
    prompt = f"""总结以下房产谈判的核心状态（50字以内）：
{full}"""
    try:
        resp = await client.chat.completions.create(
            model=LLM_SUMMARY_MODEL,
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
            data = await websocket.receive_json()
            msg_type = data.get("type", "")

            # ===== 音频消息：调用讯飞识别 =====
            if msg_type == "audio":
                audio_b64 = data.get("data", "")
                if not audio_b64:
                    await websocket.send_json({"type": "error", "message": "音频数据为空"})
                    continue

                print(f"[{datetime.now()}] 收到音频，调用讯飞识别...")
                recognized_text = xunfei_stt(audio_b64)
                print(f"[识别结果] {recognized_text[:60]}...")

                if recognized_text.startswith("["):
                    # 错误信息
                    await websocket.send_json({
                        "type": "error",
                        "message": recognized_text
                    })
                    continue

                if not recognized_text:
                    await websocket.send_json({
                        "type": "error",
                        "message": "未识别到语音"
                    })
                    continue

                # 发送转写结果
                await websocket.send_json({
                    "type": "transcript",
                    "text": recognized_text,
                    "timestamp": datetime.now().isoformat()
                })

                session.transcript.append(recognized_text)

                # 触发分析
                should_analyze = (
                    len(session.transcript) % 3 == 0 or
                    any(w in recognized_text for w in TRIGGER_WORDS)
                ) and len(session.transcript) >= 2

                if should_analyze:
                    advice_json = await analyze_dialogue(session)
                    print(f"[建议] {advice_json[:100]}...")
                    await websocket.send_json({
                        "type": "advice",
                        "content": advice_json,
                        "timestamp": datetime.now().isoformat()
                    })

            # ===== 文本消息：直接处理（保留兼容） =====
            elif msg_type == "text" or "text" in data:
                text = data.get("text", "").strip()
                if not text:
                    continue

                print(f"[收到文本] {text}")
                session.transcript.append(text)

                await websocket.send_json({
                    "type": "transcript",
                    "text": text,
                    "timestamp": datetime.now().isoformat()
                })

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
        print(f"连接断开: {e}")
    finally:
        sessions.pop(session_id, None)
        print(f"[{datetime.now()}] 客户端断开: {session_id}")


if __name__ == "__main__":
    import uvicorn
    print("启动谈判助理后端...")
    print(f"LLM 模型: {LLM_MODEL}")
    print(f"LLM 地址: {client.base_url}")
    print(f"讯飞 AppID: {XUNFEI_APPID}")
    uvicorn.run(app, host="0.0.0.0", port=8000)
