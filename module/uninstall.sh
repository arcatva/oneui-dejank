#!/system/bin/sh
# Runs when the module is removed. What was applied goes away with the reboot.
rm -rf /data/adb/oneui-dejank
# the app too, once the package manager is up
(
    until [ "$(getprop sys.boot_completed)" = "1" ]; do
        sleep 2
    done
    pm uninstall dev.arcatva.dejank >/dev/null 2>&1
) &
