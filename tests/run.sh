#!/bin/bash
# Runs the module's scripts against a made-up phone. Usage: run.sh <shell command>
# Everything lives in a temporary directory; nothing real is touched.
SHELL_CMD=${1:-sh}
HERE=$(cd "$(dirname "$0")" && pwd)
SRC=$(cd "$HERE/../module" && pwd)
T=$(mktemp -d /tmp/dejank.XXXXXX)
trap 'rm -rf "$T"' EXIT

export DATA=$T/data RUN=$T/run PROC=$T/proc VM=$T/vm SYSMOD=$T/sysmod CG=$T/cg
export RMDIR="rm -rf" KILL=$T/bin/kill9 NAP=0 WATCH_FAST=0 WATCH_ROUNDS=2 WATCH_ONCE=1 BOOT_RECLAIM_AFTER=0 KV_HOLD=0 T
KO=$T/mod/kmod/kgsl_nowait-android16-6.12.ko
KVKO=$T/mod/kmod/kvmalloc_nowait-android16-6.12.ko
HOOK="ffffffc008123450 T __traceiter_android_vh_kvmalloc_node_use_vmalloc"
ADDR=ffffffc07e322f20

# ---------------------------------------------------------- the phone
phone() {
    # a watcher from the case before may still be on its way out
    rm -rf "$T/mod" "$DATA" "$RUN" "$PROC" "$VM" "$SYSMOD" "$CG" "$T/bin" "$T/pids" "$T/calls" 2>/dev/null || {
        sleep 0.5
        rm -rf "$T/mod" "$DATA" "$RUN" "$PROC" "$VM" "$SYSMOD" "$CG" "$T/bin" "$T/pids" "$T/calls"
    }
    rm -f "$T/dumpsys.out" "$T/insmod.fail"
    mkdir -p "$T/mod/kmod" "$PROC/sys/kernel/random" "$VM" "$SYSMOD/msm_kgsl" "$CG/apps/uid_10056/pid_100" "$T/bin" "$T/pids"
    cp "$SRC"/*.sh "$SRC"/module.prop "$T/mod/"
    : >"$KO"
    : >"$KVKO"
    : >"$T/calls"
    echo "6.12.58-android16-6-abc-4k" >"$T/uname"
    echo 2 >"$T/kgsl.value"
    echo "11111111-aaaa" >"$PROC/sys/kernel/random/boot_id"
    echo 2 >"$PROC/sys/kernel/kptr_restrict"
    printf '%s\n' "ffffffc000000010 T schedule" "$HOOK" \
        "ffffffc07e300000 b kgsl_pool_retry_min_order_x	[other]" \
        "$ADDR b kgsl_pool_retry_min_order	[msm_kgsl]" >"$PROC/kallsyms"
    echo "1000.50 900.00" >"$PROC/uptime"
    printf '%s\n' "MemTotal:       11351040 kB" "MemFree:          383000 kB" >"$PROC/meminfo"
    echo 73 >"$VM/watermark_scale_factor"
    echo "memory" >"$CG/cgroup.controllers"
    : >"$CG/cgroup.subtree_control"
    : >"$CG/cgroup.procs"
    : >"$CG/apps/uid_10056/pid_100/cgroup.procs"
    printf '%s\n' "priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=true" \
        "com.example.home/.Launcher" >"$T/home.out"

    cat >"$T/bin/uname" <<'EOF'
#!/bin/sh
cat "$T/uname"
EOF
    cat >"$T/bin/insmod" <<'EOF'
#!/bin/sh
echo "insmod $*" >>"$T/calls"
[ -f "$T/insmod.fail" ] && echo "insmod: failed to load: Exec format error" >&2 && exit 1
case $1 in
*/kvmalloc_nowait-*)
    # what the script noted before it let the hook in
    [ -f "$DATA/kvmalloc.trying" ] && echo "noted $(cat "$DATA/kvmalloc.trying")" >>"$T/calls"
    p=$SYSMOD/kvmalloc_nowait/parameters
    /bin/mkdir -p "$p"
    echo 1 >"$p/enabled"
    echo 12 >"$p/seen"
    echo 9 >"$p/sent"
    exit 0
    ;;
esac
p=$SYSMOD/kgsl_nowait/parameters
/bin/mkdir -p "$p"
cat "$T/kgsl.value" >"$p/saved"
echo 0 >"$p/now"
echo 1 >"$p/enabled"
EOF
    cat >"$T/bin/rmmod" <<'EOF'
#!/bin/sh
echo "rmmod $*" >>"$T/calls"
rm -rf "$SYSMOD/$1"
EOF
    cat >"$T/bin/pidof" <<'EOF'
#!/bin/sh
cat "$T/pids/$1" 2>/dev/null
EOF
    # Android starts a killed app again: here, at once and with the next pid
    cat >"$T/bin/kill9" <<'EOF'
#!/bin/sh
shift
for pid in "$@"; do
    echo "kill $pid" >>"$T/calls"
    "$T/bin/gone" "$pid"
    for f in "$T"/pids/*; do
        [ "$(cat "$f")" = "$pid" ] || continue
        new=$((pid + 1))
        echo "$new" >"$f"
        /bin/mkdir -p "$PROC/$new"
        echo "$new (app) S 1 0 0 0 -1 0 0 0 0 0 0 0 0 0 20 0 1 0 99990 0 0" >"$PROC/$new/stat"
        echo "0::/apps/uid_10056/pid_$new" >"$PROC/$new/cgroup"
    done
done
EOF
    # the kernel takes a process that ended off the list of its group
    cat >"$T/bin/gone" <<'EOF'
#!/bin/sh
find "$CG" -name cgroup.procs | while read -r f; do
    grep -qx "$1" "$f" || continue
    grep -vx "$1" "$f" >"$f.tmp"
    cat "$f.tmp" >"$f"
    rm -f "$f.tmp"
done
EOF
    cat >"$T/bin/cmd" <<'EOF'
#!/bin/sh
echo "cmd $*" >>"$T/calls"
cat "$T/home.out" 2>/dev/null
EOF
    # the kernel fills a new memory group with its files
    cat >"$T/bin/mkdir" <<'EOF'
#!/bin/sh
/bin/mkdir "$@" || exit 1
for d in "$@"; do
    case $d in "$CG"/*)
        echo 0 >"$d/memory.min"
        echo max >"$d/memory.swap.max"
        echo 656683008 >"$d/memory.current"
        echo 0 >"$d/memory.swap.current"
        printf '%s\n' "anon 300000000" "file 356683008" "shmem 0" "file_mapped 356683008" >"$d/memory.stat"
        : >"$d/cgroup.procs"
        ;;
    esac
done
EOF
    printf '#!/bin/sh\necho 1\n' >"$T/bin/getprop"
    printf '#!/bin/sh\nexit 0\n' >"$T/bin/sync"
    printf '#!/bin/sh\ncat "$T/dumpsys.out" 2>/dev/null\nexit 0\n' >"$T/bin/dumpsys"
    # the process list: pid and name
    cat >"$T/bin/ps" <<'EOF'
#!/bin/sh
echo "PID NAME"
for f in "$T"/pids/*; do
    [ -f "$f" ] && echo "$(cat "$f") ${f##*/}"
done
EOF
    printf '#!/bin/sh\nexit 0\n' >"$T/bin/pm"
    chmod +x "$T"/bin/*
    # busybox ash runs its own mkdir, pidof, insmod... before it looks at PATH,
    # so the stand-ins are also given to the script as functions
    for f in "$T"/bin/*; do
        n=${f##*/}
        case $n in kill9 | gone) ;; *) echo "$n() { \"$f\" \"\$@\"; }" ;; esac
    done >"$T/prelude.sh"
}

# app <name> <pid> <started at, in hundredths of a second since boot>. One of
# that name that ran before has ended.
app() {
    [ -f "$T/pids/$1" ] && "$T/bin/gone" "$(cat "$T/pids/$1")"
    echo "$2" >"$T/pids/$1"
    mkdir -p "$PROC/$2"
    echo "$2 (app) S 1 0 0 0 -1 0 0 0 0 0 0 0 0 0 20 0 1 0 $3 0 0" >"$PROC/$2/stat"
    echo "0::/apps/uid_10056/pid_$2" >"$PROC/$2/cgroup"
}

ctl() {
    PATH="$T/bin:$PATH" $SHELL_CMD -c '. "$T/prelude.sh"; . "$0"' "$T/mod/dejank.sh" "$@"
    rc=$?
    # switching something on leaves a watcher behind; here it does two rounds
    # and goes, and the checks wait for that so they never read a file it is writing
    case "$1 $3" in "set 1" | "apply "* | "resume "*) sleep 0.4 ;; esac
    return $rc
}

pass=0
fail=0
ok() { pass=$((pass + 1)); }
bad() {
    fail=$((fail + 1))
    echo "  FAIL: $*"
}
expect() { if [ "$2" = "$3" ]; then ok; else bad "$1 -> '$2' (expected '$3')"; fi; }
has() { if printf '%s\n' "$2" | grep -qxF -- "$3"; then ok; else bad "$1 -> missing '$3'"; fi; }
called() { if grep -qxF -- "$2" "$T/calls"; then ok; else bad "$1 -> not called: $2"; fi; }
not_called() { if grep -q -- "^$2" "$T/calls"; then bad "$1 -> called: $2"; else ok; fi; }
logged() { if grep -q -- "$2" "$DATA/log" 2>/dev/null; then ok; else bad "$1 -> not in the log: $2"; fi; }

# ---------------------------------------------------------- a fresh phone
phone
S=$(ctl status)
has "api" "$S" "api=4"
has "version" "$S" "version=$(sed -n 's/^version=//p' "$SRC/module.prop")"
has "build" "$S" "build=$(sed -n 's/^versionCode=//p' "$SRC/module.prop")"
has "everything is wanted by default" "$S" "gpu=1"
has "reserve wanted" "$S" "reserve=1"
has "the hook into kvmalloc is not" "$S" "kvmalloc=0"
has "and is off" "$S" "kvmalloc_state=off"
has "gpu not applied yet" "$S" "gpu_state=off"
has "reserve not applied yet" "$S" "reserve_state=off"
has "reserve shows the phone's value" "$S" "reserve_now=73"
has "not paused" "$S" "paused=0"
[ ! -e "$DATA/conf" ] && ok || bad "status saves nothing"

# ---------------------------------------------------------- gpu
expect "gpu on" "$(ctl set gpu 1)" "ok=1"
called "the module for this kernel, with the address of the driver's own variable" "insmod $KO addr=0x$ADDR"
expect "kptr_restrict is put back" "$(cat "$PROC/sys/kernel/kptr_restrict")" "2"
expect "the address is remembered for this boot" "$(cat "$RUN/gpu.addr")" "$ADDR"
S=$(ctl status)
has "gpu is on" "$S" "gpu_state=on"
has "what the driver had" "$S" "gpu_was=2"
logged "gpu on is logged" "gpu: on, the driver had 2"

expect "gpu off" "$(ctl set gpu 0)" "ok=1"
called "the module is unloaded" "rmmod kgsl_nowait"
S=$(ctl status)
has "gpu is off" "$S" "gpu_state=off"
has "off is remembered" "$S" "gpu=0"
expect "the switch is saved" "$(grep '^GPU=' "$DATA/conf")" "GPU=0"

phone
echo "6.6.30-android15-8-xyz" >"$T/uname"
S=$(ctl status)
has "no build for another kernel series" "$S" "gpu_state=na"
has "says why" "$S" "gpu_why=kernel"
has "and which series it is" "$S" "gpu_series=android15-6.6"
expect "cannot be switched on there" "$(ctl set gpu 1)" "error=failed"
not_called "nothing is loaded" "insmod"
: >"$T/mod/kmod/kgsl_nowait-android15-6.6.ko"
expect "with a build for that series it can" "$(ctl set gpu 1)" "ok=1"
called "and that build is the one loaded" "insmod $T/mod/kmod/kgsl_nowait-android15-6.6.ko addr=0x$ADDR"

phone
echo "4.19.113-perf+" >"$T/uname"
S=$(ctl status)
has "a kernel outside the series" "$S" "gpu_why=kernel"
has "has no series" "$S" "gpu_series="

phone
rm -rf "$SYSMOD/msm_kgsl"
S=$(ctl status)
has "no Adreno driver" "$S" "gpu_state=na"
has "says why" "$S" "gpu_why=driver"

phone
printf '%s\n' "ffffffc07e300000 b kgsl_pool_retry_min_order_x	[other]" \
    "ffffffc07e300008 b kgsl_pool_retry_min_order	[other]" >"$PROC/kallsyms"
S=$(ctl status)
has "a driver without the number has nothing to fix" "$S" "gpu_state=na"
has "says so" "$S" "gpu_why=setting"
expect "and cannot be switched on" "$(ctl set gpu 1)" "error=failed"
not_called "nothing is loaded without the address" "insmod"
expect "the symbol list is read once per boot" "$(cat "$RUN/gpu.addr")" "none"

phone
printf '%s\n' "0000000000000000 b kgsl_pool_retry_min_order	[msm_kgsl]" >"$PROC/kallsyms"
expect "a hidden address is no address" "$(ctl set gpu 1)" "error=failed"
not_called "nothing is loaded with address 0" "insmod"
[ ! -e "$RUN/gpu.addr" ] && ok || bad "a hidden address is remembered as none"

phone
: >"$T/insmod.fail"
expect "the kernel refuses the module" "$(ctl set gpu 1)" "error=failed"
S=$(ctl status)
has "refusal is an error" "$S" "gpu_state=err"
has "the error is insmod" "$S" "gpu_why=insmod"
logged "the kernel's reason is logged" "insmod failed: insmod: failed to load: Exec format error"
rm -f "$T/insmod.fail"
expect "a later try works" "$(ctl set gpu 1)" "ok=1"
has "and clears the error" "$(ctl status)" "gpu_state=on"

# ---------------------------------------------------------- kvmalloc
phone
expect "kvmalloc on" "$(ctl set kvmalloc 1)" "ok=1"
called "the module for this kernel" "insmod $KVKO"
called "the boot and the second are noted before the hook goes in" "noted 11111111-aaaa 1000"
[ ! -e "$DATA/kvmalloc.trying" ] && ok || bad "the note stays although the hook held"
S=$(ctl status)
has "kvmalloc is on" "$S" "kvmalloc_state=on"
has "wanted" "$S" "kvmalloc=1"
has "requests above the limit" "$S" "kvmalloc_seen=12"
has "requests sent to vmalloc" "$S" "kvmalloc_sent=9"
has "the gpu switch is another one" "$S" "gpu_state=off"
logged "kvmalloc on is logged" "kvmalloc: on"
expect "the switch is saved" "$(grep '^KVMALLOC=' "$DATA/conf")" "KVMALLOC=1"
expect "kvmalloc off" "$(ctl set kvmalloc 0)" "ok=1"
called "the module is unloaded" "rmmod kvmalloc_nowait"
S=$(ctl status)
has "kvmalloc is off" "$S" "kvmalloc_state=off"
has "off is remembered" "$S" "kvmalloc=0"
has "no numbers without the module" "$S" "kvmalloc_sent="
logged "what it did is logged" "kvmalloc: off, 9 requests went to vmalloc"

phone
ctl set gpu 1 >/dev/null
ctl set kvmalloc 1 >/dev/null
ctl set gpu 0 >/dev/null
S=$(ctl status)
has "switching the gpu fix off leaves the hook" "$S" "kvmalloc_state=on"
has "and takes its own module out" "$S" "gpu_state=off"

phone
echo "6.18.3-android17-2-abc-4k" >"$T/uname"
: >"$T/mod/kmod/kvmalloc_nowait-android17-6.18.ko"
S=$(ctl status)
has "Linux 6.16 and later do not wait" "$S" "kvmalloc_state=na"
has "says why" "$S" "kvmalloc_why=builtin"
expect "nothing to switch on there" "$(ctl set kvmalloc 1)" "error=failed"
not_called "nothing is loaded" "insmod"

phone
echo "6.6.30-android15-8-xyz" >"$T/uname"
S=$(ctl status)
has "no build for another kernel series" "$S" "kvmalloc_state=na"
has "says why" "$S" "kvmalloc_why=kernel"
expect "cannot be switched on there" "$(ctl set kvmalloc 1)" "error=failed"

phone
printf '%s\n' "ffffffc000000010 T schedule" "${HOOK}_x" >"$PROC/kallsyms"
S=$(ctl status)
has "a kernel without Android's hook" "$S" "kvmalloc_state=na"
has "says why" "$S" "kvmalloc_why=hook"
expect "cannot be switched on" "$(ctl set kvmalloc 1)" "error=failed"
not_called "nothing is loaded without the hook" "insmod"
expect "the symbol list is read once per boot" "$(cat "$RUN/kvmalloc.hook")" "0"

phone
: >"$T/insmod.fail"
expect "the kernel refuses the module" "$(ctl set kvmalloc 1)" "error=failed"
S=$(ctl status)
has "refusal is an error" "$S" "kvmalloc_state=err"
has "the error is insmod" "$S" "kvmalloc_why=insmod"
[ ! -e "$DATA/kvmalloc.trying" ] && ok || bad "a module that never loaded left a note"
rm -f "$T/insmod.fail"
expect "a later try works" "$(ctl set kvmalloc 1)" "ok=1"
has "and clears the error" "$(ctl status)" "kvmalloc_state=on"

# the note of a start: it goes when the hook has held, and trips the switch after a reboot
phone
expect "on, with the hook on probation for a second" "$(KV_HOLD=1 ctl set kvmalloc 1)" "ok=1"
[ -f "$DATA/kvmalloc.trying" ] && ok || bad "no note while the hook is on probation"
has "a look in that time leaves the note" "$(KV_HOLD=1 ctl status)" "kvmalloc_state=on"
[ -f "$DATA/kvmalloc.trying" ] && ok || bad "status took the note away early"
sleep 1.3
[ ! -e "$DATA/kvmalloc.trying" ] && ok || bad "the note stays after the hook held"

phone
ctl set kvmalloc 1 >/dev/null
echo "11111111-aaaa 990" >"$DATA/kvmalloc.trying"
S=$(KV_HOLD=3 ctl status)
has "a note nobody took away, same boot" "$S" "kvmalloc_state=on"
[ ! -e "$DATA/kvmalloc.trying" ] && ok || bad "an old note of this boot stays"
has "is no reboot" "$S" "kvmalloc=1"

phone
ctl set kvmalloc 1 >/dev/null
# the phone went down: another boot, nothing loaded, the note still there
echo "11111111-aaaa 1000" >"$DATA/kvmalloc.trying"
echo "22222222-bbbb" >"$PROC/sys/kernel/random/boot_id"
rm -rf "$SYSMOD/kvmalloc_nowait" "$RUN"
: >"$T/calls"
S=$(ctl status)
has "a note from another boot switches the hook off" "$S" "kvmalloc=0"
has "as an error" "$S" "kvmalloc_state=err"
has "that says why" "$S" "kvmalloc_why=reboot"
[ ! -e "$DATA/kvmalloc.trying" ] && ok || bad "the note of the other boot stays"
expect "the switch is saved as off" "$(grep '^KVMALLOC=' "$DATA/conf")" "KVMALLOC=0"
logged "the reboot is logged" "kvmalloc: the phone went down right after the hook was switched on"
ctl apply >/dev/null
not_called "apply leaves it off" "insmod $KVKO"
has "and it stays an error" "$(ctl status)" "kvmalloc_why=reboot"
expect "switching it on by hand tries again" "$(ctl set kvmalloc 1)" "ok=1"
S=$(ctl status)
has "and it is on" "$S" "kvmalloc_state=on"
has "with no reason left" "$S" "kvmalloc_why="

phone
mkdir -p "$DATA" && echo "KVMALLOC=1" >"$DATA/conf"
echo "11111111-aaaa 1000" >"$DATA/kvmalloc.trying"
echo "22222222-bbbb" >"$PROC/sys/kernel/random/boot_id"
ctl apply boot >/dev/null
not_called "a start after such a reboot does not load the hook" "insmod $KVKO"
called "the gpu fix is applied as ever" "insmod $KO addr=0x$ADDR"

phone
mkdir -p "$DATA" && echo "KVMALLOC=1" >"$DATA/conf"
expect "apply at boot" "$(ctl apply boot)" "ok=1"
called "loads the hook when it is wanted" "insmod $KVKO"
has "and it is on" "$(ctl status)" "kvmalloc_state=on"

# ---------------------------------------------------------- reserve
phone
expect "reserve on" "$(ctl set reserve 1)" "ok=1"
expect "the value is written" "$(cat "$VM/watermark_scale_factor")" "300"
S=$(ctl status)
has "reserve is on" "$S" "reserve_state=on"
has "now" "$S" "reserve_now=300"
has "the phone's own value is kept" "$S" "reserve_stock=73"
logged "reserve is logged" "reserve: on, 73 -> 300"
expect "reserve off" "$(ctl set reserve 0)" "ok=1"
expect "the phone's own value is back" "$(cat "$VM/watermark_scale_factor")" "73"
has "reserve is off" "$(ctl status)" "reserve_state=off"

phone
echo 400 >"$VM/watermark_scale_factor"
expect "a phone that already keeps more" "$(ctl set reserve 1)" "ok=1"
expect "is left alone" "$(cat "$VM/watermark_scale_factor")" "400"
ctl set reserve 0 >/dev/null
expect "also when switched off" "$(cat "$VM/watermark_scale_factor")" "400"

phone
rm "$VM/watermark_scale_factor"
has "no such setting" "$(ctl status)" "reserve_state=na"
expect "cannot be switched on" "$(ctl set reserve 1)" "error=failed"

# ---------------------------------------------------------- kept in RAM
phone
echo "cpu io" >"$CG/cgroup.controllers"
S=$(ctl status)
has "no memory controller" "$S" "ui_state=na"
has "says why" "$S" "keep_why=controller"
expect "cannot be switched on" "$(ctl set ui 1)" "error=failed"

phone
S=$(ctl status)
has "System UI is wanted by default" "$S" "ui=1"
has "the home app too" "$S" "home=1"
has "nothing else is" "$S" "sf=0"
has "composer off" "$S" "composer=0"
has "allocator off" "$S" "alloc=0"
has "system_server off" "$S" "server=0"
has "nothing applied yet" "$S" "ui_state=off"
has "RAM of the phone" "$S" "mem_total_kb=11351040"
has "the home app is named" "$S" "home_app=com.sec.android.app.launcher"
has "a system process that is not there cannot be kept" "$S" "composer_state=none"

phone
app com.android.systemui 100 5000
app com.example.home 200 6000
expect "System UI on" "$(ctl set ui 1)" "ok=1"
expect "the controller is switched on at the root" "$(cat "$CG/cgroup.subtree_control")" "+memory"
expect "no floor before the floors are worked out" "$(cat "$CG/dejank-ui/memory.min")" "0"
expect "the floors are counted from the watcher's start" "$(cat "$RUN/floor.start")" "1000"
expect "swap is left as the system has it" "$(cat "$CG/dejank-ui/memory.swap.max")" "max"
called "System UI is restarted into its group" "kill 100"
not_called "the home app is left alone" "kill 200"
expect "the new process is in the group" "$(cat "$CG/dejank-ui/cgroup.procs")" "101"
[ ! -d "$CG/dejank-home" ] && ok || bad "one switch made another group"
expect "the switch is saved" "$(grep '^KEEP_UI=' "$DATA/conf")" "KEEP_UI=1"
logged "it is logged" "keep ui: on"
S=$(ctl status)
has "System UI is on" "$S" "ui_state=on"
has "what its group holds" "$S" "ui_kb=641291"
has "no floor yet" "$S" "ui_min_kb=0"
has "the home app is not applied" "$S" "home_state=off"

# the floor: nine tenths of what the process holds, without files nothing has mapped
echo 800000000 >"$CG/dejank-ui/memory.current"
printf '%s\n' "anon 400000000" "file 390000000" "kernel 10000000" "shmem 8000000" "file_mapped 282000000" >"$CG/dejank-ui/memory.stat"
ctl watch keep
expect "not in the first second" "$(cat "$CG/dejank-ui/memory.min")" "0"
echo "1001.20 900.00" >"$PROC/uptime"
ctl watch keep
expect "after one second: nine tenths of usage less the unmapped file cache" "$(cat "$CG/dejank-ui/memory.min")" "615150K"
S=$(ctl status)
has "the floor is shown" "$S" "ui_min_kb=615150"
has "next to what the group holds" "$S" "ui_kb=781250"
echo 900000000 >"$CG/dejank-ui/memory.current"
echo "1003.00 900.00" >"$PROC/uptime"
ctl watch keep
expect "not again before the fourth second" "$(cat "$CG/dejank-ui/memory.min")" "615150K"
echo "1004.00 900.00" >"$PROC/uptime"
ctl watch keep
expect "again after four seconds" "$(cat "$CG/dejank-ui/memory.min")" "703080K"
expect "two of the four steps done" "$(cat "$RUN/floor.step")" "2"
# a look that comes late does the steps it missed in one
echo 800000000 >"$CG/dejank-ui/memory.current"
echo "1070.00 900.00" >"$PROC/uptime"
ctl watch keep
expect "16 and 64 s in one look" "$(cat "$CG/dejank-ui/memory.min")" "615150K"
expect "the first minute is over" "$(cat "$RUN/floor.step")" "4"
echo 900000000 >"$CG/dejank-ui/memory.current"
echo "1200.00 900.00" >"$PROC/uptime"
ctl watch keep
expect "left alone for half an hour" "$(cat "$CG/dejank-ui/memory.min")" "615150K"
echo "2900.00 900.00" >"$PROC/uptime"
ctl watch keep
expect "then worked out again" "$(cat "$CG/dejank-ui/memory.min")" "703080K"

# a switch starts the watcher anew, and with it the count for all floors
expect "the home app on" "$(ctl set home 1)" "ok=1"
called "the default home app is restarted into its own group" "kill 200"
expect "it is in its group" "$(cat "$CG/dejank-home/cgroup.procs")" "201"
expect "the home app is remembered" "$(cat "$DATA/home")" "com.example.home"
has "it is named" "$(ctl status)" "home_app=com.example.home"
expect "counted from now" "$(cat "$RUN/floor.start")" "2900"
expect "System UI keeps its floor meanwhile" "$(cat "$CG/dejank-ui/memory.min")" "703080K"
expect "the home app has none yet" "$(cat "$CG/dejank-home/memory.min")" "0"
# all floors together hold a tenth of the RAM at most: 1135100 kB here.
# 703080 + 577080 is more, so both shrink to 88 percent
echo "2901.00 900.00" >"$PROC/uptime"
ctl watch keep
expect "over the cap, System UI shrinks" "$(cat "$CG/dejank-ui/memory.min")" "618640K"
expect "and the home app by the same factor" "$(cat "$CG/dejank-home/memory.min")" "507760K"
has "the floors shown are the ones in force" "$(ctl status)" "home_min_kb=507760"

# a restarted app shows up outside its group; the watcher brings it in
app com.example.home 300 99000
has "the new one is outside" "$(ctl status)" "home_state=wait"
ctl watch keep
has "the watcher moved it" "$(ctl status)" "home_state=on"
not_called "without restarting it again" "kill 300"
expect "its group keeps the floor until the floors are next worked out" "$(cat "$CG/dejank-home/memory.min")" "507760K"
# a group whose process is not there keeps the floor it had
"$T/bin/gone" 300
rm -f "$T/pids/com.example.home"
echo 400000000 >"$CG/dejank-home/memory.current"
echo "2904.00 900.00" >"$PROC/uptime"
ctl watch keep
expect "the floors were worked out" "$(cat "$RUN/floor.step")" "2"
expect "the empty group was not measured" "$(cat "$CG/dejank-home/memory.min")" "507760K"
app com.example.home 310 290400
ctl watch keep
has "the home app is back in its group" "$(ctl status)" "home_state=on"

# a floor that goes leaves room for the others
expect "the home app off" "$(ctl set home 0)" "ok=1"
expect "System UI gets its full floor back" "$(cat "$CG/dejank-ui/memory.min")" "703080K"
expect "the home app on again" "$(ctl set home 1)" "ok=1"

expect "System UI off" "$(ctl set ui 0)" "ok=1"
[ ! -d "$CG/dejank-ui" ] && ok || bad "the group is removed"
has "the home app stays on" "$(ctl status)" "home_state=on"
expect "the controller stays while a group is left" "$(cat "$CG/cgroup.subtree_control")" "+memory"
[ -f "$RUN/watch.token" ] && ok || bad "the watcher is stopped although a switch is on"
expect "the home app off" "$(ctl set home 0)" "ok=1"
# (the files here are plain ones: a process that moved twice is listed twice)
expect "back where it came from, or to the root when that is gone" "$(sort -u "$CG/cgroup.procs" | tr '\n' ' ')" "101 310 "
expect "the controller is switched off again" "$(cat "$CG/cgroup.subtree_control")" "-memory"
[ ! -e "$RUN/watch.token" ] && ok || bad "the watcher is left running"
logged "off is logged" "keep home: off"

# a process that has only just started is moved, not restarted
phone
app com.android.systemui 100 99500
expect "on, young process" "$(ctl set ui 1)" "ok=1"
not_called "no restart" "kill"
expect "moved" "$(cat "$CG/dejank-ui/cgroup.procs")" "100"
expect "where it came from is noted" "$(cat "$RUN/origin.100")" "/apps/uid_10056/pid_100"
ctl set ui 0 >/dev/null
expect "and it goes back there" "$(cat "$CG/apps/uid_10056/pid_100/cgroup.procs")" "100"

# a controller that someone else switched on is not switched off
phone
echo "memory" >"$CG/cgroup.subtree_control"
ctl set ui 1 >/dev/null
ctl set ui 0 >/dev/null
expect "left as found" "$(cat "$CG/cgroup.subtree_control")" "memory"

# no default home app: Samsung's launcher
phone
printf '%s\n' "android/com.android.internal.app.ResolverActivity" >"$T/home.out"
app com.sec.android.app.launcher 400 5000
ctl set home 1 >/dev/null
called "falls back to One UI Home" "kill 400"

# the system processes: moved as they are, never restarted
# native <name> <pid>: one that has run since boot, in Android's own group
native() {
    app "$1" "$2" 300
    mkdir -p "$CG/system/uid_1000/pid_$2"
    : >"$CG/system/uid_1000/pid_$2/cgroup.procs"
    echo "0::/system/uid_1000/pid_$2" >"$PROC/$2/cgroup"
}

phone
native system_server 50
native surfaceflinger 60
native vendor.qti.hardware.display.composer-service 70
native vendor.qti.hardware.display.allocator-service 80
native android.hardware.audio.service 90
app com.android.systemui 100 5000
S=$(ctl status)
has "found: system_server" "$S" "server_state=off"
has "found: the composer" "$S" "composer_state=off"
has "found: the allocator" "$S" "alloc_state=off"
for k in server sf composer alloc; do
    expect "$k on" "$(ctl set $k 1)" "ok=1"
done
expect "system_server is in its group" "$(cat "$CG/dejank-server/cgroup.procs")" "50"
expect "SurfaceFlinger in its own" "$(cat "$CG/dejank-sf/cgroup.procs")" "60"
expect "the composer service is picked by its name" "$(cat "$CG/dejank-composer/cgroup.procs")" "70"
expect "the allocator service too" "$(cat "$CG/dejank-alloc/cgroup.procs")" "80"
expect "where each came from is noted" "$(cat "$RUN/origin.70")" "/system/uid_1000/pid_70"
not_called "nothing is ever restarted for them" "kill"
[ ! -d "$CG/dejank-ui" ] && ok || bad "a system switch applied System UI as well"
S=$(ctl status)
has "system_server is on" "$S" "server_state=on"
has "the composer is on" "$S" "composer_state=on"
has "System UI is still not applied" "$S" "ui_state=off"
expect "saved" "$(grep '^KEEP_COMPOSER=' "$DATA/conf")" "KEEP_COMPOSER=1"

# a system_server that came back is brought in by the watcher
native system_server 55
has "the new one is outside" "$(ctl status)" "server_state=wait"
ctl watch keep
has "the watcher moved it" "$(ctl status)" "server_state=on"
not_called "without restarting anything" "kill"
# at boot: what system_server read while it scanned the packages is asked back, once
ctl set ui 0 >/dev/null
ctl set home 0 >/dev/null
printf '%s\n' "anon 100000000" "file 300000000" "shmem 4000000" "file_mapped 96000000" >"$CG/dejank-server/memory.stat"
ctl watch keep
[ ! -f "$CG/dejank-server/memory.reclaim" ] && ok || bad "asked back by a watcher that a switch started"
ctl watch
expect "the unmapped file cache, and nothing swapped for it" "$(cat "$CG/dejank-server/memory.reclaim")" "195312K swappiness=0"
logged "it is logged" "server: asked 195312 KiB of unmapped file cache back"
echo gone >"$CG/dejank-server/memory.reclaim"
ctl watch
expect "only once in a boot" "$(cat "$CG/dejank-server/memory.reclaim")" "gone"

expect "system_server off" "$(ctl set server 0)" "ok=1"
expect "back where it came from" "$(cat "$CG/system/uid_1000/pid_55/cgroup.procs")" "55"
has "the others stay" "$(ctl status)" "sf_state=on"

# another chip: other names for the two vendor services
phone
native android.hardware.graphics.composer@2.4-service 70
native android.hardware.graphics.allocator-V2-service 80
expect "composer by another name" "$(ctl set composer 1)" "ok=1"
expect "allocator by another name" "$(ctl set alloc 1)" "ok=1"
expect "the right one each" "$(cat "$CG/dejank-composer/cgroup.procs")$(cat "$CG/dejank-alloc/cgroup.procs")" "7080"

# a phone without such a process
phone
expect "nothing to keep" "$(ctl set composer 1)" "error=failed"
[ ! -d "$CG/dejank-composer" ] && ok || bad "a group was left behind for a process that is not there"
expect "the controller is not left on" "$(cat "$CG/cgroup.subtree_control")" "-memory"

# Android 17 does this itself where SystemMemoryBudgetManager is at work
phone
app com.android.systemui 100 5000
echo "SystemMemoryBudgetManager:" >"$T/dumpsys.out"
mkdir -p "$CG/system" && echo 1073741824 >"$CG/system/memory.min"
S=$(ctl status)
has "Android keeps them itself" "$S" "keep_why=official"
has "so the switches are not available" "$S" "ui_state=na"
expect "and cannot be turned on" "$(ctl set ui 1)" "error=failed"
[ ! -d "$CG/dejank-ui" ] && ok || bad "a group was made although Android does it itself"
expect "apply leaves it alone as well" "$(ctl apply)" "ok=1"
not_called "nothing is restarted" "kill"
has "the other fixes still apply" "$(ctl status)" "reserve_state=on"
expect "the answer is kept for this build" "$(cat "$DATA/official")" "1 1"
ctl watch
[ ! -d "$CG/dejank-ui" ] && ok || bad "the watcher made a group although Android does it itself"

# the service is there but has set no floors: not at work
phone
app com.android.systemui 100 5000
echo "SystemMemoryBudgetManager:" >"$T/dumpsys.out"
has "a service that does nothing does not count" "$(ctl status)" "keep_why="
expect "so it can be turned on" "$(ctl set ui 1)" "ok=1"

# floors someone set by hand do not count without the service
phone
mkdir -p "$CG/apps" && echo 734003200 >"$CG/apps/memory.min"
echo "No dumpable named SystemMemoryBudgetManager" >"$T/dumpsys.out"
has "no service, no official mechanism" "$(ctl status)" "keep_why="

# at boot the watcher sets up what is switched on
phone
native system_server 50
app com.android.systemui 100 99500
app com.example.home 200 5000
mkdir -p "$DATA" && printf 'KEEP_UI=1\nKEEP_HOME=0\nKEEP_SERVER=1\n' >"$DATA/conf" && echo "com.example.home" >"$DATA/home"
ctl watch
S=$(ctl status)
has "boot: System UI" "$S" "ui_state=on"
has "boot: system_server" "$S" "server_state=on"
has "boot: what is off stays off" "$S" "home_state=off"
not_called "boot: system_server is not restarted" "kill 50"
not_called "boot: nor an app that is switched off" "kill 200"

# the watcher applies nothing when everything is off
phone
mkdir -p "$DATA" && printf 'KEEP_UI=0\nKEEP_HOME=0\n' >"$DATA/conf"
app com.android.systemui 100 5000
ctl watch
[ ! -d "$CG/dejank-ui" ] && ok || bad "watcher made a group with everything off"

# settings and groups of the versions before
phone
app com.android.systemui 100 5000
app com.example.home 200 6000
native system_server 50
mkdir -p "$DATA" && printf 'GPU=0\nRESERVE=0\nPIN=1\nSYS=1\n' >"$DATA/conf"
S=$(ctl status)
has "the old switch for both apps: System UI" "$S" "ui=1"
has "and the home app" "$S" "home=1"
has "the test build's switch" "$S" "server=1"
mkdir "$CG/dejank" "$CG/dejank-sys"
echo 100 >"$CG/dejank/cgroup.procs" && echo 200 >>"$CG/dejank/cgroup.procs" && echo 50 >"$CG/dejank-sys/cgroup.procs"
echo "+memory" >"$CG/cgroup.subtree_control" && mkdir -p "$RUN" && : >"$RUN/pin.root"
expect "apply" "$(ctl apply)" "ok=1"
expect "System UI is restarted into its own group" "$(cat "$CG/dejank-ui/cgroup.procs")" "101"
expect "the home app too" "$(cat "$CG/dejank-home/cgroup.procs")" "201"
expect "system_server is moved" "$(cat "$CG/dejank-server/cgroup.procs")" "50"
[ ! -d "$CG/dejank" ] && [ ! -d "$CG/dejank-sys" ] && ok || bad "an old group is left"
expect "the controller stays on" "$(cat "$CG/cgroup.subtree_control")" "+memory"
printf 'PIN=0\n' >"$DATA/conf"
S=$(ctl status)
has "the old switch, off" "$S" "ui=0"
has "for both" "$S" "home=0"

# ---------------------------------------------------------- start-up guard
phone
ctl guard
expect "first start goes ahead" "$?" "0"
expect "and is marked" "$(cat "$DATA/starting")" "11111111-aaaa"
ctl guard
expect "a second run in the same boot goes ahead" "$?" "0"
ctl settled
[ ! -e "$DATA/starting" ] && ok || bad "settled clears the mark"

ctl guard
echo "22222222-bbbb" >"$PROC/sys/kernel/random/boot_id"
ctl guard
expect "a start that never settled pauses the next boot" "$?" "1"
has "paused" "$(ctl status)" "paused=1"
logged "pause is logged" "paused: the last start did not finish"
app com.android.systemui 100 5000
ctl watch
[ ! -d "$CG/dejank-ui" ] && ok || bad "paused watcher made a group"
expect "resume" "$(ctl resume)" "ok=1"
S=$(ctl status)
has "no longer paused" "$S" "paused=0"
has "resume applies gpu" "$S" "gpu_state=on"
has "resume applies reserve" "$S" "reserve_state=on"
has "resume applies what is kept in RAM" "$S" "ui_state=on"

# ---------------------------------------------------------- apply
phone
app com.android.systemui 100 5000
ctl set gpu 0 >/dev/null
expect "apply at boot" "$(ctl apply boot)" "ok=1"
S=$(ctl status)
has "a fix that is off stays off" "$S" "gpu_state=off"
has "reserve is applied" "$S" "reserve_state=on"
has "what is kept in RAM is left to the watcher" "$S" "ui_state=off"
expect "apply now" "$(ctl apply)" "ok=1"
S=$(ctl status)
has "System UI is applied" "$S" "ui_state=on"
has "what is off stays off" "$S" "sf_state=none"

# ---------------------------------------------------------- settings file
phone
mkdir -p "$DATA"
printf '%s\n' 'GPU=0' 'RESERVE=2' 'KEEP_UI=$(touch "$T/executed")' 'junk' 'EVIL=1; touch "$T/executed"' 'KEEP_SF=1' >"$DATA/conf"
S=$(ctl status)
has "a saved 0 is read" "$S" "gpu=0"
has "anything but 0 or 1 is ignored" "$S" "reserve=1"
has "a value that is a command is ignored" "$S" "ui=1"
has "a saved 1 is read" "$S" "sf=1"
has "a missing key keeps its default" "$S" "server=0"
[ ! -e "$T/executed" ] && ok || bad "something in the settings file was executed"
expect "unknown switch" "$(ctl set blur 1)" "error=name"
expect "unknown value" "$(ctl set gpu 2)" "error=value"

# ---------------------------------------------------------- the whole start
phone
app com.android.systemui 100 5000
app com.example.home 200 6000
mkdir -p "$DATA" && echo "com.example.home" >"$DATA/home"
# service.sh calls "sh dejank.sh ..."; that sh gets the stand-ins as well
export SHELL_CMD
echo 'sh() { $SHELL_CMD -c '"'"'. "$T/prelude.sh"; . "$0"'"'"' "$@"; }' >"$T/prelude-sh.sh"
PATH="$T/bin:$PATH" BOOT_SETTLE=0 SETTLED_AFTER=0 $SHELL_CMD -c '. "$T/prelude.sh"; . "$T/prelude-sh.sh"; . "$0"' "$T/mod/service.sh"
sleep 1
S=$(ctl status)
has "service: gpu" "$S" "gpu_state=on"
has "service: reserve" "$S" "reserve_state=on"
has "service: System UI" "$S" "ui_state=on"
has "service: the home app" "$S" "home_state=on"
[ ! -e "$DATA/starting" ] && ok || bad "service: the start settled"

echo "$SHELL_CMD: $pass passed, $fail failed"
[ "$fail" = 0 ]
