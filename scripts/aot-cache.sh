#!/usr/bin/env bash
#
# Starts the application from a jar with the JVM's AOT cache (Project Leyden, JEP 483/514, JDK 25+).
# The counterpart of aot-cache.ps1 for Linux and macOS; see there for the measurements.
#
# Three steps, as Spring Boot documents them:
#   1. package   mvn package -Paot-cache      (the jar including vaadin-dev, see the profile in pom.xml)
#   2. extract   java -Djarmode=tools ... extract   (the cache does not cover classes in a nested jar)
#   3. train     one start with -XX:AOTCacheOutput and spring.context.exit=onRefresh, which writes the cache
# and then the start itself with -XX:AOTCache.
#
# The cache belongs to exactly this jar, these libraries and this JDK. After a code or dependency change it no
# longer matches; the JVM then ignores it without a word and starts at normal speed. Run "build" again.
#
# Usage:
#   scripts/aot-cache.sh                          # this help (same as 'help' or --help)
#   scripts/aot-cache.sh all                      # build (if there is no cache yet) and start
#   scripts/aot-cache.sh build                    # package, extract and train only
#   scripts/aot-cache.sh run --profile dev
#   scripts/aot-cache.sh run --verify             # log whether the cache was used (-Xlog:aot)
#   scripts/aot-cache.sh run -- --server.port=8091   # everything after -- goes to the application

set -euo pipefail

action=help
profile=
verify=0
app_args=()

while [[ $# -gt 0 ]]; do
    case "$1" in
        help | all | build | run) action=$1 ;;
        -p | --profile) profile=${2:?--profile needs a value}; shift ;;
        -v | --verify) verify=1 ;;
        --) shift; app_args=("$@"); break ;;
        -h | --help) action=help ;;
        *) echo "unknown argument: $1 (see --help)" >&2; exit 2 ;;
    esac
    shift
done

if [[ $action == help ]]; then
    sed -n '2,21p' "$0" | sed 's/^# \{0,1\}//'
    exit 0
fi

# From the project root: Vaadin's dev mode looks for the Maven project in the working directory.
cd "$(dirname "${BASH_SOURCE[0]}")/.."

dir=target/aot
cache=$dir/app.aot

app_jar() {
    # The extracted jar only; the libraries sit in lib/ and are not matched by this glob.
    local jar
    for jar in "$dir"/*.jar; do
        [[ -e $jar ]] && { basename "$jar"; return 0; }
    done
    return 1
}

build_cache() {
    mvn -q package -DskipTests -Paot-cache

    # The newest jar in target/, not the "-plain" one a plain-jar goal might add.
    local fat_jar
    fat_jar=$(ls -t target/*.jar | grep -v -- '-plain\.jar$' | head -n 1)
    rm -rf "$dir"
    java -Djarmode=tools -jar "$fat_jar" extract --destination "$dir"

    # The training run: starts the context, exits after the refresh, and the JVM writes the cache on exit.
    # A random port, so it does not collide with a running instance; no browser; needs the database.
    local jar
    jar=$(app_jar)
    java "-XX:AOTCacheOutput=$cache" -Dspring.context.exit=onRefresh -Dvaadin.launch-browser=false \
        -Dserver.port=0 "-Dlogging.file.name=$dir/training.log" -jar "$dir/$jar" \
        > "$dir/training-console.log" 2>&1 || true
    if [[ ! -f $cache ]]; then
        echo "no cache written - see $dir/training-console.log" >&2
        return 1
    fi
    if grep -q 'Application run failed' "$dir/training-console.log"; then
        rm -f "$cache"
        echo "the training start failed (database reachable?) - see $dir/training-console.log" >&2
        return 1
    fi
    echo "AOT cache written: $cache ($(du -m "$cache" | cut -f1) MB)"
}

start_app() {
    local jar
    if ! jar=$(app_jar) || [[ ! -f $cache ]]; then
        echo "no cache in $dir - run 'scripts/aot-cache.sh build' first" >&2
        return 1
    fi
    local jvm=("-XX:AOTCache=$cache")
    [[ $verify -eq 1 ]] && jvm+=(-Xlog:aot=info)
    local app=()
    [[ -n $profile ]] && app+=("--spring.profiles.active=$profile")
    exec java "${jvm[@]}" -jar "$dir/$jar" "${app[@]}" "${app_args[@]}"
}

case $action in
    build) build_cache ;;
    run) start_app ;;
    all)
        [[ -f $cache ]] || build_cache
        start_app
        ;;
esac
