#!/usr/bin/env bash
# Headless smoke test of the built plugin jar in a disposable Fiji.
#
#   bash scripts/fiji-smoke/run-smoke.sh <fiji-dir> <work-dir> [log-dir]
#
# With SMOKE_SIBLINGS="cpc volcoloc territories proximity" (after installing
# those plugins' jars into the same Fiji) it also runs one command of each.
#
# Installs target/Object_Colocalization_Suite-<version>.jar into
# <fiji-dir>/plugins (removing any other copy there), builds synthetic inputs in
# <work-dir>, runs each smoke macro and the API script headless, and checks the
# output. Prints one PASS/FAIL line per check, writes a digest of every output
# file to <log-dir>/digests.txt, and exits non-zero if any check fails.
# Use a throwaway copy of Fiji: its plugins folder is modified.
set -u

FIJI="${1:?usage: run-smoke.sh <fiji-dir> <work-dir> [log-dir]}"
WORK="${2:?usage: run-smoke.sh <fiji-dir> <work-dir> [log-dir]}"
LOGS="${3:-$WORK/logs}"
HERE="$(cd "$(dirname "$0")" && pwd)"
PROJECT="$(cd "$HERE/../.." && pwd)"
PREFIX="${SMOKE_LOG_PREFIX:-ocs-smoke}"
LAUNCH_TIMEOUT="${SMOKE_TIMEOUT:-600}"

native() {
    if command -v cygpath > /dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi
}

EXE=""
for candidate in fiji-windows-x64.exe ImageJ-win64.exe fiji-linux-x64 ImageJ-linux64 fiji; do
    if [ -f "$FIJI/$candidate" ]; then EXE="$FIJI/$candidate"; break; fi
done
[ -n "$EXE" ] || { echo "FAIL setup: no Fiji launcher in $FIJI"; exit 2; }

JAR=""
for candidate in "$PROJECT"/target/Object_Colocalization_Suite-*.jar; do
    case "$candidate" in
        *-sources.jar|*-javadoc.jar|*-tests.jar|*/original-*) ;;
        *) [ -f "$candidate" ] && JAR="$candidate" ;;
    esac
done
[ -n "$JAR" ] || { echo "FAIL setup: build the plugin first (./mvnw clean verify)"; exit 2; }

for old in "$FIJI"/plugins/Object_Colocalization_Suite-*.jar; do
    [ -f "$old" ] && rm -f "$old"
done
cp "$JAR" "$FIJI/plugins/"
echo "Installed $(basename "$JAR") into $FIJI/plugins"

rm -rf "$WORK"
mkdir -p "$WORK" "$LOGS"
WORK_NATIVE="$(native "$WORK")"
failures=0

# Runs Fiji with a time limit. Not through `timeout`: the Windows launcher
# writes its console output only when standard output is a file it was handed
# directly, and it prints nothing under `timeout`. A watchdog kills it instead.
launch() {
    local log="$1" err="$2" exitfile="$3"
    shift 3
    "$EXE" "$@" > "$log" 2> "$err" &
    local pid=$!
    # Detached from this script's output, or the sleeping watchdog would hold
    # the caller's pipe open after Fiji has exited.
    ( sleep "$LAUNCH_TIMEOUT"; kill "$pid" 2> /dev/null ) > /dev/null 2>&1 &
    local watchdog=$!
    wait "$pid"
    echo $? > "$exitfile"
    kill "$watchdog" 2> /dev/null
    wait "$watchdog" 2> /dev/null
}

run_macro() {
    local name="$1" short="${1#smoke-}"
    launch "$LOGS/$PREFIX-$short.log" "$LOGS/$PREFIX-$short.err.log" "$LOGS/$PREFIX-$short.exit" \
        --headless -macro "$(native "$HERE/$name.ijm")" "$WORK_NATIVE"
}

check() {
    local label="$1" log="$2"
    if grep -q "SMOKE PASS $label\$" "$log" && ! grep -q "SMOKE FAIL" "$log"; then
        echo "PASS $label"
    else
        echo "FAIL $label (see $log)"
        grep "SMOKE FAIL" "$log" | sed 's/^/    /'
        failures=$((failures + 1))
    fi
}

exit_zero() {
    local label="$1" short="$2"
    if [ "$(cat "$LOGS/$PREFIX-$short.exit")" = "0" ]; then
        echo "PASS $label exit code 0"
    else
        echo "FAIL $label exit code $(cat "$LOGS/$PREFIX-$short.exit")"
        failures=$((failures + 1))
    fi
}

run_macro make-fixtures
check fixtures "$LOGS/$PREFIX-make-fixtures.log"

run_macro smoke-commands
check commands "$LOGS/$PREFIX-commands.log"

run_macro smoke-single
for label in quick-look object intensity spatial discovery all 3d titles; do
    check "$label" "$LOGS/$PREFIX-single.log"
done
exit_zero single single

run_macro smoke-batch
check batch "$LOGS/$PREFIX-batch.log"
exit_zero batch batch

launch "$LOGS/$PREFIX-api.log" "$LOGS/$PREFIX-api.err.log" "$LOGS/$PREFIX-api.exit" \
    --headless --run "$(native "$HERE/api-check.groovy")" "work='$WORK_NATIVE'"
check api "$LOGS/$PREFIX-api.log"
exit_zero api api

# Errors: each bad run must print its message, and no stack trace from the
# plugin's classes may reach the console.
errors_ok=1
for pair in "smoke-errors-option|permutations= must be at least 1" \
            "smoke-errors-output|cannot save to"; do
    name="${pair%%|*}"
    message="${pair#*|}"
    run_macro "$name"
    short="${name#smoke-}"
    log="$LOGS/$PREFIX-$short.log"
    err="$LOGS/$PREFIX-$short.err.log"
    if ! grep -q -F "$message" "$log" "$err"; then
        echo "    $name: no '$message' message"
        errors_ok=0
    fi
    if grep -q -E "at (ocs\.|Object_Colocalization_Suite|OCS_Batch)" "$log" "$err"; then
        echo "    $name: stack trace printed"
        errors_ok=0
    fi
done
if [ "$errors_ok" -eq 1 ]; then
    echo "PASS errors"
else
    echo "FAIL errors"
    failures=$((failures + 1))
fi

# Every output file's digest, by path inside the work folder, so two runs (for
# example with and without other plugins installed) can be compared.
( cd "$WORK" && find out-* -type f | LC_ALL=C sort | while read -r file; do
    printf '%s  %s\n' "$(sha256sum "$file" | cut -d' ' -f1)" "$file"
  done ) > "$LOGS/digests.txt"
echo "Digests of $(wc -l < "$LOGS/digests.txt") output files in $LOGS/digests.txt"

# Siblings last, so their output never enters the digests above.
for sibling in ${SMOKE_SIBLINGS:-}; do
    launch "$LOGS/$PREFIX-sibling-$sibling.log" "$LOGS/$PREFIX-sibling-$sibling.err.log" \
        "$LOGS/$PREFIX-sibling-$sibling.exit" \
        --headless -macro "$(native "$HERE/smoke-siblings.ijm")" "$WORK_NATIVE|$sibling"
    check "sibling-$sibling" "$LOGS/$PREFIX-sibling-$sibling.log"
done

if [ "$failures" -eq 0 ]; then
    echo "SMOKE: all checks passed ($(basename "$JAR"))"
    exit 0
fi
echo "SMOKE: $failures check(s) failed"
exit 1
