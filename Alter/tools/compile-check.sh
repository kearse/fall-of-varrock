#!/usr/bin/env bash
#
# Gradle-free compile check for the Alter server modules.
#
# Why: some sandboxes (e.g. Claude Code remote sessions) sit behind an egress policy
# that blocks the Gradle distribution hosts (downloads.gradle.org / GitHub release
# assets) but allows Maven Central. This script bootstraps Apache Maven and the
# standalone Kotlin compiler FROM Maven Central, resolves the external classpath via
# tools/compile-check/pom.xml, and compiles every module in dependency order.
#
# It is a COMPILE CHECK, not a build: no resources, no jars, no tests — it exists to
# catch type/signature errors when `gradlew compileKotlin` isn't runnable. The real
# build remains Gradle.
#
# Usage: tools/compile-check.sh [module ...]   (default: all, in dependency order)
# Cache: $FOV_COMPILE_CACHE or ~/.cache/fov-compile (~350MB after first run)
set -euo pipefail

ALTER="$(cd "$(dirname "$0")/.." && pwd)"
CACHE="${FOV_COMPILE_CACHE:-$HOME/.cache/fov-compile}"
LIBS="$CACHE/libs"
OUT="$CACHE/out"
CENTRAL="https://repo.maven.apache.org/maven2"
MAVEN_VER="3.9.9"
KOTLIN_VER="2.0.20" # keep in sync with gradle/libs.versions.toml [versions] kotlin
MVN="$CACHE/apache-maven-$MAVEN_VER/bin/mvn"
KOTLINC_JAR="$CACHE/kotlin-compiler-$KOTLIN_VER.jar"
SER_PLUGIN="$CACHE/kotlin-serialization-compiler-plugin-$KOTLIN_VER.jar"

# Modules in dependency order: earlier outputs join the classpath of later ones.
# plugins/tools is deliberately EXCLUDED: it's a dev-tools module no game module imports,
# and its 4koma dependency lives only on jitpack (blocked in the sandboxes this script
# exists for). Check it with Gradle locally instead.
ALL_MODULES=(util plugins/rscm plugins/filestore game-server game-api game-plugins)
MODULES=("${@:-${ALL_MODULES[@]}}")

mkdir -p "$CACHE" "$LIBS" "$OUT"

fetch() { # fetch <url> <dest>
    [ -f "$2" ] && return 0
    echo "[compile-check] fetching $(basename "$2")"
    curl -fsSL -o "$2.part" "$1" && mv "$2.part" "$2"
}

# 1) Apache Maven (from Maven Central — the distribution hosts may be blocked, this isn't).
if [ ! -x "$MVN" ]; then
    fetch "$CENTRAL/org/apache/maven/apache-maven/$MAVEN_VER/apache-maven-$MAVEN_VER-bin.zip" "$CACHE/maven.zip"
    unzip -qo "$CACHE/maven.zip" -d "$CACHE" && rm -f "$CACHE/maven.zip"
fi

# 2) Standalone Kotlin compiler + serialization compiler plugin.
fetch "$CENTRAL/org/jetbrains/kotlin/kotlin-compiler/$KOTLIN_VER/kotlin-compiler-$KOTLIN_VER.jar" "$KOTLINC_JAR"
fetch "$CENTRAL/org/jetbrains/kotlin/kotlin-serialization-compiler-plugin/$KOTLIN_VER/kotlin-serialization-compiler-plugin-$KOTLIN_VER.jar" "$SER_PLUGIN"

# 3) External classpath, resolved (with transitives) by Maven from the manifest pom.
if [ ! -f "$LIBS/.done" ]; then
    echo "[compile-check] resolving external dependencies (first run only)…"
    "$MVN" -q -f "$ALTER/tools/compile-check/pom.xml" dependency:copy-dependencies \
        -DoutputDirectory="$LIBS" -Dmdep.copyPom=false -DincludeScope=runtime
    touch "$LIBS/.done"
fi

BASE_CP="$(find "$LIBS" -name '*.jar' | paste -sd: -)"

# 4) Compile each module; prior modules' class dirs join the classpath.
built_cp=""
status=0
for m in "${ALL_MODULES[@]}"; do
    out="$OUT/${m//\//_}"
    # Only compile modules that were requested (but ALWAYS accumulate the classpath in
    # dependency order, so `compile-check.sh game-plugins` reuses earlier outputs).
    want=0
    for sel in "${MODULES[@]}"; do [ "$sel" = "$m" ] && want=1; done
    if [ "$want" = 1 ]; then
        src="$ALTER/$m/src/main/kotlin"
        if [ ! -d "$src" ]; then echo "[compile-check] SKIP $m (no sources)"; else
            # Cheap up-to-date check: skip when no source is newer than the last stamp.
            if [ -f "$out/.stamp" ] && [ -z "$(find "$src" -name '*.kt' -newer "$out/.stamp" -print -quit)" ]; then
                echo "[compile-check] $m up to date"
            else
                echo "[compile-check] compiling $m…"
                mkdir -p "$out"
                if java -Xmx3g -cp "$KOTLINC_JAR" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
                    -jvm-target 17 -nowarn -no-stdlib \
                    -Xplugin="$SER_PLUGIN" \
                    -cp "$BASE_CP${built_cp:+:$built_cp}" \
                    -d "$out" "$src"; then
                    touch "$out/.stamp"
                else
                    echo "[compile-check] FAILED: $m" >&2
                    status=1
                    break
                fi
            fi
        fi
    fi
    [ -d "$out" ] && built_cp="${built_cp:+$built_cp:}$out"
done

if [ "$status" = 0 ]; then echo "[compile-check] OK"; fi
exit "$status"
