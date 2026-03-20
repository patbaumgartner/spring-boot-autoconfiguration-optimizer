#!/usr/bin/env bash
# ============================================================
# Spring Boot Autoconfiguration Optimizer - Native Image Benchmark Runner
# ============================================================
# Usage: ./benchmarks/scripts/run-benchmarks-native.sh [native-binary-path]
#
# This script measures Spring Boot startup time for a GraalVM native image,
# comparing baseline (optimizer disabled) vs optimized (optimizer enabled).
#
# If no native binary path is given, the script builds one from the PetClinic
# sample Maven project using the 'native' profile. This requires GraalVM with
# native-image installed (e.g. via GRAALVM_HOME or PATH).
#
# Note on native images and the optimizer:
#   In JVM mode, the optimizer skips condition evaluation for auto-configurations
#   not seen during training, saving startup time. In native image mode, Spring
#   Boot's AOT processing has already pre-resolved which beans and configurations
#   apply at build time, so the AutoConfigurationImportFilter may not be invoked
#   at all. Native images therefore typically start within 50-400ms regardless of
#   whether the optimizer is active. Run this benchmark to validate empirically.
#
# This script is structured to mirror run-benchmarks-maven.sh so results can be
# compared side-by-side.
# ============================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
RESULTS_DIR="${PROJECT_ROOT}/benchmarks/results-native"
REPORT_FILE="${RESULTS_DIR}/benchmark-report.md"
NATIVE_BINARY="${1:-}"
ITERATIONS="${BENCHMARK_ITERATIONS:-10}"

mkdir -p "${RESULTS_DIR}"

echo "=================================================="
echo " Spring Boot Autoconfiguration Optimizer Benchmarks (Native)"
echo "=================================================="
echo ""

# ---- Prerequisite: native-image ----
if ! command -v native-image &>/dev/null; then
    echo "ERROR: native-image not found in PATH."
    echo "Install GraalVM (https://www.graalvm.org/) and ensure native-image is available."
    echo "On GitHub Actions use: graalvm/setup-graalvm@v1"
    exit 1
fi

NATIVE_IMAGE_VERSION=$(native-image --version 2>&1 | head -1)
echo "native-image: ${NATIVE_IMAGE_VERSION}"
echo "Iterations:   ${ITERATIONS}"
echo ""

# ---- Step 1: Build native binary if not provided ----
if [ -z "${NATIVE_BINARY}" ]; then
    echo "--- Step 1: Build Native Image ---"

    PETCLINIC_JAR_GLOB="${PROJECT_ROOT}/integration-tests/petclinic-sample/target/autoconfiguration-optimizer-petclinic-sample-*.jar"
    PETCLINIC_JAR_PATH=$(compgen -G "${PETCLINIC_JAR_GLOB}" 2>/dev/null | head -1)

    if [ -z "${PETCLINIC_JAR_PATH}" ]; then
        echo "PetClinic JAR not found; running full Maven build (JVM mode, skip tests)..."
        mvn --batch-mode --no-transfer-progress install -DskipTests -q \
            -pl spring-boot-autoconfiguration-optimizer-maven-plugin -am \
            -f "${PROJECT_ROOT}/pom.xml"
        mvn --batch-mode --no-transfer-progress package -DskipTests -q \
            -f "${PROJECT_ROOT}/integration-tests/petclinic-sample/pom.xml"
        PETCLINIC_JAR_PATH=$(compgen -G "${PETCLINIC_JAR_GLOB}" 2>/dev/null | head -1)
    fi

    if [ -z "${PETCLINIC_JAR_PATH}" ]; then
        echo "ERROR: PetClinic JAR not found after build."
        exit 1
    fi

    # Training run (using JVM JAR) to generate the optimizer properties
    echo "Running training run via JVM JAR to generate optimizer properties..."
    TRAINING_OUTPUT_DIR="${RESULTS_DIR}/training"
    mkdir -p "${TRAINING_OUTPUT_DIR}"

    JAVA="${JAVA_HOME:+${JAVA_HOME}/bin/java}"
    JAVA="${JAVA:-$(command -v java)}"

    timeout 120 "${JAVA}" \
        -Dautoconfiguration.optimizer.training-run=true \
        -Dautoconfiguration.optimizer.exit-after-training=true \
        -Dautoconfiguration.optimizer.output-directory="${TRAINING_OUTPUT_DIR}" \
        -Dautoconfiguration.optimizer.output-file="autoconfiguration-optimizer.properties" \
        -jar "${PETCLINIC_JAR_PATH}" \
        --spring.main.banner-mode=off \
        --server.port=0 \
        --spring.jpa.show-sql=false 2>&1 | tail -5 || true

    OPTIMIZER_PROPS="${TRAINING_OUTPUT_DIR}/autoconfiguration-optimizer.properties"
    if [ ! -f "${OPTIMIZER_PROPS}" ]; then
        echo "ERROR: Training run failed to generate properties file."
        exit 1
    fi
    echo "Training complete."

    # Extract stats
    TRAINING_AVAILABLE=$(grep -oP '(?<=# Total available auto-configurations: )\d+' "${OPTIMIZER_PROPS}" || echo "")
    TRAINING_LOADED=$(grep -oP '(?<=# Auto-configurations loaded during training: )\d+' "${OPTIMIZER_PROPS}" || echo "")
    TRAINING_EXCLUDED=$(grep -oP '(?<=# Auto-configurations to skip at startup: )\d+' "${OPTIMIZER_PROPS}" || echo "")
    if [ -n "${TRAINING_AVAILABLE}" ]; then
        echo "  Available auto-configurations: ${TRAINING_AVAILABLE}"
        echo "  Loaded auto-configurations:    ${TRAINING_LOADED}"
        echo "  Excluded auto-configurations:  ${TRAINING_EXCLUDED}"
    fi
    echo ""

    # Bake the optimizer properties into the classes directory then build the native image
    echo "Copying optimizer properties into build output and compiling native image..."
    mkdir -p "${PROJECT_ROOT}/integration-tests/petclinic-sample/target/classes/META-INF"
    cp "${OPTIMIZER_PROPS}" \
       "${PROJECT_ROOT}/integration-tests/petclinic-sample/target/classes/META-INF/autoconfiguration-optimizer.properties"

    mvn --batch-mode --no-transfer-progress package -DskipTests -Pnative -q \
        -f "${PROJECT_ROOT}/integration-tests/petclinic-sample/pom.xml"

    NATIVE_BINARY_GLOB="${PROJECT_ROOT}/integration-tests/petclinic-sample/target/petclinic"
    NATIVE_BINARY=$(ls "${NATIVE_BINARY_GLOB}" 2>/dev/null | head -1)
    if [ -z "${NATIVE_BINARY}" ]; then
        echo "ERROR: Native binary not found after build. Expected: ${NATIVE_BINARY_GLOB}"
        exit 1
    fi
    echo "Native binary built: ${NATIVE_BINARY}"
    echo ""
else
    echo "--- Step 1: Using provided native binary ---"
    echo "Binary: ${NATIVE_BINARY}"
    TRAINING_AVAILABLE=""
    TRAINING_LOADED=""
    TRAINING_EXCLUDED=""
    echo ""
fi

if [ ! -x "${NATIVE_BINARY}" ]; then
    echo "ERROR: ${NATIVE_BINARY} is not executable."
    exit 1
fi

# ---- Step 2: Baseline measurements (optimizer disabled) ----
echo "--- Step 2: Baseline Measurements (optimizer disabled) ---"
BASELINE_TIMES=()
for i in $(seq 1 "${ITERATIONS}"); do
    TMPFILE=$(mktemp)
    "${NATIVE_BINARY}" \
        -Dautoconfiguration.optimizer.enabled=false \
        --spring.main.banner-mode=off \
        --server.port=0 \
        --spring.jpa.show-sql=false > "${TMPFILE}" 2>&1 &
    PROC_PID=$!

    OUTPUT=""
    END_TIME=$(($(date +%s) + 30))
    while [ "$(date +%s)" -lt "${END_TIME}" ]; do
        OUTPUT=$(grep -m1 "Started .* in" "${TMPFILE}" 2>/dev/null || true)
        if [ -n "${OUTPUT}" ]; then break; fi
        if ! kill -0 "${PROC_PID}" 2>/dev/null; then
            OUTPUT=$(grep -m1 "Started .* in" "${TMPFILE}" 2>/dev/null || true)
            break
        fi
        sleep 0.1
    done

    kill "${PROC_PID}" 2>/dev/null || true
    for shutdown_attempt in $(seq 1 10); do
        if ! kill -0 "${PROC_PID}" 2>/dev/null; then break; fi
        sleep 0.2
    done
    kill -9 "${PROC_PID}" 2>/dev/null || true
    wait "${PROC_PID}" 2>/dev/null || true
    rm -f "${TMPFILE}"

    SECONDS_VAL=$(echo "${OUTPUT}" | grep -oP 'in \K[\d.]+(?= seconds)' || echo "")
    if [ -n "${SECONDS_VAL}" ]; then
        MS=$(printf "%.0f" "$(echo "${SECONDS_VAL} * 1000" | bc)")
        BASELINE_TIMES+=("${MS}")
        echo "  Run ${i}: ${MS}ms"
    else
        echo "  Run ${i}: timed out or failed"
        BASELINE_TIMES+=("0")
    fi
done

# ---- Step 3: Optimized measurements (optimizer enabled) ----
echo ""
echo "--- Step 3: Optimized Measurements (optimizer enabled) ---"
OPTIMIZED_TIMES=()
for i in $(seq 1 "${ITERATIONS}"); do
    TMPFILE=$(mktemp)
    "${NATIVE_BINARY}" \
        -Dautoconfiguration.optimizer.enabled=true \
        --spring.main.banner-mode=off \
        --server.port=0 \
        --spring.jpa.show-sql=false > "${TMPFILE}" 2>&1 &
    PROC_PID=$!

    OUTPUT=""
    END_TIME=$(($(date +%s) + 30))
    while [ "$(date +%s)" -lt "${END_TIME}" ]; do
        OUTPUT=$(grep -m1 "Started .* in" "${TMPFILE}" 2>/dev/null || true)
        if [ -n "${OUTPUT}" ]; then break; fi
        if ! kill -0 "${PROC_PID}" 2>/dev/null; then
            OUTPUT=$(grep -m1 "Started .* in" "${TMPFILE}" 2>/dev/null || true)
            break
        fi
        sleep 0.1
    done

    kill "${PROC_PID}" 2>/dev/null || true
    for shutdown_attempt in $(seq 1 10); do
        if ! kill -0 "${PROC_PID}" 2>/dev/null; then break; fi
        sleep 0.2
    done
    kill -9 "${PROC_PID}" 2>/dev/null || true
    wait "${PROC_PID}" 2>/dev/null || true
    rm -f "${TMPFILE}"

    SECONDS_VAL=$(echo "${OUTPUT}" | grep -oP 'in \K[\d.]+(?= seconds)' || echo "")
    if [ -n "${SECONDS_VAL}" ]; then
        MS=$(printf "%.0f" "$(echo "${SECONDS_VAL} * 1000" | bc)")
        OPTIMIZED_TIMES+=("${MS}")
        echo "  Run ${i}: ${MS}ms"
    else
        echo "  Run ${i}: timed out or failed"
        OPTIMIZED_TIMES+=("0")
    fi
done

# ---- Calculate averages ----
calc_avg() {
    local arr=("$@")
    local total=0
    local count=0
    for val in "${arr[@]}"; do
        if [ "${val}" -gt 0 ] 2>/dev/null; then
            total=$((total + val))
            count=$((count + 1))
        fi
    done
    if [ "${count}" -gt 0 ]; then
        echo $((total / count))
    else
        echo "0"
    fi
}

BASELINE_AVG=$(calc_avg "${BASELINE_TIMES[@]}")
OPTIMIZED_AVG=$(calc_avg "${OPTIMIZED_TIMES[@]}")

if [ "${BASELINE_AVG}" -gt 0 ] && [ "${OPTIMIZED_AVG}" -gt 0 ]; then
    IMPROVEMENT=$((BASELINE_AVG - OPTIMIZED_AVG))
    IMPROVEMENT_PCT=$(echo "scale=1; ${IMPROVEMENT} * 100 / ${BASELINE_AVG}" | bc)
else
    IMPROVEMENT=0
    IMPROVEMENT_PCT="0.0"
fi

GRAALVM_VERSION=$(native-image --version 2>&1 | head -1)
TIMESTAMP=$(date -u +"%Y-%m-%dT%H:%M:%SZ")

# ---- Generate Markdown report ----
cat > "${REPORT_FILE}" << EOF
# Spring Boot Autoconfiguration Optimizer - Native Image Benchmark Results

**Generated:** ${TIMESTAMP}
**GraalVM:** ${GRAALVM_VERSION}
**Iterations:** ${ITERATIONS}
**Target Application:** Spring PetClinic Sample (Web + JPA + Actuator + Cache + Validation)

## Context

GraalVM native images start dramatically faster than JVM-mode Spring Boot because
Spring Boot's AOT processing pre-resolves which beans and configurations apply at
**build time**. As a result, the \`AutoConfigurationImportFilter\` that powers the
optimizer may not be invoked during native startup at all. The figures below
confirm whether the optimizer provides any additional benefit in native mode.

For JVM-mode results (where the optimizer's impact is most significant), see the
Maven/Gradle benchmark reports in \`benchmarks/results/\` and \`benchmarks/results-gradle/\`.

## Startup Time Results (Native)

| Configuration | Average Startup Time |
|---|---|
| ⚪ Native baseline (optimizer disabled) | ${BASELINE_AVG}ms |
| 🟢 Native optimized (optimizer enabled) | ${OPTIMIZED_AVG}ms |
| **Difference** | **${IMPROVEMENT}ms (${IMPROVEMENT_PCT}%)** |

## Auto-Configuration Stats (from training run)

$(if [ -n "${TRAINING_AVAILABLE}" ]; then
cat << STATS_EOF
| Metric | Count |
|---|---|
| Total available auto-configurations | ${TRAINING_AVAILABLE} |
| Loaded (training set) | ${TRAINING_LOADED} |
| Excluded by optimizer | ${TRAINING_EXCLUDED} |
STATS_EOF
else
echo "_Training stats not available (binary provided externally)_"
fi)

## Detailed Results

### Native Baseline (Optimizer Disabled)
$(for i in "${!BASELINE_TIMES[@]}"; do echo "- Run $((i+1)): ${BASELINE_TIMES[$i]}ms"; done)

### Native Optimized (Optimizer Enabled)
$(for i in "${!OPTIMIZED_TIMES[@]}"; do echo "- Run $((i+1)): ${OPTIMIZED_TIMES[$i]}ms"; done)

## How to Reproduce

\`\`\`bash
# Prerequisites: GraalVM with native-image in PATH

# Build the project (JVM mode) and run training
mvn install -DskipTests -q -pl spring-boot-autoconfiguration-optimizer-maven-plugin -am
mvn package -DskipTests -f integration-tests/petclinic-sample/pom.xml

# Build native image (uses 'native' Maven profile)
./benchmarks/scripts/run-benchmarks-native.sh
\`\`\`

## Environment Details

| Property | Value |
|---|---|
| GraalVM | ${GRAALVM_VERSION} |
| Operating System | $(uname -s) $(uname -r) |
| Benchmark Date | ${TIMESTAMP} |
EOF

echo ""
echo "=================================================="
echo " Native Image Benchmark Results Summary"
echo "=================================================="
echo ""
echo "Native baseline (optimizer disabled): ${BASELINE_AVG}ms"
echo "Native optimized (optimizer enabled): ${OPTIMIZED_AVG}ms"
echo "Difference:                           ${IMPROVEMENT}ms (${IMPROVEMENT_PCT}%)"
echo ""
echo "Full report: ${REPORT_FILE}"
