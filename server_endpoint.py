"""
카카오봇 서버 엔드포인트
Android 앱 → POST /chat → LLM → {"reply": "..."} 반환

Groq 다중 모델 폴백 전략:
  요청 → 모델 A 시도 → 429면 모델 B → 또 429면 모델 C → ...
  - RPM 429: 120s 쿨다운 후 복귀
  - RPD 429: 해당 모델 오늘 하루 제외 (일일 한도 소진)
  - 모든 모델 RPD 소진 시 "오늘 사용량 모두 소진" 안내 반환

합산 처리량 (RPD 기준):
  gpt-oss-120b(1K) + llama-3.3-70b(1K) + qwen-27b(1K) + gpt-oss-20b(1K)
  + llama-3.1-8b(14.4K) = 약 18,400 req/day
"""

import json
import time
import os
from datetime import date, datetime, timezone, timedelta
import groq
from flask import Blueprint, request, jsonify
from dotenv import load_dotenv

load_dotenv()

kakao_bp = Blueprint("kakao", __name__)

# 품질 우선순위 순서 — 위에서부터 시도, llama-3.1-8b는 RPD가 14.4K라 마지막 안전망
GROQ_MODELS = [
    "openai/gpt-oss-120b",     # RPD 1K / TPD 200K
    # "llama-3.3-70b-versatile", # RPD 1K / TPD 100K - 단종됨
    "qwen/qwen3.6-27b",        # RPD 1K / TPD 200K
    "openai/gpt-oss-20b",      # RPD 1K / TPD 200K
    "llama-3.1-8b-instant",    # RPD 14.4K / TPD 500K — 최후 보루
]

# RPM 429: 2분 쿨다운, RPD 429: 24시간 제외
COOLDOWN_RPM = 120
COOLDOWN_RPD = 86400

DAILY_EXHAUSTED_MSG = "오늘의 AI 사용량이 모두 소진됐어요. 내일 다시 만나요!"

KST = timezone(timedelta(hours=9))

# ── 툴 정의 ──────────────────────────────────────────────────────
TOOLS = [
    {
        "type": "function",
        "function": {
            "name": "get_datetime",
            "description": "현재 날짜, 요일, 시각(KST)을 반환합니다. 오늘 날짜나 지금 몇 시인지 물어볼 때 사용하세요.",
            "parameters": {"type": "object", "properties": {}, "required": []},
        },
    }
]


def _run_tool(name: str, _args: dict) -> str:
    if name == "get_datetime":
        now = datetime.now(KST)
        weekdays = ["월", "화", "수", "목", "금", "토", "일"]
        return now.strftime(f"%Y년 %m월 %d일 ({weekdays[now.weekday()]}요일) %H:%M KST")
    return f"(지원하지 않는 툴: {name})"


BOT_NAME = "춘배"
SYSTEM_PROMPT = f"""
당신의 이름은 "{BOT_NAME}"입니다.
카카오톡 AI 봇입니다.

- 친근하고 자연스럽게 답합니다.
- 핵심만 1~4문장으로 답하고, 길어지면 두괄식으로 말합니다.
- 상황에 맞게 가벼운 유머는 허용하지만 진지한 주제에서는 사용하지 않습니다.
- 모르면 모른다고 말하고, 추측은 추측임을 명확히 밝힙니다.
- 이전 대화와 사용자의 취향을 반영합니다.
- '저메추', '점메추', '야식' 요청 시 3~5개의 메뉴와 간단한 이유를 추천합니다.
"""


class DailyLimitExhausted(Exception):
    pass


class GroqRouter:
    """429(Rate Limit) 발생 시 다음 모델로 자동 폴백하는 라우터.

    RPM 429 → COOLDOWN_RPM 초 쿨다운 후 복귀
    RPD 429 → 오늘 하루 해당 모델 제외 (COOLDOWN_RPD = 24h)
    모든 모델 RPD 소진 → DailyLimitExhausted 발생
    """

    def __init__(self, api_key: str, models: list):
        self.client = groq.Groq(api_key=api_key)
        self.models = models
        self._blocked: dict = {}  # model → unblock_time (monotonic)
        self._day_dead: set = set()  # RPD 소진 모델 (오늘 내 영구 제외)
        self._dead_date: date = date.today()  # _day_dead를 기록한 날짜

    def _is_daily_limit(self, exc: groq.RateLimitError) -> bool:
        msg = getattr(exc, "message", str(exc))
        return any(x in msg for x in ("per day", "per_day", "RPD", "TPD"))

    def _block(self, model: str, exc: groq.RateLimitError):
        if self._is_daily_limit(exc):
            self._day_dead.add(model)
            self._blocked[model] = time.monotonic() + COOLDOWN_RPD
            print(f"[GroqRouter] {model} → 일일 한도(RPD) 소진")
        else:
            self._blocked[model] = time.monotonic() + COOLDOWN_RPM
            print(f"[GroqRouter] {model} → RPM 429, {COOLDOWN_RPM}s 쿨다운")

    def _reset_if_new_day(self):
        today = date.today()
        if today != self._dead_date:
            self._day_dead.clear()
            # RPD 블록만 해제 (RPM 쿨다운은 monotonic이라 자동 만료)
            self._blocked = {
                m: t for m, t in self._blocked.items()
                if t - time.monotonic() < COOLDOWN_RPD
            }
            self._dead_date = today
            print(f"[GroqRouter] 날짜 변경 감지 ({today}) → 일일 한도 초기화")

    def _live_models(self) -> list:
        """RPD 소진 모델 제외"""
        return [m for m in self.models if m not in self._day_dead]

    def _available(self) -> list:
        now = time.monotonic()
        live = self._live_models()
        ready = [m for m in live if self._blocked.get(m, 0) <= now]
        return ready or live  # 전부 RPM 쿨다운 중이면 live 전체 재시도

    def chat(self, messages: list, **kwargs):
        """
        Returns: (response, model_used_str)
        Raises:  DailyLimitExhausted | groq.APIError | 마지막 RateLimitError
        """
        self._reset_if_new_day()
        if not self._live_models():
            raise DailyLimitExhausted()

        last_exc = None
        for model in self._available():
            try:
                resp = self.client.chat.completions.create(
                    model=model, messages=messages, **kwargs
                )
                return resp, model
            except groq.RateLimitError as e:
                self._block(model, e)
                last_exc = e
                if not self._live_models():
                    raise DailyLimitExhausted() from e
            except groq.APIError:
                raise  # 5xx 등 서버 에러는 폴백 없이 즉시 올림
        raise last_exc


_router: GroqRouter = None


def get_router() -> GroqRouter:
    global _router
    if _router is None:
        _router = GroqRouter(api_key=os.environ["GROQ_API_KEY"], models=GROQ_MODELS)
    return _router


@kakao_bp.route("/chat", methods=["POST"])
def chat():
    data = request.get_json(force=True)
    room_id = data.get("room_id", "unknown")
    sender  = data.get("sender", "unknown")
    message = data.get("message", "")
    history = data.get("history", [])  # [{"role": "user/assistant", "content": "..."}]

    messages = [{"role": "system", "content": SYSTEM_PROMPT}]
    messages += history
    messages.append({"role": "user", "content": message})

    try:
        resp, model_used = get_router().chat(
            messages=messages,
            max_tokens=600,
            temperature=0.7,
            tools=TOOLS,
        )

        # 툴 콜 루프: 모델이 툴을 호출하면 실행 결과를 넣고 재요청
        for _ in range(5):  # 무한루프 방지
            if resp.choices[0].finish_reason != "tool_calls":
                break
            assistant_msg = resp.choices[0].message
            messages.append({
                "role": "assistant",
                "content": assistant_msg.content,
                "tool_calls": [
                    {
                        "id": tc.id,
                        "type": "function",
                        "function": {"name": tc.function.name, "arguments": tc.function.arguments},
                    }
                    for tc in (assistant_msg.tool_calls or [])
                ],
            })
            for tc in (assistant_msg.tool_calls or []):
                result = _run_tool(tc.function.name, json.loads(tc.function.arguments or "{}"))
                messages.append({"role": "tool", "tool_call_id": tc.id, "content": result})
            resp, model_used = get_router().chat(
                messages=messages,
                max_tokens=600,
                temperature=0.7,
                tools=TOOLS,
            )

        reply = resp.choices[0].message.content.strip()
    except DailyLimitExhausted:
        return jsonify({"reply": DAILY_EXHAUSTED_MSG}), 429
    except groq.RateLimitError:
        return jsonify({"reply": "잠시 후 다시 시도해주세요. (RPM 한도 일시 초과)"}), 429
    except Exception as e:
        return jsonify({"reply": f"(오류: {e})"}), 500

    print(f"[{room_id}] {sender}: {message!r} → {reply!r} (via {model_used})", flush=True)
    return jsonify({"reply": reply})


# ─── 기존 main.py에 등록하는 방법 ────────────────────────────────
# from server_endpoint import kakao_bp
# app.register_blueprint(kakao_bp)
