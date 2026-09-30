# Antigravity Server Gateway (`server/`)

High-performance asynchronous server gateway built with **Python 3.12 (FastAPI / Uvicorn / WebSockets)**, providing seamless bidirectional communication between the **Antigravity CLI (`agy`)** terminal session on your server/VPS and the **Antigravity Android** mobile client.

---

## 🌟 Key Features

1. **Real-Time Bidirectional Terminal & App Synchronization:**
   * **Terminal → App:** A background `TranscriptWatcher` continuously tails `transcript_full.jsonl` of the active conversation, streaming tool calls, model thoughts (`thinking`), structured code diffs, and responses over WebSockets with sub-80ms latency.
   * **App → Terminal:** When sending messages from the mobile client, the gateway checks for an active interactive `tmux` session (`agy`). If active, the prompt is injected directly via **Bracketed Paste (`tmux paste-buffer -p`)** without spawning parallel processes or interrupting existing sessions.
2. **Execution State Tracking & Live "Stop" Button:**
   * An active-state inspector (`is_tmux_turn_active()`) monitors tmux spinners and execution status (`Running command...`, `esc to cancel`).
   * The mobile app displays an animated activity indicator and an active red **Stop** button in real time. Tapping Stop in the app issues `Escape` / `Ctrl+C` into the tmux session, instantly interrupting agent execution.
3. **Reliable Event Logging & Reconnection Backfill:**
   * SQLite database in **WAL mode** (`gateway.db`).
   * Each event receives a monotonically increasing sequence ID (`seq`). On network drops, the mobile client reconnects with `?after=<seq>` to backfill missed steps with guaranteed zero duplication.
4. **Device Token Authentication:**
   * Multi-device token management via the `agy-token.py` CLI utility.
   * Device tokens are stored as SHA-256 hashes in the database with constant-time verification (`secrets.compare_digest`) to protect against timing attacks.
5. **Rich Media & Attachments:**
   * Upload images, videos, documents, and voice messages via `POST /v1/attachments`.
   * Secure file streaming and download endpoints with token authorization.
6. **Minimal Resource Footprint:**
   * Single-worker Uvicorn process consuming only **~25–35 MB RAM** with virtually zero idle CPU usage.

---

## 📋 System Requirements

* **Operating System:** Linux (Ubuntu 22.04 / 24.04, Debian 11 / 12, or equivalent).
* **Python:** 3.10, 3.11, or 3.12 (with `python3-venv` package installed).
* **tmux:** Installed on the system (`sudo apt install -y tmux`).
* **Antigravity CLI:** Installed `agy` binary (defaults to `~/.local/bin/agy`).

---

## 🚀 One-Command Automated Installation

If running on your server as `root` (or with `sudo`):

```bash
cd server
chmod +x install.sh
sudo ./install.sh
```

The script will automatically:
1. Verify `python3`, `python3-venv`, and `tmux` dependencies.
2. Create a virtual environment in `venv/` and install packages from `requirements.txt`.
3. Generate a `.env` configuration file with a secure random master token.
4. Create the `/root/agy-uploads` directory for uploads.
5. Install, enable at boot, and start the `agy-gateway.service` systemd daemon.

---

## 🛠 Step-by-Step Manual Installation

### Step 1. Install System Dependencies

```bash
sudo apt update
sudo apt install -y python3 python3-venv python3-pip tmux sqlite3
```

### Step 2. Create Virtual Environment & Install Packages

```bash
cd server
python3 -m venv venv
venv/bin/pip install --upgrade pip
venv/bin/pip install -r requirements.txt
```

### Step 3. Configure `.env`

Copy the environment template:
```bash
cp .env.example .env
chmod 600 .env
```

Edit `.env` as needed:
```ini
# Generate a master token: openssl rand -hex 32
AUTH_TOKEN=example_master_auth_token_placeholder

HOST=127.0.0.1
PORT=8765
MAX_CONCURRENT_RUNS=2
DB_PATH=./gateway.db

# Directory for file and media attachments
UPLOADS_DIR=/root/agy-uploads
UPLOAD_MAX_SIZE_BYTES=52428800

# Allowed workspace roots for file browsing (comma-separated)
ALLOWED_WORKSPACE_ROOTS=/root,/root/agy-uploads

# Path to the Antigravity CLI binary
AGY_BIN=/root/.local/bin/agy
```

### Step 4. Set Up the systemd Service

Copy the service file to the system directory:
```bash
sudo cp agy-gateway.service /etc/systemd/system/agy-gateway.service
```

If your installation directory differs from `/root/agy-android/server`, adjust the paths in `/etc/systemd/system/agy-gateway.service`:
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

Reload systemd, enable, and start the service:
```bash
sudo systemctl daemon-reload
sudo systemctl enable agy-gateway
sudo systemctl start agy-gateway
```

Verify service status:
```bash
sudo systemctl status agy-gateway
```

---

## 🔑 Provisioning Device Tokens for the Android App

The gateway supports individual, revokable device tokens:

1. **Generate a token for a new device:**
   ```bash
   venv/bin/python3 agy-token.py create "Pixel 8 Pro"
   ```
   Output:
   ```text
   Device: Pixel 8 Pro (ID: 1)
   Token:
   agy_android_example_device_token_placeholder
   Created: 2026-09-30T10:00:00Z
   NOTE: Store this token in your Android app. The raw token is not stored on the server.
   ```

2. **List all registered devices:**
   ```bash
   venv/bin/python3 agy-token.py list
   ```

3. **Revoke a device token:**
   ```bash
   venv/bin/python3 agy-token.py revoke <ID>
   ```

Enter the generated token in the connection settings of your Antigravity Android app.

---

## 🌐 Reverse Proxy Configuration (Nginx / HTTPS / WebSockets)

The gateway binds locally to `127.0.0.1:8765`. For secure access from your mobile device over the internet, configure an Nginx reverse proxy with WebSocket upgrade support and SSL/TLS:

```nginx
server {
    server_name agy.yourdomain.com;

    # SSL certificates (Let's Encrypt / Certbot)
    listen 443 ssl http2;
    ssl_certificate /etc/letsencrypt/live/agy.yourdomain.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/agy.yourdomain.com/privkey.pem;

    client_max_body_size 64M;

    location / {
        proxy_pass http://127.0.0.1:8765;
        proxy_http_version 1.1;

        # WebSocket Upgrade headers
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";

        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;

        # Timeouts for long-lived WebSocket sessions
        proxy_read_timeout 86400s;
        proxy_send_timeout 86400s;
    }
}
```

---

## 💻 tmux Integration (Termius / SSH)

To route messages from your mobile app into your active terminal workflow, run `agy` inside a named `tmux` session named `agy`:

```bash
# Start a new agy session inside tmux
tmux new-session -s agy agy

# Attach to an existing session
tmux attach-session -t agy
```

When the `agy` tmux session is running:
- Any message sent from the Android mobile app is injected directly into this interactive session via bracketed paste.
- All commands executed, tool outputs, and reasoning steps in the terminal stream directly to your phone screen in real time.

---

## 🧪 Running Unit Tests

The repository includes a pytest test suite covering tmux injection, WebSocket subscription, and transcript watcher logic:

```bash
cd server
PYTHONPATH=. venv/bin/pytest tests -v
```

All tests should pass (`5 passed`).

---

## 📊 Monitoring & Logs

View live server logs:
```bash
sudo journalctl -u agy-gateway -f
```
