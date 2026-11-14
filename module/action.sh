#!/system/bin/sh
# The Action button in the root manager.
MODDIR=${0%/*}
sh "$MODDIR/dejank.sh" status
echo
sh "$MODDIR/dejank.sh" log 15
