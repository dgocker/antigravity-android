from __future__ import annotations

import json
import re
from typing import Any, Optional
from app.db import get_attachment
from app.models import AttachmentRef, CodeDiff, NormalizedStep, ToolCall

USER_REQUEST_RE = re.compile(r"<USER_REQUEST>\s*(.*?)\s*</USER_REQUEST>", re.DOTALL)
VOICE_ATTACH_RE = re.compile(r"\[Голосовое сообщение:\s*(?P<path>[^ ]+)\s*\((?P<mime>[^,\)]+)(?:,\s*(?P<dur>\d+)s)?\)\](?:\s*\nРасшифровка аудио:\s*\"(?P<trans>[^\"]*)\")?", re.MULTILINE)
IMAGE_ATTACH_RE = re.compile(r"\[Изображение:\s*(?P<path>[^ ]+)\s*\((?P<mime>[^,]+),\s*(?P<size>\d+)\s*KB\)\]", re.MULTILINE)
VIDEO_ATTACH_RE = re.compile(r"\[Видео:\s*(?P<path>[^ ]+)\s*\((?P<mime>[^,]+),\s*(?P<size>\d+)\s*KB\)\]", re.MULTILINE)
FILE_ATTACH_RE = re.compile(r"\[Вложение:\s*(?P<name>[^ ]+)\s*\((?P<path>[^,]+),\s*(?P<size>\d+)\s*KB\)\]", re.MULTILINE)

def extract_clean_user_prompt(content: Optional[str]) -> Optional[str]:
    if not content:
        return None
    # Strip <ADDITIONAL_METADATA>...</ADDITIONAL_METADATA>
    text = re.sub(r"<ADDITIONAL_METADATA>.*?</ADDITIONAL_METADATA>", "", content, flags=re.DOTALL).strip()
    match = USER_REQUEST_RE.search(text)
    if match:
        text = match.group(1).strip()
    return text

def extract_diffs_from_tool_calls(tool_calls: list[dict[str, Any]]) -> list[CodeDiff]:
    diffs: list[CodeDiff] = []
    for tc in tool_calls:
        name = tc.get("name", "")
        args = tc.get("args") or {}
        if not isinstance(args, dict):
            continue

        if name == "write_to_file":
            target_file = args.get("TargetFile", "")
            is_append = bool(args.get("Append", False))
            is_overwrite = bool(args.get("Overwrite", False))
            action = "append" if is_append else ("overwrite" if is_overwrite else "create")
            diffs.append(
                CodeDiff(
                    file=target_file,
                    action=action,
                    replacement_content=args.get("CodeContent"),
                    instruction=args.get("Description"),
                )
            )
        elif name == "replace_file_content":
            target_file = args.get("TargetFile", "")
            diffs.append(
                CodeDiff(
                    file=target_file,
                    action="replace",
                    start_line=args.get("StartLine"),
                    end_line=args.get("EndLine"),
                    target_content=args.get("TargetContent"),
                    replacement_content=args.get("ReplacementContent"),
                    instruction=args.get("Instruction"),
                )
            )
        elif name == "multi_replace_file_content":
            target_file = args.get("TargetFile", "")
            chunks = args.get("ReplacementChunks", [])
            diffs.append(
                CodeDiff(
                    file=target_file,
                    action="multi_replace",
                    replacement_content=json.dumps(chunks, ensure_ascii=False) if isinstance(chunks, (list, dict)) else str(chunks),
                    instruction=args.get("Instruction"),
                )
            )
    return diffs

def parse_transcript_line_to_step(raw_line: str) -> Optional[NormalizedStep]:
    raw_line = raw_line.strip()
    if not raw_line:
        return None
    try:
        data = json.loads(raw_line, strict=False)
    except Exception:
        try:
            data = json.loads(re.sub(r'\\([^"\\/bfnrtu])', r'\1', raw_line), strict=False)
        except Exception:
            return None

    step_index = data.get("step_index", 0)
    source = data.get("source", "SYSTEM")
    step_type = data.get("type", "GENERIC")
    status = data.get("status", "DONE")
    created_at = data.get("created_at", "")
    thinking = data.get("thinking")
    content = data.get("content")

    # Filter out internal checkpoints and compaction summaries
    if step_type in ("CHECKPOINT", "SYSTEM"):
        return None
    if source == "SYSTEM" and step_type != "USER_INPUT":
        return None
    if content and isinstance(content, str):
        if (
            content.startswith("<SYSTEM_MESSAGE>")
            or content.startswith("[Notice] All your subagents")
            or content.startswith("# Resuming from a compaction")
            or "<CONTEXT_SUMMARY>" in content
        ):
            return None

    error = data.get("error")
    if isinstance(error, dict):
        error = json.dumps(error, ensure_ascii=False)
    elif error is not None:
        error = str(error)

    attachments: list[AttachmentRef] = []

    # 1. Parse media from transcript schema if present
    for m in data.get("media", []) if isinstance(data.get("media"), list) else []:
        if isinstance(m, dict):
            uri = m.get("uri", "")
            mime = m.get("mime_type", "application/octet-stream")
            fname = uri.split("/")[-1] if "/" in uri else "media"
            mtype = "image" if mime.startswith("image/") else ("video" if mime.startswith("video/") else ("audio" if mime.startswith("audio/") else "document"))
            attachments.append(AttachmentRef(
                id=uri,
                type=mtype,
                file_name=fname,
                mime_type=mime,
                size=0,
                server_path=uri
            ))

    user_prompt = None
    if step_type == "USER_INPUT":
        user_prompt = extract_clean_user_prompt(content)
        if user_prompt:
            # Extract voice notes
            for match in VOICE_ATTACH_RE.finditer(user_prompt):
                p = match.group("path")
                mime = match.group("mime")
                dur = int(match.group("dur")) if match.group("dur") else None
                trans = match.group("trans")
                if not trans:
                    try:
                        rec = get_attachment(p)
                        if rec and rec.get("transcription"):
                            trans = rec["transcription"]
                    except Exception:
                        pass
                attachments.append(AttachmentRef(
                    id=p,
                    type="audio",
                    file_name=p.split("/")[-1],
                    mime_type=mime,
                    size=0,
                    duration=dur,
                    server_path=p,
                    transcription=trans
                ))
            # Extract images
            for match in IMAGE_ATTACH_RE.finditer(user_prompt):
                p = match.group("path")
                mime = match.group("mime")
                sz = int(match.group("size")) * 1024 if match.group("size") else 0
                attachments.append(AttachmentRef(
                    id=p,
                    type="image",
                    file_name=p.split("/")[-1],
                    mime_type=mime,
                    size=sz,
                    server_path=p
                ))
            # Extract videos
            for match in VIDEO_ATTACH_RE.finditer(user_prompt):
                p = match.group("path")
                mime = match.group("mime")
                sz = int(match.group("size")) * 1024 if match.group("size") else 0
                attachments.append(AttachmentRef(
                    id=p,
                    type="video",
                    file_name=p.split("/")[-1],
                    mime_type=mime,
                    size=sz,
                    server_path=p
                ))
            # Extract other files
            for match in FILE_ATTACH_RE.finditer(user_prompt):
                nm = match.group("name")
                p = match.group("path")
                sz = int(match.group("size")) * 1024 if match.group("size") else 0
                attachments.append(AttachmentRef(
                    id=p,
                    type="document",
                    file_name=nm,
                    mime_type="application/octet-stream",
                    size=sz,
                    server_path=p
                ))
            
            # Clean user_prompt from attachment headers for display
            clean_text = VOICE_ATTACH_RE.sub("", user_prompt)
            clean_text = IMAGE_ATTACH_RE.sub("", clean_text)
            clean_text = VIDEO_ATTACH_RE.sub("", clean_text)
            clean_text = FILE_ATTACH_RE.sub("", clean_text).strip()
            user_prompt = clean_text
            content = clean_text

    raw_media = data.get("media", [])
    if isinstance(raw_media, list):
        for m in raw_media:
            if isinstance(m, dict):
                uri = m.get("uri", "")
                mime = m.get("mime_type", "application/octet-stream")
                clean_p = uri.removeprefix("file://")
                fn = clean_p.split("/")[-1]
                m_type = "image" if mime.startswith("image/") else ("video" if mime.startswith("video/") else "document")
                attachments.append(AttachmentRef(
                    id=clean_p,
                    type=m_type,
                    file_name=fn,
                    mime_type=mime,
                    size=0,
                    server_path=clean_p
                ))

    raw_tool_calls = data.get("tool_calls", [])
    tool_calls: list[ToolCall] = []
    if isinstance(raw_tool_calls, list):
        for tc in raw_tool_calls:
            if isinstance(tc, dict):
                tool_calls.append(
                    ToolCall(
                        name=tc.get("name", ""),
                        args=tc.get("args") if isinstance(tc.get("args"), dict) else {},
                    )
                )

    diffs = extract_diffs_from_tool_calls(raw_tool_calls if isinstance(raw_tool_calls, list) else [])

    return NormalizedStep(
        step_index=step_index,
        source=source,
        type=step_type,
        status=status,
        created_at=created_at,
        thinking=thinking,
        content=content,
        user_prompt=user_prompt,
        tool_calls=tool_calls,
        diffs=diffs,
        attachments=attachments,
        error=error,
    )
