# Antigravity Gateway API Reference (v1)

Спецификация серверного шлюза `agy-gateway` поверх Antigravity CLI (`agy`) для разработчиков мобильного приложения под Android.

---

## 1. Базовые параметры и подключение

* **Базовый HTTP URL:** `http://127.0.0.1:8765` (строго локальный интерфейс)
* **WebSocket URL:** `ws://127.0.0.1:8765/v1/ws`
* **Формат данных:** `application/json` (UTF-8)
* **Сетевой транспорт:** HTTP/1.1 и WebSocket

### Аутентификация
Все эндпоинты (HTTP и WebSocket) защищены Bearer-токеном. Сравнение токена выполняется в константном времени (`secrets.compare_digest`), предотвращая timing attacks.

* **HTTP запросы:** Заголовок `Authorization: Bearer <AUTH_TOKEN>`
* **WebSocket:** Параметр строки запроса `?token=<AUTH_TOKEN>` или стандартный HTTP-заголовок `Authorization: Bearer <AUTH_TOKEN>` при рукопожатии.
* **Ошибка аутентификации:** При отсутствии или неверном токене сервер немедленно возвращает `HTTP 401 Unauthorized` (для WebSocket рукопожатие прерывается со статусом 401).

---

## 2. Архитектура событий и сравнение источников данных

### 2.1. Исследование `stream-json` vs `transcript_full.jsonl`
Практические тесты показали, что нативный вывод `stream-json` бинарника `agy` оптимизирован для консольного терминала и имеет ограничения:
1. **Рассуждения (Thinking / Chain-of-Thought):** в `stream-json` выдаются только счетчики `thinking_tokens` в объекте `usage`. Текстовое тело рассуждений модели (`thinking`) полностью отсутствует.
2. **Вызовы инструментов (`tool_calls`):** в событиях `step_update` аргументы инструментов усекаются (например, тело записываемого файла `CodeContent` или параметры замены кода опускаются).
3. **Результаты инструментов (`GENERIC`):** вывод выполненных bash-команд или статус чтения файлов в `stream-json` не передаются либо приходят без payload.
4. **Диффы изменений кода:** готовые структуры диффов отсутствуют.

**Решение шлюза:**
Шлюз объединяет два потока данных:
* Из `stdout` процесса (`stream-json`) берутся:
  * Событие `init` (мгновенное получение `conversation_id`).
  * События `step_update` с `text_delta` (потоковая трансляция токенов ответа живым WS-клиентам).
  * Событие `result` (финальная статистика, длительность, токен-каунтеры).
* Из файла `brain/<conversation_id>/.system_generated/logs/transcript_full.jsonl` в реальном времени (tail) считываются и парсятся **богатые нормализованные шаги (`step`)** со всеми аргументами инструментов, рассуждениями и диффами кода.

---

## 3. Схема нормализованного шага (`NormalizedStep`)

Объект законченного шага диалога или действия агента.

### 3.1. JSON Schema

```json
{
  "step_index": 1,
  "source": "MODEL",
  "type": "PLANNER_RESPONSE",
  "status": "DONE",
  "created_at": "2026-09-29T13:55:26Z",
  "thinking": "The user wants to create a configuration file. I will use write_to_file.",
  "content": "Created the file successfully.",
  "user_prompt": null,
  "tool_calls": [
    {
      "name": "write_to_file",
      "args": {
        "TargetFile": "/root/agy-gateway/testws/app.json",
        "Overwrite": true,
        "CodeContent": "{\n  \"version\": \"1.0.0\"\n}\n",
        "Description": "Create app.json configuration"
      }
    }
  ],
  "diffs": [
    {
      "file": "/root/agy-gateway/testws/app.json",
      "action": "overwrite",
      "start_line": null,
      "end_line": null,
      "target_content": null,
      "replacement_content": "{\n  \"version\": \"1.0.0\"\n}\n",
      "instruction": "Create app.json configuration"
    }
  ],
  "error": null
}
```

### 3.2. Описание полей

| Поле | Тип | Описание |
|---|---|---|
| `step_index` | `int` | Порядковый номер шага в транскрипте разговора (начиная с 0). |
| `source` | `string` | Источник события: `USER_EXPLICIT` (пользователь), `MODEL` (агент/LLM), `SYSTEM` (система). |
| `type` | `string` | Тип шага: `USER_INPUT`, `PLANNER_RESPONSE`, `GENERIC` (вывод инструмента), `CODE_ACTION`, `ERROR_MESSAGE`, `SYSTEM_MESSAGE`. |
| `status` | `string` | Статус шага: `DONE`, `RUNNING`. |
| `created_at` | `string` | Временная метка ISO 8601 (UTC). |
| `thinking` | `string \| null` | Текст внутренних скрытых рассуждений модели (Chain-of-Thought). |
| `content` | `string \| null` | Текстовый вывод шага (сообщение модели или консольный вывод инструмента). |
| `user_prompt` | `string \| null` | Для шагов `USER_INPUT` — очищенный от служебных тегов текст исходного запроса пользователя. |
| `tool_calls` | `array[ToolCall]` | Список вызванных инструментов с полными аргументами. |
| `diffs` | `array[CodeDiff]` | Структурированные изменения файлов для подсветки синтаксиса и отображения диффов в приложении. |
| `error` | `string \| null` | Текст ошибки шага при наличии. |

#### Спецификация `CodeDiff`:
* `file` (`string`): Абсолютный путь к модифицируемому файлу.
* `action` (`string`): `"create"` \| `"overwrite"` \| `"append"` \| `"replace"` \| `"multi_replace"`.
* `start_line` (`int | null`): Номер начальной строки для замены (1-indexed).
* `end_line` (`int | null`): Номер конечной строки для замены.
* `target_content` (`string | null`): Исходный фрагмент, подлежащий замене.
* `replacement_content` (`string | null`): Новый код для вставки.
* `instruction` (`string | null`): Описание изменения агентом.

---

## 4. Журнал событий (`events`) и жизненный цикл

Все устойчивые события сохраняются в базе данных `gateway.db` в режиме SQLite WAL с автоинкрементным монотонно возрастающим полем `seq`.

### 4.1. Типы событий

1. **`run_started`**: Агент начал выполнение шага.
   * `payload`: `{ run_id, conversation_id, workspace, prompt, model, effort, mode }`
2. **`step`**: Завершение очередного богатого шага транскрипта.
   * `payload`: объект `NormalizedStep`.
3. **`run_finished`**: Завершение обработки хода агентом.
   * `payload`: `{ run_id, conversation_id, status: "SUCCESS"|"FAILED", duration_seconds, usage: { input_tokens, output_tokens, thinking_tokens, total_tokens }, response }`
4. **`run_error`**: Ошибка выполнения (ненулевой код возврата, исключение процесса, перезапуск сервера).
   * `payload`: `{ run_id, conversation_id, error, exit_code }`
5. **`run_cancelled`**: Запуск был отменен клиентом.
   * `payload`: `{ run_id, conversation_id, reason: "cancelled_by_user" }`
6. **`text_delta` (только живой поток WS, в БД не пишется):**
   * Формат: `{ type: "text_delta", conversation_id, run_id, step_index, text_delta: "token..." }`

---

## 5. Спецификация HTTP REST эндпоинтов

### 5.1. `GET /v1/health`
Проверка жизнеспособности сервера и конфигурации очереди.

* **Response 200 OK:**
```json
{
  "status": "ok",
  "service": "agy-gateway",
  "gateway": "1.0",
  "agy_available": true,
  "max_concurrent_runs": 2
}
```

---

### 5.1.1. `POST /v1/auth/device-tokens`
Создание нового токена для мобильного устройства (256 бит энтропии). В БД сохраняется только криптографический SHA-256 хэш. Исходный токен возвращается только один раз при создании.

* **Request Body:**
```json
{
  "device_name": "Pixel 8 Pro"
}
```

* **Response 200 OK:**
```json
{
  "id": 1,
  "device_name": "Pixel 8 Pro",
  "token": "agy_android_a1b2c3d4...",
  "created_at": "2026-09-29T14:26:07.123456+00:00"
}
```

---

### 5.1.2. `GET /v1/auth/device-tokens`
Список зарегистрированных токенов устройств. Исходные токены и хэши не раскрываются.

* **Response 200 OK:**
```json
[
  {
    "id": 1,
    "device_name": "Pixel 8 Pro",
    "created_at": "2026-09-29T14:26:07.123456+00:00",
    "last_used_at": "2026-09-29T14:27:00.123456+00:00",
    "revoked_at": null
  }
]
```

---

### 5.1.3. `DELETE /v1/auth/device-tokens/{id}`
Отзыв токена устройства. Отозванный токен немедленно теряет доступ к API и WebSocket (401 Unauthorized).

* **Response 200 OK:**
```json
{
  "status": "revoked",
  "id": 1
}
```

---

### 5.2. `GET /v1/models`
Список доступных языковых моделей (кэшируется на 10 минут).

* **Response 200 OK:**
```json
[
  {
    "id": "gemini-3.8-flash-high",
    "name": "Gemini 3.8 Flash (High)",
    "reasoning_level": "High"
  },
  {
    "id": "claude-sonnet-4-6",
    "name": "Claude Sonnet 4.6 (Thinking)",
    "reasoning_level": "Dynamic Thinking"
  },
  {
    "id": "gemini-3.7-flash-low",
    "name": "Gemini 3.7 Flash (Low)",
    "reasoning_level": "Low"
  }
]
```

---

### 5.3. `GET /v1/chats`
Список всех существующих разговоров (читается напрямую из SQLite `conversation_summaries.db` в режиме `mode=ro`).

* **Response 200 OK:**
```json
[
  {
    "id": "9d3d9197-33f4-4673-9078-597c20e5190c",
    "title": "Turn 1 Success",
    "preview": "Turn 2 Success",
    "status": "CASCADE_RUN_STATUS_IDLE",
    "step_count": 5,
    "last_modified": "2026-09-29 14:02:08.123456+00:00",
    "workspace": "/root/agy-gateway/testws",
    "parent_conversation_id": null
  }
]
```

---

### 5.4. `POST /v1/chats`
Создание нового разговора. Блокирует соединение до получения первого события `init` от процесса `agy` (~1–3 сек), возвращает `conversation_id` и `run_id`, после чего генерация продолжается асинхронно в фоне.

* **Request Body:**
```json
{
  "workspace": "/root/agy-gateway/testws",
  "message": "Create a file named welcome.txt with 'Hello Android!'",
  "model": "gemini-3.8-flash-high",
  "effort": "low",
  "mode": "accept-edits"
}
```
*Поля `model`, `effort` (`low|medium|high`), `mode` (`plan|accept-edits`) — опциональны.*

* **Response 200 OK:**
```json
{
  "conversation_id": "9d3d9197-33f4-4673-9078-597c20e5190c",
  "run_id": "run_e5ccf448cf8e"
}
```

---

### 5.5. `POST /v1/chats/{id}/messages`
Отправка последующего сообщения в существующий разговор. Сообщение ставится в очередь исполнения и сразу возвращает `202 Accepted`. Внутри одного чата сообщения выполняются строго последовательно.

* **Request Body:**
```json
{
  "text": "Add a second line with the current date to welcome.txt",
  "model": "gemini-3.8-flash-high",
  "effort": "low",
  "mode": null
}
```

* **Response 202 Accepted:**
```json
{
  "run_id": "run_9db3c0399eff",
  "status": "queued"
}
```

---

### 5.6. `POST /v1/chats/{id}/cancel`
Прерывание текущего запуска. Процессу `agy` отправляется сигнал `SIGTERM`. Если процесс не завершился в течение 5 секунд, шлюз принудительно завершает его по `SIGKILL`.

* **Response 200 OK (если был активный процесс):**
```json
{
  "conversation_id": "9d3d9197-33f4-4673-9078-597c20e5190c",
  "status": "cancelling",
  "run_id": "run_e2a74f92c6cb"
}
```

* **Response 200 OK (если чат не выполнялся):**
```json
{
  "conversation_id": "9d3d9197-33f4-4673-9078-597c20e5190c",
  "status": "not_running",
  "run_id": null
}
```

---

### 5.7. `GET /v1/chats/{id}/steps?after_step=&limit=`
Получение истории шагов чата из `transcript_full.jsonl` с пагинацией.

* **Query Parameters:**
  * `after_step` (`int`, optional): вернуть шаги с `step_index > after_step`.
  * `limit` (`int`, optional, default 50, max 200): максимальное число шагов.
* **Response 200 OK:** Массив `[NormalizedStep, ...]`.

---

### 5.8. `GET /v1/events?after=<seq>&conversation_id=&limit=`
Выборка событий из журнала `gateway.db` для догрузки после офлайна.

* **Query Parameters:**
  * `after` (`int`, optional, default 0): фильтр `seq > after`.
  * `conversation_id` (`string`, optional): фильтр по конкретному разговору.
  * `limit` (`int`, optional, default 100, max 1000).
* **Response 200 OK:**
```json
[
  {
    "seq": 10,
    "conversation_id": "9d3d9197-33f4-4673-9078-597c20e5190c",
    "run_id": "run_e5ccf448cf8e",
    "type": "run_started",
    "payload": {
      "run_id": "run_e5ccf448cf8e",
      "conversation_id": "9d3d9197-33f4-4673-9078-597c20e5190c",
      "workspace": "/root/agy-gateway/testws",
      "prompt": "Create welcome.txt"
    },
    "ts": "2026-09-29T14:01:50.123456+00:00"
  },
  {
    "seq": 11,
    "conversation_id": "9d3d9197-33f4-4673-9078-597c20e5190c",
    "run_id": "run_e5ccf448cf8e",
    "type": "step",
    "payload": {
      "step_index": 0,
      "source": "USER_EXPLICIT",
      "type": "USER_INPUT",
      "status": "DONE",
      "content": "Create welcome.txt",
      "user_prompt": "Create welcome.txt"
    },
    "ts": "2026-09-29T14:01:52.654321+00:00"
  }
]
```

---

### 5.9. `GET /v1/files?path=<path>`
Просмотр списка файлов и поддиректорий внутри разрешенных рабочих каталогов.
*Защита:* Любые пути с выходом за пределы разрешенных корней (`..`, абсолютные ссылки, симлинки наружу) блокируются с кодом `403 Forbidden`.

* **Response 200 OK:**
```json
{
  "path": "/root/agy-gateway/testws",
  "entries": [
    {
      "name": "src",
      "path": "/root/agy-gateway/testws/src",
      "is_dir": true,
      "is_symlink": false,
      "size": 0,
      "last_modified": "2026-09-29T13:55:00+00:00"
    },
    {
      "name": "test.txt",
      "path": "/root/agy-gateway/testws/test.txt",
      "is_dir": false,
      "is_symlink": false,
      "size": 4,
      "last_modified": "2026-09-29T13:55:31+00:00"
    }
  ]
}
```

---

### 5.10. `GET /v1/files/content?path=<path>`
Чтение содержимого файла с проверкой размера и защитой от бинарных файлов.

* **Response 200 OK (текстовый файл):**
```json
{
  "path": "/root/agy-gateway/testws/test.txt",
  "is_binary": false,
  "size": 4,
  "content": "123\n",
  "message": null
}
```

* **Response 200 OK (бинарный файл):**
```json
{
  "path": "/root/agy-gateway/testws/image.png",
  "is_binary": true,
  "size": 48120,
  "content": null,
  "message": "Binary file content omitted"
}
```

* **Ошибки:**
  * `403 Forbidden`: попытка чтения файла вне разрешенных каталогов.
  * `404 Not Found`: файл не существует.
  * `413 Payload Too Large`: размер файла превышает лимит (5 МБ).

---

## 6. WebSocket протокол (`/v1/ws`)

### 6.1. Подключение
* **URL:** `ws://127.0.0.1:8765/v1/ws?token=<AUTH_TOKEN>[&after=<seq>][&conversation_id=<id>]`
* При подключении с параметром `after=<seq>` сервер:
  1. Сначала отправляет клиенту все пропущенные события из таблицы `events` с `seq > after` в хронологическом порядке.
  2. Переключается на живой поток (`text_delta`, новые шаги `step`, `run_finished` и т.д.).
  3. Дедуплицирует события: ни одно событие с `seq <= after` повторно передано не будет.
* Каждые **20 секунд** сервер передает heartbeat ping:
  ```json
  { "type": "ping", "ts": "2026-09-29T14:03:00.000000+00:00" }
  ```
  Клиент может отправлять `{ "type": "pong" }` или игнорировать.

### 6.2. Потоковое событие токена ответа (`text_delta`)
Передается в реальном времени, когда модель генерирует символы:
```json
{
  "type": "text_delta",
  "conversation_id": "9d3d9197-33f4-4673-9078-597c20e5190c",
  "run_id": "run_e5ccf448cf8e",
  "step_index": 1,
  "text_delta": "Hello "
}
```

---

## 7. Пример интеграции в Android (Kotlin / OkHttp)

```kotlin
// 1. Создание OkHttpClient с Bearer токеном
val client = OkHttpClient.Builder()
    .readTimeout(0, TimeUnit.MILLISECONDS) // Для долгоживущих WebSocket
    .build()

val token = "YOUR_SECRET_TOKEN"
var lastReceivedSeq: Long = getSavedSeqFromRoom()

// 2. Подключение к WebSocket с догрузкой пропущенных событий
val wsUrl = "ws://127.0.0.1:8765/v1/ws?token=$token&after=$lastReceivedSeq"
val request = Request.Builder().url(wsUrl).build()

val ws = client.newWebSocket(request, object : WebSocketListener() {
    override fun onMessage(webSocket: WebSocket, text: String) {
        val json = JSONObject(text)
        val type = json.optString("type")
        val seq = json.optLong("seq", -1L)

        if (seq > lastReceivedSeq) {
            lastReceivedSeq = seq
            saveSeqToRoom(lastReceivedSeq)
        }

        when (type) {
            "text_delta" -> {
                val delta = json.getString("text_delta")
                runOnUiThread { chatAdapter.appendTextDelta(delta) }
            }
            "step" -> {
                val stepPayload = json.getJSONObject("payload")
                val step = parseNormalizedStep(stepPayload)
                runOnUiThread { chatAdapter.addOrUpdateStep(step) }
            }
            "run_finished" -> {
                runOnUiThread { chatAdapter.markRunComplete() }
            }
        }
    }
})
```
