#!/usr/bin/env bash
set -uo pipefail
# Note: NOT using set -e. Maven returns non-zero when tests fail (including
# the 6 expected GROUP BY failures), which would abort the script before
# summary files are created. Each step handles errors explicitly instead.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

usage() {
    cat <<EOF
Usage: $(basename "$0") --step <1|2|3|4|5|all> --output <dir_name> [--offline]

Runs baseline/regression test steps and saves output to research_logs/<dir_name>/

Options:
  --online   Disable offline mode (check remote repos for dependency updates)

Steps:
  1   Translator module unit tests (fast, ~12s)
  2   All modules unit tests (needs -fae for known failures, ~2-5min)
  3   Integration tests via Testcontainers (needs Docker, ~5-15min)
  3b  GroupByIT only — fast re-test of GROUP BY integration tests (~2-3min)
  4   Translator Cypher output capture (SqlToCypherTests only)
  5   Checkstyle state recording
  all Run steps 1-5 sequentially

Examples:
  $(basename "$0") --step 1 --output init_baseline
  $(basename "$0") --step all --output phase4
  $(basename "$0") --step 1 --output post_phase2

Output files per step:
  Step 1: <dir>/translator-unit.log, <dir>/translator-summary.txt
  Step 2: <dir>/all-unit.log, <dir>/all-unit-summary.txt
  Step 3: <dir>/integration.log, <dir>/integration-summary.txt
  Step 3b: <dir>/groupby.log, <dir>/groupby-summary.txt
  Step 4: <dir>/cypher-output.log
  Step 5: <dir>/checkstyle.log
EOF
    exit 1
}

# --- Parse arguments ---
STEP=""
OUTPUT_DIR=""
MAVEN_OFFLINE="-o"

while [[ $# -gt 0 ]]; do
    case "$1" in
        --step)  STEP="$2"; shift 2 ;;
        --output) OUTPUT_DIR="$2"; shift 2 ;;
        --online) MAVEN_OFFLINE=""; shift ;;
        -h|--help) usage ;;
        *) echo "Unknown argument: $1"; usage ;;
    esac
done

if [[ -z "$STEP" || -z "$OUTPUT_DIR" ]]; then
    echo "Error: --step and --output are required."
    usage
fi

OUT="$SCRIPT_DIR/$OUTPUT_DIR"
mkdir -p "$OUT"

SKIP_FLAGS="-Dasciidoctor.skip=true -Dcheckstyle.skip=true -Dcyclonedx.skip=true -Ddocker.skip=true -Dinvoker.skip=true -Djacoco.skip=true -Djapicmp.skip=true -Djqassistant.skip=true -Dlicense.skip=true -Dmaven.javadoc.skip=true -Dpmd.skip=true -Dsort.skip=true -Dspring-javaformat.skip=true"

# --- Step functions ---

step1() {
    echo "=== Step 1: Translator module unit tests ==="
    cd "$PROJECT_ROOT"
    local rc=0
    ./mvnw $MAVEN_OFFLINE -DskipITs $SKIP_FLAGS \
        -am -pl neo4j-jdbc-translator/impl clean test \
        2>&1 | tee "$OUT/translator-unit.log" || rc=$?

    grep -E "Tests run:|<<< FAIL" "$OUT/translator-unit.log" \
        > "$OUT/translator-summary.txt" || true

    echo ""
    echo "--- Step 1 Summary (maven exit code: $rc) ---"
    tail -5 "$OUT/translator-summary.txt"
    echo "Output: $OUT/translator-unit.log"
}

step2() {
    echo "=== Step 2: All modules unit tests ==="
    cd "$PROJECT_ROOT"
    local rc=0
    ./mvnw $MAVEN_OFFLINE -fae -DskipITs $SKIP_FLAGS \
        clean test \
        2>&1 | tee "$OUT/all-unit.log" || rc=$?

    grep -E "Tests run:|BUILD" "$OUT/all-unit.log" \
        | tail -20 > "$OUT/all-unit-summary.txt" || true

    echo ""
    echo "--- Step 2 Summary (maven exit code: $rc) ---"
    cat "$OUT/all-unit-summary.txt"
    echo "Output: $OUT/all-unit.log"
}

step3() {
    echo "=== Step 3: Integration tests (requires Docker) ==="
    if ! docker info > /dev/null 2>&1; then
        echo "Error: Docker is not running. Step 3 requires Docker for Testcontainers."
        return 1
    fi

    # Clean up stale containers from previous runs
    echo "--- Cleaning up stale containers ---"
    local stale
    stale=$(docker ps -aq --filter "label=org.testcontainers" 2>/dev/null || true)
    if [[ -n "$stale" ]]; then
        echo "Removing stale Testcontainers: $stale"
        docker rm -f $stale 2>/dev/null || true
    fi
    # Also remove any leftover socat or neo4j containers
    for pattern in socat neo4j; do
        local ids
        ids=$(docker ps -aq --filter "ancestor=$pattern" 2>/dev/null || true)
        if [[ -n "$ids" ]]; then
            echo "Removing leftover $pattern containers: $ids"
            docker rm -f $ids 2>/dev/null || true
        fi
    done

    # Check Docker has enough resources
    echo "--- Checking Docker resources ---"
    local disk_pct
    disk_pct=$(docker system df --format '{{.Percentage}}' 2>/dev/null | head -1 | tr -d '%' || echo "0")
    if [[ -n "$disk_pct" && "$disk_pct" =~ ^[0-9]+$ && "$disk_pct" -ge 90 ]]; then
        echo "Error: Docker disk usage is at ${disk_pct}%. Free space with 'docker system prune'."
        exit 1
    fi

    local mem_total
    mem_total=$(docker info --format '{{.MemTotal}}' 2>/dev/null || echo "0")
    # MemTotal is in bytes; Neo4j needs at least 2GB (~2147483648)
    if [[ "$mem_total" -gt 0 && "$mem_total" -lt 2147483648 ]]; then
        echo "Error: Docker has $(( mem_total / 1048576 ))MB memory. Neo4j requires at least 2048MB."
        echo "Increase memory in Docker Desktop settings."
        exit 1
    fi
    echo "Docker OK: disk ${disk_pct:-?}% used, memory $(( mem_total / 1048576 ))MB"

    cd "$PROJECT_ROOT"
    # First: build and package everything without running any tests.
    # This ensures all JARs exist so unit tests that read manifests don't fail,
    # and so IT modules have their dependencies available.
    echo "--- Building all modules (no tests) ---"
    local build_rc=0
    ./mvnw $MAVEN_OFFLINE -DskipTests $SKIP_FLAGS install \
        2>&1 | tee "$OUT/integration-build.log" || build_rc=$?
    if [[ $build_rc -ne 0 ]]; then
        echo "ERROR: Build failed (exit code $build_rc). Check $OUT/integration-build.log"
        return 1
    fi

    # Then: run only the integration tests (skip unit tests)
    echo "--- Running integration tests ---"
    local rc=0
    ./mvnw $MAVEN_OFFLINE -fae -DskipUTs -DskipClusterIT=true -DskipReauthenticationIT=true $SKIP_FLAGS \
        verify \
        2>&1 | tee "$OUT/integration.log" || rc=$?

    grep -E "Tests run:|BUILD" "$OUT/integration.log" \
        | tail -20 > "$OUT/integration-summary.txt" || true

    echo ""
    echo "--- Step 3 Summary (maven exit code: $rc) ---"
    cat "$OUT/integration-summary.txt"
    echo "Output: $OUT/integration.log"
}

step3b() {
    echo "=== Step 3b: GroupByIT integration tests only ==="
    if ! docker info > /dev/null 2>&1; then
        echo "Error: Docker is not running. Step 3b requires Docker for Testcontainers."
        return 1
    fi

    cd "$PROJECT_ROOT"
    # Build only the modules needed for the IT cp module (no tests)
    echo "--- Building required modules (no tests) ---"
    local build_rc=0
    ./mvnw $MAVEN_OFFLINE -DskipTests $SKIP_FLAGS -pl neo4j-jdbc-it/neo4j-jdbc-it-cp -am install \
        2>&1 | tee "$OUT/groupby-build.log" || build_rc=$?
    if [[ $build_rc -ne 0 ]]; then
        echo "ERROR: Build failed (exit code $build_rc). Check $OUT/groupby-build.log"
        return 1
    fi

    # Run only GroupByIT
    echo "--- Running GroupByIT ---"
    local rc=0
    ./mvnw $MAVEN_OFFLINE -fae -DskipUTs $SKIP_FLAGS \
        -pl neo4j-jdbc-it/neo4j-jdbc-it-cp \
        -Dit.test="GroupByIT" \
        verify \
        2>&1 | tee "$OUT/groupby.log" || rc=$?

    grep -E "Tests run:|BUILD|<<< FAIL|<<< ERROR" "$OUT/groupby.log" \
        | tail -30 > "$OUT/groupby-summary.txt" || true

    echo ""
    echo "--- Step 3b Summary (maven exit code: $rc) ---"
    cat "$OUT/groupby-summary.txt"
    echo "Output: $OUT/groupby.log"
}

step4() {
    echo "=== Step 4: Cypher output capture ==="
    cd "$PROJECT_ROOT"
    local rc=0
    ./mvnw $MAVEN_OFFLINE -DskipITs $SKIP_FLAGS \
        -am -pl neo4j-jdbc-translator/impl test \
        -Dtest=SqlToCypherTests -Dsurefire.useFile=false -Dsurefire.failIfNoSpecifiedTests=false \
        2>&1 | tee "$OUT/cypher-output.log" || rc=$?

    echo ""
    echo "Output: $OUT/cypher-output.log (maven exit code: $rc)"
}

step5() {
    echo "=== Step 5: Checkstyle state ==="
    cd "$PROJECT_ROOT"
    ./mvnw $MAVEN_OFFLINE -pl neo4j-jdbc-translator/impl checkstyle:check \
        2>&1 | tee "$OUT/checkstyle.log" || true

    echo ""
    echo "Output: $OUT/checkstyle.log"
}

# --- Compare helper ---
compare_summary() {
    local baseline="$1"
    local current="$2"
    if [[ -f "$baseline" && -f "$current" ]]; then
        echo ""
        echo "--- Regression diff (baseline vs current) ---"
        diff <(grep "<<< FAIL" "$baseline" || true) \
             <(grep "<<< FAIL" "$current" || true) \
            && echo "No new failures." \
            || true
    fi
}

# --- Run requested step(s) ---
case "$STEP" in
    1) step1 ;;
    2) step2 ;;
    3) step3 ;;
    3b) step3b ;;
    4) step4 ;;
    5) step5 ;;
    all)
        step1
        echo ""
        step2
        echo ""
        step3
        echo ""
        step4
        echo ""
        step5
        echo ""
        echo "=== All steps complete. Output directory: $OUT ==="
        ;;
    *)
        echo "Error: Unknown step '$STEP'. Must be 1, 2, 3, 3b, 4, 5, or all."
        usage
        ;;
esac
