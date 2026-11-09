param(
    [ValidateSet('Fixture', 'RealEmbedding', 'IndexSuite', 'QaSuite')][string]$Mode = 'Fixture',
    [string]$QdrantArchive,
    [switch]$Download,
    [string]$JavaHome = 'E:\JDK17',
    [string]$Maven = 'E:\apache-maven-3.9.15\bin\mvn.cmd',
    [ValidateRange(0, 65535)][int]$HttpPort = 0,
    [ValidateRange(0, 65535)][int]$GrpcPort = 0
)

$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
$backend = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..')).Path
$version = '1.16.3'
$commit = 'bd49f45a8a2d4e4774cac50fa29507c4e8375af2'
$asset = 'qdrant-x86_64-pc-windows-msvc.zip'
$assetUrl = "https://github.com/qdrant/qdrant/releases/download/v$version/$asset"
$assetSha256 = 'a1159282922776a05bdeaad9e90c85d8d1ca0bc90d9e5e4add56133e15748753'
$runId = [Guid]::NewGuid().ToString('N').Substring(0, 10)
# Qdrant creates nested UUID paths under each collection. Keep both names short
# for Windows path length limits.
$runDir = Join-Path $backend "target\q4-$runId"
$artifactDir = Join-Path $runDir 'artifacts'
$collection = "healthagent_smoke_$runId"
$windowsPowerShell = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
$script:qdrantProcess = $null
$script:qdrantStage = $null
$stops = [Collections.Generic.List[object]]::new()
$phases = [Collections.Generic.List[object]]::new()

foreach ($required in @((Join-Path $JavaHome 'bin\java.exe'), $Maven, $windowsPowerShell)) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) { throw "Missing executable: $required" }
}

# Qdrant's environment overrides its configuration file. Check names only
# before starting the child, so overrides cannot redirect the storage or bind.
$overrideNames = [Environment]::GetEnvironmentVariables('Process').Keys |
    Where-Object { $_ -match '^(QDRANT(?:_|$)|RUN_MODE$)' }
if ($overrideNames) { throw 'Qdrant configuration override found; use an isolated process environment' }

if (-not $QdrantArchive) {
    $QdrantArchive = Join-Path $backend "target\qdrant-release\$version\$asset"
    if ($Download -and -not (Test-Path -LiteralPath $QdrantArchive -PathType Leaf)) {
        New-Item -ItemType Directory -Path $runDir | Out-Null
        $QdrantArchive = Join-Path $runDir $asset
        Invoke-WebRequest -Uri $assetUrl -UseBasicParsing -TimeoutSec 180 -OutFile $QdrantArchive
    }
}
if (-not (Test-Path -LiteralPath $QdrantArchive -PathType Leaf)) {
    throw "Provide the official Qdrant $version zip with -QdrantArchive, or use -Download"
}
$archiveDigest = (Get-FileHash -LiteralPath $QdrantArchive -Algorithm SHA256).Hash.ToLowerInvariant()
if ($archiveDigest -ne $assetSha256) { throw 'Qdrant release archive SHA-256 does not match the pinned official asset' }
New-Item -ItemType Directory -Path $artifactDir | Out-Null
$binaryDir = Join-Path $runDir 'bin'
Expand-Archive -LiteralPath $QdrantArchive -DestinationPath $binaryDir
$qdrantExe = Join-Path $binaryDir 'qdrant.exe'

# Reserve both ports together while selecting them. The PID check at startup
# handles a different process binding a port after these sockets are closed.
$httpSocket = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, $HttpPort)
$grpcSocket = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, $GrpcPort)
try {
    $httpSocket.Server.ExclusiveAddressUse = $true
    $grpcSocket.Server.ExclusiveAddressUse = $true
    $httpSocket.Start()
    $grpcSocket.Start()
    $HttpPort = ([Net.IPEndPoint]$httpSocket.LocalEndpoint).Port
    $GrpcPort = ([Net.IPEndPoint]$grpcSocket.LocalEndpoint).Port
} finally {
    $grpcSocket.Stop()
    $httpSocket.Stop()
}

@"
log_level: DEBUG
storage:
  storage_path: ./s
  snapshots_path: ./snap
service:
  host: 127.0.0.1
  http_port: $HttpPort
  grpc_port: $GrpcPort
  max_workers: 2
  enable_cors: false
  enable_static_content: false
cluster:
  enabled: false
telemetry_disabled: true
"@ | Set-Content -LiteralPath (Join-Path $runDir 'verify.yaml') -Encoding ASCII

function Read-QdrantJson([string]$Path) {
    $request = [Net.HttpWebRequest]::Create("http://127.0.0.1:$HttpPort/$Path")
    $request.Proxy = $null
    $request.Timeout = 2000
    $request.ReadWriteTimeout = 2000
    $response = $request.GetResponse()
    try {
        $reader = [IO.StreamReader]::new($response.GetResponseStream())
        try { return ($reader.ReadToEnd() | ConvertFrom-Json) }
        finally { $reader.Dispose() }
    } finally { $response.Close() }
}

function Start-QdrantServer([string]$Stage) {
    $script:qdrantStage = $Stage
    $script:qdrantProcess = Start-Process -FilePath $qdrantExe -ArgumentList @('--config-path', 'verify.yaml', '--disable-telemetry') `
        -WorkingDirectory $runDir -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput (Join-Path $runDir "$Stage.stdout.log") -RedirectStandardError (Join-Path $runDir "$Stage.stderr.log")
    # Windows PowerShell can lose ExitCode on redirected child processes unless
    # the Process handle is acquired while the child is running.
    $null = $script:qdrantProcess.Handle
    $deadline = [DateTime]::UtcNow.AddSeconds(25)
    while ([DateTime]::UtcNow -lt $deadline -and -not $script:qdrantProcess.HasExited) {
        $listeners = @(Get-NetTCPConnection -OwningProcess $script:qdrantProcess.Id -State Listen -ErrorAction SilentlyContinue)
        if (@($listeners | Where-Object { $_.LocalAddress -ne '127.0.0.1' -or $_.LocalPort -notin @($HttpPort, $GrpcPort) }).Count) {
            throw 'Qdrant opened an unexpected listener'
        }
        if ($HttpPort -in $listeners.LocalPort -and $GrpcPort -in $listeners.LocalPort) {
            $identity = Read-QdrantJson ''
            if ($identity.version -ne $version -or $identity.commit -ne $commit) { throw 'Unexpected Qdrant HTTP identity' }
            return $script:qdrantProcess.Id
        }
        Start-Sleep -Milliseconds 250
    }
    throw "Qdrant failed to bind both loopback listeners; inspect $runDir\$Stage.*.log"
}

function Stop-QdrantServer {
    if ($null -eq $script:qdrantProcess) { return }
    $process = $script:qdrantProcess
    $stage = $script:qdrantStage
    $method = 'already-exited'
    $signalExit = $null
    if (-not $process.HasExited) {
        # CTRL+C broadcasts to a console. Only signal a console proven to contain
        # exactly the owned Qdrant and this temporary sender, never our own shell.
        $signalCommand = @'
$ErrorActionPreference = 'Stop'
Add-Type -TypeDefinition @"
using System;
using System.Runtime.InteropServices;
using System.Threading;
public static class QdrantConsoleStop {
    [DllImport("kernel32.dll")] static extern bool FreeConsole();
    [DllImport("kernel32.dll")] static extern bool AttachConsole(uint processId);
    [DllImport("kernel32.dll")] static extern uint GetConsoleProcessList([Out] uint[] ids, uint count);
    [DllImport("kernel32.dll")] static extern bool SetConsoleCtrlHandler(IntPtr handler, bool add);
    [DllImport("kernel32.dll")] static extern bool GenerateConsoleCtrlEvent(uint signal, uint group);
    public static int Send(uint target, uint self) {
        FreeConsole();
        if (!AttachConsole(target)) return 2;
        try {
            uint[] ids = new uint[2];
            if (GetConsoleProcessList(ids, 2) != 2 || Array.IndexOf(ids, target) < 0 || Array.IndexOf(ids, self) < 0) return 3;
            if (!SetConsoleCtrlHandler(IntPtr.Zero, true)) return 4;
            if (!GenerateConsoleCtrlEvent(0, 0)) return 5;
            Thread.Sleep(150);
            return 0;
        } finally { FreeConsole(); }
    }
}
"@
exit [QdrantConsoleStop]::Send(__TARGET_PID__, $PID)
'@
        $encoded = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($signalCommand.Replace('__TARGET_PID__', [string]$process.Id)))
        $sender = Start-Process -FilePath $windowsPowerShell -ArgumentList @('-NoProfile', '-NonInteractive', '-EncodedCommand', $encoded) `
            -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $runDir "$stage.stop.stdout.log") `
            -RedirectStandardError (Join-Path $runDir "$stage.stop.stderr.log")
        $null = $sender.Handle
        if ($sender.WaitForExit(10000)) { $signalExit = $sender.ExitCode }
        else { $sender.Kill(); $sender.WaitForExit(5000) | Out-Null }

        $serverExited = $process.WaitForExit(10000)
        if ($signalExit -eq 0 -and $serverExited -and $process.ExitCode -eq 0) {
            $method = 'graceful-control-c'
        } elseif (-not $process.HasExited) {
            $process.Kill()
            if (-not $process.WaitForExit(5000)) { throw 'Could not stop owned Qdrant process' }
            $method = 'forced-termination'
        } else { $method = 'nonzero-or-unconfirmed-exit' }
        if ($method -ne 'graceful-control-c') { Write-Warning "Qdrant $stage shutdown was $method (console sender exit: $signalExit)" }
    }
    $stops.Add([ordered]@{ stage = $stage; pid = $process.Id; method = $method; exitCode = $process.ExitCode; consoleSenderExit = $signalExit })
    $script:qdrantProcess = $null
}

function Invoke-SmokePhase([string]$Phase) {
    $env:RAG_SMOKE_PHASE = $Phase
    $phaseStarted = [DateTime]::UtcNow
    $savedPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue' # PS 5.1 wraps native stderr as errors.
        $output = & $Maven -o -q '-Prag-smoke' '-Dtest=RagSmokeIT' test 2>&1
        $result = $LASTEXITCODE
    } finally { $ErrorActionPreference = $savedPreference }
    $log = Join-Path $runDir "maven-$Phase.log"
    ($output | ForEach-Object { $_.ToString() }) -join [Environment]::NewLine | Set-Content -LiteralPath $log -Encoding UTF8
    if ($null -eq $result -or $result -ne 0) { throw "RAG smoke phase $Phase failed; inspect $log" }
    $reports = @(Get-ChildItem -LiteralPath (Join-Path $backend 'target\surefire-reports') -Filter 'TEST-*.RagSmokeIT.xml' |
        Where-Object LastWriteTimeUtc -GE $phaseStarted)
    if ($reports.Count -ne 1) { throw "Missing fresh integration test report for $Phase" }
    [xml]$report = Get-Content -LiteralPath $reports[0].FullName -Raw
    $suite = $report.testsuite
    if ([int]$suite.tests -lt 1 -or [int]$suite.errors -gt 0 -or [int]$suite.failures -gt 0 -or [int]$suite.skipped -gt 0) {
        throw "RAG smoke test report for $Phase did not pass without skips"
    }
    $phases.Add([ordered]@{ phase = $Phase; tests = [int]$suite.tests; failures = [int]$suite.failures; errors = [int]$suite.errors; skipped = [int]$suite.skipped })
}

$firstPhase = if ($Mode -eq 'Fixture') { 'fixture' } else { 'write' }
$oldEnvironment = @{}
$testEnvironment = @{
    JAVA_HOME = $JavaHome
    Path = (Join-Path $JavaHome 'bin') + ';' + $env:Path
    RAG_SMOKE_QDRANT_PORT = [string]$GrpcPort
    RAG_SMOKE_COLLECTION = $collection
    RAG_SMOKE_ARTIFACT_DIR = $artifactDir
    RAG_SMOKE_PHASE = $firstPhase
}
if ($Mode -eq 'QaSuite') { $testEnvironment['BATCH6_EVAL_ARTIFACT_DIR'] = $artifactDir }
try {
    foreach ($name in $testEnvironment.Keys) {
        $oldEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
        [Environment]::SetEnvironmentVariable($name, $testEnvironment[$name], 'Process')
    }
    Push-Location -LiteralPath $backend
    try {
        if ($Mode -in @('IndexSuite', 'QaSuite')) {
            $suiteName = if ($Mode -eq 'QaSuite') { 'KnowledgeQa' } else { 'KnowledgeIndex' }
            $indexPid = Start-QdrantServer $suiteName
            $savedPreference = $ErrorActionPreference
            try {
                $ErrorActionPreference = 'Continue'
                $indexOutput = & $windowsPowerShell -NoProfile -ExecutionPolicy Bypass -File db/verify-mysql.ps1 `
                    -Suite $suiteName -QdrantGrpcPort $GrpcPort -JavaHome $JavaHome -Maven $Maven 2>&1
                $indexExit = $LASTEXITCODE
            } finally { $ErrorActionPreference = $savedPreference }
            $indexLog = Join-Path $runDir 'mysql-index-suite.log'
            $indexOutput | ForEach-Object { $_.ToString() } | Set-Content -LiteralPath $indexLog -Encoding UTF8
            if ($indexExit -ne 0) { throw "$suiteName integration failed. See $indexLog" }
            Stop-QdrantServer
            $indexOutput | ForEach-Object { $_.ToString() }
            $summaryName = if ($Mode -eq 'QaSuite') { 'qa-summary.json' } else { 'index-summary.json' }
            $summaryPath = Join-Path $runDir $summaryName
            [ordered]@{
                mode = $Mode; version = $version; commit = $commit; archiveSha256 = $archiveDigest
                bindAddress = '127.0.0.1'; httpPort = $HttpPort; grpcPort = $GrpcPort; pid = $indexPid
                stops = $stops; mysqlLog = $indexLog; verifiedAtUtc = [DateTime]::UtcNow.ToString('o')
            } | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $summaryPath -Encoding UTF8
            Write-Output "$suiteName Qdrant verification passed. Evidence $summaryPath"
            return
        }
        $firstPid = Start-QdrantServer $firstPhase
        Invoke-SmokePhase $firstPhase
        $pointsBefore = (Read-QdrantJson "collections/$collection").result.points_count
        if ($pointsBefore -lt 1) { throw 'The first phase did not write any points' }
        Stop-QdrantServer

        $secondPid = Start-QdrantServer 'read'
        if ($firstPid -eq $secondPid) { throw 'The restart did not produce a new process' }
        $pointsAfter = (Read-QdrantJson "collections/$collection").result.points_count
        if ($pointsAfter -ne $pointsBefore) { throw 'Collection point count changed after restart' }
        Invoke-SmokePhase 'read'
        Stop-QdrantServer
    } finally { Pop-Location }

    $summary = [ordered]@{
        mode = $Mode; version = $version; commit = $commit; asset = $assetUrl; archiveSha256 = $archiveDigest
        bindAddress = '127.0.0.1'; httpPort = $HttpPort; grpcPort = $GrpcPort; collection = $collection
        firstPid = $firstPid; restartedPid = $secondPid; storage = (Join-Path $runDir 's')
        artifacts = $artifactDir; pointsBeforeRestart = $pointsBefore; pointsAfterRestart = $pointsAfter
        stops = $stops; phases = $phases; verifiedAtUtc = [DateTime]::UtcNow.ToString('o')
    }
    $summaryPath = Join-Path $runDir 'summary.json'
    $summary | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $summaryPath -Encoding UTF8
    Write-Output "Qdrant verification passed. Evidence: $summaryPath"
} finally {
    try { Stop-QdrantServer }
    finally {
        foreach ($name in $oldEnvironment.Keys) {
            [Environment]::SetEnvironmentVariable($name, $oldEnvironment[$name], 'Process')
        }
    }
}
