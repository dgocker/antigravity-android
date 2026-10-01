# Tmux Sessions, Bubble UX, Media Handling, and Full Localization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement isolated per-chat tmux sessions with lifecycle controls (stop, rename, cascade-delete) on the Gateway, build Telegram-style bubble menus, text selection, clean markdown copy, immediate circular upload progress, click-to-load agent images, and full English/Russian localization across the Android application.

**Architecture:** 
- Gateway spawns and supervises dedicated tmux sessions (`agy-<conv_id>`) for each chat, reporting session liveness (`is_active`) and exposing `stop`, `PATCH`, and `DELETE` endpoints.
- Android `ChatsScreen` displays live status indicators and offers a Telegram-style `ModalBottomSheet` on long press.
- Android `ChatScreen` integrates message long-press menus, `SelectionContainer` for native touch selection, a header copy button for clean markdown (excluding thinking/executed/diffs), and sequential turn dividers.
- Immediate background file uploads with circular progress bars gate the composer Send button, while agent images feature Telegram-style blurred placeholders with centered download buttons.
- App-wide localization is managed via `AppCompatDelegate.setApplicationLocales` backed by `DataStore`, replacing hardcoded text with `res/values/strings.xml` and `res/values-ru/strings.xml`.

**Tech Stack:** Python 3.12, FastAPI, Tmux, SQLite, Kotlin, Jetpack Compose Material 3, Room, Coil, Coroutines, Flow, DataStore, Gradle.

**Spec:** `/root/agy-android/docs/superpowers/specs/2026-10-01-ux-tmux-media-localization-design.md`

## Global Constraints
- Target Projects: `/root/agy-gateway` and `/root/agy-android`.
- Never break existing WebSocket protocol messages or token authentication.
- Never hardcode user-facing strings; all UI strings must use Android string resources.
- Tmux sessions must strictly follow the naming pattern `agy-<conv_id>`.

---

### Task 1: Gateway Tmux Session Lifecycle Management & REST Endpoints

**Files:**
- Modify: `/root/agy-gateway/app/tmux_injector.py`
- Modify: `/root/agy-gateway/app/models.py`
- Modify: `/root/agy-gateway/app/main.py`
- Test: `/root/agy-gateway/test_tmux_lifecycle.py`

**Interfaces:**
- Produces:
  - `POST /v1/chats/{id}/stop` -> `{"status": "stopped", "conversation_id": id}`
  - `PATCH /v1/chats/{id}` -> `{"status": "updated", "id": id, "title": str}`
  - `DELETE /v1/chats/{id}` -> `{"status": "deleted", "id": id}`
  - `ChatSummary.is_active: bool` in `GET /v1/chats`

- [ ] **Step 1: Write test for tmux lifecycle, stop, rename, delete**

Create `/root/agy-gateway/test_tmux_lifecycle.py`:
```python
import os
import sqlite3
import subprocess
import requests

BASE_URL = "http://127.0.0.1:8765"
TOKEN = os.environ.get("AUTH_TOKEN", "dcccd5fc9865ba223bc9e138f776b5b577121c2c1d108df3d57d09956fc1a348")
HEADERS = {"Authorization": f"Bearer {TOKEN}"}

def test_tmux_lifecycle():
    # 1. Check list chats returns is_active field
    resp = requests.get(f"{BASE_URL}/v1/chats", headers=HEADERS)
    assert resp.status_code == 200, resp.text
    chats = resp.json()
    assert isinstance(chats, list)
    if chats:
        assert "is_active" in chats[0]
        conv_id = chats[0]["id"]
        # 2. Test PATCH rename
        patch_resp = requests.patch(f"{BASE_URL}/v1/chats/{conv_id}", json={"title": "Test Renamed Chat"}, headers=HEADERS)
        assert patch_resp.status_code == 200, patch_resp.text
        assert patch_resp.json()["title"] == "Test Renamed Chat"
        # 3. Test POST stop
        stop_resp = requests.post(f"{BASE_URL}/v1/chats/{conv_id}/stop", headers=HEADERS)
        assert stop_resp.status_code == 200, stop_resp.text
    print("Tmux lifecycle test passed!")

if __name__ == "__main__":
    test_tmux_lifecycle()
```

- [ ] **Step 2: Run test to verify it fails**

Run: `python3 /root/agy-gateway/test_tmux_lifecycle.py`  
Expected: FAIL (404 or 405 on PATCH/stop, or missing is_active).

- [ ] **Step 3: Implement tmux session helpers in `tmux_injector.py`**

In `/root/agy-gateway/app/tmux_injector.py`:
- Add `get_chat_session_name(conv_id: str) -> str`: returns `f"agy-{conv_id[:8]}"` or `f"agy-{conv_id}"`.
- Add `is_chat_session_running(conv_id: str) -> bool`: checks `tmux has-session -t agy-<conv_id>`.
- Add `ensure_chat_session(conv_id: str, workspace: str) -> None`: if not running, launches `tmux new-session -d -s agy-<conv_id> -c <workspace> "<settings.agy_bin> --conversation <conv_id>"`.
- Add `kill_chat_session(conv_id: str) -> bool`: executes `tmux kill-session -t agy-<conv_id>`.

- [ ] **Step 4: Update models & add endpoints in `main.py`**

In `/root/agy-gateway/app/models.py`:
- Add `is_active: bool = False` to `ChatSummary`.
- Add `UpdateChatTitleRequest(BaseModel): title: str`.
- Add `ChatActionResponse(BaseModel): status: str, id: Optional[str] = None, title: Optional[str] = None`.

In `/root/agy-gateway/app/main.py`:
- In `list_chats()`: populate `is_active = is_chat_session_running(conv_id)`.
- In `create_chat()` and `send_message()`: ensure session is spawned in tmux if not running, and inject via tmux buffer.
- Add `@app.post("/v1/chats/{id}/stop")`: kill tmux session, mark runs interrupted.
- Add `@app.patch("/v1/chats/{id}")`: update title in `conversation_summaries.db` and `gateway.db`.
- Add `@app.delete("/v1/chats/{id}")`: kill tmux session, delete from DBs, remove brain dir.

- [ ] **Step 5: Run test to verify it passes**

Run: `python3 /root/agy-gateway/test_tmux_lifecycle.py`  
Expected: PASS with 200 OK.

- [ ] **Step 6: Commit**

```bash
cd /root/agy-gateway
git add app/tmux_injector.py app/models.py app/main.py
git commit -m "feat(gateway): add per-chat tmux session lifecycle, stop, rename, delete endpoints"
```

---

### Task 2: Android Gateway API, DTOs & Repository Layer

**Files:**
- Modify: `/root/agy-android/app/src/main/java/com/antigravity/client/data/remote/dto/GatewayDtos.kt`
- Modify: `/root/agy-android/app/src/main/java/com/antigravity/client/data/remote/GatewayApi.kt`
- Modify: `/root/agy-android/app/src/main/java/com/antigravity/client/data/local/Entities.kt`
- Modify: `/root/agy-android/app/src/main/java/com/antigravity/client/data/local/Daos.kt`
- Modify: `/root/agy-android/app/src/main/java/com/antigravity/client/data/repository/ChatRepository.kt`

**Interfaces:**
- Produces:
  - `ChatSummary.isActive: Boolean`
  - `ChatRepository.stopSession(chatId: String): Result<Unit>`
  - `ChatRepository.renameChat(chatId: String, newTitle: String): Result<Unit>`
  - `ChatRepository.deleteChat(chatId: String): Result<Unit>`

- [ ] **Step 1: Update DTOs and Room Entities**

In `GatewayDtos.kt`:
- Add `val isActive: Boolean = false` to `ChatSummaryDto`.
- Add `data class UpdateTitleRequest(val title: String)`.
- Add `data class ChatActionResponse(val status: String, val id: String? = null, val title: String? = null)`.

In `Entities.kt`:
- Add `val isActive: Boolean = false` to `ChatSummaryEntity`.

In `Daos.kt`:
- Add `@Query("UPDATE chat_summaries SET title = :newTitle WHERE id = :chatId") suspend fun updateTitle(chatId: String, newTitle: String)`.
- Add `@Query("UPDATE chat_summaries SET isActive = :isActive WHERE id = :chatId") suspend fun updateActiveStatus(chatId: String, isActive: Boolean)`.
- Add `@Query("DELETE FROM chat_summaries WHERE id = :chatId") suspend fun deleteChatById(chatId: String)`.

- [ ] **Step 2: Update GatewayApi with REST endpoints**

In `GatewayApi.kt`:
```kotlin
@POST("v1/chats/{id}/stop")
suspend fun stopChat(@Path("id") id: String): Response<ChatActionResponse>

@PATCH("v1/chats/{id}")
suspend fun updateChatTitle(
    @Path("id") id: String,
    @Body req: UpdateTitleRequest
): Response<ChatActionResponse>

@DELETE("v1/chats/{id}")
suspend fun deleteChat(@Path("id") id: String): Response<ChatActionResponse>
```

- [ ] **Step 3: Implement operations in `ChatRepository.kt`**

Add implementations in `ChatRepository.kt`:
- `suspend fun stopSession(chatId: String): Result<Unit>`
- `suspend fun renameChat(chatId: String, newTitle: String): Result<Unit>`
- `suspend fun deleteChat(chatId: String): Result<Unit>` (also delete messages, attachments, runs from Room)

- [ ] **Step 4: Verify Compilation**

Run: `cd /root/agy-android && ./gradlew compileDebugKotlin`  
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
cd /root/agy-android
git add app/src/main/java/com/antigravity/client/data/
git commit -m "feat(data): add stop, rename, delete chat API and isActive status mapping"
```

---

### Task 3: Android ChatsScreen UI & Long-Press BottomSheet

**Files:**
- Modify: `/root/agy-android/app/src/main/java/com/antigravity/client/ui/chats/ChatsViewModel.kt`
- Modify: `/root/agy-android/app/src/main/java/com/antigravity/client/ui/chats/ChatsScreen.kt`

**Interfaces:**
- Produces:
  - Live active indicator badge on chat items.
  - Telegram-style `ModalBottomSheet` on long click with:
    - Stop session (if active)
    - Rename session
    - Delete chat (with confirmation dialog)

- [ ] **Step 1: Add actions to `ChatsViewModel.kt`**

In `ChatsViewModel.kt`:
- Add `fun stopSession(chatId: String)`: calls `repository.stopSession(chatId)` and refreshes list.
- Add `fun renameChat(chatId: String, newTitle: String)`: calls `repository.renameChat(chatId, newTitle)`.
- Add `fun deleteChat(chatId: String)`: calls `repository.deleteChat(chatId)`.

- [ ] **Step 2: Add Active status badge and long-click handler in `ChatsScreen.kt`**

In `ChatItem`:
- Combine clickable with `combinedClickable(onClick = { onChatClick(chat.id) }, onLongClick = { onLongClick(chat) })`.
- If `chat.isActive`: render animated pulsating green dot `Box(modifier = Modifier.size(8.dp).background(AccentGreen, CircleShape))` next to title/timestamp.

- [ ] **Step 3: Implement Telegram-style ModalBottomSheet in `ChatsScreen.kt`**

- When a chat is selected on long-press, open `ModalBottomSheet`:
  - Show chat title and workspace.
  - Button "Остановить сессию" (visible if `chat.isActive`), icon `AppIcons.Stop`.
  - Button "Переименовать", icon `AppIcons.Edit`. Opens text edit dialog.
  - Button "Удалить чат", icon `AppIcons.Delete`, colored in `ErrorRed`.
  - Confirmation Dialog before deletion: "Удалить чат и все данные на сервере?".

- [ ] **Step 4: Verify Compilation & Behavior**

Run: `cd /root/agy-android && ./gradlew compileDebugKotlin`  
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
cd /root/agy-android
git add app/src/main/java/com/antigravity/client/ui/chats/
git commit -m "feat(ui): add active session indicators and Telegram-style chat context sheet"
```

---

### Task 4: Android Chat Bubble Context Menu, Text Selection & Markdown Copy

**Files:**
- Modify: `/root/agy-android/app/src/main/java/com/antigravity/client/ui/chat/ChatScreen.kt`
- Modify: `/root/agy-android/app/src/main/java/com/antigravity/client/ui/chat/ChatViewModel.kt`

**Interfaces:**
- Produces:
  - Long press context menu on user message (Copy clean text, Delete if FAILED).
  - `SelectionContainer` around agent markdown text for finger selection.
  - Header copy button in agent bubble copying complete Markdown (excluding thinking/executed/modified).
  - Subtle divider and timestamp for multiple responses within a turn.

- [ ] **Step 1: Implement user bubble context menu**

In `ChatScreen.kt`:
- Add long-press gesture detector on `UserBubble`.
- Display Telegram-style context popup / sheet:
  - "Скопировать текст": regex strips `\[(Изображение|Голосовое|Вложение):.*?\]` and copies pure text to clipboard.
  - "Удалить": displayed ONLY if `bubble.deliveryStatus == MessageDeliveryStatus.FAILED`.
  - In `ChatViewModel`: add `fun deleteOutboxMessage(clientMessageId: String)`.

- [ ] **Step 2: Enable SelectionContainer & add Markdown Copy button to AgentBubble**

In `AgentBubble`:
- Wrap `MarkdownText` in `androidx.compose.foundation.text.selection.SelectionContainer`.
- In the top header row of the agent bubble (next to `Step` badge):
  - Add an IconButton with `AppIcons.Copy` (or content copy icon).
  - On click: copy `bubble.messageText` directly to `LocalClipboardManager`.
  - Ensure `bubble.messageText` preserves all code blocks and markdown formatting, without including `thinking`, `toolCalls`, or file diffs.
  - Show Toast: `context.getString(R.string.copied_to_clipboard)`.

- [ ] **Step 3: Implement sequential response separators with timestamp**

In `AgentBubble`:
- If the turn contains multiple distinct text responses separated by tool executions:
  - Render an elegant subtle horizontal divider:
    `Divider(color = DarkBorder.copy(alpha = 0.5f), thickness = 0.5.dp)`
    with a small timestamp label (`14:23:45`) indicating when the subsequent text segment was produced.

- [ ] **Step 4: Verify Compilation**

Run: `cd /root/agy-android && ./gradlew compileDebugKotlin`  
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
cd /root/agy-android
git add app/src/main/java/com/antigravity/client/ui/chat/
git commit -m "feat(ui): add bubble context menu, native text selection, and clean markdown copy"
```

---

### Task 5: Immediate Uploads with Circular Progress & Telegram-style Agent Images

**Files:**
- Modify: `/root/agy-android/app/src/main/java/com/antigravity/client/ui/components/AttachmentPreviewBar.kt`
- Modify: `/root/agy-android/app/src/main/java/com/antigravity/client/ui/components/TelegramComposer.kt`
- Modify: `/root/agy-android/app/src/main/java/com/antigravity/client/ui/chat/ChatViewModel.kt`
- Modify: `/root/agy-android/app/src/main/java/com/antigravity/client/ui/chat/ChatScreen.kt`

**Interfaces:**
- Produces:
  - Asynchronous upload upon attachment selection with circular percentage progress.
  - Disabled Send button while uploads are active.
  - Clean `Executed` spoiler for `view_file` calls.
  - Telegram click-to-load card for agent images.

- [ ] **Step 1: Start immediate background upload on file selection**

In `ChatViewModel.kt`:
- When user attaches a file/image (`addAttachment`):
  - Create `Attachment` in state `AttachmentUploadState.UPLOADING`.
  - Launch coroutine calling `repository.uploadAttachment` with progress callback.
  - Update `attachment.uploadProgress` (0.0f -> 1.0f) and `AttachmentUploadState.COMPLETED`.
  - Expose `val hasActiveUploads: Boolean = attachments.any { it.uploadState == AttachmentUploadState.UPLOADING }`.

- [ ] **Step 2: Update AttachmentPreviewChip & TelegramComposer**

In `AttachmentPreviewBar.kt`:
- On the preview chip: display `CircularProgressIndicator(progress = { attachment.uploadProgress })` with percentage text in the center.
- In `TelegramComposer.kt`:
  - Disable Send button (`enabled = text.isNotBlank() && !hasActiveUploads`).

- [ ] **Step 3: Hide `view_file` and implement click-to-load for agent images**

In `ChatScreen.kt`:
- In `extractAttachmentsFromBubble`: remove `"view_file"` from adding image attachments. The `view_file` card stays inside `Executed` tools list.
- In `AgentBubble` image rendering:
  - If image is not yet loaded in session: show blurred box with download icon in center circle.
  - On click: load image with fade-in animation and allow full-screen preview.

- [ ] **Step 4: Verify Compilation**

Run: `cd /root/agy-android && ./gradlew compileDebugKotlin`  
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
cd /root/agy-android
git add app/src/main/java/com/antigravity/client/ui/
git commit -m "feat(media): implement circular upload progress and telegram click-to-load images"
```

---

### Task 6: Full App Localization (English & Russian)

**Files:**
- Create/Modify: `/root/agy-android/app/src/main/res/values/strings.xml`
- Create/Modify: `/root/agy-android/app/src/main/res/values-ru/strings.xml`
- Modify: `/root/agy-android/app/src/main/java/com/antigravity/client/ui/settings/SettingsScreen.kt`
- Modify: `/root/agy-android/app/src/main/java/com/antigravity/client/ui/settings/SettingsViewModel.kt`
- Modify: `/root/agy-android/app/src/main/java/com/antigravity/client/MainActivity.kt`

**Interfaces:**
- Produces:
  - Dynamic language selector: Russian, English, System.
  - Zero hardcoded English/Russian hybrid strings across all screens.

- [ ] **Step 1: Create comprehensive `strings.xml` (English) and `values-ru/strings.xml` (Russian)**

Define all keys:
- App name, connection strings (server URL, device token, connect, reconnecting, connected, disconnected).
- Chats screen (active session, stop session, rename, delete, confirm delete, search).
- Chat screen (input placeholder, attach file, photo, mic, cancel, retry, copy, deleted).
- Spoilers (Thinking, Executed, Modified, Step, Running).
- Settings (Model, Language, Theme, English, Russian, System Default).

- [ ] **Step 2: Add Language Switcher in Settings**

In `SettingsViewModel.kt`:
- Persist `language: String` ("en", "ru", "system") in DataStore.
- In `MainActivity.kt`: apply `AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(lang))`.

- [ ] **Step 3: Replace all hardcoded strings across all screens**

Refactor strings in `ChatScreen.kt`, `ChatsScreen.kt`, `SettingsScreen.kt`, `ConnectionScreen.kt`, `ToolCallCard.kt`, `FilesScreen.kt` to use `stringResource(R.string...)`.

- [ ] **Step 4: Verify Compilation in both locales**

Run: `cd /root/agy-android && ./gradlew compileDebugKotlin`  
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
cd /root/agy-android
git add app/src/main/res/ app/src/main/java/
git commit -m "feat(i18n): implement full English and Russian localization and language selector"
```

---

### Task 7: End-to-End Build & Release Verification

**Files:**
- Verify all changes across `agy-gateway` and `agy-android`.
- Build release/debug APK.

- [ ] **Step 1: Run gateway automated tests**

Run: `python3 /root/agy-gateway/test_tmux_lifecycle.py`  
Expected: PASS.

- [ ] **Step 2: Assemble Android Debug APK**

Run: `cd /root/agy-android && ./gradlew assembleDebug`  
Expected: BUILD SUCCESSFUL, generating APK in `app/build/outputs/apk/debug/app-debug.apk`.

- [ ] **Step 3: Verify systemd service status**

Run: `systemctl restart agy-gateway && systemctl is-active agy-gateway`  
Expected: `active`.

- [ ] **Step 4: Commit & Tag**

```bash
cd /root/agy-android
git commit -am "chore(release): bump version and finalize tmux, media, and localization features"
```
