#!/bin/bash
# install.sh — build and deploy bms-to-inverter as a systemd service
#
# Usage:  sudo ./install.sh
#
# First run: migrates existing config from ~/bms/config/ if present.
# Subsequent runs: rebuilds JARs and restarts service; config is never overwritten.

set -euo pipefail

INSTALL_DIR=/opt/bms-to-inverter
CONFIG_DIR=/etc/bms-to-inverter
SERVICE_FILE=/etc/systemd/system/bms.service
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ZIP="$SCRIPT_DIR/bms-to-inverter-main/target/bms-to-inverter.zip"

# ── 1. Must run as root ────────────────────────────────────────────────────────
if [[ $EUID -ne 0 ]]; then
    echo "ERROR: run with sudo: sudo $0" >&2
    exit 1
fi

SERVICE_USER="${SUDO_USER:-$USER}"
if [[ "$SERVICE_USER" == "root" ]]; then
    echo "ERROR: do not run as root directly — use: sudo ./install.sh (from your normal user account)" >&2
    exit 1
fi

echo "Installing as service user: $SERVICE_USER"

# ── 2. Check Java ─────────────────────────────────────────────────────────────
if ! java -version &>/dev/null; then
    echo "Java not found — installing openjdk-17-jre-headless..."
    apt-get install -y openjdk-17-jre-headless
fi

# ── 3. Build ──────────────────────────────────────────────────────────────────
if command -v mvn &>/dev/null; then
    echo "Building..."
    sudo -u "$SERVICE_USER" bash -c "cd '$SCRIPT_DIR' && mvn package -DskipTests -q"
elif [[ -f "$ZIP" ]]; then
    echo "Maven not found — using existing build: $ZIP"
else
    echo "ERROR: Maven not found and no pre-built zip at $ZIP" >&2
    echo "Either install Maven or copy a pre-built bms-to-inverter.zip to:" >&2
    echo "  $ZIP" >&2
    exit 1
fi

# ── 4. Stop existing service / processes ──────────────────────────────────────
if systemctl is-active --quiet bms.service 2>/dev/null; then
    echo "Stopping bms.service..."
    systemctl stop bms.service
fi
pkill -f bms-to-inverter-main 2>/dev/null || true

# ── 5. Install JARs ───────────────────────────────────────────────────────────
echo "Installing JARs to $INSTALL_DIR..."
mkdir -p "$INSTALL_DIR"
unzip -q -o "$ZIP" -d "$INSTALL_DIR"

# ── 6. Write start.sh (always updated) ────────────────────────────────────────
cat > "$INSTALL_DIR/start.sh" << 'STARTEOF'
#!/bin/bash
exec java \
  -DconfigFile=/etc/bms-to-inverter/config.properties \
  -Dlog4j2.configurationFile=file:/etc/bms-to-inverter/log4j2.xml \
  -jar /opt/bms-to-inverter/lib/bms-to-inverter-main-0.0.1-SNAPSHOT.jar
STARTEOF
chmod +x "$INSTALL_DIR/start.sh"

# ── 7. Config — first install only (never overwrite) ──────────────────────────
mkdir -p "$CONFIG_DIR"

if [[ ! -f "$CONFIG_DIR/config.properties" ]]; then
    OLD_CONFIG="/home/$SERVICE_USER/bms/config/config.properties"
    if [[ -f "$OLD_CONFIG" ]]; then
        echo "Migrating config from $OLD_CONFIG"
        cp "$OLD_CONFIG" "$CONFIG_DIR/config.properties"
    else
        cp "$SCRIPT_DIR/bms-to-inverter-main/src/main/resources/config.properties" \
           "$CONFIG_DIR/config.properties"
        echo ""
        echo "WARNING: Config template installed at $CONFIG_DIR/config.properties"
        echo "         Edit it to match your hardware before starting the service."
        echo ""
    fi
fi

# log4j2.xml is always replaced — it controls log verbosity and we want
# the console-only/info-level default; logs go to journald via systemd stdout.
cp "$SCRIPT_DIR/bms-to-inverter-main/src/main/resources/log4j2.xml" \
   "$CONFIG_DIR/log4j2.xml"

# ── 8. Ownership ──────────────────────────────────────────────────────────────
chown -R "$SERVICE_USER:$SERVICE_USER" "$INSTALL_DIR" "$CONFIG_DIR"

# ── 9. Write systemd unit (always updated) ────────────────────────────────────
cat > "$SERVICE_FILE" << SERVICEEOF
[Unit]
Description=BMS to Inverter
After=multi-user.target

[Service]
Type=simple
User=$SERVICE_USER
ExecStart=$INSTALL_DIR/start.sh
Restart=always
RestartSec=5
StartLimitIntervalSec=0

[Install]
WantedBy=multi-user.target
SERVICEEOF

# ── 10. Enable and start ──────────────────────────────────────────────────────
systemctl daemon-reload
systemctl enable bms.service
systemctl start bms.service

echo ""
echo "Done. Config: $CONFIG_DIR/config.properties"
echo "Logs:  journalctl -u bms.service -f"
echo ""
systemctl status bms.service --no-pager
