"""
Chat Profile Miner - LLM 结构化抽取器
对规则无法覆盖的隐含偏好，用 LLM + JSON Schema 抽取。
LLM 做语义解析器，不做人格判断器。
"""
import json
from typing import List, Dict, Optional
from datetime import datetime
from schemas import (
    Message, Evidence, EvidenceType, Preference, BehaviorPattern,
    Polarity, InferenceLevel, PreferenceStatus, Confidence
)


# ============================================================
# LLM Prompt 模板
# ============================================================

EXTRACTION_SYSTEM_PROMPT = """你是一个聊天记录分析助手。你的任务是从情侣聊天记录中提取结构化的偏好信息。

重要原则：
1. 只提取聊天中有证据支持的信息，不要猜测
2. 区分三种情况：
   - explicit: 她自己明确说的（"我喜欢XX"）
   - behavioral: 她的行为暗示的（多次分享、实际购买）
   - inferred: 你推断的（必须标注为推断）
3. 如果证据不足，标注 confidence < 0.5
4. 保留原话作为证据
5. 注意反讽、否定、转折（"好看是好看，但不适合我"）

输出严格的 JSON 格式。"""

EXTRACTION_USER_PROMPT = """分析以下聊天记录，提取 {target_name} 的偏好信息。

聊天记录（{msg_count} 条）：
---
{chat_text}
---

请输出 JSON：
{{
  "preferences": [
    {{
      "category": "jewelry/fragrance/fashion/food/...",
      "object": "具体描述",
      "polarity": "like/dislike/neutral",
      "inference_level": "explicit/direct_behavior/behavioral_inference/weak_inference",
      "evidence_quotes": ["原话1", "原话2"],
      "confidence": 0.0-1.0,
      "reasoning": "为什么这样判断"
    }}
  ],
  "behavior_patterns": [
    {{
      "trigger": "什么情况下",
      "behavior": "她会怎样",
      "evidence_quotes": ["原话"],
      "confidence": 0.0-1.0
    }}
  ],
  "gift_signals": [
    {{
      "object": "具体商品/品类",
      "desire": "high/moderate/low",
      "price_barrier": true/false,
      "evidence_quotes": ["原话"],
      "confidence": 0.0-1.0
    }}
  ],
  "unknowns": ["证据不足无法判断的事项"]
}}"""


class LLMExtractor:
    """LLM 结构化抽取器"""

    def __init__(self, model_name: str = "deepseek-v4-flash",
                 api_base: str = "https://api.deepseek.com",
                 api_key: str = ""):
        self.model_name = model_name
        self.api_base = api_base
        self.api_key = api_key

    def _format_chat(self, messages: List[Message], max_chars: int = 8000) -> str:
        """格式化聊天记录为文本"""
        lines = []
        total = 0
        for msg in messages:
            speaker = msg.speaker
            text = msg.text
            time_str = msg.timestamp.strftime("%m-%d %H:%M")
            line = f"[{time_str}] {speaker}: {text}"
            if total + len(line) > max_chars:
                lines.append("... (更多消息省略)")
                break
            lines.append(line)
            total += len(line)
        return '\n'.join(lines)

    def extract_from_session(self, messages: List[Message],
                             target_name: str) -> Dict:
        """
        从一个 session 的消息中抽取偏好。
        返回结构化 dict。
        """
        if not messages:
            return {"preferences": [], "behavior_patterns": [], "gift_signals": [], "unknowns": []}

        # 过滤掉太短的 session（少于5条消息）
        if len(messages) < 5:
            return {"preferences": [], "behavior_patterns": [], "gift_signals": [], "unknowns": []}

        # 检查是否有目标人物的消息
        target_msgs = [m for m in messages if m.speaker_role.value == "target"]
        if len(target_msgs) < 2:
            return {"preferences": [], "behavior_patterns": [], "gift_signals": [], "unknowns": []}

        chat_text = self._format_chat(messages)
        prompt = EXTRACTION_USER_PROMPT.format(
            target_name=target_name,
            msg_count=len(messages),
            chat_text=chat_text
        )

        # 调用 LLM
        result = self._call_llm(EXTRACTION_SYSTEM_PROMPT, prompt)

        # 解析 JSON
        try:
            # 尝试从响应中提取 JSON
            json_str = self._extract_json(result)
            return json.loads(json_str)
        except (json.JSONDecodeError, ValueError):
            return {"preferences": [], "behavior_patterns": [], "gift_signals": [], "unknowns": []}

    def _call_llm(self, system: str, user: str) -> str:
        """调用 LLM API"""
        try:
            import httpx
            headers = {
                "Authorization": f"Bearer {self.api_key}",
                "Content-Type": "application/json"
            }
            payload = {
                "model": self.model_name,
                "messages": [
                    {"role": "system", "content": system},
                    {"role": "user", "content": user}
                ],
                "temperature": 0.1,
                "max_tokens": 4096
            }
            resp = httpx.post(
                f"{self.api_base}/v1/chat/completions",
                json=payload,
                headers=headers,
                timeout=60
            )
            resp.raise_for_status()
            data = resp.json()
            return data["choices"][0]["message"]["content"]
        except Exception as e:
            return '{"preferences": [], "behavior_patterns": [], "gift_signals": [], "unknowns": ["LLM 调用失败: ' + str(e) + '"]}'

    def _extract_json(self, text: str) -> str:
        """从 LLM 响应中提取 JSON"""
        # 尝试直接解析
        try:
            json.loads(text)
            return text
        except json.JSONDecodeError:
            pass

        # 尝试提取 ```json ... ``` 块
        import re
        m = re.search(r'```json\s*(.*?)\s*```', text, re.DOTALL)
        if m:
            return m.group(1)

        # 尝试提取 { ... }
        m = re.search(r'\{.*\}', text, re.DOTALL)
        if m:
            return m.group(0)

        return text


class SessionSplitter:
    """会话切分器 - 按时间间隔切分聊天记录为 session"""

    def __init__(self, gap_hours: float = 2.0):
        """
        gap_hours: 超过多少小时的间隔算作新 session
        """
        self.gap_hours = gap_hours

    def split(self, messages: List[Message]) -> List[List[Message]]:
        """将消息列表切分为多个 session"""
        if not messages:
            return []

        # 按时间排序
        sorted_msgs = sorted(messages, key=lambda m: m.timestamp)

        sessions = []
        current_session = [sorted_msgs[0]]

        for i in range(1, len(sorted_msgs)):
            prev = sorted_msgs[i-1]
            curr = sorted_msgs[i]

            gap = (curr.timestamp - prev.timestamp).total_seconds() / 3600

            if gap > self.gap_hours:
                sessions.append(current_session)
                current_session = [curr]
            else:
                current_session.append(curr)

        if current_session:
            sessions.append(current_session)

        return sessions
