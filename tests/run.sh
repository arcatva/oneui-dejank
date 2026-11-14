#!/bin/bash
# Runs the module's scripts against a made-up phone. Usage: run.sh <shell command>
# Everything lives in a temporary directory; nothing real is touched.
SHELL_CMD=${1:-sh}
HERE=$(cd "$(dirname "$0")" && pwd)
SRC=$(cd "$HERE/../module" && pwd)
T=$(mktemp -d /tmp/dejank.XXXXXX)
trap 'rm -rf "$T"' EXIT

export DATA=$T/data RUN=$T/run PROC=$T/proc VM=$T/vm SYSMOD=$T/sysmod CG=$T/cg
export RMDIR="rm -rf" KILL=$T/bin/kill9 NAP=0 WATCH_FAST=0 WATCH_ROUNDS=2 WATCH_ONCE=1 T
KO=$T/mod/kmod/kgsl_nowait-android16-6.12.ko
ADDR=ffffffc07e322f20

# ---------------------------------------------------------- the phone
phone() {
    # a watcher from the case before may still be on its way out
    rm -rf "$T/mod" "$DATA" "$RUN" "$PROC" "$VM" "$SYSMOD" "$CG" "$T/bin" "$T/pids" "$T/calls" 2>/dev/null || {
        sleep 0.5
        rm -rf "$T/mod" "$DATA" "$RUN" "$PROC" "$VM" "$SYSMOD" "$CG" "$T/bin" "$T/pids" "$T/calls"
    }
    mkdir -p "$T/mod/kmod" "$PROC/sys/kernel/random" "$VM" "$SYSMOD/msm_kgsl" "$CG/apps/uid_10056/pid_100" "$T/bin" "$T/pids"
    cp "$SRC"/*.sh "$SRC"/module.prop "$T/mod/"
    : >"$KO"
    : >"$T/calls"
    echo "6.12.58-android16-6-abc-4k" >"$T/uname"
    echo 2 >"$T/kgsl.value"
    echo "11111111-aaaa" >"$PROC/sys/kernel/random/boot_id"
    echo 2 >"$PROC/sys/kernel/kptr_restrict"
    printf '%s\n' "ffffffc000000010 T schedule" \
        "ffffffc07e300000 b kgsl_pool_retry_min_order_x	[other]" \
        "$ADDR b kgsl_pool_retry_min_order	[msm_kgsl]" >"$PROC/kallsyms"
    echo "1000.50 900.00" >"$PROC/uptime"
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
p=$SYSMOD/kgsl_nowait/parameters
/bin/mkdir -p "$p"
cat "$T/kgsl.value" >"$p/saved"
echo 0 >"$p/now"
echo 1 >"$p/enabled"
EOF
    cat >"$T/bin/rmmod" <<'EOF'
#!/bin/sh
echo "rmmod $*" >>"$T/calls"
rm -rf "$SYSMOD/kgsl_nowait"
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
        printf '%s\n' "anon 511705088" "file 1271922688" "kernel 0" "file_mapped 632291328" >"$d/memory.stat"
        : >"$d/cgroup.procs"
        ;;
    esac
done
EOF
    printf '#!/bin/sh\necho 1\n' >"$T/bin/getprop"
    printf '#!/bin/sh\nexit 0\n' >"$T/bin/sync"
    printf '#!/bin/sh\nexit 0\n' >"$T/bin/dumpsys"
    printf '#!/bin/sh\nexit 0\n' >"$T/bin/pm"
    chmod +x "$T"/bin/*
    # busybox ash runs its own mkdir, pidof, insmod... before it looks at PATH,
    # so the stand-ins are also given to the script as functions
    for f in "$T"/bin/*; do
        n=${f##*/}
        [ "$n" = kill9 ] || echo "$n() { \"$f\" \"\$@\"; }"
    done >"$T/prelude.sh"
}

# app <name> <pid> <started at, in hundredths of a second since boot>
app() {
    echo "$2" >"$T/pids/$1"
    mkdir -p "$PROC/$2"
    echo "$2 (app) S 1 0 0 0 -1 0 0 0 0 0 0 0 0 0 20 0 1 0 $3 0 0" >"$PROC/$2/stat"
    echo "0::/apps/uid_10056/pid_$2" >"$PROC/$2/cgroup"
}

ctl() {
    PATH="$T/bin:$PATH" $SHELL_CMD -c '. "$T/prelude.sh"; . "$0"' "$T/mod/dejank.sh" "$@"
    rc=$?
    # "pin on" leaves a watcher behind; here it does two rounds and goes, and
    # the checks wait for that so they never read a file it is writing
    case "$1 $2 $3" in "set pin 1" | apply* | resume*) sleep 0.4 ;; esac
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
has "api" "$S" "api=1"
has "version" "$S" "version=$(sed -n 's/^version=//p' "$SRC/module.prop")"
has "build" "$S" "build=$(sed -n 's/^versionCode=//p' "$SRC/module.prop")"
has "everything is wanted by default" "$S" "gpu=1"
has "reserve wanted" "$S" "reserve=1"
has "pin wanted" "$S" "pin=1"
has "gpu not applied yet" "$S" "gpu_state=off"
has "reserve not applied yet" "$S" "reserve_state=off"
has "reserve shows the phone's value" "$S" "reserve_now=73"
has "pin not applied yet" "$S" "pin_state=off"
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

# ---------------------------------------------------------- pin
phone
echo "cpu io" >"$CG/cgroup.controllers"
has "no memory controller" "$(ctl status)" "pin_state=na"
expect "cannot be switched on" "$(ctl set pin 1)" "error=failed"

phone
app com.android.systemui 100 5000
app com.example.home 200 6000
expect "pin on" "$(ctl set pin 1)" "ok=1"
expect "the controller is switched on at the root" "$(cat "$CG/cgroup.subtree_control")" "+memory"
expect "never swapped" "$(cat "$CG/dejank/memory.swap.max")" "0"
called "System UI is restarted into the group" "kill 100"
called "the default home app too" "kill 200"
expect "the new processes are in the group" "$(sort "$CG/dejank/cgroup.procs" | tr '\n' ' ')" "101 201 "
expect "the home app is remembered" "$(cat "$DATA/home")" "com.example.home"
S=$(ctl status)
has "pin is on" "$S" "pin_state=on"
has "two apps run" "$S" "pin_apps=2"
has "both are in" "$S" "pin_in=2"
has "memory kept" "$S" "pin_kb=641292"
has "nothing swapped" "$S" "pin_swap_kb=0"

expect "after its first rounds the watcher protects what the apps use plus 128 MB" "$(cat "$CG/dejank/memory.min")" "1219M"

# a restarted app shows up outside the group; the watcher brings it in
app com.example.home 300 99000
has "one app is outside" "$(ctl status)" "pin_in=1"
ctl watch
has "the watcher moved it" "$(ctl status)" "pin_in=2"
not_called "without restarting it again" "kill 300"

expect "pin off" "$(ctl set pin 0)" "ok=1"
[ ! -d "$CG/dejank" ] && ok || bad "the group is removed"
expect "back where it came from" "$(cat "$CG/apps/uid_10056/pid_100/cgroup.procs" | tr '\n' ' ')" ""
expect "or to the root when that is gone" "$(sort "$CG/cgroup.procs" | tr '\n' ' ')" "101 201 300 "
expect "the controller is switched off again" "$(cat "$CG/cgroup.subtree_control")" "-memory"
has "pin is off" "$(ctl status)" "pin_state=off"

# while the group is set up everything in it is protected
phone
WATCH_ROUNDS=1000000 ctl apply boot >/dev/null
app com.android.systemui 100 99500
( PATH="$T/bin:$PATH" WATCH_ROUNDS=1000000 $SHELL_CMD -c '. "$T/prelude.sh"; . "$0"' "$T/mod/dejank.sh" watch & )
sleep 0.6
expect "no budget yet during the first minutes" "$(cat "$CG/dejank/memory.min")" "max"
rm -f "$RUN/watch.token"
sleep 0.3

# a group without the figures keeps full protection
phone
app com.android.systemui 100 5000
ctl set pin 1 >/dev/null
: >"$CG/dejank/memory.stat"
echo max >"$CG/dejank/memory.min"
ctl watch
expect "no figures, no budget" "$(cat "$CG/dejank/memory.min")" "max"

# a process that has only just started is moved, not restarted
phone
app com.android.systemui 100 99500
echo "com.example.home" >"$T/pids/none"
: >"$T/home.out"
expect "pin on, young process" "$(ctl set pin 1)" "ok=1"
not_called "no restart" "kill"
expect "moved" "$(cat "$CG/dejank/cgroup.procs")" "100"
expect "where it came from is noted" "$(cat "$RUN/origin.100")" "/apps/uid_10056/pid_100"
ctl set pin 0 >/dev/null
expect "and it goes back there" "$(cat "$CG/apps/uid_10056/pid_100/cgroup.procs")" "100"

# a controller that someone else switched on is not switched off
phone
echo "memory" >"$CG/cgroup.subtree_control"
ctl set pin 1 >/dev/null
ctl set pin 0 >/dev/null
expect "left as found" "$(cat "$CG/cgroup.subtree_control")" "memory"

# no default home app: Samsung's launcher
phone
printf '%s\n' "android/com.android.internal.app.ResolverActivity" >"$T/home.out"
app com.sec.android.app.launcher 400 5000
ctl set pin 1 >/dev/null
called "falls back to One UI Home" "kill 400"

# the watcher does nothing when pin is off
phone
ctl set pin 0 >/dev/null
app com.android.systemui 100 5000
ctl watch
[ ! -d "$CG/dejank" ] && ok || bad "watcher with pin off made a group"

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
[ ! -d "$CG/dejank" ] && ok || bad "paused watcher made a group"
expect "resume" "$(ctl resume)" "ok=1"
S=$(ctl status)
has "no longer paused" "$S" "paused=0"
has "resume applies gpu" "$S" "gpu_state=on"
has "resume applies reserve" "$S" "reserve_state=on"
has "resume applies pin" "$S" "pin_state=on"

# ---------------------------------------------------------- apply
phone
app com.android.systemui 100 5000
ctl set gpu 0 >/dev/null
expect "apply at boot" "$(ctl apply boot)" "ok=1"
S=$(ctl status)
has "a fix that is off stays off" "$S" "gpu_state=off"
has "reserve is applied" "$S" "reserve_state=on"
has "pin is left to the watcher" "$S" "pin_state=off"
expect "apply now" "$(ctl apply)" "ok=1"
has "pin is applied" "$(ctl status)" "pin_state=on"

# ---------------------------------------------------------- settings file
phone
mkdir -p "$DATA"
printf '%s\n' 'GPU=0' 'RESERVE=2' 'PIN=$(touch "$T/executed")' 'junk' 'EVIL=1; touch "$T/executed"' >"$DATA/conf"
S=$(ctl status)
has "a saved 0 is read" "$S" "gpu=0"
has "anything but 0 or 1 is ignored" "$S" "reserve=1"
has "a value that is a command is ignored" "$S" "pin=1"
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
has "service: pin" "$S" "pin_state=on"
has "service: both apps in" "$S" "pin_in=2"
[ ! -e "$DATA/starting" ] && ok || bad "service: the start settled"

echo "$SHELL_CMD: $pass passed, $fail failed"
[ "$fail" = 0 ]
