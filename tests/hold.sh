#!/bin/bash
# Builds native/hold.c for this machine and checks what it does with a small
# file. Usage: hold.sh [compiler]
CC=${1:-cc}
HERE=$(cd "$(dirname "$0")" && pwd)
T=$(mktemp -d /tmp/hold.XXXXXX)
trap 'rm -rf "$T"' EXIT

pass=0
fail=0
ok() { pass=$((pass + 1)); }
bad() {
    fail=$((fail + 1))
    echo "  FAIL: $*"
}
expect() { if [ "$2" = "$3" ]; then ok; else bad "$1 -> '$2' (expected '$3')"; fi; }

$CC -O2 -Wall -Wextra -Werror -o "$T/hold" "$HERE/../native/hold.c" || {
    echo "hold: does not compile"
    exit 1
}
head -c 8192 /dev/urandom >"$T/file"

# what it prints is read to the end here: that must not wait for the one that stays
out=$("$T/hold" "$T/pid" "$T/file" "$T/missing" 2>&1)
expect "returns 0 once the file is held" "$?" "0"
case $out in *"/missing: No such file"*) ok ;; *) bad "a file that is not there is named -> '$out'" ;; esac
pid=$(cat "$T/pid" 2>/dev/null)
case $pid in '' | *[!0-9]*) bad "the pid file holds a pid -> '$pid'" && pid= ;; *) ok ;; esac
if [ -n "$pid" ]; then
    expect "the one that stays is the program" "$(tr '\0' ' ' <"/proc/$pid/cmdline" | cut -d' ' -f1)" "$T/hold"
    expect "all of the file is locked" "$(sed -n 's/^VmLck:[[:space:]]*//p' "/proc/$pid/status")" "8 kB"
    expect "it leads a session of its own" "$(ps -o sid= -p "$pid" | tr -d ' ')" "$pid"
    expect "and holds nothing of the caller's open" "$(readlink "/proc/$pid/fd/1")" "/dev/null"
    kill "$pid"
fi

"$T/hold" "$T/pid2" "$T/missing" 2>/dev/null
expect "returns 1 when nothing could be held" "$?" "1"
[ ! -e "$T/pid2" ] && ok || bad "and writes no pid file then"
"$T/hold" "$T/nowhere/pid" "$T/file" 2>/dev/null
expect "returns 1 when the pid file cannot be written" "$?" "1"
"$T/hold" 2>/dev/null
expect "returns 2 without arguments" "$?" "2"
sleep 0.2
left=$(pgrep -f "^$T/hold" | wc -l)
expect "nothing is left running" "$left" "0"

echo "hold ($CC): $pass passed, $fail failed"
[ "$fail" = 0 ]
