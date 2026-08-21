#!/usr/bin/env bash
# Clone-Master environment setup (Debian/Ubuntu) – installs full toolchain + 6GB swap
set -e
cd "$(dirname "$0")"

if [ "$(id -u)" -ne 0 ] && command -v sudo >/dev/null 2>&1; then
  SUDO=sudo
else
  SUDO=""
fi

echo "[1/7] Base packages..."
export DEBIAN_FRONTEND=noninteractive
$SUDO apt-get update -qq
$SUDO apt-get install -y -qq openjdk-17-jdk-headless aapt apksigner zipalign git unzip zip curl python3 python3-pip wget

echo "[2/7] 6GB swap..."
if ! swapon --show 2>/dev/null | grep -q swap; then
  if [ ! -f /swapfile ]; then
    $SUDO fallocate -l 6G /swapfile || $SUDO dd if=/dev/zero of=/swapfile bs=1M count=6144
    $SUDO chmod 600 /swapfile
    $SUDO mkswap /swapfile
  fi
  $SUDO swapon /swapfile || echo "swap enable failed (may already be on or restricted)"
  grep -q swapfile /etc/fstab 2>/dev/null || echo '/swapfile none swap sw 0 0' | $SUDO tee -a /etc/fstab >/dev/null
else
  echo "swap already active"
fi
free -h

echo "[3/7] apktool..."
APKTOOL_VER=$(curl -s https://api.github.com/repos/iBotPeaches/Apktool/releases/latest | grep -oP '"tag_name":\s*"\K[^"]+' || echo "v2.9.3")
$SUDO mkdir -p /opt/apktool
$SUDO curl -sL --retry 3 -o /opt/apktool/apktool.jar "https://github.com/iBotPeaches/Apktool/releases/download/${APKTOOL_VER}/apktool_${APKTOOL_VER#v}.jar" || \
  $SUDO curl -sL -o /opt/apktool/apktool.jar https://bitbucket.org/iBotPeaches/apktool/downloads/apktool_2.9.3.jar
$SUDO tee /usr/local/bin/apktool >/dev/null <<'EOF'
#!/bin/sh
exec java -jar /opt/apktool/apktool.jar "$@"
EOF
$SUDO chmod +x /usr/local/bin/apktool
apktool --version || true

echo "[4/7] uber-apk-signer..."
UAS_VER=$(curl -s https://api.github.com/repos/patrickfav/uber-apk-signer/releases/latest | grep -oP '"tag_name":\s*"\K[^"]+' || echo "v1.3.0")
$SUDO curl -sL --retry 3 -o /opt/uber-apk-signer.jar "https://github.com/patrickfav/uber-apk-signer/releases/download/${UAS_VER}/uber-apk-signer-${UAS_VER#v}.jar" || echo "uber signer download failed, will use apksigner"

echo "[5/7] Android SDK cmdline-tools (if missing)..."
if [ ! -d "$HOME/android-sdk/cmdline-tools" ]; then
  mkdir -p $HOME/android-sdk
  cd $HOME/android-sdk
  wget -q https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip -O cmd.zip
  unzip -qo cmd.zip
  mkdir -p cmdline-tools/latest
  mv cmdline-tools/* cmdline-tools/latest/ 2>/dev/null || true
  mv lib bin NOTICE.txt source.properties cmdline-tools/latest/ 2>/dev/null || true
  export ANDROID_HOME=$HOME/android-sdk
  export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin
  yes | sdkmanager --licenses >/dev/null 2>&1 || true
  sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0" >/dev/null 2>&1 || true
  cd -
fi

echo "[6/7] Python deps..."
pip3 install --quiet lxml xmltodict 2>/dev/null || true

echo "[7/7] Verifying..."
java -version
free -h | head -n 2
apktool --version 2>&1 | head -n 1 || echo "apktool not in PATH yet"
echo "Done."
