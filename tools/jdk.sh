#!/usr/bin/env bash
#
# Finds a JDK 17 and exports JAVA_HOME. Sourced, not run:
#
#   . "$(dirname "$0")/jdk.sh"
#
# ## Why this exists
#
# `app-launcher/gradle.properties` used to pin `org.gradle.java.home` to an
# absolute path on one particular machine. That works on exactly that machine
# and fails on every clone with a message about an invalid Java home, naming a
# directory the reader has never heard of. A public repository cannot carry a
# line like that.
#
# So the path is found instead of declared. `gradlew` needs JAVA_HOME, and so
# does `apksigner`, which is a .bat shelling out to java — both are satisfied by
# exporting it once here.
#
# Search order, most explicit first:
#
#   1. JAVA_HOME already set          — the caller knows better than we do
#   2. ~/.gradle/gradle.properties    — where a per-machine pin belongs
#   3. `java` on PATH                 — resolved back to its home
#   4. Known Android Studio / SDK JDK locations
#
# It never guesses silently: if nothing is found, the caller is told what to do.

# Resolve a Windows-style path to whatever this shell can open. Harmless
# elsewhere — cygpath only exists under MSYS/Cygwin.
_jdk_unix() {
    if command -v cygpath >/dev/null 2>&1; then
        cygpath -u "$1" 2>/dev/null || printf '%s' "$1"
    else
        printf '%s' "$1"
    fi
}

_jdk_ok() {
    [ -n "${1:-}" ] && { [ -x "$1/bin/java" ] || [ -x "$1/bin/java.exe" ]; }
}

_jdk_find() {
    # 1. Already set and usable.
    if _jdk_ok "${JAVA_HOME:-}"; then
        printf '%s' "$JAVA_HOME"
        return 0
    fi

    # 2. The per-machine pin, in the place Gradle itself reads for user-level
    #    settings. Keeping it there rather than in the repo is the whole point.
    local user_props="$HOME/.gradle/gradle.properties"
    if [ -r "$user_props" ]; then
        local pinned
        pinned=$(sed -n 's/^[[:space:]]*org\.gradle\.java\.home[[:space:]]*=[[:space:]]*//p' \
            "$user_props" | tail -1 | tr -d '\r')
        if [ -n "$pinned" ]; then
            pinned=$(_jdk_unix "$pinned")
            if _jdk_ok "$pinned"; then
                printf '%s' "$pinned"
                return 0
            fi
        fi
    fi

    # 3. Whatever `java` is on PATH, walked back to its home.
    if command -v java >/dev/null 2>&1; then
        local exe home
        exe=$(command -v java)
        home=$(dirname "$(dirname "$exe")")
        if _jdk_ok "$home"; then
            printf '%s' "$home"
            return 0
        fi
    fi

    # 4. Where the Android tooling puts one. Ordered newest-looking first; the
    #    glob is deliberate, so a version bump does not need an edit here.
    local candidate
    for candidate in \
        "/c/Program Files/Android/Android Studio/jbr" \
        "/c/Program Files/Android/Android Studio/jre" \
        /c/Program\ Files*/Android/openjdk/jdk-17* \
        /Applications/Android\ Studio.app/Contents/jbr/Contents/Home \
        /usr/lib/jvm/java-17-openjdk* \
        /usr/lib/jvm/temurin-17*
    do
        if _jdk_ok "$candidate"; then
            printf '%s' "$candidate"
            return 0
        fi
    done

    return 1
}

if _found=$(_jdk_find); then
    JAVA_HOME="$_found"
    export JAVA_HOME
    unset _found
else
    cat >&2 <<'NOJDK'
   NG  no JDK 17 found.

       Gradle 8.9 / AGP 8.7 need Java 17. Do one of:

         export JAVA_HOME=/path/to/jdk-17

       or, to pin it for every Gradle invocation on this machine without
       putting a machine-specific path in the repository:

         mkdir -p ~/.gradle
         echo 'org.gradle.java.home=/path/to/jdk-17' >> ~/.gradle/gradle.properties

       On Windows use forward slashes, e.g. C:/Program Files/Android/Android Studio/jbr
NOJDK
    return 1 2>/dev/null || exit 1
fi
