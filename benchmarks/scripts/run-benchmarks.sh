#!/usr/bin/env bash
# ============================================================
# Spring Boot Autoconfiguration Optimizer - Benchmark Runner
# ============================================================
# Usage: ./benchmarks/scripts/run-benchmarks.sh [petclinic-jar-path]
#
# This script:
# 1. Runs the PetClinic app in training mode to generate optimizer data
# 2. Measures startup time without optimization (baseline)
# 3. Measures startup time with optimization enabled
# 4. Generates a markdown report
# ============================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
RESULTS_DIR="${PROJECT_ROOT}/benchmarks/results"
REPORT_FILE="${RESULTS_DIR}/benchmark-report.md"
PETCLINIC_JAR="${1:-${PROJECT_ROOT}/integration-tests/petclinic-sample/target/autoconfiguration-optimizer-petclinic-sample-*.jar}"
ITERATIONS="${BENCHMARK_ITERATIONS:-5}"

mkdir -p "${RESULTS_DIR}"

JAVA="${JAVA_HOME:-$(dirname $(dirname $(which java)))}/bin/java"

echo "=================================================="
echo " Spring Boot Autoconfiguration Optimizer Benchmarks"
echo "=================================================="
echo ""
echo "Java: $(${JAVA} -version 2>&1 | head -1)"
echo "Iterations: ${ITERATIONS}"
echo ""

# Find the petclinic JAR
PETCLINIC_JAR_PATH=$(ls ${PETCLINIC_JAR} 2>/dev/null | head -1)
if [ -z "${PETCLINIC_JAR_PATH}" ]; then
    echo "ERROR: PetClinic JAR not found. Build first with: mvn package -pl integration-tests/petclinic-sample"
    exit 1
fi

echo "PetClinic JAR: ${PETCLINIC_JAR_PATH}"
echo ""

# Step 1: Training run
echo "--- Step 1: Training Run ---"
echo "Starting PetClinic in training mode to generate optimizer data..."

TRAINING_OUTPUT_DIR="${RESULTS_DIR}/training"
mkdir -p "${TRAINING_OUTPUT_DIR}"

timeout 120 ${JAVA} \
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
    echo "ERROR: Training run failed to generate properties file"
    exit 1
fi

echo "Training complete. Optimizer properties generated."

# Extract training stats from the properties file header
TRAINING_AVAILABLE=$(grep -oP '(?<=# Total available auto-configurations: )\d+' "${OPTIMIZER_PROPS}" || echo "")
TRAINING_LOADED=$(grep -oP '(?<=# Total auto-configurations loaded: )\d+' "${OPTIMIZER_PROPS}" || echo "")
TRAINING_EXCLUDED=$(grep -oP '(?<=# Auto-configurations excluded: )\d+' "${OPTIMIZER_PROPS}" || echo "")

if [ -n "${TRAINING_AVAILABLE}" ]; then
    echo "  Available auto-configurations: ${TRAINING_AVAILABLE}"
    echo "  Loaded auto-configurations:    ${TRAINING_LOADED}"
    echo "  Excluded auto-configurations:  ${TRAINING_EXCLUDED}"
fi
echo ""

# Step 2: Baseline measurements (no optimizer)
echo "--- Step 2: Baseline Measurements (no optimizer) ---"
BASELINE_TIMES=()
for i in $(seq 1 ${ITERATIONS}); do
    OUTPUT=$(timeout 60 ${JAVA} \
        -Dautoconfiguration.optimizer.enabled=false \
        -jar "${PETCLINIC_JAR_PATH}" \
        --spring.main.banner-mode=off \
        --server.port=0 \
        --spring.jpa.show-sql=false 2>&1 | \
        grep -m1 "Started .* in" || true)

    STARTUP_SECONDS=$(echo "${OUTPUT}" | grep -oP 'in \K[\d.]+(?= seconds)' || echo "")
    if [ -n "${STARTUP_SECONDS}" ]; then
        STARTUP_MS=$(printf "%.0f" $(echo "${STARTUP_SECONDS} * 1000" | bc))
        BASELINE_TIMES+=("${STARTUP_MS}")
        echo "  Run ${i}: ${STARTUP_MS}ms"
    else
        echo "  Run ${i}: timed out or failed"
        BASELINE_TIMES+=("0")
    fi
done

# Step 3: Optimized measurements (with optimizer)
echo ""
echo "--- Step 3: Optimized Measurements (with optimizer) ---"
OPTIMIZED_TIMES=()

# Create a temp dir with the optimizer properties on the classpath
TEMP_CLASSPATH_DIR=$(mktemp -d)
mkdir -p "${TEMP_CLASSPATH_DIR}/META-INF"
cp "${OPTIMIZER_PROPS}" "${TEMP_CLASSPATH_DIR}/META-INF/autoconfiguration-optimizer.properties"

for i in $(seq 1 ${ITERATIONS}); do
    OUTPUT=$(timeout 60 ${JAVA} \
        -cp "${TEMP_CLASSPATH_DIR}:${PETCLINIC_JAR_PATH}" \
        -Dloader.path="${TEMP_CLASSPATH_DIR}" \
        -Dautoconfiguration.optimizer.enabled=true \
        org.springframework.boot.loader.launch.JarLauncher \
        --spring.main.banner-mode=off \
        --server.port=0 \
        --spring.jpa.show-sql=false 2>&1 | \
        grep -m1 "Started .* in" || true)

    STARTUP_SECONDS=$(echo "${OUTPUT}" | grep -oP 'in \K[\d.]+(?= seconds)' || echo "")
    if [ -n "${STARTUP_SECONDS}" ]; then
        STARTUP_MS=$(printf "%.0f" $(echo "${STARTUP_SECONDS} * 1000" | bc))
        OPTIMIZED_TIMES+=("${STARTUP_MS}")
        echo "  Run ${i}: ${STARTUP_MS}ms"
    else
        echo "  Run ${i}: timed out or failed"
        OPTIMIZED_TIMES+=("0")
    fi
done

rm -rf "${TEMP_CLASSPATH_DIR}"

# Calculate averages
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
    if [ ${count} -gt 0 ]; then
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

JAVA_VERSION=$(${JAVA} -version 2>&1 | grep -oP '(?<=version ")[^"]+')
TIMESTAMP=$(date -u +"%Y-%m-%dT%H:%M:%SZ")

# Generate Markdown report
cat > "${REPORT_FILE}" << EOF
# Spring Boot Autoconfiguration Optimizer - Benchmark Results

**Generated:** ${TIMESTAMP}
**Java Version:** ${JAVA_VERSION}
**Iterations:** ${ITERATIONS}
**Target Application:** Spring PetClinic Sample (Web + JPA + Actuator + Cache + Validation)

## Startup Time Results

| Configuration | Average Startup Time |
|---|---|
| ⚪ Baseline (no optimizer) | ${BASELINE_AVG}ms |
| 🟢 Optimized (with optimizer) | ${OPTIMIZED_AVG}ms |
| **Improvement** | **${IMPROVEMENT}ms (${IMPROVEMENT_PCT}% faster)** |

## Auto-Configuration Reduction

$(if [ -n "${TRAINING_AVAILABLE}" ]; then
cat << STATS_EOF
| Metric | Count |
|---|---|
| Total available auto-configurations | ${TRAINING_AVAILABLE} |
| Loaded (training set) | ${TRAINING_LOADED} |
| Excluded by optimizer | ${TRAINING_EXCLUDED} |
STATS_EOF
else
echo "_Training stats not available (properties file header format not recognised)_"
fi)

## Detailed Results

### Baseline (No Optimizer)
$(for i in "${!BASELINE_TIMES[@]}"; do echo "- Run $((i+1)): ${BASELINE_TIMES[$i]}ms"; done)

### Optimized (With Optimizer)
$(for i in "${!OPTIMIZED_TIMES[@]}"; do echo "- Run $((i+1)): ${OPTIMIZED_TIMES[$i]}ms"; done)

## How to Reproduce

\`\`\`bash
# Build the project
mvn package -DskipTests

# Run benchmarks
./benchmarks/scripts/run-benchmarks.sh integration-tests/petclinic-sample/target/*.jar
\`\`\`

## Environment Details

| Property | Value |
|---|---|
| Java Version | ${JAVA_VERSION} |
| Operating System | $(uname -s) $(uname -r) |
| Benchmark Date | ${TIMESTAMP} |
EOF

echo ""
echo "=================================================="
echo " Benchmark Results Summary"
echo "=================================================="
echo ""
echo "Baseline (no optimizer):     ${BASELINE_AVG}ms"
echo "Optimized (with optimizer):  ${OPTIMIZED_AVG}ms"
echo "Improvement:                 ${IMPROVEMENT}ms (${IMPROVEMENT_PCT}% faster)"
echo ""
echo "Full report: ${REPORT_FILE}"
