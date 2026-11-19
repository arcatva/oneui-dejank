#!/system/bin/sh
# One UI Dejank: memory fixes, each with its own switch.
#
#   sh dejank.sh status
#   sh dejank.sh set <name> <0|1>
#   sh dejank.sh apply        everything that is switched on, now
#   sh dejank.sh resume       after a start that did not finish
#   sh dejank.sh log [lines]
#
# gpu      loads kgsl_nowait.ko: the Adreno driver takes a smaller block of
#          memory at once instead of waiting for a large one
# kvmalloc loads kvmalloc_nowait.ko: a kernel request that can do without
#          contiguous memory no longer frees memory on the spot to get it,
#          as from Linux 6.16; off unless switched on
# reserve  vm.watermark_scale_factor = 300: memory is freed in the background
#          earlier, so apps rarely have to wait for it
# shaderc  keeps the Adreno driver's shader compiler in RAM; off unless
#          switched on
#
# Kept in RAM, one switch and one memory group per process:
#   ui home   System UI and the home app; on by default, restarted once into
#             their groups
#   server    system_server; moved as it is
# A process that is moved as it is brings nothing along: what it holds stays
# with the group it came from, and only what it allocates from then on counts
# for its new one. SurfaceFlinger and the two display services allocate next
# to nothing after boot, so their floors came to a few megabytes at most.
# Their switches are gone, and their groups are taken down by this version.
# Each group gets a floor the kernel does not reclaim below. The rules are
# those of Android's SystemMemoryBudgetManager as its documentation gives them
# (source.android.com, "System memory budgets"; its source is not public yet):
#   - a floor is nine tenths of what the process holds, without the file cache
#     nothing has mapped
#   - all floors are worked out together, 1, 4, 16 and 64 s after the start and
#     every half hour after that; the start is the watcher's, as it is the
#     service's after boot there
#   - one thing is not Android's: a floor only goes up while its process lives
#     (see floor)
#   - together they hold at most a tenth of the RAM; when they would hold more,
#     every floor shrinks by the same factor
#   - swap is left as the system has it
#   - 10 s after boot has completed, the file cache nothing has mapped is asked
#     back from system_server's group, once
# Android writes a floor at three levels of its own groups because a parent
# caps its children. A group here has no parent but the root, so one write
# does the same. Where Android runs that service itself, these switches cannot
# be turned on.
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
# where the watcher goes to be nobody's child: the top of each hierarchy
FREE=${FREE-"$CG /acct /dev/cg2_bpf /dev/freezer"}
# and where it takes its share of the CPUs from
BACK=${BACK-"/dev/cpuset /dev/cpuctl /dev/blkio"}
REVIVE=${REVIVE:-1}
NAP=${NAP:-0.1}
KEEP="ui home server"
RETIRED="sf composer alloc" # switches of versions before this one
SETTLE=${SETTLE:-"1 4 16 64"} # seconds after the start at which the floors are worked out
REFRESH=${REFRESH:-1800}      # and seconds between two looks after that
FLOOR_PCT=90                  # how much of what a process works with is kept
CAP_PCT=10                    # all floors together, in percent of the RAM
CONF=$DATA/conf
LOG=$DATA/log
GUARD=$DATA/starting
PAUSE=$DATA/paused
WSF=$VM/watermark_scale_factor
KPARAM=$SYSMOD/kgsl_nowait/parameters
KVPARAM=$SYSMOD/kvmalloc_nowait/parameters
KV_HOOK=__traceiter_android_vh_adjust_kvmalloc_flags
KV_VERSION=2 # of kvmalloc_nowait.ko as this script expects it
KV_TRY=$DATA/kvmalloc.trying   # the boot and second the hook was switched on in, until it has held
KV_TRIP=$DATA/kvmalloc.tripped # the phone went down before that
KV_HOLD=${KV_HOLD:-3}
RESERVE_TARGET=300
SC_FILES=${SC_FILES:-/vendor/lib64/libllvm-qgl.so}
SC_BIN=$MODDIR/bin/hold
SC_PID=$RUN/shaderc.pid
SYSTEMUI=com.android.systemui
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

# Only these keys with 0 or 1 are read; nothing in the file is executed.
load_conf() {
    GPU=1
    KV=0
    RESERVE=1
    SC=0
    K_ui=1
    K_home=1
    K_server=0
    c_pin=
    c_sys=
    c_new=0
    [ -f "$CONF" ] || return 0
    while IFS='=' read -r k v || [ -n "$k" ]; do
        case $v in 0 | 1) ;; *) continue ;; esac
        case $k in
        GPU) GPU=$v ;;
        KVMALLOC) KV=$v ;;
        RESERVE) RESERVE=$v ;;
        SHADERC) SC=$v ;;
        KEEP_UI) K_ui=$v && c_new=1 ;;
        KEEP_HOME) K_home=$v && c_new=1 ;;
        KEEP_SERVER) K_server=$v && c_new=1 ;;
        PIN) c_pin=$v ;;
        SYS) c_sys=$v ;;
        esac
    done <"$CONF"
    # settings from before 0.2: one switch for System UI and Home together
    if [ "$c_new" = 0 ]; then
        if [ -n "$c_pin" ]; then K_ui=$c_pin && K_home=$c_pin; fi
        if [ -n "$c_sys" ]; then K_server=$c_sys; fi
    fi
    return 0
}

save_conf() {
    printf 'GPU=%s\nKVMALLOC=%s\nRESERVE=%s\nSHADERC=%s\nKEEP_UI=%s\nKEEP_HOME=%s\nKEEP_SERVER=%s\n' \
        "$GPU" "$KV" "$RESERVE" "$SC" "$K_ui" "$K_home" "$K_server" >"$CONF.tmp" && mv "$CONF.tmp" "$CONF"
}

# wanted <key>: is this switch on in the settings?
wanted() {
    eval "w=\$K_$1"
    [ "$w" = 1 ]
}

boot_id() {
    BOOT=$(cat "$PROC/sys/kernel/random/boot_id" 2>/dev/null)
    [ -n "$BOOT" ] || BOOT=unknown
}

nap() {
    sleep "$NAP" 2>/dev/null || sleep 1
}

# detach <command>: this script once more, on its own and outliving the caller
detach() {
    if command -v setsid >/dev/null 2>&1; then
        setsid sh "$0" "$@" </dev/null >/dev/null 2>&1 &
    else
        nohup sh "$0" "$@" </dev/null >/dev/null 2>&1 &
    fi
}

# ----------------------------------------------------------------- gpu

# KMI: the kernel series, e.g. android16-6.12 for release 6.12.58-android16-6-...
# KMAJ, KMIN: the kernel version, 6 and 12 there; empty when it cannot be read
series() {
    KMI=
    KMAJ=
    KMIN=
    rel=$(uname -r 2>/dev/null)
    kv=${rel%%-*}
    case $kv in
    [0-9]*.[0-9]*)
        KMAJ=${kv%%.*}
        KMIN=${kv#*.}
        KMIN=${KMIN%%.*}
        case $KMAJ$KMIN in *[!0-9]*) KMAJ= && KMIN= ;; esac
        ;;
    esac
    case $rel in
    [0-9]*.[0-9]*.*-android[0-9]*-*)
        kv=${kv%.*}
        gen=${rel#*-}
        gen=${gen%%-*}
        case $kv$gen in *[!a-z0-9.]*) ;; *) KMI=$gen-$kv ;; esac
        ;;
    esac
    return 0
}

# KO: the build of the kernel module for this kernel series, or empty
gpu_ko() {
    KO=
    series
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

# ------------------------------------------------------------ kvmalloc

# Whether this kernel has Android's hook into kvmalloc(); asked once per boot.
kv_hook() {
    first "$RUN/kvmalloc.hook"
    case $VAL in 0) return 1 ;; 1) return 0 ;; esac
    [ -r "$PROC/kallsyms" ] || return 0
    if grep -q " $KV_HOOK\$" "$PROC/kallsyms" 2>/dev/null; then
        echo 1 >"$RUN/kvmalloc.hook"
        return 0
    fi
    echo 0 >"$RUN/kvmalloc.hook"
    return 1
}

# KV_STATE: on, off, err or na. KV_WHY:
#   builtin  Linux 6.16 and later do not wait here in the first place
#   kernel   there is no build for this kernel series
#   hook     this kernel is without Android's hook
#   insmod   the kernel did not take the module
#   reboot   the phone went down right after the hook was switched on
# KV_SEEN, KV_CHANGED: requests of more than a page, and how many of their
# first attempts lost the wait
kv_state() {
    KV_WHY=
    KV_SEEN=
    KV_CHANGED=
    if [ -d "$KVPARAM" ]; then
        first "$KVPARAM/seen" && KV_SEEN=$VAL
        first "$KVPARAM/changed" && KV_CHANGED=$VAL
        first "$KVPARAM/enabled"
        if [ "$VAL" = 1 ]; then KV_STATE=on; else KV_STATE=off; fi
        return 0
    fi
    KV_STATE=na
    KVKO=
    [ -n "$KMAJ" ] || series
    if [ -n "$KMAJ" ] && { [ "$KMAJ" -gt 6 ] || { [ "$KMAJ" = 6 ] && [ "$KMIN" -ge 16 ]; }; }; then
        KV_WHY=builtin
        return 0
    fi
    [ -n "$KMI" ] && [ -f "$MODDIR/kmod/kvmalloc_nowait-$KMI.ko" ] && KVKO=$MODDIR/kmod/kvmalloc_nowait-$KMI.ko
    if [ -z "$KVKO" ]; then
        KV_WHY=kernel
    elif ! kv_hook; then
        KV_WHY=hook
    elif [ -f "$KV_TRIP" ]; then
        KV_STATE=err
        KV_WHY=reboot
    elif [ -f "$RUN/kvmalloc.err" ]; then
        KV_STATE=err
        KV_WHY=insmod
    else
        KV_STATE=off
    fi
}

# The hook is switched on with a note of the boot and the second, and the
# note goes once the hook has held for KV_HOLD seconds. A note from another
# boot means the phone went down in between: the switch goes off and stays
# off until someone switches it on again. Needs the settings loaded.
kv_guard() {
    [ -f "$KV_TRY" ] || return 0
    boot_id
    uptime_s
    read -r kt_boot kt_up 2>/dev/null <"$KV_TRY"
    if [ "$kt_boot" != "$BOOT" ]; then
        rm -f "$KV_TRY"
        : >"$KV_TRIP"
        KV=0
        save_conf
        log "kvmalloc: the phone went down right after the hook was switched on; left off"
        return 0
    fi
    is_num "$kt_up" || kt_up=0
    [ $((UP - kt_up)) -ge "$KV_HOLD" ] && rm -f "$KV_TRY"
    return 0
}

# A module left loaded by another version of this script does something else
# or counts something else: it goes, so that the one of this version comes.
kv_stale() {
    [ -d "$SYSMOD/kvmalloc_nowait" ] || return 1
    first "$SYSMOD/kvmalloc_nowait/version"
    [ "$VAL" != "$KV_VERSION" ]
}

kv_on() {
    rm -f "$KV_TRIP"
    if kv_stale; then
        rmmod kvmalloc_nowait 2>/dev/null || return 1
        log "kvmalloc: the module of another version is out"
    fi
    if [ -d "$KVPARAM" ]; then
        echo 1 >"$KVPARAM/enabled" 2>/dev/null
        return 0
    fi
    kv_state
    [ "$KV_STATE" = na ] && return 1
    boot_id
    uptime_s
    echo "$BOOT $UP" >"$KV_TRY"
    sync
    if out=$(insmod "$KVKO" 2>&1); then
        rm -f "$RUN/kvmalloc.err"
        log "kvmalloc: on"
        if [ "$KV_HOLD" = 0 ]; then rm -f "$KV_TRY"; else detach kvheld; fi
        return 0
    fi
    rm -f "$KV_TRY"
    : >"$RUN/kvmalloc.err"
    log "kvmalloc: insmod failed: $out"
    return 1
}

kv_off() {
    rm -f "$RUN/kvmalloc.err" "$KV_TRIP" "$KV_TRY"
    [ -d "$SYSMOD/kvmalloc_nowait" ] || return 0
    first "$KVPARAM/changed"
    rmmod kvmalloc_nowait 2>/dev/null || echo 0 >"$KVPARAM/enabled" 2>/dev/null
    log "kvmalloc: off, ${VAL:-0} requests did not wait"
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

# ------------------------------------------------------------- shaderc

# The first time an app draws in a way it has not drawn before, the Adreno
# driver compiles a shader for it, in the middle of a frame, with the code in
# libllvm-qgl.so. Nothing else runs that code, so the kernel drops the file
# from RAM, and the compile then stops for flash again and again: 10 to 112 ms
# of a frame in the traces this was found in, on top of the compile itself.
# The file is kept in RAM the way Android's PinnerService keeps the files on
# its list, mapped and locked, by a small program that stays (native/hold.c).
# Pixel 5 has two libraries of the same driver on that list for the same
# reason; Samsung's list is empty.

# Whether the program that holds the file is there.
sc_runs() {
    first "$SC_PID"
    is_num "$VAL" || return 1
    grep -q 'bin/hold' "$PROC/$VAL/cmdline" 2>/dev/null
}

# SC_STATE: on, off, err or na. SC_WHY:
#   file   this phone has no such library: another GPU, or another driver
#   build  this build of the module came without the program
#   start  the program did not get to hold the file
# SC_KB: what is held, in kB
sc_state() {
    SC_WHY=
    SC_KB=
    SCF=
    for s_f in $SC_FILES; do
        [ -f "$s_f" ] && SCF="$SCF $s_f"
    done
    if sc_runs; then
        SC_STATE=on
        while read -r s_k s_v _; do
            [ "$s_k" = VmLck: ] && SC_KB=$s_v && break
        done 2>/dev/null <"$PROC/$VAL/status"
        is_num "$SC_KB" || SC_KB=
        return 0
    fi
    if [ -z "$SCF" ]; then
        SC_STATE=na
        SC_WHY=file
    elif [ ! -f "$SC_BIN" ]; then
        SC_STATE=na
        SC_WHY=build
    elif [ -f "$RUN/shaderc.err" ]; then
        SC_STATE=err
        SC_WHY=start
    else
        SC_STATE=off
    fi
}

# The program returns once the file is held, or with what went wrong.
sc_on() {
    rm -f "$RUN/shaderc.err"
    sc_state
    [ "$SC_STATE" = na ] && return 1
    if [ "$SC_STATE" != on ]; then
        rm -f "$SC_PID"
        chmod 755 "$SC_BIN" 2>/dev/null
        # shellcheck disable=SC2086
        out=$("$SC_BIN" "$SC_PID" $SCF 2>&1 </dev/null)
        if ! sc_runs; then
            rm -f "$SC_PID" "$RUN/shaderc.on"
            : >"$RUN/shaderc.err"
            log "shaderc: not held: ${out:-the program did not stay}"
            return 1
        fi
        # started by a shell of the app, it would be frozen and killed with it
        free_pid "$VAL"
        sc_state
        log "shaderc: on, ${SC_KB:-?} kB held"
    fi
    : >"$RUN/shaderc.on"
    return 0
}

sc_off() {
    rm -f "$RUN/shaderc.err" "$RUN/shaderc.on"
    if sc_runs; then
        $KILL -9 "$VAL" 2>/dev/null
        log "shaderc: off"
    fi
    rm -f "$SC_PID"
    return 0
}

# A program that is gone is started again by whoever looks next, as the
# watcher is. One that did not get to hold the file is not tried again.
sc_revive() {
    [ -f "$RUN/shaderc.on" ] && [ ! -f "$PAUSE" ] && [ "$REVIVE" = 1 ] || return 0
    sc_runs && return 0
    log "shaderc: the program was gone, started again"
    sc_on
    return 0
}

# ---------------------------------------------------------------- keep

# KIB: a byte count in KiB. The shell on Android counts in 32 bits, so the
# last three digits are dropped before any arithmetic.
kib() {
    KIB=
    case $1 in '' | *[!0-9]*) return 1 ;; esac
    case $1 in
    ?????????????*) KIB=2000000000 ;;
    ????*)
        kb_=${1%???}
        if [ "$kb_" -lt 17000000 ]; then KIB=$((kb_ * 125 / 128)); else KIB=$((kb_ / 128 * 125)); fi
        ;;
    *) KIB=0 ;;
    esac
    return 0
}

# VAL: the first line of file $1, read without starting a program
first() {
    VAL=
    [ -f "$1" ] && read -r VAL 2>/dev/null <"$1"
    return 0
}

uptime_s() {
    first "$PROC/uptime"
    UP=${VAL%%.*}
    is_num "$UP" || UP=0
}

# MEMKB: the RAM of this phone in kB
mem_total() {
    MEMKB=
    while read -r m_k m_v _; do
        [ "$m_k" = MemTotal: ] && MEMKB=$m_v && break
    done 2>/dev/null <"$PROC/meminfo"
    is_num "$MEMKB" || MEMKB=
}

keep_avail() {
    grep -qw memory "$CG/cgroup.controllers" 2>/dev/null
}

# G: the memory group of key $1
grp() {
    G=$CG/dejank-$1
}

# has_proc <group>: a process is in it. The kernel takes a process that ended
# off the list, so a group that still has one needs no look at the process list.
has_proc() {
    first "$1/cgroup.procs"
    [ -n "$VAL" ]
}

any_group() {
    for a in $KEEP $RETIRED; do
        [ -d "$CG/dejank-$a" ] && return 0
    done
    [ -d "$CG/dejank" ] || [ -d "$CG/dejank-sys" ]
}

# HOMEAPP: the default home app. "ask" also asks the package manager, which is
# not there yet early in boot; without it the last answer is used.
home_app() {
    HOMEAPP=
    if [ "$1" = ask ]; then
        out=$(cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME 2>/dev/null | tail -n 1)
        case $out in */*) HOMEAPP=${out%%/*} ;; esac
        case $HOMEAPP in '' | android | *[!a-zA-Z0-9._]*) HOMEAPP= ;; esac
        [ -n "$HOMEAPP" ] && echo "$HOMEAPP" >"$DATA/home"
    fi
    [ -n "$HOMEAPP" ] || HOMEAPP=$(cat "$DATA/home" 2>/dev/null)
    case $HOMEAPP in '' | *[!a-zA-Z0-9._]*) HOMEAPP=$HOME_FALLBACK ;; esac
}

# PIDS: the pids of the process behind key $1
pids_of() {
    case $1 in
    ui) PIDS=$(pidof "$SYSTEMUI" 2>/dev/null) ;;
    home)
        [ -n "$HOMEAPP" ] || home_app
        PIDS=$(pidof "$HOMEAPP" 2>/dev/null)
        ;;
    server) PIDS=$(pidof system_server 2>/dev/null) ;;
    *) PIDS= ;;
    esac
}

# OFFICIAL: 1 when this Android keeps these processes in RAM itself, 0 when it
# does not, empty while that cannot be told. Android 17 has a service for it,
# SystemMemoryBudgetManager; where it is at work it has set a floor on
# Android's own groups. The answer is kept for this build of the system.
official_check() {
    OFFICIAL=
    out=$(dumpsys system_server_dumper --name SystemMemoryBudgetManager 2>&1 | head -n 1)
    case $out in
    "No dumpable named"*) OFFICIAL=0 ;;
    '' | *"an't find service"* | *"oes not exist"*)
        # no answer: system_server is not up yet, or this Android has no such thing
        [ "$(getprop sys.boot_completed 2>/dev/null)" = 1 ] && OFFICIAL=0
        ;;
    *)
        OFFICIAL=0
        for a in system apps; do
            v=$(cat "$CG/$a/memory.min" 2>/dev/null)
            case $v in '' | 0) ;; *) OFFICIAL=1 ;; esac
        done
        ;;
    esac
    fp=$(getprop ro.build.fingerprint 2>/dev/null)
    case $fp in '' | *' '*) return 0 ;; esac
    [ -n "$OFFICIAL" ] && echo "$fp $OFFICIAL" >"$DATA/official"
    return 0
}

official_known() {
    OFFICIAL=
    fp=$(getprop ro.build.fingerprint 2>/dev/null)
    if [ -n "$fp" ] && [ -f "$DATA/official" ]; then
        read -r c_fp c_v <"$DATA/official"
        if [ "$c_fp" = "$fp" ]; then
            case $c_v in 0 | 1) OFFICIAL=$c_v && return 0 ;; esac
        fi
    fi
    official_check
}

# Makes $1 a memory group. It sits directly under the root, where its
# protection is its own setting: no budget from a parent, nothing shared with
# other apps. It has no floor until the floors are worked out, a second after
# the watcher starts. Swap is as the system has it; versions before this one
# had switched it off for the group.
grp_setup() {
    keep_avail || return 1
    if ! grep -qw memory "$CG/cgroup.subtree_control" 2>/dev/null; then
        echo +memory >"$CG/cgroup.subtree_control" 2>/dev/null
        grep -qw memory "$CG/cgroup.subtree_control" 2>/dev/null || return 1
        : >"$RUN/pin.root"
    fi
    [ -d "$1" ] || mkdir "$1" 2>/dev/null
    [ -f "$1/memory.min" ] || return 1
    echo max >"$1/memory.swap.max" 2>/dev/null
    return 0
}

# Sends everything in group $1 back where it came from and removes the group.
# The memory controller is switched off again once no group is left.
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
    if [ -f "$RUN/pin.root" ] && ! any_group; then
        echo -memory >"$CG/cgroup.subtree_control" 2>/dev/null
        rm -f "$RUN/pin.root"
    fi
    return 0
}

# Versions before 0.2 kept System UI and Home in one group, and a test build
# had one for system_server. Whatever is still in them goes back.
legacy_drop() {
    rm -f "$RUN/watch.pin" "$RUN/watch.sys"
    grp_drop "$CG/dejank"
    grp_drop "$CG/dejank-sys"
    for l_d in $RETIRED; do
        rm -f "$RUN/watch.$l_d" "$RUN/raw.$l_d" "$RUN/of.$l_d" "$RUN/since.$l_d" "$RUN/floored.$l_d"
        grp_drop "$CG/dejank-$l_d"
    done
}

# in_group <pid> <group>
in_group() {
    grep -qx "$1" "$2/cgroup.procs" 2>/dev/null
}

# move <pid> <group>. The group keeps the floor it has until the floors are
# next worked out.
move() {
    o=$(sed -n 's/^0:://p' "$PROC/$1/cgroup" 2>/dev/null | head -n 1)
    case $o in '' | /dejank | /dejank-*) ;; *) echo "$o" >"$RUN/origin.$1" ;; esac
    echo "$1" >>"$2/cgroup.procs" 2>/dev/null
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

# catch <name> <old pids> <group>: moves the next process of that name
# that is not one of the old ones, the moment it shows up; memory it gets
# before the move stays outside the group. 15 s at most.
catch() {
    c=0
    while [ "$c" -lt 150 ]; do
        for q in $(pidof "$1" 2>/dev/null); do
            case " $2 " in *" $q "*) continue ;; esac
            move "$q" "$3"
            return 0
        done
        nap
        c=$((c + 1))
    done
    return 1
}

# restart <name> <group>. Android starts System UI and Home again by itself.
restart() {
    r_old=$(pidof "$1" 2>/dev/null)
    # shellcheck disable=SC2086
    [ -n "$r_old" ] && $KILL -9 $r_old 2>/dev/null
    catch "$1" "$r_old" "$2"
}

# place <key>: brings the process behind a key into its group. An app that
# runs outside it is moved if it has only just started and restarted into the
# group otherwise; a system process is always moved as it is, so what it
# already holds stays outside.
place() {
    grp "$1"
    case $1 in
    ui | home)
        if [ "$1" = ui ]; then p_name=$SYSTEMUI; else p_name=$HOMEAPP; fi
        p_old=0
        for q in $(pidof "$p_name" 2>/dev/null); do
            in_group "$q" "$G" && continue
            age_of "$q"
            if [ "$AGE" -lt 20 ]; then move "$q" "$G"; else p_old=1; fi
        done
        [ "$p_old" = 1 ] && restart "$p_name" "$G"
        ;;
    *)
        pids_of "$1"
        for q in $PIDS; do
            in_group "$q" "$G" || move "$q" "$G"
        done
        ;;
    esac
    return 0
}

# UNMAPPED: the file cache in group $1 that nothing has mapped, in KiB: what a
# process read or wrote once and does not hold on to.
unmapped() {
    u_file=0
    u_shmem=0
    u_mapped=0
    while read -r k v; do
        case $k in
        file) kib "$v" && u_file=$KIB ;;
        shmem) kib "$v" && u_shmem=$KIB ;;
        file_mapped) kib "$v" && u_mapped=$KIB ;;
        esac
    done 2>/dev/null <"$1/memory.stat"
    UNMAPPED=$((u_file - u_shmem - u_mapped))
    [ "$UNMAPPED" -gt 0 ] || UNMAPPED=0
}

# The floor of key $1 before the cap: nine tenths of what its group holds once
# the unmapped file cache is taken off. Kept in KiB for floors_write.
# It does not follow its process down. What a process holds shrinks while it
# waits in the background, because the kernel takes what lies above the floor;
# a floor worked out then would be the smaller for it, and the part the
# process grows back on its return would have no floor at all. So the highest
# figure stands for as long as the same process is in the group.
floor() {
    grp "$1"
    first "$G/cgroup.procs"
    f_pid=$VAL
    first "$G/memory.current"
    kib "$VAL" || return 1
    f_cur=$KIB
    unmapped "$G"
    f_keep=$((f_cur - UNMAPPED))
    [ "$f_keep" -gt 0 ] || f_keep=0
    f_new=$((f_keep / 100 * FLOOR_PCT))
    first "$RUN/of.$1"
    f_of=$VAL
    first "$RUN/raw.$1"
    if [ -n "$f_pid" ] && [ "$f_of" = "$f_pid" ] && is_num "$VAL" && [ "$VAL" -gt "$f_new" ]; then
        f_new=$VAL
    fi
    echo "$f_pid" >"$RUN/of.$1"
    echo "$f_new" >"$RUN/raw.$1"
}

# Writes the floors of all groups. Together they may hold a tenth of the RAM;
# when they would hold more, every floor shrinks by the same factor.
floors_write() {
    w_sum=0
    for a in $KEEP; do
        first "$RUN/raw.$a"
        is_num "$VAL" && w_sum=$((w_sum + VAL))
    done
    w_pct=100
    mem_total
    if [ -n "$MEMKB" ]; then
        w_cap=$((MEMKB / 100 * CAP_PCT))
        [ "$w_sum" -gt "$w_cap" ] && w_pct=$((w_cap * 100 / w_sum))
    fi
    for a in $KEEP; do
        first "$RUN/raw.$a"
        is_num "$VAL" || continue
        [ "$w_pct" = 100 ] || VAL=$((VAL / 100 * w_pct))
        grp "$a"
        echo "${VAL}K" >"$G/memory.min" 2>/dev/null
    done
    return 0
}

# The floors are counted from now.
floors_begin() {
    uptime_s
    echo "$UP" >"$RUN/floor.start"
    rm -f "$RUN/floor.step" "$RUN/floor.last"
}

# Works out all floors when they are due: 1, 4, 16 and 64 s after the start,
# then every half hour. A look that comes late does the steps it missed in
# one. A group whose process is not there keeps the floor it had.
floors() {
    uptime_s
    first "$RUN/floor.start"
    f_start=$VAL
    is_num "$f_start" || return 0
    first "$RUN/floor.step"
    f_step=$VAL
    is_num "$f_step" || f_step=0
    f_n=0
    f_all=0
    for f_t in $SETTLE; do
        f_all=$((f_all + 1))
        [ $((UP - f_start)) -ge "$f_t" ] && f_n=$((f_n + 1))
    done
    if [ "$f_n" -gt "$f_step" ]; then
        f_step=$f_n
    else
        # still in the first minute, or looked at less than half an hour ago
        [ "$f_step" -ge "$f_all" ] || return 0
        first "$RUN/floor.last"
        is_num "$VAL" && [ $((UP - VAL)) -lt "$REFRESH" ] && return 0
    fi
    for a in $KEEP; do
        [ -f "$RUN/watch.$a" ] || continue
        grp "$a"
        has_proc "$G" && floor "$a"
    done
    echo "$f_step" >"$RUN/floor.step"
    echo "$UP" >"$RUN/floor.last"
    floors_write
    return 0
}

# While it scans the packages at boot, system_server reads far more files than
# it goes on using. Android asks that cache back from its group once, 10 s
# after boot has completed, and nothing is swapped for it. A kernel that does
# not know the request refuses it, and then it is left at that.
boot_reclaim() {
    [ -f "$RUN/watch.server" ] || return 0
    grp server
    has_proc "$G" || return 0
    unmapped "$G"
    [ "$UNMAPPED" -gt 0 ] || return 0
    if echo "${UNMAPPED}K swappiness=0" >"$G/memory.reclaim" 2>/dev/null; then
        log "server: asked $UNMAPPED KiB of unmapped file cache back"
    fi
    return 0
}

# The watcher has to outlive the command that started it. A new one takes
# over from one that is already running.
watch_start() {
    floors_begin
    # on its way: nobody takes it for gone before it has put its own name there
    echo "start $UP" >"$RUN/watch.token"
    detach watch keep
}

# A root shell that an app starts sits in that app's cgroup: Android freezes
# the group when the app leaves the screen and kills it with the app. So
# whatever is to stay goes to the top of each hierarchy, where a daemon lives.
# free_pid <pid>
free_pid() {
    for w_cg in $FREE; do
        [ -f "$w_cg/cgroup.procs" ] && echo "$1" >>"$w_cg/cgroup.procs" 2>/dev/null
    done
    # it has the CPU share of the app that started it, the one on the screen:
    # the background's is its own, or failing that nobody's
    for w_cg in $BACK; do
        if [ -f "$w_cg/system-background/cgroup.procs" ]; then
            echo "$1" >>"$w_cg/system-background/cgroup.procs" 2>/dev/null
        elif [ -f "$w_cg/cgroup.procs" ]; then
            echo "$1" >>"$w_cg/cgroup.procs" 2>/dev/null
        fi
    done
    [ -f "$PROC/$1/oom_score_adj" ] && echo -1000 >"$PROC/$1/oom_score_adj" 2>/dev/null
    return 0
}

watch_free() {
    free_pid $$
}

# Whether the watcher that holds the token is still there.
watch_runs() {
    first "$RUN/watch.token"
    case $VAL in
    "start "*)
        w_at=${VAL#start }
        uptime_s
        is_num "$w_at" && [ $((UP - w_at)) -lt 30 ]
        return
        ;;
    esac
    is_num "$VAL" || return 1
    [ -f "$PROC/$VAL/cmdline" ] || return 1
    grep -q 'dejank\.sh' "$PROC/$VAL/cmdline" 2>/dev/null
}

# A watcher that is gone is started again by whoever looks next: the app asks
# for the status every time it is opened. WATCHER: 1 when one is running.
watch_revive() {
    WATCHER=0
    [ ! -f "$PAUSE" ] && any_mark || return 0
    if watch_runs; then
        WATCHER=1
    elif [ "$REVIVE" = 1 ]; then
        log "watcher: was gone, started again"
        watch_start
        WATCHER=1
    fi
    return 0
}

any_mark() {
    for a in $KEEP; do
        [ -f "$RUN/watch.$a" ] && return 0
    done
    return 1
}

# keep_on <key> [quiet]: "quiet" leaves starting the watcher to the caller
keep_on() {
    official_known
    [ "$OFFICIAL" = 1 ] && return 1
    grp "$1"
    grp_setup "$G" || return 1
    [ "$1" = home ] && home_app ask
    place "$1"
    k_in=0
    has_proc "$G" && k_in=1
    case $1:$k_in in
    ui:* | home:* | *:1) ;;
    *)
        # no such process on this phone
        grp_drop "$G"
        return 1
        ;;
    esac
    : >"$RUN/watch.$1"
    log "keep $1: on"
    [ "$2" = quiet ] || watch_start
    return 0
}

# The floors that stay may grow back to their full size.
keep_off() {
    rm -f "$RUN/watch.$1" "$RUN/raw.$1" "$RUN/of.$1" "$RUN/since.$1" "$RUN/floored.$1"
    any_mark || rm -f "$RUN/watch.token"
    grp "$1"
    [ -d "$G" ] && log "keep $1: off"
    grp_drop "$G"
    floors_write
}

keep_drop_all() {
    for d in $KEEP; do keep_off "$d"; done
    legacy_drop
}

# keep_state <key>. KS: on, wait (the group is there, its process is not in it
# yet), off, na (cannot be used here, see KEEP_WHY) or none (this phone has no
# such process). KKB: what the group holds; KMIN: its floor.
keep_state() {
    KS=off
    KKB=
    KMIN=
    if [ -n "$KEEP_WHY" ]; then
        KS=na
        return 0
    fi
    grp "$1"
    k_found=0
    k_in=0
    if has_proc "$G"; then
        k_found=1
        k_in=1
    else
        pids_of "$1"
        for q in $PIDS; do
            k_found=1
            in_group "$q" "$G" && k_in=1
        done
    fi
    if [ ! -f "$G/memory.min" ]; then
        case $1:$k_found in ui:* | home:* | *:1) ;; *) KS=none ;; esac
        return 0
    fi
    if [ "$k_in" = 1 ]; then KS=on; else KS=wait; fi
    first "$G/memory.current"
    kib "$VAL" && KKB=$KIB
    first "$G/memory.min"
    v=$VAL
    case $v in
    max) KMIN=max ;;
    *K) KMIN=${v%K} ;;
    *) kib "$v" && KMIN=$KIB ;;
    esac
    return 0
}

# ------------------------------------------------------------- watcher

# Keeps every process that is switched on in its group and works out the
# floors when they are due. It looks five times a second for its first three
# minutes, so the floors come at their seconds, and every 20 s after that; a
# look at a group that has its process starts no program. At boot it also sets
# the groups up, early enough to catch System UI and Home as they start, and
# asks system_server's boot cache back. "keep" is how a switch or an update
# starts it: it only looks after the groups that are there, and applies nothing.
watch() {
    load_conf
    [ ! -f "$PAUSE" ] || return 0
    watch_free
    w_boot=0
    if [ "$1" != keep ]; then
        # only once it is known whether this Android does the same itself
        official_known
        o_wait=0
        while [ -z "$OFFICIAL" ] && [ "$o_wait" -lt "${OFFICIAL_WAIT:-300}" ]; do
            sleep 1
            official_check
            o_wait=$((o_wait + 1))
        done
        if [ "$OFFICIAL" = 1 ]; then
            keep_drop_all
            return 0
        fi
        home_app
        for d in $KEEP; do
            wanted "$d" || continue
            grp "$d"
            grp_setup "$G" || continue
            : >"$RUN/watch.$d"
            place "$d"
        done
        legacy_drop
        floors_begin
        w_boot=1
    fi
    any_mark || return 0
    [ -f "$RUN/floor.start" ] || floors_begin
    token=$$
    echo "$token" >"$RUN/watch.token"
    log "watcher: started"
    home_app
    for d in $KEEP; do
        [ -f "$RUN/watch.$d" ] || continue
        grp "$d"
        # a version before this one had switched swap off for its groups
        [ -f "$G/memory.swap.max" ] && echo max >"$G/memory.swap.max" 2>/dev/null
    done
    w_left=${WATCH_ROUNDS:-900}
    w_slow=0
    w_home=0
    w_done=
    i=0
    while first "$RUN/watch.token" && [ "$VAL" = "$token" ]; do
        any_mark || return 0
        for d in $KEEP; do
            [ -f "$RUN/watch.$d" ] || continue
            # only the two apps start during boot; the rest is looked at every 20 s
            case $d:$w_left in ui:* | home:* | *:0) ;; *) [ $((i % 25)) = 0 ] || continue ;; esac
            grp "$d"
            [ -f "$G/cgroup.procs" ] || continue
            if has_proc "$G"; then
                # unless another app has become the home app
                [ "$d:$w_home" = home:1 ] || continue
            fi
            pids_of "$d"
            for q in $PIDS; do
                in_group "$q" "$G" || move "$q" "$G"
            done
        done
        w_home=0
        if [ "$w_boot" = 1 ] && [ ! -f "$RUN/boot.reclaimed" ]; then
            if [ -z "$w_done" ]; then
                # asked every 5 s
                case $w_left:$((i % 25)) in 0:* | *:0)
                    if [ "$(getprop sys.boot_completed 2>/dev/null)" = 1 ]; then
                        uptime_s
                        w_done=$UP
                    fi
                    ;;
                esac
            else
                uptime_s
                if [ $((UP - w_done)) -ge "${BOOT_RECLAIM_AFTER:-10}" ]; then
                    : >"$RUN/boot.reclaimed"
                    boot_reclaim
                fi
            fi
        fi
        if [ "$w_left" -gt 0 ]; then
            [ $((i % 5)) = 0 ] && floors
            i=$((i + 1))
            w_left=$((w_left - 1))
            sleep "${WATCH_FAST:-0.2}" 2>/dev/null || sleep 1
        else
            floors
            [ -n "$WATCH_ONCE" ] && return 0
            sleep 20
            # the default home app can change; asked every five minutes
            w_slow=$((w_slow + 1))
            if [ $((w_slow % 15)) = 0 ]; then
                w_old=$HOMEAPP
                home_app ask
                [ "$HOMEAPP" = "$w_old" ] || w_home=1
            fi
        fi
    done
}

# After an update the watcher of the new version takes over from the one that
# is running; nothing is restarted and no switch changes.
takeover() {
    [ ! -f "$PAUSE" ] || return 0
    load_conf
    if [ "$KV" = 1 ] && kv_stale; then kv_on; fi
    legacy_drop
    any_mark || return 0
    watch_start
}

# ------------------------------------------------------------ commands

# "boot" leaves what is kept in RAM to the watcher, which service.sh starts
# before this.
apply() {
    load_conf
    kv_guard
    if [ "$GPU" = 1 ]; then gpu_on; fi
    if [ "$KV" = 1 ]; then kv_on; fi
    if [ "$RESERVE" = 1 ]; then reserve_on; fi
    if [ "$SC" = 1 ]; then sc_on; fi
    [ "$1" = boot ] && return 0
    official_check
    [ -n "$OFFICIAL" ] || official_known
    if [ "$OFFICIAL" = 1 ]; then
        keep_drop_all
        return 0
    fi
    # a watcher of an older version would put the apps back into its own group
    any_group && echo stop >"$RUN/watch.token"
    for d in $KEEP; do
        if wanted "$d"; then keep_on "$d" quiet; fi
    done
    legacy_drop
    any_mark && watch_start
    return 0
}

status() {
    load_conf
    kv_guard
    watch_revive
    sc_revive
    sc_state
    gpu_state
    kv_state
    reserve_state
    official_known
    KEEP_WHY=
    keep_avail || KEEP_WHY=controller
    [ "$OFFICIAL" = 1 ] && KEEP_WHY=official
    paused=0
    [ -f "$PAUSE" ] && paused=1
    echo "api=6"
    echo "version=$(sed -n 's/^version=//p' "$MODDIR/module.prop" 2>/dev/null)"
    echo "build=$(sed -n 's/^versionCode=//p' "$MODDIR/module.prop" 2>/dev/null)"
    echo "paused=$paused"
    echo "gpu=$GPU"
    echo "gpu_state=$GPU_STATE"
    echo "gpu_why=$GPU_WHY"
    echo "gpu_was=$GPU_WAS"
    echo "gpu_series=$KMI"
    echo "kvmalloc=$KV"
    echo "kvmalloc_state=$KV_STATE"
    echo "kvmalloc_why=$KV_WHY"
    echo "kvmalloc_seen=$KV_SEEN"
    echo "kvmalloc_changed=$KV_CHANGED"
    echo "watcher=$WATCHER"
    echo "reserve=$RESERVE"
    echo "reserve_state=$RES_STATE"
    echo "reserve_now=$RES_NOW"
    echo "reserve_stock=$RES_STOCK"
    echo "shaderc=$SC"
    echo "shaderc_state=$SC_STATE"
    echo "shaderc_why=$SC_WHY"
    echo "shaderc_kb=$SC_KB"
    echo "keep_why=$KEEP_WHY"
    mem_total
    echo "mem_total_kb=$MEMKB"
    # the most all floors may come to together
    if [ -n "$MEMKB" ]; then echo "keep_cap_kb=$((MEMKB / 100 * CAP_PCT))"; else echo "keep_cap_kb="; fi
    home_app
    echo "home_app=$HOMEAPP"
    for d in $KEEP; do
        eval "w=\$K_$d"
        keep_state "$d"
        echo "$d=$w"
        echo "${d}_state=$KS"
        echo "${d}_kb=$KKB"
        echo "${d}_min_kb=$KMIN"
    done
}

mkdir -p "$DATA" "$RUN" 2>/dev/null

case $1 in
status) status ;;
set)
    load_conf
    kv_guard
    case $3 in 0 | 1) ;; *) echo "error=value" && exit 1 ;; esac
    case $2 in
    gpu) GPU=$3 && fn=gpu ;;
    kvmalloc) KV=$3 && fn=kv ;;
    reserve) RESERVE=$3 && fn=reserve ;;
    shaderc) SC=$3 && fn=sc ;;
    ui | home | server) eval "K_$2=\$3" && fn="keep" ;;
    *) echo "error=name" && exit 1 ;;
    esac
    save_conf
    if [ "$3" = 1 ]; then fn=${fn}_on; else fn=${fn}_off; fi
    if [ "$fn" = keep_on ] || [ "$fn" = keep_off ]; then set -- "$2"; else set --; fi
    if "$fn" "$@"; then echo "ok=1"; else echo "error=failed" && exit 1; fi
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
takeover) takeover ;;
kvheld)
    # the hook has held: the note of its start can go
    sleep "$KV_HOLD" 2>/dev/null
    rm -f "$KV_TRY"
    ;;
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
    echo "usage: dejank.sh status | set <gpu|kvmalloc|reserve|shaderc|ui|home|server> <0|1> | apply | resume | log [lines]"
    exit 1
    ;;
esac
