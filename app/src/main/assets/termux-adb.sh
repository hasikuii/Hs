#!/data/data/com.termux/files/usr/bin/sh
# termux-adb — CLI wrapper that sends ADB shell commands to the running
# AdbService foreground service via am broadcast.
#
# Prerequisites:
#   1. Termux app installed and ADB pairing done (WirelessAdbActivity → Pair → Connect).
#   2. AdbService running (notification "Termux ADB" visible).
#   3. This script installed at $PREFIX/bin/adb and executable:
#        cp termux-adb.sh $PREFIX/bin/adb && chmod +x $PREFIX/bin/adb
#
# Usage:
#   adb shell ls -l /sdcard
#   adb shell pm list packages -3
#   adb shell id -u
#   adb shell logcat -d -t 50
#
# The command is sent as "shell:<args>" to AdbService's AdbCommandReceiver,
# which runs it on the held AdbConnection (UID 2000 / shell) and writes the
# output to a temp file. This script waits for the file and cats it.

set -e

OUTFILE="/tmp/adb_out_$$.txt"
rm -f "$OUTFILE"

PKG="${TERMUX_ADB_PKG:-com.termux}"
RECEIVER="$PKG/.app.adb.AdbService\$AdbCommandReceiver"

if [ $# -eq 0 ]; then
    echo "Usage: adb shell <command...>" >&2
    exit 1
fi

if [ "$1" = "shell" ]; then
    shift
    CMD="shell:$*"
else
    CMD="shell:$*"
fi

am broadcast -a com.termux.adb.RUN_COMMAND -n "$RECEIVER" \
    --es command "$CMD" \
    --es outfile "$OUTFILE" >/dev/null 2>&1

i=0
while [ $i -lt 300 ]; do
    [ -f "$OUTFILE" ] && break
    i=$((i + 1))
    sleep 0.1
done

if [ -f "$OUTFILE" ]; then
    cat "$OUTFILE"
    rm -f "$OUTFILE"
else
    echo "Lỗi: ADB service không phản hồi sau 30s." >&2
    echo "Kiểm tra: Termux app → ADB không dây → đã KẾT NỐI chưa?" >&2
    rm -f "$OUTFILE"
    exit 1
fi
