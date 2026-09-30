# Antigravity Gateway (`agy-gateway`)

Серверный шлюз (gateway) поверх Antigravity CLI (`agy`) для взаимодействия с мобильным Android-приложением.

---

## 1. Архитектура и особенности реализации

Шлюз построен на **Python 3.12** с использованием **FastAPI** и **Uvicorn** (1 воркер) с минимальным набором зависимостей (`fastapi`, `uvicorn[standard]`, `websockets`) и минимальным потреблением оперативной памяти (~1–2 МБ в простое, до 20 МБ под нагрузкой).

### Ключевые возможности:
1. **Журнал событий (`gateway.db`):** 
   * Собственная база данных SQLite в режиме **WAL** (`PRAGMA journal_mode = WAL`, `PRAGMA synchronous = NORMAL`).
   * Таблица `events(seq INTEGER PK AUTOINCREMENT, conversation_id, run_id, type, payload JSON, ts)`.
   * Типы событий: `run_started`, `step`, `run_finished`, `run_error`, `run_cancelled`.
   * Потоковые токены `text_delta` транслируются **только живым WebSocket-клиентам** и не засоряют журнал на диске.
   * Гарантированная догрузка пропущенных событий по монотонному `seq` без дублей при обрывах связи.
2. **Очередь и управление параллелизмом:**
   * Глобальное ограничение: **не более 2 одновременных процессов `agy`** (`asyncio.Semaphore`).
   * Строгая **последовательность внутри одного чата** (`asyncio.Lock` на каждый `conversation_id`).
   * Отмена запуска: отправка процессу сигнала `SIGTERM`, через 5 секунд — принудительный `SIGKILL`.
   * При старте шлюза все незавершенные запуски (`running`, `queued`) автоматически переводятся в статус `interrupted` с фиксацией события `run_error`.
3. **Богатые нормализованные шаги (`Rich Steps`):**
   * В ходе натурных тестов подтверждено: нативный `stream-json` бинарника `agy` не содержит текст рассуждений (`thinking`), усекает параметры вызовов инструментов (например, тело записываемых файлов `CodeContent`) и не дает диффы кода.
   * Шлюз непрерывно отслеживает (tail) файл `~/.gemini/antigravity-cli/brain/<id>/.system_generated/logs/transcript_full.jsonl`.
   * Оттуда формируются и отправляются нормализованные шаги `NormalizedStep`: `step_index`, `source`, `type`, `status`, `thinking`, `content`, `user_prompt`, `tool_calls` и структурированные `diffs`.
4. **Список и история чатов:**
   * Список сессий читается напрямую из `conversation_summaries.db` в безопасном режиме только для чтения (`mode=ro`).
   * История шагов отдается из `transcript_full.jsonl` с пагинацией (`after_step`, `limit`).
5. **Безопасная работа с файлами:**
   * Просмотр директорий (`GET /v1/files`) и чтение файлов (`GET /v1/files/content`).
   * Защита от выхода за пределы разрешенных рабочих каталогов (по умолчанию `/root/agy-workspaces`, `/root/agy-gateway/testws` и рабочие папки существующих чатов).
   * Автоматическая блокировка `../`, абсолютных путей и символических ссылок, указывающих во внешнюю файловую систему (`403 Forbidden`).
   * Определение бинарных файлов (по наличию `\x00` и валидности UTF-8) и ограничение размера файла (5 МБ).
7. **Интерактивная сессия через tmux (`app/tmux_injector.py`):**
   * Если чат активен в терминале разработчика (сессия `tmux`), сообщения из мобильного приложения инжектируются напрямую в tmux-сессию через безопасный буфер bracketed paste (`paste-buffer -p`).
   * Встроенный цикл верификации и повторной отправки (`C-m`) гарантирует отправку промпта, даже если терминал занят.
   * Отслеживание активности спиннеров Antigravity в терминале и синхронизация статуса выполнения с мобильным клиентом.
8. **Голосовые заметки и расшифровка (`set_transcription.py`):**
   * Приём и сохранение голосовых сообщений (аудио m4a/aac) и медиафайлов.
   * Утилита `set_transcription.py "<path>" "<text>"` мгновенно обновляет базу данных и рассылает WebSocket-событие на телефон, отображая расшифровку прямо в бабле сообщения.
9. **Устойчивость к сжатию контекста (Context Compaction):**
   * Наблюдатель `transcript_watcher.py` отслеживает размер файла журнала. При выполнении компактности транскрипта (`curr_size < file_offset`) оффсет автоматически сбрасывается и пересчитывается без потери новых событий.
10. **Криптографическая авторизация:**
   * Bearer-токен хранится в `/root/agy-gateway/.env` с правами доступа `0600`.
   * Поддержка множественных постоянных токенов устройств (`agy-token.py`) с хэшированием SHA-256.
   * Константно-временная проверка (`secrets.compare_digest`).
11. **Изоляция сети и обратный прокси:**
   * Шлюз слушает строго локальный интерфейс `127.0.0.1:8765`.
   * Доступ обеспечивается через Nginx (`/v1/`) с поддержкой WebSocket (`Upgrade`, `Connection "upgrade"`) и лимитом загрузки `client_max_body_size 500M;`.
   * Существующие сервисы хоста (`sshd`, `xray`, `nginx`, `gitea`) не затрагиваются.

---

## 2. Структура каталога

```text
/root/agy-gateway/
├── .env                  # Конфигурация и случайный 32-байтный токен (права 0600)
├── agy-gateway.service   # Юнит systemd (MemoryMax=300M, Restart=on-failure)
├── gateway.db            # База данных журнала событий SQLite WAL
├── venv/                 # Виртуальное окружение Python 3.12
├── app/
│   ├── __init__.py
│   ├── config.py         # Настройки, пути и загрузка .env
│   ├── auth.py           # Константно-временная проверка токена (HTTP и WS)
│   ├── db.py             # SQLite WAL, таблица events, reconciliation
│   ├── models.py         # Pydantic модели запросов, ответов, шагов и диффов
│   ├── step_parser.py    # Парсер богатых шагов из transcript_full.jsonl
│   ├── hub.py            # WebSocket Hub: replay пропущенного, live broadcast, ping
│   ├── runner.py         # Управление запуском agy, tail транскрипта, SIGTERM/SIGKILL
│   ├── queue.py          # Очередь (semaphore max 2 + per-chat sequential lock)
│   ├── files.py          # Безопасный файловый доступ с защитой от traversal и symlink
│   ├── agy_cli.py        # Парсер моделей agy models с кэшем
│   └── main.py           # FastAPI приложение и маршруты
├── test_case_a.py        # Тест: новый чат, WS-стриминг, второй ход
├── test_case_b.py        # Тест: обрыв WS, реконнект after=<seq>, без дублей
├── test_case_c.py        # Тест: отмена запуска
├── test_case_e.py        # Тест: 3 параллельных запроса, очередь
├── verify_all.py         # Комплексный скрипт валидации всех тестов
├── API.md                # Спецификация API для разработчика Android
└── README.md             # Настоящее руководство
```

---

## 3. Установка и первоначальная настройка

### 3.1. Создание виртуального окружения и установка пакетов
```bash
cd /root/agy-gateway
python3 -m venv venv
venv/bin/pip install --no-cache-dir fastapi "uvicorn[standard]"
```

### 3.2. Генерация `.env` с токеном и правами 0600
```bash
/root/agy-gateway/venv/bin/python3 -c "
import secrets, os
token = secrets.token_hex(32)
with open('/root/agy-gateway/.env', 'w') as f:
    f.write(f'''AUTH_TOKEN={token}
HOST=127.0.0.1
PORT=8765
MAX_CONCURRENT_RUNS=2
DB_PATH=/root/agy-gateway/gateway.db
CONVERSATION_SUMMARIES_DB=/root/.gemini/antigravity-cli/conversation_summaries.db
BRAIN_DIR=/root/.gemini/antigravity-cli/brain
ALLOWED_WORKSPACE_ROOTS=/root/agy-workspaces,/root/agy-gateway/testws
FILE_MAX_SIZE_BYTES=5242880
AGY_BIN=/root/.local/bin/agy
''')
os.chmod('/root/agy-gateway/.env', 0o600)
"
```

---

## 4. Управление службой через systemd

Шлюз оформлен в виде системного сервиса `agy-gateway.service` с ограничением памяти `MemoryMax=300M` и автоматическим перезапуском `Restart=on-failure`.

### Установка и активация службы:
```bash
# 1. Скопировать юнит в каталог systemd
cp /root/agy-gateway/agy-gateway.service /etc/systemd/system/agy-gateway.service

# 2. Перечитать конфигурацию systemd
systemctl daemon-reload

# 3. Включить автозапуск при загрузке системы
systemctl enable agy-gateway.service

# 4. Запустить службу
systemctl start agy-gateway.service

# 5. Проверить статус
systemctl status agy-gateway.service
```

### Просмотр логов службы:
```bash
journalctl -u agy-gateway.service -f
```

---

## 5. Практическая верификация и результаты тестов

Все функциональные требования были протестированы на практике на сервере.

### Сводный запуск тестов:
```bash
/root/agy-gateway/venv/bin/python3 /root/agy-gateway/verify_all.py
```

### Результаты проверок:

#### (а) Новый чат, стриминг по WS, следующее сообщение в том же чате
* **Скрипт:** `/root/agy-gateway/test_case_a.py`
* **Ход выполнения:**
  * Запрос `POST /v1/chats` успешно инициализировал сессию `9d3d9197-33f4-4673-9078-597c20e5190c` и вернул `run_id`.
  * По WebSocket получены живые чанки `text_delta` первого ответа: `'Turn 1 Success\n'`.
  * Из `transcript_full.jsonl` считаны и отданы шаги `USER_INPUT` (шаг 0) и `PLANNER_RESPONSE` (шаг 1).
  * Следом отправлен второй запрос `POST /v1/chats/.../messages` (`run_id: run_9db3c0399eff`).
  * По тому же WebSocket-соединению принят поток токенов `'Turn 2 Success\n'` и шаги 2, 3, 4.
* **Статус:** **PASSED**

#### (б) Обрыв WS посреди ответа, переподключение с after=<seq>, без дублей
* **Скрипт:** `/root/agy-gateway/test_case_b.py`
* **Ход выполнения:**
  * Запущен ход генерации списка преимуществ SQLite WAL.
  * После получения `seq=10` клиент сымитировал разрыв соединения и закрыл сокет.
  * Процесс `agy` продолжил выполнение в фоновом режиме на сервере.
  * Клиент переподключился через 5 секунд с параметром `after=10`.
  * Сервер дослал события `[11, 12, 13, 14]` строго по порядку без повторов событий `<= 10`.
* **Статус:** **PASSED**

#### (в) Отмена запуска (SIGTERM -> SIGKILL)
* **Скрипт:** `/root/agy-gateway/test_case_c.py`
* **Ход выполнения:**
  * Запущен длительный ход генерации эссе по Paxos/Raft.
  * После подтверждения `run_started` отправлен `POST /v1/chats/{id}/cancel`.
  * Процессу немедленно отправлен `SIGTERM`.
  * В WebSocket поступило событие `run_cancelled` с причиной `cancelled_by_user`.
  * В SQLite статус запуска переведен в `'cancelled'` с фиксацией отметки времени.
* **Статус:** **PASSED**

#### (г) Защита от выхода за пределы корня через `../` и symlink
* **Скрипт:** `verify_all.py` (блок Path Traversal)
* **Ход выполнения:**
  * Запрос `GET /v1/files?path=/root/agy-workspaces/../../etc` -> **403 Forbidden**.
  * Запрос `GET /v1/files/content?path=/root/agy-workspaces/../../etc/passwd` -> **403 Forbidden**.
  * Создан символический линк `/root/agy-workspaces/test_outside_link -> /etc/passwd`.
  * Запрос к содержимому через симлинк -> **403 Forbidden**.
  * Чтение разрешенного файла `/root/agy-gateway/testws/test.txt` -> **200 OK**.
* **Статус:** **PASSED**

#### (д) Авторизация: неверный или отсутствующий токен даёт 401
* **Скрипт:** `verify_all.py` (блок Auth)
* **Ход выполнения:**
  * `curl http://127.0.0.1:8765/v1/health` без заголовка -> **HTTP 401 Unauthorized**.
  * `curl -H "Authorization: Bearer bad-token"` -> **HTTP 401 Unauthorized**.
  * `websockets.connect("ws://127.0.0.1:8765/v1/ws")` без токена -> рукопожатие прерывается с **HTTP 401**.
  * Запрос с корректным токеном -> **200 OK**.
* **Статус:** **PASSED**

#### (е) 3 одновременных запроса, третий ждёт в очереди
* **Скрипт:** `/root/agy-gateway/test_case_e.py`
* **Ход выполнения:**
  * Запущены одновременно 3 запроса на создание чатов.
  * Запросы 1 и 2 заняли 2 слота глобального семафора и начали выполнение (время выполнения ~8 сек).
  * Запрос 3 поставлен в очередь ожидания: во время выполнения первых двух в БД зафиксирован статус `queued`, число одновременно работающих процессов строго равнялось 2.
  * После завершения слота Запрос 3 перешел в `running` и успешно завершился (общее время ~23 сек).
* **Статус:** **PASSED**

#### Автоматическое восстановление при перезапуске (Startup Reconciliation)
* При старте сервиса шлюз проверяет таблицу `runs`.
* Если сервер был перезапущен во время работы процессов, все незавершенные запуски переводятся в статус `interrupted`, и в журнал вставляется событие `run_error` с описанием `"Run was interrupted by server restart"`. Проверено тестом с преднамеренным перезапуском службы.

---

## 6. Отличия от отчета и технические выводы

1. **Неполнота данных в `stream-json`:**
   В натурных испытаниях выявлено, что `stream-json` не передает рассуждения (`thinking`) и опускает аргументы инструментов. Реализованный в шлюзе механизм tailing-мониторинга `transcript_full.jsonl` полностью решает проблему и предоставляет клиенту исчерпывающий `NormalizedStep` с диффами.
2. **Отказ WebSocket без токена:**
   Для строгого соответствия правилу «без токена всё отдаёт 401» сервер прерывает незащищенное рукопожатие WebSocket выбросом `HTTPException(401)`, возвращая клиенту стандартный HTTP-ответ `401 Unauthorized` еще до завершения протокольного апгрейда.
3. **Безопасность рабочих пространств:**
   Параметр `--add-dir` гарантирует доступ `agy` к директории чата даже в том случае, если она не была предварительно прописана в `settings.json`.

---

## 7. Этап 2: Вложения, Голосовые сообщения и Проверка Мультимодальности Antigravity

### 7.1. Фактические результаты проверки CLI Antigravity (`agy`)

Перед реализацией Этапа 2 было проведено детальное тестирование текущей версии `agy` CLI (`Antigravity 2.0`):

1. **Может ли `agy` принимать `image`/`audio`/`file` как нативный CLI input?**
   * **НЕТ.** Проверка через `--input-format stream-json` показала, что парсер `agy` жестко валидирует типы блоков содержимого и завершается с критической ошибкой:
     `error: stream input content block type "unknown" is not supported (only "text")`
     при попытке передать блоки типа `image`, `media`, `file` или `input_file`.
   * CLI-аргументы (`agy -p <prompt>`) принимают исключительно текстовую строку.
2. **Как модели в Antigravity реально работают с мультимодальными данными?**
   * Агент Antigravity оснащен нативным инструментом `view_file`, который умеет считывать не только текстовые файлы, но и бинарные форматы: изображения (`.png`, `.jpg`, `.jpeg`, `.webp`), аудио (`.m4a`, `.mp3`), документы (`.pdf`, `.json`, `.csv`) и видео при передаче абсолютного пути файловой системы.
3. **Реализованное архитектурное решение (Attachment Storage + Prompt Formatting):**
   * Вместо имитации неподдерживаемого CLI-ввода шлюз сохраняет загруженные вложения в защищенное хранилище на сервере: `/root/agy-uploads/<uuid>_<filename>`.
   * Директория `/root/agy-uploads` добавлена в список разрешенных путей (`allowed_workspace_roots`).
   * В prompt агента внедряется структурированная ссылка на локальный файл:
     `[Изображение: /root/agy-uploads/... (image/jpeg, 120 KB)]`
     `[Голосовое сообщение: /root/agy-uploads/... (audio/m4a, 7s)]`
     `Расшифровка аудио: "Текст сообщения"`
     `[Вложение: filename.pdf (/root/agy-uploads/... 250 KB)]`
   * Агент `agy` использует инструмент `view_file` для прямого мультимодального анализа файла.

### 7.2. API Вложений

* `POST /v1/attachments`: multipart-загрузка файлов до 50 МБ.
  * Поля: `file` (Multipart), опционально `conversation_id`, `transcription`, `duration`.
  * Валидация: блокировка исполняемых расширений (`.sh`, `.exe`, `.so`, `.py`), защита от path traversal, генерация безопасного имени с UUID-префиксом.
  * Ответ: `{ id, file_name, mime_type, size, storage_path, download_url, transcription }`.
* `GET /v1/attachments/{id}`: потоковая отдача файла (поддержка `?download=true` и авторизации через заголовок или query token).

### 7.3. Очередь Outbox и Клиентский UX

* В Room-базе мобильного клиента реализована таблица `outbox` со статусами (`PENDING`, `SENDING`, `SENT`, `FAILED`).
* Все отправляемые сообщения и вложения немедленно сохраняются локально.
* При сбоях сети или недоступности сервера сообщение помечается как `FAILED` с кнопкой `↻ Повторить`.
* При восстановлении соединения (`ConnectionStatus.CONNECTED`) фоновый воркер автоматически перезапускает отправку накопившихся в Outbox сообщений.
