<#
.SYNOPSIS
    NovaPlay automated Windows test runner (development only). See tools/README-tests.md.

.DESCRIPTION
    Runs AutoTest scenarios (desktop/src/main/kotlin/.../AutoTest.kt) one at a time through `gradlew :desktop:run`.
    For every scenario:
      * a unique run id and its own folder (<OutDir>\runs\<id>) with app.log, monitor.log, gradle.log, status.txt;
      * the result comes only from the app's own "END run=<id> status=..." line for that id, plus the exit code:
        PASS, FAIL, TIMEOUT, INCOMPLETE (crashed, killed or never started), STOPPED_* (a resource stop condition),
        SKIPPED_RESOURCES (the PC was too busy to start);
      * resource stop conditions (system CPU and free memory, the app's memory, a window not responding, a silent log)
        and a time limit, enforced both inside the app and by this runner;
      * cleanup of only the processes this runner started (the app by its logged pid, then gradle and its children).
    NovaPlay's data is copied to <OutDir>\appdata and the test app points APPDATA there, so the installed NovaPlay's
    playlists and settings are never written. Keys go into the test app's own window from inside it; this runner
    sends no keyboard or mouse input and touches no other program.

.EXAMPLE
    powershell -NoProfile -ExecutionPolicy Bypass -File tools\run-tests.ps1 fullscreen
.EXAMPLE
    powershell -NoProfile -ExecutionPolicy Bypass -File tools\run-tests.ps1 chancheck fullscreen netmulti
#>
param(
    [Parameter(Position = 0, ValueFromRemainingArguments = $true)][string[]]$Scenarios,
    # Where runs, the data copy and the lock live (outside the repository by default).
    [string]$OutDir = $(if ($env:NOVAPLAY_TEST_DIR) { $env:NOVAPLAY_TEST_DIR } else { Join-Path $env:LOCALAPPDATA 'NovaPlayTests' }),
    # Multi-view screens the scenarios use (2 to 4).
    [ValidateRange(2, 4)][int]$Screens = 2,
    # JDK for gradle; defaults to JAVA_HOME, else the newest JDK 17 under ~\.jdks.
    [string]$JavaHome = $env:JAVA_HOME,
    # Long scenarios (netsoak, about an hour) only run with this switch.
    [switch]$AllowLong,
    [switch]$List
)

$ErrorActionPreference = 'Continue'
$Project = Split-Path -Parent $PSScriptRoot

# Scenario -> time limit (s). The app stops itself at the limit; the runner kills it 120 s later.
$Known = [ordered]@{
    fullscreen = 900   # 20 channel changes, 10 opens (about 3 min)
    chancheck  = 900   # 12 channels, each judged (about 4 min)
    netmulti   = 900   # multi-view, 6 network drops (about 5 min)
    launch     = 300
    netdrop    = 600
    multiview  = 600
    cycles     = 900
    netfreeze  = 900
    netend     = 600   # live stream ended by the server: retry, error, Try again (about 3 min)
    netzap     = 900   # 50 channel changes, resources sampled, 90 s cooldown (about 10 min)
    netopen    = 600   # 15 full-screen open/close cycles, resources sampled (about 5 min)
    netmvcycle = 900   # 10 multi-view entries/exits, 2 screens, resources sampled (about 10 min)
    limits     = 900
    probes     = 600
    spike      = 600
    netsoak    = 3600  # long run: needs -AllowLong
}
$Long = @('netsoak')

$Limits = @{
    MinFreeMbStart = 3000   # don't start below this much free memory
    MaxCpuStart = 60        # ... or above this system CPU %
    MinFreeMb = 1500        # stop if free memory falls below
    MaxCpu = 90             # stop if system CPU stays above this ...
    CpuSamples = 6          # ... for this many samples in a row (3 s apart: 18 s)
    MaxAppMb = 2500         # stop if the app's private memory goes above
    MaxSilentS = 120        # stop if the app's log stays silent this long (hung), after the first 5 min
    MaxNotRespondingS = 30  # stop if Windows reports the window not responding this long
    Players = 6             # in-app limit: VLC players
    HeapMb = 1500           # in-app limit: Java heap
    CooldownS = 30          # pause between scenarios
}

if ($List -or -not $Scenarios) {
    Write-Host "Scenarios (time limit s):"; $Known.GetEnumerator() | ForEach-Object { Write-Host ("  {0,-11} {1}{2}" -f $_.Key, $_.Value, $(if ($Long -contains $_.Key) { '  (long: needs -AllowLong)' } else { '' })) }
    exit 0
}
$Scenarios = @($Scenarios | ForEach-Object { $_.Trim().ToLower() } | Where-Object { $_ })
foreach ($s in $Scenarios) {
    if (-not $Known.Contains($s)) { Write-Host "unknown scenario '$s' (see -List)"; exit 2 }
    if (($Long -contains $s) -and -not $AllowLong) { Write-Host "'$s' is a long run: add -AllowLong to start it"; exit 2 }
}

if (-not $JavaHome -or -not (Test-Path "$JavaHome\bin\java.exe")) {
    $JavaHome = Get-ChildItem (Join-Path $HOME '.jdks') -Directory -Filter 'jdk-17*' -ErrorAction SilentlyContinue | Sort-Object Name -Descending | Select-Object -First 1 -ExpandProperty FullName
}
if (-not $JavaHome) { Write-Host 'no JDK found: set JAVA_HOME or pass -JavaHome'; exit 2 }
$env:JAVA_HOME = $JavaHome

$Runs = Join-Path $OutDir 'runs'
$Sandbox = Join-Path $OutDir 'appdata'
New-Item -ItemType Directory -Force $Runs | Out-Null
$Batch = Get-Date -Format 'yyyyMMdd-HHmmss'
$Summary = Join-Path $Runs "summary-$Batch.txt"
function Say($m) { $l = "$(Get-Date -Format HH:mm:ss) $m"; Add-Content -Path $Summary -Value $l; Write-Host $l }

# One runner at a time.
$Lock = Join-Path $OutDir 'runner.lock'
if (Test-Path $Lock) {
    $old = Get-Content $Lock -ErrorAction SilentlyContinue
    if ($old -and (Get-Process -Id $old -ErrorAction SilentlyContinue)) { Write-Host "another runner (pid $old) is active: not starting"; exit 2 }
}
Set-Content -Path $Lock -Value $PID

# Copy of NovaPlay's data (playlists, settings, guide; not the picture cache), refreshed each batch. The installed
# app's folder is only read.
$src = Join-Path $env:APPDATA 'NovaPlay'
$dst = Join-Path $Sandbox 'NovaPlay'
New-Item -ItemType Directory -Force $dst | Out-Null
if (Test-Path $src) {
    Get-ChildItem $src -File | Where-Object { $_.Name -like 'settings.json' -or $_.Name -like 'lib_*.bin' -or $_.Name -like 'epg.db*' } |
        ForEach-Object { Copy-Item $_.FullName (Join-Path $dst $_.Name) -Force }
}
Say "batch ${Batch}: $($Scenarios -join ', ') (screens $Screens); runs in $Runs; test data in $dst"

function SysCpu { [int](Get-CimInstance Win32_Processor | Measure-Object LoadPercentage -Average).Average }
function SysFreeMb { [int]((Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory / 1024) }
function Tree($id) { $kids = Get-CimInstance Win32_Process -Filter "ParentProcessId=$id" -ErrorAction SilentlyContinue; foreach ($k in $kids) { Tree $k.ProcessId }; $id }
function KillTree($id) { foreach ($p in (Tree $id)) { Stop-Process -Id $p -Force -ErrorAction SilentlyContinue } }

$results = @(); $allPass = $true
try {
    foreach ($s in $Scenarios) {
        $id = "$Batch-$s-$(Get-Random -Maximum 99999)"
        $dir = Join-Path $Runs $id; New-Item -ItemType Directory -Force $dir | Out-Null
        $appLog = Join-Path $dir 'app.log'; $mon = Join-Path $dir 'monitor.log'; $glog = Join-Path $dir 'gradle.log'
        $to = $Known[$s]

        # Pre-flight: enough free memory, CPU calm.
        $cpu = SysCpu; $free = SysFreeMb
        if ($free -lt $Limits.MinFreeMbStart -or $cpu -gt $Limits.MaxCpuStart) {
            $st = 'SKIPPED_RESOURCES'; Say "$s [$id] $st (cpu $cpu%, free $free MB)"; Set-Content (Join-Path $dir 'status.txt') $st
            $results += "$s $st"; $allPass = $false; break
        }

        # The test app's environment (inherited by gradle and the app only).
        $saved = $env:APPDATA
        $env:APPDATA = $Sandbox
        $env:NOVAPLAY_AUTOTEST = $s; $env:NOVAPLAY_AUTOTEST_RUN = $id; $env:NOVAPLAY_AUTOTEST_LOG = $appLog
        $env:NOVAPLAY_AUTOTEST_TIMEOUT_S = "$to"; $env:NOVAPLAY_AUTOTEST_MAX_PLAYERS = "$($Limits.Players)"
        $env:NOVAPLAY_AUTOTEST_MAX_HEAP_MB = "$($Limits.HeapMb)"; $env:NOVAPLAY_AUTOTEST_SCREENS = "$Screens"; $env:NOVAPLAY_LOG = '1'
        $t0 = Get-Date
        Say "$s [$id] START (time limit $to s, cpu $cpu%, free $free MB)"
        $g = Start-Process -FilePath (Join-Path $Project 'gradlew.bat') -ArgumentList ':desktop:run', '--no-daemon', '-PnovaplayNoAccounts=true' `
            -WorkingDirectory $Project -RedirectStandardOutput $glog -RedirectStandardError "$glog.err" -WindowStyle Hidden -PassThru
        $null = $g.Handle   # keeps the exit code readable after the process ends
        $env:APPDATA = $saved
        foreach ($k in 'NOVAPLAY_AUTOTEST', 'NOVAPLAY_AUTOTEST_RUN', 'NOVAPLAY_AUTOTEST_LOG', 'NOVAPLAY_AUTOTEST_TIMEOUT_S',
                'NOVAPLAY_AUTOTEST_MAX_PLAYERS', 'NOVAPLAY_AUTOTEST_MAX_HEAP_MB', 'NOVAPLAY_AUTOTEST_SCREENS', 'NOVAPLAY_LOG') {
            Remove-Item "env:$k" -ErrorAction SilentlyContinue
        }

        $st = $null; $appPid = $null; $hot = 0; $notResp = 0; $lastSize = -1; $lastGrow = Get-Date
        while (-not $g.HasExited) {
            Start-Sleep -Seconds 3
            $el = [int]((Get-Date) - $t0).TotalSeconds
            if (-not $appPid -and (Test-Path $appLog)) {
                $b = Select-String -Path $appLog -Pattern "BEGIN run=$([regex]::Escape($id)) .* pid=(\d+)" | Select-Object -First 1
                if ($b) { $appPid = [int]$b.Matches[0].Groups[1].Value; Add-Content $mon "app pid $appPid" }
            }
            $cpu = SysCpu; $free = SysFreeMb; $appMb = -1; $resp = $true
            if ($appPid) { $ap = Get-Process -Id $appPid -ErrorAction SilentlyContinue; if ($ap) { $appMb = [int]($ap.PrivateMemorySize64 / 1MB); $resp = $ap.Responding } }
            $size = if (Test-Path $appLog) { (Get-Item $appLog).Length } else { 0 }
            if ($size -ne $lastSize) { $lastSize = $size; $lastGrow = Get-Date }
            $silent = [int]((Get-Date) - $lastGrow).TotalSeconds
            Add-Content $mon "+${el}s cpu=$cpu% free=${free}MB app=${appMb}MB responding=$resp logSilent=${silent}s"
            if ($cpu -gt $Limits.MaxCpu) { $hot++ } else { $hot = 0 }
            if (-not $resp) { $notResp += 3 } else { $notResp = 0 }
            if ($el -gt $to + 120) { $st = 'TIMEOUT' }
            elseif ($free -lt $Limits.MinFreeMb) { $st = 'STOPPED_MEMORY' }
            elseif ($hot -ge $Limits.CpuSamples) { $st = 'STOPPED_CPU' }
            elseif ($appMb -gt $Limits.MaxAppMb) { $st = 'STOPPED_APP_MEMORY' }
            elseif ($notResp -ge $Limits.MaxNotRespondingS) { $st = 'STOPPED_NOT_RESPONDING' }
            elseif ($silent -gt $Limits.MaxSilentS -and $el -gt 300) { $st = 'STOPPED_HUNG' }
            if ($st) { Add-Content $mon "STOP: $st"; break }
        }

        # Cleanup: the app (by its own pid), then everything this runner started (gradle and its children). Nothing else.
        if ($appPid) { Stop-Process -Id $appPid -Force -ErrorAction SilentlyContinue }
        if (-not $g.HasExited) { $g.WaitForExit(20000) | Out-Null }
        KillTree $g.Id
        Start-Sleep -Seconds 2
        $left = @(); foreach ($p in @($appPid) + @(Tree $g.Id)) { if ($p -and (Get-Process -Id $p -ErrorAction SilentlyContinue)) { $left += $p } }
        $exit = if ($g.HasExited) { $g.ExitCode } else { -1 }

        # Status: only the app's own END line for this run id counts; anything missing is not a pass.
        if (-not $st) {
            $end = if (Test-Path $appLog) { Select-String -Path $appLog -Pattern "END run=$([regex]::Escape($id)) status=(\w+)" | Select-Object -Last 1 } else { $null }
            $beg = if (Test-Path $appLog) { Select-String -Path $appLog -Pattern "BEGIN run=$([regex]::Escape($id)) " -Quiet } else { $false }
            if (-not $beg) { $st = 'INCOMPLETE (never started; see gradle.log)' }
            elseif (-not $end) { $st = 'INCOMPLETE (no END line: crashed or killed)' }
            else {
                $st = $end.Matches[0].Groups[1].Value
                if ($st -eq 'ABORTED') {
                    $st = if (Select-String -Path $appLog -Pattern '^\S+ STOP scenario ran over' -Quiet) { 'TIMEOUT' } else { 'STOPPED_LIMIT (in-app: players or heap)' }
                }
                elseif ($st -eq 'PASS' -and $exit -ne 0) { $st = "FAIL (PASS logged but exit $exit)" }
            }
        }
        if ($left.Count -gt 0) { $st += " [processes left: $($left -join ',')]" }
        $min = [math]::Round(((Get-Date) - $t0).TotalMinutes, 1)
        Set-Content (Join-Path $dir 'status.txt') $st
        Say "$s [$id] END $st in $min min (exit $exit)"
        if (Test-Path $appLog) { Select-String -Path $appLog -Pattern '^\S+ (PASS|FAIL|FAILING|STOP|RESULT|ABORTED)' | ForEach-Object { Say "    $($_.Line)" } }
        $results += "$s $st"
        if ($st -ne 'PASS') { $allPass = $false }
        if ($st -notlike 'PASS*' -and $st -notlike 'FAIL*') { Say 'stopped by a stop condition, time limit or incomplete run: remaining scenarios not started'; break }
        if ($s -ne $Scenarios[-1]) { Start-Sleep -Seconds $Limits.CooldownS }
    }
}
finally {
    Say "SUMMARY: $($results -join ' | ')"
    Remove-Item $Lock -ErrorAction SilentlyContinue
    Say 'ALLDONE'
}
exit $(if ($allPass -and $results.Count -eq $Scenarios.Count) { 0 } else { 1 })
