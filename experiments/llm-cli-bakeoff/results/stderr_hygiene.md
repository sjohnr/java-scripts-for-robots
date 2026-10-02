# Stderr/stdout hygiene (Phase 3/4, H5)

Measured on JDK 25 (Temurin 25.0.4+7), success-path invocations of `noop`.

| Mode | stderr bytes on success | verbatim noise |
|---|---:|---|
| java-class (`-cp`) | 0 | none |
| java-jar | 0 | none |
| java-aot (`-XX:AOTCache=`) | 0 | none |
| java-cds-classic (`-XX:SharedArchiveFile=`) | 0 | none |
| java-src (`java Tool.java`) | 0 | none |
| java-compact | 0 | none |

No JVM banners, no "Picked up JAVA_TOOL_OPTIONS" notices, no CDS/AOT warnings on any of these six modes when run standalone with no environment variables set and a valid, matching cache file. This is a genuinely clean result on JDK 25 -- older JDKs (8-11 era) were known for stray CDS-related stderr output by default; that appears to have been cleaned up by JDK 25.

## Two important caveats found during Phase 3 benchmarking

1. **JBang cold start is noisy.** First invocation of a JBang script after a dependency change prints `[jbang] Resolving dependencies...`, `[jbang] Building jar for tool.java...` etc. to stderr (see tools/jsonpick/java/jbang commit). Warm invocations (cache hit) are silent. This is a one-time cost, not per-invocation noise, but it is real noise an agent would see and have to filter on the very first call.

2. **A stale/mismatched AOT cache degrades silently, not to an error.** If the jar a `-XX:AOTCache=` archive was built against changes (rebuilt, moved, touched), java does NOT fail -- it prints an `[error][aot]` block to stderr (`shared class paths mismatch`) and falls back to normal class loading, which is *slower* than not using AOT at all (measured directly during this project: see bench/results/raw/jarsrc-small.json history / commit "Phase 3a"). An agent -- or a CI pipeline -- that isn't watching stderr closely would see the program still work, just mysteriously slower, with no hard failure to signal "your cache is stale."
