#!/system/bin/sh
MODDIR=${0%/*}
PKG=dev.arcatva.dejank
D="sh $MODDIR/dejank.sh"

$D guard || exit 0
# System UI and Home are caught as they start, so this runs before boot ends
$D watch >/dev/null 2>&1 &

until [ "$(getprop sys.boot_completed)" = "1" ]; do
    sleep 2
done
# after the phone's own boot scripts; a start long after boot has nothing to wait for
up=$(cut -d. -f1 /proc/uptime 2>/dev/null)
case $up in '' | *[!0-9]*) up=0 ;; esac
[ "$up" -gt 300 ] || sleep "${BOOT_SETTLE:-30}"
$D apply boot >/dev/null 2>&1

# the app comes with the zip; install it here if that did not happen
want=$(sed -n 's/^versionCode=//p' "$MODDIR/module.prop")
have=$(dumpsys package "$PKG" 2>/dev/null | sed -n 's/.*versionCode=\([0-9]*\).*/\1/p' | head -n 1)
if [ -f "$MODDIR/dejank.apk" ] && [ "$have" != "$want" ]; then
    cp "$MODDIR/dejank.apk" /data/local/tmp/dejank_install.apk &&
        chmod 644 /data/local/tmp/dejank_install.apk &&
        pm install -r -d --user 0 /data/local/tmp/dejank_install.apk >/dev/null 2>&1
    rm -f /data/local/tmp/dejank_install.apk
fi

sleep "${SETTLED_AFTER:-60}"
$D settled
