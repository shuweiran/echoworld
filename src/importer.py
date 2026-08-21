"""
Chat Profile Miner - 聊天记录导入器
支持微信/QQ/抖音/Telegram/飞书等格式，统一转为 JSONL。
"""
import re
import json
import hashlib
from datetime import datetime
from pathlib import Path
from typing import List, Generator
from schemas import Message, Platform, SpeakerRole, ContentType


class ChatImporter:
    """聊天记录导入基类"""

    def __init__(self, target_name: str, self_name: str = "我"):
        """
        target_name: 分析对象的名字（如"春杪"）
        self_name: 自己的名字（默认"我"）
        """
        self.target_name = target_name
        self.self_name = self_name

    def _make_message_id(self, text: str, timestamp: str, speaker: str) -> str:
        """生成消息ID"""
        raw = f"{timestamp}:{speaker}:{text}"
        return hashlib.sha256(raw.encode()).hexdigest()[:16]

    def _classify_speaker(self, speaker: str) -> SpeakerRole:
        """判断发送者角色"""
        if speaker == self.self_name or speaker == "我":
            return SpeakerRole.SELF
        elif speaker == self.target_name:
            return SpeakerRole.TARGET
        else:
            return SpeakerRole.OTHER

    def _guess_content_type(self, text: str) -> ContentType:
        """猜测内容类型"""
        if text.startswith("[图片]") or text.startswith("[image]"):
            return ContentType.IMAGE
        elif text.startswith("[视频]") or text.startswith("[video]"):
            return ContentType.VIDEO
        elif text.startswith("[语音]") or text.startswith("[voice]"):
            return ContentType.VOICE
        elif "http://" in text or "https://" in text:
            return ContentType.LINK
        elif text.startswith("[文件]") or text.startswith("[file]"):
            return ContentType.FILE
        elif text.startswith("[表情]") or text.startswith("[sticker]"):
            return ContentType.STICKER
        return ContentType.TEXT

    def import_file(self, file_path: str) -> List[Message]:
        """导入文件，返回统一格式消息列表"""
        raise NotImplementedError


class WeChatImporter(ChatImporter):
    """
    微信聊天记录导入器
    支持微信导出的 txt 格式：
    2026-05-13 22:16:32 春杪
    这个项链好好看

    也支持带昵称的格式：
    春杪 2026-05-13 22:16:32
    这个项链好好看
    """

    # 常见微信导出格式
    PATTERN_1 = re.compile(
        r'^(\d{4}-\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2})\s+(.+?)$'
    )
    PATTERN_2 = re.compile(
        r'^(.+?)\s+(\d{4}-\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2})$'
    )

    def import_file(self, file_path: str) -> List[Message]:
        path = Path(file_path)
        messages = []
        current_speaker = None
        current_time = None
        current_lines = []
        offset = 0

        with open(path, 'r', encoding='utf-8', errors='ignore') as f:
            for line_num, line in enumerate(f):
                line = line.rstrip('\n')
                raw_offset = offset
                offset += len(line) + 1

                # 尝试匹配时间戳行
                m1 = self.PATTERN_1.match(line)
                m2 = self.PATTERN_2.match(line)

                if m1 or m2:
                    # 保存上一条消息
                    if current_speaker and current_lines:
                        text = '\n'.join(current_lines).strip()
                        if text:
                            messages.append(self._build_message(
                                current_speaker, current_time, text,
                                str(path), raw_offset
                            ))

                    if m1:
                        current_time = m1.group(1)
                        current_speaker = m1.group(2).strip()
                    else:
                        current_speaker = m2.group(1).strip()
                        current_time = m2.group(2)
                    current_lines = []
                else:
                    if line.strip():
                        current_lines.append(line)

        # 最后一条
        if current_speaker and current_lines:
            text = '\n'.join(current_lines).strip()
            if text:
                messages.append(self._build_message(
                    current_speaker, current_time, text,
                    str(path), offset
                ))

        return messages

    def _build_message(self, speaker: str, time_str: str,
                       text: str, source_file: str, offset: int) -> Message:
        try:
            ts = datetime.strptime(time_str, "%Y-%m-%d %H:%M:%S")
        except ValueError:
            ts = datetime.now()

        return Message(
            message_id=self._make_message_id(text, time_str, speaker),
            platform=Platform.WECHAT,
            conversation_id="default",
            timestamp=ts,
            speaker=speaker,
            speaker_role=self._classify_speaker(speaker),
            content_type=self._guess_content_type(text),
            text=text,
            source_file=source_file,
            source_offset=offset,
        )


class DouyinImporter(ChatImporter):
    """
    抖音聊天记录导入器
    抖音导出格式通常为：
    [2026-05-13 22:16] 春杪: 这个项链好好看
    """

    PATTERN = re.compile(
        r'^\[(\d{4}-\d{2}-\d{2}\s+\d{2}:\d{2})\]\s+(.+?)[:：]\s+(.+)$'
    )

    def import_file(self, file_path: str) -> List[Message]:
        path = Path(file_path)
        messages = []
        offset = 0

        with open(path, 'r', encoding='utf-8', errors='ignore') as f:
            for line in f:
                line = line.rstrip('\n')
                raw_offset = offset
                offset += len(line) + 1

                m = self.PATTERN.match(line)
                if m:
                    time_str, speaker, text = m.groups()
                    messages.append(self._build_message(
                        speaker.strip(), time_str, text.strip(),
                        str(path), raw_offset
                    ))

        return messages

    def _build_message(self, speaker: str, time_str: str,
                       text: str, source_file: str, offset: int) -> Message:
        try:
            ts = datetime.strptime(time_str, "%Y-%m-%d %H:%M")
        except ValueError:
            ts = datetime.now()

        return Message(
            message_id=self._make_message_id(text, time_str, speaker),
            platform=Platform.DOUYIN,
            conversation_id="default",
            timestamp=ts,
            speaker=speaker,
            speaker_role=self._classify_speaker(speaker),
            content_type=self._guess_content_type(text),
            text=text,
            source_file=source_file,
            source_offset=offset,
        )


class PlainTextImporter(ChatImporter):
    """
    通用纯文本导入器
    格式：每行一条，自动检测发送者
    支持：
    - "我: xxx" / "她: xxx"
    - "【我】xxx" / "【她】xxx"
    - "[我] xxx" / "[她] xxx"
    """

    PATTERNS = [
        re.compile(r'^(.+?)[:：]\s*(.+)$'),
        re.compile(r'^【(.+?)】\s*(.+)$'),
        re.compile(r'^\[(.+?)\]\s*(.+)$'),
    ]

    def import_file(self, file_path: str) -> List[Message]:
        path = Path(file_path)
        messages = []
        offset = 0

        with open(path, 'r', encoding='utf-8', errors='ignore') as f:
            for line in f:
                line = line.rstrip('\n')
                raw_offset = offset
                offset += len(line) + 1
                if not line.strip():
                    continue

                for pattern in self.PATTERNS:
                    m = pattern.match(line)
                    if m:
                        speaker, text = m.groups()
                        messages.append(Message(
                            message_id=self._make_message_id(
                                text, str(raw_offset), speaker),
                            platform=Platform.UNKNOWN,
                            conversation_id="default",
                            timestamp=datetime.now(),
                            speaker=speaker.strip(),
                            speaker_role=self._classify_speaker(speaker.strip()),
                            content_type=self._guess_content_type(text),
                            text=text.strip(),
                            source_file=str(path),
                            source_offset=raw_offset,
                        ))
                        break

        return messages


class ClipboardImporter(ChatImporter):
    """直接粘贴文本导入"""

    def import_text(self, text: str, platform: Platform = Platform.UNKNOWN) -> List[Message]:
        """从粘贴的文本导入"""
        messages = []
        lines = text.strip().split('\n')

        for i, line in enumerate(lines):
            line = line.strip()
            if not line:
                continue

            # 尝试多种格式
            for pattern in PlainTextImporter.PATTERNS:
                m = pattern.match(line)
                if m:
                    speaker, content = m.groups()
                    messages.append(Message(
                        message_id=self._make_message_id(
                            content, str(i), speaker),
                        platform=platform,
                        conversation_id="clipboard",
                        timestamp=datetime.now(),
                        speaker=speaker.strip(),
                        speaker_role=self._classify_speaker(speaker.strip()),
                        content_type=self._guess_content_type(content),
                        text=content.strip(),
                        source_file="clipboard",
                        source_offset=i,
                    ))
                    break

        return messages


def export_jsonl(messages: List[Message], output_path: str):
    """导出为 JSONL 格式"""
    with open(output_path, 'w', encoding='utf-8') as f:
        for msg in messages:
            f.write(msg.model_dump_json() + '\n')


def import_jsonl(input_path: str) -> List[Message]:
    """从 JSONL 导入"""
    messages = []
    with open(input_path, 'r', encoding='utf-8') as f:
        for line in f:
            if line.strip():
                messages.append(Message.model_validate_json(line))
    return messages

