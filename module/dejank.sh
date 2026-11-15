#!/system/bin/sh
# One UI Dejank: four memory fixes, each with its own switch.
#
#   sh dejank.sh status
#   sh dejank.sh set <gpu|reserve|pin|sys> <0|1>
#   sh dejank.sh apply        everything that is switched on, now
#   sh dejank.sh resume       after a start that did not finish
#   sh dejank.sh log [lines]
#
# gpu      loads kgsl_nowait.ko: the Adreno driver takes a smaller block of
#          memory at once instead of waiting for a large one
# reserve  vm.watermark_scale_factor = 300: memory is freed in the background
#          earlier, so apps rarely have to wait for it
# pin      System UI and Home live in their own memory group that is never
#          reclaimed and never swapped
# sys      system_server is moved into a second such group; off by default
#
# Nothing is written to /system or /vendor; a reboot undoes all of it.

MODDIR=${0%/*}
DATA=${DATA:-/data/adb/oneui-dejank} # settings and log
RUN=${RUN:-/dev/.oneui-dejank}       # this boot only
PROC=${PROC:-/proc}
VM=${VM:-/proc/sys/vm}
SYSMOD=${SYSMOD:-/sys/module}
CG=${CG:-/sys/fs/cgroup}
RMDIR=${RMDIR:-rmdir}
KILL=${KILL:-kill}
NAP=${NAP:-0.1}
GRP=$CG/dejank
SGRP=$CG/dejank-sys
CONF=$DATA/conf
LOG=$DATA/log
GUARD=$DATA/starting
PAUSE=$DATA/paused
WSF=$VM/watermark_scale_factor
KPARAM=$SYSMOD/kgsl_nowait/parameters
RESERVE_TARGET=300
SYSTEMUI=com.android.systemui
SERVER=system_server
HOME_FALLBACK=com.sec.android.app.launcher

is_num() {
    case $1 in '' | *[!0-9]*) return 1 ;; esac
    return 0
}

log() {
    echo "$(date '+%m-%d %H:%M:%S') $*" >>"$LOG" 2>/dev/null
    lines=$(wc -l <"$LOG" 2>/dev/null)
    if is_num "$lines" && [ "$lines" -gt 400 ]; then
        tail -n 200 "$LOG" >"$LOG.tmp" 2>/dev/null && mv "$LOG.tmp" "$LOG"
    fi
}

# Only these four keys with 0 or 1 are read; nothing in the file is executed.
load_conf() {
    GPU=1
    RESERVE=1
    PIN=1
    SYS=0
    [ -f "$CONF" ] || return 0
    while IFS='=' read -r k v || [ -n "$k" ]; do
        case $v in 0 | 1) ;; *) continue ;; esac
        case $k in
        GPU) GPU=$v ;;
        RESERVE) RESERVE=$v ;;
        PIN) PIN=$v ;;
        SYS) SYS=$v ;;
        esac
    done <"$CONF"
    return 0
}

save_conf() {
    printf 'GPU=%s\nRESERVE=%s\nPIN=%s\nSYS=%s\n' "$GPU" "$RESERVE" "$PIN" "$SYS" >"$CONF.tmp" && mv "$CONF.tmp" "$CONF"
}

boot_id() {
    BOOT=$(cat "$PROC/sys/kernel/random/boot_id" 2>/dev/null)
    [ -n "$BOOT" ] || BOOT=unknown
}

nap() {
    sleep "$NAP" 2>/dev/null || sleep 1
}

# ----------------------------------------------------------------- gpu

# KMI: the kernel series, e.g. android16-6.12 for release 6.12.58-android16-6-...
# KO: the build of the kernel module for that series, or empty
gpu_ko() {
    KO=
    KMI=
    rel=$(uname -r 2>/dev/null)
    case $rel in
    [0-9]*.[0-9]*.*-android[0-9]*-*)
        kv=${rel%%-*}
        kv=${kv%.*}
        gen=${rel#*-}
        gen=${gen%%-*}
        case $kv$gen in *[!a-z0-9.]*) ;; *) KMI=$gen-$kv ;; esac
        ;;
    esac
    [ -n "$KMI" ] && [ -f "$MODDIR/kmod/kgsl_nowait-$KMI.ko" ] && KO=$MODDIR/kmod/kgsl_nowait-$KMI.ko
    return 0
}

# ADDR: where the driver keeps the number, or empty when this driver has none.
# The driver keeps it in a variable of its own, so the address has to come
# from the kernel's symbol list; that is read once per boot.
gpu_addr() {
    ADDR=$(cat "$RUN/gpu.addr" 2>/dev/null)
    if [ -n "$ADDR" ]; then
        [ "$ADDR" = none ] && ADDR=
        return 0
    fi
    kptr=$PROC/sys/kernel/kptr_restrict
    old=$(cat "$kptr" 2>/dev/null)
    echo 1 >"$kptr" 2>/dev/null
    grep ' kgsl_pool_retry_min_order' "$PROC/kallsyms" >"$RUN/gpu.sym" 2>/dev/null
    is_num "$old" && echo "$old" >"$kptr" 2>/dev/null
    found=0
    while read -r a t sym mod; do
        [ "$sym" = kgsl_pool_retry_min_order ] && [ "$mod" = "[msm_kgsl]" ] && ADDR=$a && found=1
    done <"$RUN/gpu.sym"
    rm -f "$RUN/gpu.sym"
    case $ADDR in
    '' | *[!0-9a-f]*) ADDR= ;;
    *[!0]*) ;;
    *)
        # all zeros: the kernel would not show the address; ask again next time
        ADDR=
        return 0
        ;;
    esac
    [ "$found" = 1 ] && [ -n "$ADDR" ] && echo "$ADDR" >"$RUN/gpu.addr" && return 0
    echo none >"$RUN/gpu.addr"
}

# GPU_STATE: on, off, err (insmod failed) or na. GPU_WHY for na:
#   driver   no Adreno driver loaded as a module
#   setting  this driver has no such number: it never waits, nothing to fix
#   kernel   the number is there, but there is no build for this kernel series
gpu_state() {
    GPU_WHY=
    GPU_WAS=
    gpu_ko
    if [ -d "$KPARAM" ]; then
        GPU_WAS=$(cat "$KPARAM/saved" 2>/dev/null)
        if [ "$(cat "$KPARAM/enabled" 2>/dev/null)" = 1 ]; then GPU_STATE=on; else GPU_STATE=off; fi
        return 0
    fi
    GPU_STATE=na
    if [ ! -d "$SYSMOD/msm_kgsl" ]; then
        GPU_WHY=driver
        return 0
    fi
    gpu_addr
    if [ -z "$ADDR" ]; then
        GPU_WHY=setting
    elif [ -z "$KO" ]; then
        GPU_WHY=kernel
    elif [ -f "$RUN/gpu.err" ]; then
        GPU_STATE=err
        GPU_WHY=insmod
    else
        GPU_STATE=off
    fi
}

gpu_on() {
    if [ -d "$KPARAM" ]; then
        echo 1 >"$KPARAM/enabled" 2>/dev/null
        return 0
    fi
    gpu_state
    [ "$GPU_STATE" = na ] && return 1
    # the module checks the name behind the address again before it writes
    if out=$(insmod "$KO" "addr=0x$ADDR" 2>&1); then
        rm -f "$RUN/gpu.err"
        log "gpu: on, the driver had $(cat "$KPARAM/saved" 2>/dev/null)"
        return 0
    fi
    : >"$RUN/gpu.err"
    log "gpu: insmod failed: $out"
    return 1
}

gpu_off() {
    rm -f "$RUN/gpu.err"
    [ -d "$SYSMOD/kgsl_nowait" ] || return 0
    rmmod kgsl_nowait 2>/dev/null || echo 0 >"$KPARAM/enabled" 2>/dev/null
    log "gpu: off"
}

# ------------------------------------------------------------- reserve

# RES_NOW, and RES_STOCK: the phone's own value, noted before the first change
reserve_state() {
    RES_NOW=
    RES_STOCK=
    if [ ! -f "$WSF" ]; then
        RES_STATE=na
        return 0
    fi
    RES_NOW=$(cat "$WSF" 2>/dev/null)
    RES_STOCK=$(cat "$RUN/reserve.stock" 2>/dev/null)
    is_num "$RES_STOCK" || RES_STOCK=$RES_NOW
    if is_num "$RES_NOW" && [ "$RES_NOW" -ge "$RESERVE_TARGET" ]; then RES_STATE=on; else RES_STATE=off; fi
}

reserve_on() {
    reserve_state
    [ "$RES_STATE" = na ] && return 1
    [ "$RES_STATE" = on ] && return 0
    [ -f "$RUN/reserve.stock" ] || echo "$RES_NOW" >"$RUN/reserve.stock"
    echo "$RESERVE_TARGET" >"$WSF" 2>/dev/null
    reserve_state
    [ "$RES_STATE" = on ] || return 1
    log "reserve: on, $RES_STOCK -> $RES_NOW"
}

reserve_off() {
    stock=$(cat "$RUN/reserve.stock" 2>/dev/null)
    is_num "$stock" || return 0
    echo "$stock" >"$WSF" 2>/dev/null
    log "reserve: off, back to $stock"
}

# ----------------------------------------------------------------- pin

pin_avail() {
    grep -qw memory "$CG/cgroup.controllers" 2>/dev/null
}

# TARGETS: System UI and the default home app. "ask" also asks the package
# manager, which is not there yet early in boot; without it the last answer is used.
pin_targets() {
    home=
    if [ "$1" = ask ]; then
        out=$(cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME 2>/dev/null | tail -n 1)
        case $out in */*) home=${out%%/*} ;; esac
        case $home in '' | android | *[!a-zA-Z0-9._]*) home= ;; esac
        [ -n "$home" ] && echo "$home" >"$DATA/home"
    fi
    [ -n "$home" ] || home=$(cat "$DATA/home" 2>/dev/null)
    case $home in '' | *[!a-zA-Z0-9._]*) home=$HOME_FALLBACK ;; esac
    TARGETS="$SYSTEMUI $home"
}

# Makes $1 a memory group that is never reclaimed and never swapped. It sits
# directly under the root, where its protection is its own setting: no budget
# from a parent, nothing shared with other apps.
grp_setup() {
    pin_avail || return 1
    if ! grep -qw memory "$CG/cgroup.subtree_control" 2>/dev/null; then
        echo +memory >"$CG/cgroup.subtree_control" 2>/dev/null
        grep -qw memory "$CG/cgroup.subtree_control" 2>/dev/null || return 1
        : >"$RUN/pin.root"
    fi
    [ -d "$1" ] || mkdir "$1" 2>/dev/null
    [ -f "$1/memory.min" ] || return 1
    echo max >"$1/memory.min" 2>/dev/null
    echo 0 >"$1/memory.swap.max" 2>/dev/null
    return 0
}

# Sends everything in group $1 back where it came from and removes the group.
# The memory controller is switched off again once neither group is left.
grp_drop() {
    if [ -d "$1" ]; then
        for q in $(cat "$1/cgroup.procs" 2>/dev/null); do
            o=$(cat "$RUN/origin.$q" 2>/dev/null)
            if [ -n "$o" ] && [ -f "$CG$o/cgroup.procs" ]; then
                echo "$q" >>"$CG$o/cgroup.procs" 2>/dev/null || echo "$q" >>"$CG/cgroup.procs" 2>/dev/null
            else
                echo "$q" >>"$CG/cgroup.procs" 2>/dev/null
            fi
            rm -f "$RUN/origin.$q"
        done
        $RMDIR "$1" 2>/dev/null
    fi
    if [ -f "$RUN/pin.root" ] && [ ! -d "$GRP" ] && [ ! -d "$SGRP" ]; then
        echo -memory >"$CG/cgroup.subtree_control" 2>/dev/null
        rm -f "$RUN/pin.root"
    fi
    return 0
}

pin_setup() {
    grp_setup "$GRP"
}

# in_group <pid> [group]
in_group() {
    grep -qx "$1" "${2:-$GRP}/cgroup.procs" 2>/dev/null
}

# move <pid> [group]
move() {
    o=$(sed -n 's/^0:://p' "$PROC/$1/cgroup" 2>/dev/null | head -n 1)
    case $o in '' | /dejank | /dejank-sys) ;; *) echo "$o" >"$RUN/origin.$1" ;; esac
    echo "$1" >>"${2:-$GRP}/cgroup.procs" 2>/dev/null
}

# AGE: seconds since process $1 started
age_of() {
    AGE=0
    up=$(cut -d. -f1 "$PROC/uptime" 2>/dev/null)
    st=$(cat "$PROC/$1/stat" 2>/dev/null)
    st=${st##*) }
    # shellcheck disable=SC2086
    set -- $st
    is_num "$up" && is_num "${20}" || return 0
    AGE=$((up - ${20} / 100))
}

# Moves the next process named $1 that is not one of $2, the moment it shows
# up: memory it gets before the move stays outside the group. 15 s at most.
catch() {
    c=0
    while [ "$c" -lt 150 ]; do
        for q in $(pidof "$1" 2>/dev/null); do
            case " $2 " in *" $q "*) continue ;; esac
            move "$q"
            return 0
        done
        nap
        c=$((c + 1))
    done
    return 1
}

# Android starts System UI and Home again by itself.
restart() {
    r_old=$(pidof "$1" 2>/dev/null)
    # shellcheck disable=SC2086
    [ -n "$r_old" ] && $KILL -9 $r_old 2>/dev/null
    catch "$1" "$r_old"
}

# Whatever of $1 runs outside the group: move it if it has only just started,
# restart it into the group otherwise.
pin_one() {
    p_old=0
    for q in $(pidof "$1" 2>/dev/null); do
        in_group "$q" && continue
        age_of "$q"
        if [ "$AGE" -lt 20 ]; then move "$q"; else p_old=1; fi
    done
    [ "$p_old" = 1 ] && restart "$1"
    return 0
}

# The watcher has to outlive the command that started it. A new one takes
# over from one that is already running.
watch_start() {
    if command -v setsid >/dev/null 2>&1; then
        setsid sh "$0" watch keep </dev/null >/dev/null 2>&1 &
    else
        nohup sh "$0" watch keep </dev/null >/dev/null 2>&1 &
    fi
}

# The watcher stops looking after a group whose mark is gone, and goes when
# neither is left.
watch_stop() {
    rm -f "$RUN/watch.$1"
    [ -f "$RUN/watch.pin" ] || [ -f "$RUN/watch.sys" ] || rm -f "$RUN/watch.token"
}

pin_on() {
    pin_setup || return 1
    pin_targets ask
    for t in $TARGETS; do pin_one "$t"; done
    log "pin: on, $TARGETS"
    watch_start
    return 0
}

pin_off() {
    watch_stop pin
    [ -d "$GRP" ] && log "pin: off"
    grp_drop "$GRP"
}

# PIN_STATE on/off/na, PIN_APPS: targets running, PIN_IN: of those, in the group
pin_state() {
    PIN_APPS=0
    PIN_IN=0
    PIN_KB=
    PIN_SWAP_KB=
    if ! pin_avail; then
        PIN_STATE=na
        return 0
    fi
    if [ ! -f "$GRP/memory.min" ]; then
        PIN_STATE=off
        return 0
    fi
    PIN_STATE=on
    pin_targets
    for t in $TARGETS; do
        for q in $(pidof "$t" 2>/dev/null); do
            PIN_APPS=$((PIN_APPS + 1))
            in_group "$q" && PIN_IN=$((PIN_IN + 1))
        done
    done
    b=$(cat "$GRP/memory.current" 2>/dev/null)
    is_num "$b" && PIN_KB=$((b / 1024))
    b=$(cat "$GRP/memory.swap.current" 2>/dev/null)
    is_num "$b" && PIN_SWAP_KB=$((b / 1024))
}

# ----------------------------------------------------------------- sys

sys_move() {
    for q in $(pidof "$SERVER" 2>/dev/null); do
        in_group "$q" "$SGRP" || move "$q" "$SGRP"
    done
}

# system_server cannot be restarted, so it is moved as it is: what it reads
# from now on belongs to the group, what it already holds does not.
sys_on() {
    grp_setup "$SGRP" || return 1
    sys_move
    log "sys: on"
    watch_start
    return 0
}

sys_off() {
    watch_stop sys
    [ -d "$SGRP" ] && log "sys: off"
    grp_drop "$SGRP"
}

# SYS_STATE on/off/na, SYS_IN: 1 when system_server is in its group
sys_state() {
    SYS_IN=0
    SYS_KB=
    SYS_SWAP_KB=
    if ! pin_avail; then
        SYS_STATE=na
        return 0
    fi
    if [ ! -f "$SGRP/memory.min" ]; then
        SYS_STATE=off
        return 0
    fi
    SYS_STATE=on
    for q in $(pidof "$SERVER" 2>/dev/null); do
        in_group "$q" "$SGRP" && SYS_IN=1
    done
    b=$(cat "$SGRP/memory.current" 2>/dev/null)
    is_num "$b" && SYS_KB=$((b / 1024))
    b=$(cat "$SGRP/memory.swap.current" 2>/dev/null)
    is_num "$b" && SYS_SWAP_KB=$((b / 1024))
    return 0
}

# ------------------------------------------------------------- watcher

# Keeps what is switched on in its group, looking again every 20 s in case
# something was restarted. At boot it also sets the groups up, early enough
# to catch System UI and Home as they start. "keep" is how a switch starts
# it: it only looks after the groups that are there, and applies nothing.
watch() {
    load_conf
    [ ! -f "$PAUSE" ] || return 0
    w_pin=0
    w_sys=0
    if [ "$1" = keep ]; then
        if [ "$PIN" = 1 ] && [ -f "$GRP/memory.min" ]; then w_pin=1; fi
        if [ "$SYS" = 1 ] && [ -f "$SGRP/memory.min" ]; then w_sys=1; fi
    else
        if [ "$PIN" = 1 ] && pin_setup; then w_pin=1; fi
        if [ "$SYS" = 1 ] && grp_setup "$SGRP"; then w_sys=1; fi
    fi
    [ "$w_pin$w_sys" != 00 ] || return 0
    token=$$
    echo "$token" >"$RUN/watch.token"
    TARGETS=
    if [ "$w_sys" = 1 ]; then
        : >"$RUN/watch.sys"
        sys_move
    fi
    if [ "$w_pin" = 1 ]; then
        : >"$RUN/watch.pin"
        pin_targets
        if [ "$1" != keep ]; then
            for t in $TARGETS; do pin_one "$t"; done
        fi
    fi
    i=0
    while [ "$(cat "$RUN/watch.token" 2>/dev/null)" = "$token" ]; do
        if [ -f "$RUN/watch.pin" ]; then
            for t in $TARGETS; do
                for q in $(pidof "$t" 2>/dev/null); do in_group "$q" || move "$q"; done
            done
        fi
        if [ -f "$RUN/watch.sys" ]; then sys_move; fi
        if [ "$i" -lt "${WATCH_ROUNDS:-900}" ]; then
            i=$((i + 1))
            sleep "${WATCH_FAST:-0.2}" 2>/dev/null || sleep 1
        else
            [ -n "$WATCH_ONCE" ] && return 0
            sleep 20
            # the default home app can change; asked every five minutes
            i=$((i + 1))
            if [ "$w_pin" = 1 ] && [ $((i % 15)) = 0 ]; then pin_targets ask; fi
        fi
    done
}

# ------------------------------------------------------------ commands

# "boot" leaves pin and sys to the watcher, which service.sh starts before this.
apply() {
    load_conf
    if [ "$GPU" = 1 ]; then gpu_on; fi
    if [ "$RESERVE" = 1 ]; then reserve_on; fi
    if [ "$PIN" = 1 ] && [ "$1" != boot ]; then pin_on; fi
    if [ "$SYS" = 1 ] && [ "$1" != boot ]; then sys_on; fi
    return 0
}

status() {
    load_conf
    gpu_state
    reserve_state
    pin_state
    sys_state
    paused=0
    [ -f "$PAUSE" ] && paused=1
    echo "api=2"
    echo "version=$(sed -n 's/^version=//p' "$MODDIR/module.prop" 2>/dev/null)"
    echo "build=$(sed -n 's/^versionCode=//p' "$MODDIR/module.prop" 2>/dev/null)"
    echo "paused=$paused"
    echo "gpu=$GPU"
    echo "gpu_state=$GPU_STATE"
    echo "gpu_why=$GPU_WHY"
    echo "gpu_was=$GPU_WAS"
    echo "gpu_series=$KMI"
    echo "reserve=$RESERVE"
    echo "reserve_state=$RES_STATE"
    echo "reserve_now=$RES_NOW"
    echo "reserve_stock=$RES_STOCK"
    echo "pin=$PIN"
    echo "pin_state=$PIN_STATE"
    echo "pin_apps=$PIN_APPS"
    echo "pin_in=$PIN_IN"
    echo "pin_kb=$PIN_KB"
    echo "pin_swap_kb=$PIN_SWAP_KB"
    echo "sys=$SYS"
    echo "sys_state=$SYS_STATE"
    echo "sys_in=$SYS_IN"
    echo "sys_kb=$SYS_KB"
    echo "sys_swap_kb=$SYS_SWAP_KB"
}

mkdir -p "$DATA" "$RUN" 2>/dev/null

case $1 in
status) status ;;
set)
    load_conf
    case $3 in 0 | 1) ;; *) echo "error=value" && exit 1 ;; esac
    case $2 in
    gpu) GPU=$3 ;;
    reserve) RESERVE=$3 ;;
    pin) PIN=$3 ;;
    sys) SYS=$3 ;;
    *) echo "error=name" && exit 1 ;;
    esac
    save_conf
    if "${2}_$([ "$3" = 1 ] && echo on || echo off)"; then echo "ok=1"; else echo "error=failed" && exit 1; fi
    ;;
apply)
    apply "$2"
    echo "ok=1"
    ;;
resume)
    rm -f "$PAUSE" "$GUARD"
    log "resumed"
    apply
    echo "ok=1"
    ;;
watch) watch "$2" ;;
guard)
    # A start that never reached "settled" (the phone went down while the
    # fixes were being applied) pauses the module instead of trying again.
    boot_id
    if [ -f "$GUARD" ] && [ "$(cat "$GUARD" 2>/dev/null)" != "$BOOT" ]; then
        : >"$PAUSE"
        rm -f "$GUARD"
        log "paused: the last start did not finish"
    fi
    [ -f "$PAUSE" ] && exit 1
    echo "$BOOT" >"$GUARD"
    sync
    ;;
settled) rm -f "$GUARD" ;;
log) tail -n "${2:-50}" "$LOG" 2>/dev/null ;;
*)
    echo "usage: dejank.sh status | set <gpu|reserve|pin|sys> <0|1> | apply | resume | log [lines]"
    exit 1
    ;;
esac
