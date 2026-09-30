# Antigravity Server Gateway (`server/`)

Высокопроизводительный асинхронный серверный шлюз (Gateway) на **Python 3.12 (FastAPI / Uvicorn / WebSocket)**, обеспечивающий двустороннюю связь между терминальной сессией **Antigravity CLI (`agy`)** на сервере/VPS и мобильным приложением **Antigravity Android**.

---

## 🌟 Ключевые возможности

1. **Двусторонняя синхронизация терминала и приложения в реальном времени:**
   * **Terminal → App:** Фоновый `TranscriptWatcher` непрерывно отслеживает `transcript_full.jsonl` активного диалога и транслирует в WebSocket все промежуточные вызовы инструментов, текст рассуждений модели (`thinking`), код изменений и финальные ответы с задержкой менее 80 мс.
   * **App → Terminal:** При отправке сообщения из мобильного приложения шлюз проверяет наличие интерактивной сессии в `tmux` (`agy`). Если сессия активна, сообщение вставляется прямо в терминал через **Bracketed Paste (`tmux paste-buffer -p`)** без запуска параллельных процессов и разрывов сессий.
2. **Управление состоянием выполнения и кнопка «Стоп»:**
   * Детектор `is_tmux_turn_active()` отслеживает активное выполнение в tmux (спиннеры, `esc to cancel`).
   * В приложении в реальном времени отображается индикатор работы и активная кнопка **«Стоп»**. Нажатие кнопки в приложении посылает `Escape` / `Ctrl+C` в tmux и мгновенно прерывает выполнение агента.
3. **Надежный журнал событий и догрузка при обрывах связи:**
   * Встроенная база данных SQLite WAL (`gateway.db`).
   * Каждому событию присваивается монотонный порядковый номер (`seq`). При обрыве связи мобильное приложение подключается с параметром `?after=<seq>` и моментально получает только пропущенные шаги без дублирования.
4. **Управление доступом по токенам устройств (Device Tokens):**
   * Поддержка генерации индивидуальных токенов для каждого телефона/планшета через консольную утилиту `agy-token.py`.
   * Хранение токенов в базе данных в виде SHA-256 хэшей. Безопасная проверка с защитой от атак по времени (`secrets.compare_digest`).
5. **Мультимедиа и вложения:**
   * Загрузка изображений, видео, файлов и аудиосообщений через `POST /v1/attachments`.
   * Поддержка предпросмотра и скачивания вложений по защищенным ссылкам с проверкой токена.
6. **Минимальное потребление ресурсов:**
   * Работает в 1 процессе Uvicorn, потребляет всего **~25–35 МБ RAM** и практически не нагружает процессор в простое.

---

## 📋 Системные требования

* **ОС:** Linux (Ubuntu 22.04 / 24.04, Debian 11 / 12 или аналоги).
* **Python:** 3.10, 3.11 или 3.12 (с установленным пакетом `python3-venv`).
* **tmux:** Установлен в системе (`apt install -y tmux`).
* **Antigravity CLI:** Установлен бинарник `agy` (по умолчанию `~/.local/bin/agy`).

---

## 🚀 Быстрая установка (в 1 команду)

Если вы запускаете установку на сервере под пользователем `root` (или с `sudo`):

```bash
cd server
chmod +x install.sh
sudo ./install.sh
```

Скрипт автоматически:
1. Проверит наличие `python3`, `python3-venv` и `tmux`.
2. Создаст виртуальное окружение `venv/` и установит зависимости из `requirements.txt`.
3. Сгенерирует файл конфигурации `.env` со случайным мастер-токеном.
4. Создаст каталог `/root/agy-uploads` для загружаемых файлов.
5. Установит, включит в автозагрузку и запустит службу systemd `agy-gateway.service`.

---

## 🛠 Пошаговая ручная установка

### Шаг 1. Установка системных зависимостей

```bash
sudo apt update
sudo apt install -y python3 python3-venv python3-pip tmux sqlite3
```

### Шаг 2. Создание виртуального окружения и установка пакетов

```bash
cd server
python3 -m venv venv
venv/bin/pip install --upgrade pip
venv/bin/pip install -r requirements.txt
```

### Шаг 3. Настройка конфигурации `.env`

Скопируйте шаблон конфигурации:
```bash
cp .env.example .env
chmod 600 .env
```

Отредактируйте `.env` при необходимости:
```ini
# Сгенерируйте мастер-токен: openssl rand -hex 32
AUTH_TOKEN=example_master_auth_token_placeholder

HOST=127.0.0.1
PORT=8765
MAX_CONCURRENT_RUNS=2
DB_PATH=./gateway.db

# Каталог для вложений и картинок
UPLOADS_DIR=/root/agy-uploads
UPLOAD_MAX_SIZE_BYTES=52428800

# Разрешенные рабочие каталоги для обзора файлов (через запятую)
ALLOWED_WORKSPACE_ROOTS=/root,/root/agy-uploads

# Путь к бинарнику Antigravity CLI
AGY_BIN=/root/.local/bin/agy
```

### Шаг 4. Настройка службы systemd

Скопируйте юнит-файл в системный каталог:
```bash
sudo cp agy-gateway.service /etc/systemd/system/agy-gateway.service
```

Если путь к репозиторию отличается от `/root/agy-android/server`, отредактируйте пути в `/etc/systemd/system/agy-gateway.service`:
```ini
[Unit]
Description=Antigravity CLI Gateway
After=network.target

[Service]
Type=simple
User=root
WorkingDirectory=/path/to/server
EnvironmentFile=/path/to/server/.env
ExecStart=/path/to/server/venv/bin/uvicorn app.main:app --host 127.0.0.1 --port 8765 --workers 1
Restart=on-failure
RestartSec=3s
MemoryMax=400M

[Install]
WantedBy=multi-user.target
```

Примените изменения и запустите сервис:
```bash
sudo systemctl daemon-reload
sudo systemctl enable agy-gateway
sudo systemctl start agy-gateway
```

Проверьте статус службы:
```bash
sudo systemctl status agy-gateway
```

---

## 🔑 Создание токена для Android-приложения

Шлюз поддерживает многопользовательские токены устройств с возможностью отзыва:

1. **Создать токен для нового устройства:**
   ```bash
   venv/bin/python3 agy-token.py create "Pixel 8 Pro"
   ```
   В выводе вы получите токен вида:
   `agy_android_example_device_token_placeholder`

2. **Посмотреть список зарегистрированных устройств:**
   ```bash
   venv/bin/python3 agy-token.py list
   ```

3. **Отозвать токен:**
   ```bash
   venv/bin/python3 agy-token.py revoke <ID>
   ```

Полученный токен вставьте в настройках подключения мобильного приложения Android.

---

## 🌐 Настройка Reverse Proxy (Nginx / HTTPS / WebSocket)

Шлюз слушает локальный адрес `127.0.0.1:8765`. Для безопасного подключения мобильного клиента через интернет рекомендуется настроить обратный прокси Nginx с поддержкой WebSocket и SSL/TLS:

```nginx
server {
    server_name agy.yourdomain.com;

    # SSL сертификаты (Let's Encrypt / Certbot)
    listen 443 ssl http2;
    ssl_certificate /etc/letsencrypt/live/agy.yourdomain.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/agy.yourdomain.com/privkey.pem;

    client_max_body_size 64M;

    location / {
        proxy_pass http://127.0.0.1:8765;
        proxy_http_version 1.1;

        # Проброс заголовков WebSocket Upgrade
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";

        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;

        # Таймауты для долгоживущих WebSocket-соединений
        proxy_read_timeout 86400s;
        proxy_send_timeout 86400s;
    }
}
```

---

## 💻 Интеграция с сессией tmux (Termius / SSH)

Чтобы сообщения из мобильного приложения залетали в интерактивную сессию терминала, запустите `agy` внутри именованной сессии `tmux` с именем `agy`:

```bash
# Запуск новой сессии agy в tmux
tmux new-session -s agy agy

# Подключение к существующей сессии
tmux attach-session -t agy
```

Когда сессия `agy` запущена в tmux:
- Любые сообщения из мобильного приложения будут автоматически впечатываться в эту интерактивную сессию.
- Все команды, вызовы инструментов и размышления агента в терминале будут транслироваться на экран вашего смартфона в режиме реального времени.

---

## 🧪 Запуск тестов

В репозитории есть готовый набор тестов pytest для проверки интеграции tmux, WebSocket и наблюдателя транскрипта:

```bash
cd server
PYTHONPATH=. venv/bin/pytest tests -v
```

Все тесты должны успешно пройти (`5 passed`).

---

## 📊 Мониторинг и логирование

Просмотр логов работы шлюза в реальном времени:
```bash
sudo journalctl -u agy-gateway -f
```
