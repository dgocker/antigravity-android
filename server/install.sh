#!/usr/bin/env bash
set -e

# ==============================================================================
# Antigravity Gateway Server Installation Script (Ubuntu / Debian)
# ==============================================================================

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
INSTALL_DIR="${INSTALL_DIR:-$SCRIPT_DIR}"
SERVICE_NAME="agy-gateway"
PORT="${PORT:-8765}"
USER_NAME="${SUDO_USER:-$USER}"

echo "=========================================================="
echo " Antigravity Gateway Server Installer"
echo " Directory: $INSTALL_DIR"
echo " User:      $USER_NAME"
echo "=========================================================="

# 1. Check system dependencies
echo "--> Checking system dependencies..."
for cmd in python3 tmux; do
    if ! command -v "$cmd" &>/dev/null; then
        echo "Error: $cmd is required. Install it using: apt-get install -y $cmd"
        exit 1
    fi
done

if ! python3 -m venv --help &>/dev/null; then
    echo "Error: python3-venv is missing. Install: apt-get install -y python3-venv"
    exit 1
fi

# 2. Setup Python virtual environment
echo "--> Setting up Python virtual environment in $INSTALL_DIR/venv..."
if [ ! -d "$INSTALL_DIR/venv" ]; then
    python3 -m venv "$INSTALL_DIR/venv"
fi

"$INSTALL_DIR/venv/bin/pip" install --upgrade pip
"$INSTALL_DIR/venv/bin/pip" install -r "$INSTALL_DIR/requirements.txt"

# 3. Setup .env file
if [ ! -f "$INSTALL_DIR/.env" ]; then
    echo "--> Creating default .env configuration..."
    cp "$INSTALL_DIR/.env.example" "$INSTALL_DIR/.env"
    MASTER_TOKEN=$(python3 -c "import secrets; print(secrets.token_hex(32))")
    sed -i "s|^AUTH_TOKEN=.*|AUTH_TOKEN=$MASTER_TOKEN|" "$INSTALL_DIR/.env"
    echo "    Generated master token: $MASTER_TOKEN"
fi

# 4. Create uploads directory
mkdir -p /root/agy-uploads
chmod 755 /root/agy-uploads

# 5. Setup systemd service
if [ "$(id -u)" -eq 0 ]; then
    echo "--> Installing systemd service ($SERVICE_NAME)..."
    cat <<EOF > /etc/systemd/system/${SERVICE_NAME}.service
[Unit]
Description=Antigravity CLI Gateway
After=network.target

[Service]
Type=simple
User=root
WorkingDirectory=$INSTALL_DIR
EnvironmentFile=$INSTALL_DIR/.env
ExecStart=$INSTALL_DIR/venv/bin/uvicorn app.main:app --host 127.0.0.1 --port $PORT --workers 1
Restart=on-failure
RestartSec=3s
MemoryMax=400M

[Install]
WantedBy=multi-user.target
EOF

    systemctl daemon-reload
    systemctl enable "${SERVICE_NAME}"
    systemctl restart "${SERVICE_NAME}"
    echo "--> Service ${SERVICE_NAME} started and enabled at boot."
    systemctl status "${SERVICE_NAME}" --no-pager
else
    echo "--> Non-root installation: skipped systemd setup."
    echo "    To start manually: $INSTALL_DIR/venv/bin/uvicorn app.main:app --host 127.0.0.1 --port $PORT"
fi

# 6. Generate device token
echo ""
echo "=========================================================="
echo " Installation Complete!"
echo "=========================================================="
echo ""
echo "To generate a device token for your Android phone, run:"
echo "  $INSTALL_DIR/venv/bin/python3 $INSTALL_DIR/agy-token.py create \"My Phone\""
echo ""
