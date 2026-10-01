# Architectural Design: Tmux Sessions, Bubble UX, Media Handling, and Full Localization

**Date**: 2026-10-01  
**Author**: Antigravity  
**Status**: Approved by User  
**Target Projects**:
- `agy-gateway` (`/root/agy-gateway`)
- `agy-android` (`/root/agy-android`)

---

## 1. Overview & Objectives

This specification defines the architecture, data contracts, and UI/UX behaviors for a comprehensive upgrade of the Antigravity mobile client and server gateway.

Key objectives:
1. **Tmux-First Session Architecture**: Run every active conversation in an isolated, named tmux session (`agy-<conv_id>`) on the Linux server. Expose session status, stop, rename, and cascade-deletion APIs in `agy-gateway`.
2. **Main Screen Session Control**: Show real-time active/inactive session indicators in `ChatsScreen`. Provide a Telegram-style `ModalBottomSheet` on long tap with Stop Session, Rename, and Delete actions.
3. **Interactive Bubble UX & Selection**:
   - Long-press context menu on messages (Telegram style): copy pure text; delete failed unsent messages from Outbox.
   - Native text selection (`SelectionContainer`) on agent responses.
   - Dedicated copy button in agent bubble header: copies pure Markdown (including formatted code blocks, bold, lists) while strictly excluding service blocks (`thinking`, `executed` tool cards, `modified` diffs).
   - Compound agent bubble: cleanly separate sequential responses from the same turn with timestamps and dividers.
4. **Interactive Media & Upload Progress**:
   - Immediate asynchronous upload upon attaching files in composer.
   - Circular progress indicator directly on attachment chips in composer; disable Send button until all files reach 100% upload completion.
   - Hide `view_file` calls and analyzed images inside the `Executed` spoiler (no duplicate inline images in chat).
   - Telegram-style click-to-load for agent-generated images: blurred placeholder with a centered circular download button.
5. **Full Localization (English & Russian)**:
   - Dynamic language switcher in Settings (Russian / English / System Default) backed by DataStore.
   - Clean extraction of 100% UI strings into `res/values/strings.xml` and `res/values-ru/strings.xml`.

---

## 2. Server Architecture: Gateway & Tmux (`agy-gateway`)

### 2.1 Tmux Session Lifecycle
- **Naming Pattern**: Each chat runs in an isolated tmux session named `agy-<conv_id>` (e.g. `agy-9f2a2ff6`).
- **Initialization**:
  - When `POST /v1/chats` or `POST /v1/chats/{id}/messages` is called:
  - Gateway checks `tmux has-session -t agy-<conv_id>`.
  - If not running, launches interactive session:
    `tmux new-session -d -s agy-<conv_id> -c <workspace> "<settings.agy_bin> --conversation <conv_id>"`
  - Prompt text is fed via `tmux load-buffer` + `tmux paste-buffer -p` + `Enter` (bracketed paste).
- **Session Liveness**:
  - Gateway inspects `tmux has-session -t agy-<conv_id>` and checks pane process state.
  - Returned in `ChatSummary.is_active` (`GET /v1/chats`).

### 2.2 New REST Endpoints
1. `POST /v1/chats/{id}/stop`:
   - Checks if `agy-<id>` exists in tmux.
   - If running: issues `tmux kill-session -t agy-<id>`.
   - Updates any active run for this conversation in `gateway.db` to status `interrupted`.
   - Returns `{ "status": "stopped", "conversation_id": id }`.
2. `PATCH /v1/chats/{id}`:
   - Request body: `{ "title": "New Title" }`.
   - Updates `summary` / `title` in `/root/.gemini/antigravity-cli/conversation_summaries.db` and `gateway.db`.
   - Broadcasts update event via WebSocket hub.
   - Returns `{ "status": "updated", "id": id, "title": "New Title" }`.
3. `DELETE /v1/chats/{id}`:
   - Terminates `agy-<id>` tmux session if active.
   - Deletes conversation entry from `conversation_summaries.db`.
   - Deletes records from `runs`, `events`, and `attachments` in `gateway.db`.
   - Recursively removes brain workspace `/root/.gemini/antigravity-cli/brain/<id>`.
   - Returns `{ "status": "deleted", "id": id }`.

---

## 3. Android Client Architecture (`agy-android`)

### 3.1 ChatsScreen: Session Status & Long-Press Bottom Sheet
- **UI Indicators**:
  - Active session: Green glowing indicator badge (`• Active` / `• Активна`).
  - Inactive: Muted badge or standard timestamp.
- **Telegram-style BottomSheet on Long Press**:
  - Header: Chat title + workspace path.
  - Actions:
    - ⏹ **Stop session** (visible only if `chat.isActive == true`). Triggers `POST /v1/chats/{id}/stop`.
    - ✏️ **Rename chat**. Opens text dialog; triggers `PATCH /v1/chats/{id}` and updates local Room entity.
    - 🗑 **Delete chat**. Red destructive action; triggers confirmation dialog; calls `DELETE /v1/chats/{id}` and deletes from Room DB.

### 3.2 ChatScreen: Bubble Context Menu, Text Selection & Clean Copy
- **User Bubble Long Press**:
  - Material 3 floating context menu / bottom sheet.
  - 📋 **Copy text**: copies message text stripped of service attachment tags (`[Изображение...]`, `[Голосовое...]`).
  - 🗑 **Delete**: visible ONLY when delivery status is `FAILED` (retry button active). Deletes message from Room Outbox.
- **Agent Bubble Interactions**:
  - Native selection: wrap markdown content in `SelectionContainer` so the user can select words, lines, or blocks with finger handles.
  - **Copy Markdown Button**: placed in top-right header area of agent bubble (adjacent to `Step` badge).
    - Extracts complete Markdown response with inline code blocks, bold/italic, lists, and tables.
    - Excludes `thinking` (accordion), `executed` tool cards, and `modified` diffs.
    - Puts markdown string onto system clipboard with toast confirmation.
- **Compound Response Turn Separation**:
  - When an agent produces multiple text outputs across sub-steps within a single turn, text segments are displayed within the compound card separated by a subtle divider with the exact timestamp of each subsequent response.

### 3.3 Attachments & Image Handling
- **Pre-upload with Circular Progress**:
  - When a file/image is picked, upload begins immediately via `CountingRequestBody` calling `POST /v1/attachments`.
  - Preview chip displays `CircularProgressIndicator` overlaid with current upload percentage.
  - Composer Send button remains disabled while any attachment is in `UPLOADING` state.
  - When all uploads reach `COMPLETED`, Send button activates.
- **Tool Artifact Hygiene (`view_file`)**:
  - `view_file` executions are strictly contained inside the collapsible `Executed` spoiler. They are never rendered as prominent inline media previews.
- **Telegram-style Click-to-Load for Agent Images**:
  - Agent-generated images (`generate_image`, file links) render as blurred/darkened placeholders.
  - Centered circular download button with download icon.
  - Tapping downloads/loads the image with a spinner; once loaded, full resolution image is displayed with click-to-preview support.

### 3.4 Full Localization (English & Russian)
- **Settings Screen**:
  - New preference setting: "App Language" / "Язык приложения".
  - Options: English, Русский, System Default.
  - Persisted in `PreferencesDataStore`; applied via Android `AppCompatDelegate.setApplicationLocales`.
- **String Resources**:
  - Zero hardcoded user-facing strings in Kotlin Compose files.
  - Comprehensive translations in `res/values/strings.xml` and `res/values-ru/strings.xml`.

---

## 4. Verification & Testing Strategy
- **Gateway Verification**:
  - Test session creation: verify `tmux has-session -t agy-<id>` returns 0.
  - Test stop endpoint: verify `tmux kill-session` executes and session disappears.
  - Test rename endpoint: verify database record updates.
  - Test delete endpoint: verify tmux killed, DB rows deleted, brain folder removed.
- **Android Verification**:
  - Compile and build debug APK with `./gradlew assembleDebug`.
  - Verify string resolution in both English and Russian locales.
  - Verify bubble context menu behavior for user and agent messages.
  - Verify attachment upload progress and send button gating.
