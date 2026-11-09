<#
.SYNOPSIS
  Starts the application from a jar with the JVM's AOT cache (Project Leyden, JEP 483/514, JDK 25+).

.DESCRIPTION
  Three steps, as Spring Boot documents them:
    1. package   mvn package -Paot-cache      (the jar including vaadin-dev, see the profile in pom.xml)
    2. extract   java -Djarmode=tools ... extract   (the cache does not cover classes in a nested jar)
    3. train     one start with -XX:AOTCacheOutput and spring.context.exit=onRefresh, which writes the cache
  and then the start itself with -XX:AOTCache.

  Measured on this project (JDK 25.0.2, 2026-11-09): "Started Application" after 3.4 s with the cache,
  5.8 s from the same jar without it, 9.4 s with "mvn spring-boot:run".

  The cache belongs to exactly this jar, these libraries and this JDK. After a code or dependency change it no
  longer matches; the JVM then ignores it without a word and starts at normal speed. Run "build" again.

.EXAMPLE
  scripts/aot-cache.ps1                 # this help (same as 'help')
  scripts/aot-cache.ps1 all             # build (if there is no cache yet) and start
  scripts/aot-cache.ps1 build           # package, extract and train only
  scripts/aot-cache.ps1 run -Profile dev
  scripts/aot-cache.ps1 run -Verify     # log whether the cache was used (-Xlog:aot)
  scripts/aot-cache.ps1 run -AppArgs '--server.port=8091'
#>
param(
    [ValidateSet('help', 'all', 'build', 'run')]
    [string]$Action = 'help',
    # Spring profile(s) for the start, e.g. dev. The training run uses none.
    [string]$Profile = '',
    # Print the JVM's AOT log, to see that the cache is mapped and not rejected.
    [switch]$Verify,
    # Further arguments for the application, e.g. -AppArgs '--server.port=8091'
    [string[]]$AppArgs = @()
)

if ($Action -eq 'help') {
    Get-Help $PSCommandPath
    return
}

$ErrorActionPreference = 'Stop'
# From the project root: Vaadin's dev mode looks for the Maven project in the working directory.
Set-Location (Split-Path $PSScriptRoot -Parent)

$dir = 'target/aot'
$cache = "$dir/app.aot"

function Get-AppJar {
    Get-ChildItem $dir -Filter '*.jar' -ErrorAction SilentlyContinue | Select-Object -First 1
}

function Build-Cache {
    # Windows PowerShell 5.1 turns every stderr line of a native program into a terminating error under 'Stop' as
    # soon as stderr is redirected - and the JVM prints "Picked up JAVA_TOOL_OPTIONS" there for the child JVM of the
    # training run. Local to this function; success is judged by exit code, cache file and log below.
    $ErrorActionPreference = 'Continue'
    mvn -q package -DskipTests -Paot-cache
    if ($LASTEXITCODE -ne 0) { throw 'mvn package failed' }

    $fatJar = Get-ChildItem target -Filter '*.jar' | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (Test-Path $dir) { Remove-Item -Recurse -Force -Confirm:$false $dir }
    java -Djarmode=tools -jar $fatJar.FullName extract --destination $dir
    if ($LASTEXITCODE -ne 0) { throw 'extracting the jar failed' }

    # The training run: starts the context, exits after the refresh, and the JVM writes the cache on exit.
    # A random port, so it does not collide with a running instance; no browser; needs the database.
    $jar = Get-AppJar
    java "-XX:AOTCacheOutput=$cache" '-Dspring.context.exit=onRefresh' '-Dvaadin.launch-browser=false' `
        '-Dserver.port=0' "-Dlogging.file.name=$dir/training.log" -jar "$dir/$($jar.Name)" > "$dir/training-console.log" 2>&1
    if (-not (Test-Path $cache)) { throw "no cache written - see $dir/training-console.log" }
    if (Select-String -Path "$dir/training-console.log" -Pattern 'Application run failed' -Quiet) {
        Remove-Item -Force -Confirm:$false $cache
        throw "the training start failed (database reachable?) - see $dir/training-console.log"
    }
    "AOT cache written: $cache ($([math]::Round((Get-Item $cache).Length / 1MB)) MB)"
}

function Start-App {
    $jar = Get-AppJar
    if (-not $jar -or -not (Test-Path $cache)) { throw "no cache in $dir - run 'scripts/aot-cache.ps1 build' first" }
    $jvm = @("-XX:AOTCache=$cache")
    if ($Verify) { $jvm += '-Xlog:aot=info' }
    $app = @()
    if ($Profile) { $app += "--spring.profiles.active=$Profile" }
    java @jvm -jar "$dir/$($jar.Name)" @app @AppArgs
}

switch ($Action) {
    'build' { Build-Cache }
    'run' { Start-App }
    'all' {
        if (-not (Test-Path $cache)) { Build-Cache }
        Start-App
    }
}
