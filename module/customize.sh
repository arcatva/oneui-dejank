#!/system/bin/sh
# Sourced by the root manager while the zip is installed.
APK=$MODPATH/dejank.apk
TMP_APK=/data/local/tmp/dejank_install.apk

if [ -f "$APK" ]; then
    ui_print "- Installing the Dejank app"
    out=failed
    if cp "$APK" "$TMP_APK" && chmod 644 "$TMP_APK"; then
        out=$(pm install -r -d --user 0 "$TMP_APK" 2>&1)
        # a build signed with another key cannot update the app: replace it
        case $out in *UPDATE_INCOMPATIBLE*)
            pm uninstall dev.arcatva.dejank >/dev/null 2>&1
            out=$(pm install -r -d --user 0 "$TMP_APK" 2>&1)
            ;;
        esac
    fi
    rm -f "$TMP_APK"
    case $out in
    *Success*) ;;
    *) ui_print "! App not installed ($out). Install $APK by hand." ;;
    esac
fi
# an update takes over what the version before it keeps in RAM
sh "$MODPATH/dejank.sh" takeover >/dev/null 2>&1 || true
ui_print "- Give Dejank root, open it and tap Apply. No reboot needed."
