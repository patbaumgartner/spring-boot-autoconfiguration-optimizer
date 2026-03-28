# Spring Boot Autoconfiguration Optimizer - Benchmark Results

**Generated:** 2026-03-16T16:45:28Z
**Java Version:** 21.0.10
**Iterations:** 42
**Target Application:** Spring PetClinic Sample (Web + JPA + Actuator + Cache + Validation)

## Startup Time Results

| Configuration | Average Startup Time |
|---|---|
| ⚪ Baseline (no optimizer) | 6088ms |
| 🟢 Optimized (with optimizer) | 4330ms |
| **Improvement** | **1758ms (28.8% faster)** |

## Auto-Configuration Reduction

| Metric | Count |
|---|---|
| Total available auto-configurations | 115 |
| Loaded (training set) |  |
| Excluded by optimizer |  |

## Detailed Results

### Baseline (No Optimizer)
- Run 1: 5691ms
- Run 2: 5706ms
- Run 3: 5669ms
- Run 4: 5489ms
- Run 5: 7523ms
- Run 6: 6525ms
- Run 7: 6782ms
- Run 8: 6737ms
- Run 9: 6586ms
- Run 10: 6328ms
- Run 11: 6383ms
- Run 12: 6496ms
- Run 13: 6422ms
- Run 14: 6770ms
- Run 15: 6727ms
- Run 16: 6379ms
- Run 17: 6560ms
- Run 18: 6949ms
- Run 19: 7011ms
- Run 20: 6574ms
- Run 21: 6653ms
- Run 22: 6893ms
- Run 23: 6662ms
- Run 24: 6750ms
- Run 25: 7024ms
- Run 26: 6934ms
- Run 27: 6899ms
- Run 28: 5352ms
- Run 29: 6537ms
- Run 30: 4998ms
- Run 31: 5119ms
- Run 32: 5213ms
- Run 33: 5468ms
- Run 34: 5259ms
- Run 35: 5274ms
- Run 36: 5728ms
- Run 37: 5324ms
- Run 38: 5310ms
- Run 39: 5578ms
- Run 40: 4220ms
- Run 41: 5329ms
- Run 42: 3885ms

### Optimized (With Optimizer)
- Run 1: 3989ms
- Run 2: 4298ms
- Run 3: 3829ms
- Run 4: 3963ms
- Run 5: 3888ms
- Run 6: 4153ms
- Run 7: 3779ms
- Run 8: 4229ms
- Run 9: 4783ms
- Run 10: 4020ms
- Run 11: 4261ms
- Run 12: 3809ms
- Run 13: 4543ms
- Run 14: 3749ms
- Run 15: 4071ms
- Run 16: 3769ms
- Run 17: 4099ms
- Run 18: 3816ms
- Run 19: 3842ms
- Run 20: 4165ms
- Run 21: 3986ms
- Run 22: 3829ms
- Run 23: 4101ms
- Run 24: 4204ms
- Run 25: 4278ms
- Run 26: 4529ms
- Run 27: 3898ms
- Run 28: 3785ms
- Run 29: 3804ms
- Run 30: 4296ms
- Run 31: 3791ms
- Run 32: 4738ms
- Run 33: 4320ms
- Run 34: 4055ms
- Run 35: 3835ms
- Run 36: 5414ms
- Run 37: 5629ms
- Run 38: 5667ms
- Run 39: 5648ms
- Run 40: 5437ms
- Run 41: 5781ms
- Run 42: 5806ms

## How to Reproduce

```bash
# Build the project
mvn package -DskipTests

# Run benchmarks
./benchmarks/scripts/run-benchmarks-maven.sh integration-tests/petclinic-sample/target/*.jar
```

## Environment Details

| Property | Value |
|---|---|
| Java Version | 21.0.10 |
| Operating System | Linux 6.6.87.2-microsoft-standard-WSL2 |
| Benchmark Date | 2026-03-16T16:45:28Z |
