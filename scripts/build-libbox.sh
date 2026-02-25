#!/bin/bash
#============================================================================
# Сборка libbox.aar из исходников sing-box
# Требования: Go 1.22+, Android SDK, Android NDK
#============================================================================

set -euo pipefail

echo "═══ Проверка зависимостей ═══"

command -v go >/dev/null 2>&1 || { echo "Go не установлен!"; exit 1; }
echo "Go: $(go version)"

if [ -z "${ANDROID_HOME:-}" ]; then
    echo "ANDROID_HOME не установлен!"
    echo "export ANDROID_HOME=\$HOME/Android/Sdk"
    exit 1
fi

if [ -z "${ANDROID_NDK_HOME:-}" ]; then
    # Try to find NDK
    NDK_DIR=$(ls -d "$ANDROID_HOME/ndk/"* 2>/dev/null | tail -1)
    if [ -n "$NDK_DIR" ]; then
        export ANDROID_NDK_HOME="$NDK_DIR"
        echo "NDK найден: $ANDROID_NDK_HOME"
    else
        echo "ANDROID_NDK_HOME не установлен и NDK не найден!"
        exit 1
    fi
fi

echo ""
echo "═══ Установка gomobile ═══"
go install golang.org/x/mobile/cmd/gomobile@latest
go install golang.org/x/mobile/cmd/gobind@latest
gomobile init

echo ""
echo "═══ Клонирование sing-box ═══"
WORK_DIR="/tmp/sing-box-build"
rm -rf "$WORK_DIR"
git clone --depth 1 https://github.com/SagerNet/sing-box.git "$WORK_DIR"
cd "$WORK_DIR"

echo ""
echo "═══ Сборка libbox.aar ═══"
echo "Это может занять 5-10 минут..."

gomobile bind -v \
    -androidapi 26 \
    -javapkg=io.nekohasekai \
    -tags "with_quic,with_utls,with_gvisor" \
    -o libbox.aar \
    ./experimental/libbox

echo ""
echo "═══ Готово! ═══"
ls -lh libbox.aar
echo ""
echo "Скопируй в проект:"
echo "  cp $WORK_DIR/libbox.aar /path/to/android-app/app/libs/"
echo ""
