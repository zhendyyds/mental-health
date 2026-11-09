param(
    [string]$MySqlBase = 'E:\MySQL\MySQL Server 9.4',
    [string]$JavaHome = 'E:\JDK17',
    [string]$Maven = 'E:\apache-maven-3.9.15\bin\mvn.cmd',
    [ValidateRange(0, 65535)][int]$Port = 0,
    [ValidateSet('Consultation', 'KnowledgeIndex', 'KnowledgeQa')][string]$Suite = 'Consultation',
    [ValidateRange(0, 65535)][int]$QdrantGrpcPort = 0
)

# Creates only a new target fixture, binds only loopback, never connects to the
# application's configured database, and leaves logs/data for inspection.
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
if ($QdrantGrpcPort -gt 0 -and $Suite -eq 'Consultation') { throw 'QdrantGrpcPort requires a knowledge suite' }
$batch3Backend = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$batch3Stamp = [DateTime]::UtcNow.ToString('yyyyMMddHHmmssfff')
$mysqlRunPrefix = switch ($Suite) {
    'KnowledgeQa' { 'batch6-qa-mysql' }
    'KnowledgeIndex' { 'batch5-index-mysql' }
    default { 'batch3-mysql' }
}
$batch3Run = Join-Path $batch3Backend "target\$mysqlRunPrefix-$batch3Stamp-$PID"
$batch3Data = Join-Path $batch3Run 'data'
$batch3Database = "batch3_verify_$batch3Stamp"
$batch3ServerExe = Join-Path $MySqlBase 'bin\mysqld.exe'
$batch3ClientExe = Join-Path $MySqlBase 'bin\mysql.exe'
$batch3AdminExe = Join-Path $MySqlBase 'bin\mysqladmin.exe'
foreach ($batch3Required in @($batch3ServerExe, $batch3ClientExe, $batch3AdminExe, $Maven, (Join-Path $JavaHome 'bin\java.exe'))) {
    if (-not (Test-Path -LiteralPath $batch3Required -PathType Leaf)) { throw "Missing executable: $batch3Required" }
}
if ($Port -eq 0) {
    $batch3Listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
    $batch3Listener.Start()
    try { $Port = ([Net.IPEndPoint]$batch3Listener.LocalEndpoint).Port }
    finally { $batch3Listener.Stop() }
}
$batch3AdminArgs = @('--no-defaults', '--protocol=TCP', '--host=127.0.0.1', "--port=$Port", '--user=root')
$batch3ClientArgs = $batch3AdminArgs + @('--default-character-set=utf8mb4', '--batch', '--skip-column-names')
$script:batch3Process = $null
$script:batch3VerifiedServer = $false
$batch3OldJava = $env:JAVA_HOME
$batch3OldPath = $env:Path
$batch3OldUrl = $env:BATCH3_JDBC_URL
$batch3OldDir = $env:BATCH3_BACKEND_DIR
$batch5OldQdrantPort = $env:BATCH5_QDRANT_PORT
$batch6OldQdrantPort = $env:BATCH6_QDRANT_PORT
$batch3OldEncoding = $OutputEncoding
$batch3Results = [Collections.Generic.List[object]]::new()

# Windows PowerShell 5.1 wraps native stderr in NativeCommandError even when
# the process exits successfully (mysqld logs and JAVA_TOOL_OPTIONS use stderr).
# Relax only this invocation; file operations and all callers retain Stop.
function Invoke-Batch3Native {
    param([string]$FilePath, [string[]]$ArgumentList, [string]$InputText, [string]$LogPath)
    $batch3SavedPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        if ($PSBoundParameters.ContainsKey('InputText')) {
            $batch3NativeOutput = $InputText | & $FilePath @ArgumentList 2>&1
        } else {
            $batch3NativeOutput = & $FilePath @ArgumentList 2>&1
        }
        $batch3NativeExit = $LASTEXITCODE
    } finally { $ErrorActionPreference = $batch3SavedPreference }
    $batch3Lines = @($batch3NativeOutput | ForEach-Object { $_.ToString() })
    if ($LogPath) {
        Add-Content -LiteralPath $LogPath -Value ($batch3Lines -join [Environment]::NewLine) -Encoding UTF8
    }
    $batch3LaunchErrors = @($batch3NativeOutput | Where-Object {
        $_ -is [Management.Automation.ErrorRecord] -and $_.FullyQualifiedErrorId -notmatch '^NativeCommandError'
    })
    if ($batch3LaunchErrors.Count -gt 0 -or $null -eq $batch3NativeExit) {
        throw "Could not execute native program: $FilePath"
    }
    return [pscustomobject]@{ ExitCode = [int]$batch3NativeExit; Output = $batch3Lines }
}

function Start-Batch3Server {
    $script:batch3VerifiedServer = $false
    $batch3Arguments = @('--no-defaults', "--basedir=`"$MySqlBase`"", "--datadir=`"$batch3Data`"", '--bind-address=127.0.0.1', "--port=$Port", '--mysqlx=OFF', '--skip-log-bin', "--log-error=`"$(Join-Path $batch3Run 'server.log')`"", '--default-time-zone=+08:00')
    $script:batch3Process = Start-Process -FilePath $batch3ServerExe -ArgumentList $batch3Arguments -WindowStyle Hidden -PassThru
    $batch3Deadline = [DateTime]::UtcNow.AddSeconds(30)
    while ([DateTime]::UtcNow -lt $batch3Deadline) {
        if ($script:batch3Process.HasExited) { throw "Isolated mysqld exited; inspect $batch3Run\server.log" }
        $batch3Ping = Invoke-Batch3Native -FilePath $batch3AdminExe -ArgumentList ($batch3AdminArgs + 'ping')
        if ($batch3Ping.ExitCode -eq 0) {
            # A port selected earlier can be taken before mysqld binds. Verify
            # the server's data directory before any write or admin shutdown.
            $batch3Identity = Invoke-Batch3Native -FilePath $batch3ClientExe -ArgumentList $batch3ClientArgs -InputText 'SELECT @@datadir;' -LogPath (Join-Path $batch3Run 'identity.log')
            if ($batch3Identity.ExitCode -ne 0) { throw 'Could not verify the isolated server identity' }
            $batch3ActualPath = [IO.Path]::GetFullPath(($batch3Identity.Output -join '').Trim()).TrimEnd([char[]]'/\')
            $batch3ExpectedPath = [IO.Path]::GetFullPath($batch3Data).TrimEnd([char[]]'/\')
            if (-not [string]::Equals($batch3ActualPath, $batch3ExpectedPath, [StringComparison]::OrdinalIgnoreCase)) {
                throw 'The selected port belongs to another MySQL data directory; no writes were made'
            }
            $script:batch3VerifiedServer = $true
            return
        }
        Start-Sleep -Milliseconds 200
    }
    throw 'Isolated MySQL did not become ready in 30 seconds'
}

function Stop-Batch3Server {
    if ($null -eq $script:batch3Process -or $script:batch3Process.HasExited) { return }
    if (-not $script:batch3VerifiedServer) {
        $script:batch3Process.Kill()
        $script:batch3Process.WaitForExit(5000) | Out-Null
        return
    }
    $batch3Shutdown = Invoke-Batch3Native -FilePath $batch3AdminExe -ArgumentList ($batch3AdminArgs + 'shutdown') -LogPath (Join-Path $batch3Run 'shutdown.log')
    if ($batch3Shutdown.ExitCode -ne 0) {
        if (-not $script:batch3Process.HasExited) {
            $script:batch3Process.Kill()
            $script:batch3Process.WaitForExit(5000) | Out-Null
        }
        throw 'Isolated MySQL shutdown command failed; inspect the fixture logs'
    }
    if (-not $script:batch3Process.WaitForExit(10000)) {
        $script:batch3Process.Kill()
        $script:batch3Process.WaitForExit(5000) | Out-Null
        throw 'Isolated mysqld required forced termination; inspect the fixture logs'
    }
    $script:batch3VerifiedServer = $false
}

function Invoke-Batch3Sql([string]$Sql, [string]$DatabaseName = '') {
    $batch3Args = $batch3ClientArgs
    if ($DatabaseName) { $batch3Args += "--database=$DatabaseName" }
    $batch3SqlResult = Invoke-Batch3Native -FilePath $batch3ClientExe -ArgumentList $batch3Args -InputText $Sql -LogPath (Join-Path $batch3Run 'schema.log')
    if ($batch3SqlResult.ExitCode -ne 0) { throw "Fixture SQL failed; inspect $batch3Run\schema.log" }
}

function Invoke-Batch3Test([string]$Test, [string]$LogName, [string[]]$ReportClasses, [switch]$SummaryOnly) {
    $testStartedAt = [DateTime]::UtcNow
    $testCommand = @{ FilePath = $Maven; ArgumentList = @('-o', '-q', "-Dtest=$Test", 'test') }
    if (-not $SummaryOnly) { $testCommand.LogPath = Join-Path $batch3Run "$LogName.log" }
    $batch3TestResult = Invoke-Batch3Native @testCommand
    if ($batch3TestResult.ExitCode -ne 0) {
        if ($SummaryOnly) { throw "Evaluation test failed: $Test. Inspect its local Surefire result." }
        throw "Integration tests failed; inspect $batch3Run\$LogName.log"
    }
    foreach ($batch3Class in $ReportClasses) {
        $batch3ReportPath = Join-Path $batch3Backend "target\surefire-reports\TEST-com.flj.persistence.$batch3Class.xml"
        if ((Get-Item -LiteralPath $batch3ReportPath).LastWriteTimeUtc -lt $testStartedAt) { throw 'Integration report is stale' }
        [xml]$batch3Report = Get-Content -LiteralPath $batch3ReportPath -Raw
        $batch3Suite = $batch3Report.testsuite
        if ([int]$batch3Suite.tests -lt 1 -or [int]$batch3Suite.failures -gt 0 -or [int]$batch3Suite.errors -gt 0 -or [int]$batch3Suite.skipped -gt 0) { throw 'Integration report did not pass without skips' }
        if (-not $SummaryOnly) {
            Copy-Item -LiteralPath $batch3ReportPath -Destination (Join-Path $batch3Run "$LogName-$batch3Class.xml")
        }
        $batch3Results.Add([ordered]@{ phase = $LogName; suite = $batch3Class; tests = [int]$batch3Suite.tests; failures = [int]$batch3Suite.failures; errors = [int]$batch3Suite.errors; skipped = [int]$batch3Suite.skipped })
    }
}

try {
    New-Item -ItemType Directory -Path $batch3Data | Out-Null
    $OutputEncoding = [Text.UTF8Encoding]::new($false)
    $batch3Initialize = Invoke-Batch3Native -FilePath $batch3ServerExe -ArgumentList @('--no-defaults', '--initialize-insecure', "--basedir=$MySqlBase", "--datadir=$batch3Data", '--console') -LogPath (Join-Path $batch3Run 'initialize.log')
    if ($batch3Initialize.ExitCode -ne 0) { throw "MySQL initialization failed; inspect $batch3Run\initialize.log" }
    Start-Batch3Server
    Invoke-Batch3Sql "CREATE DATABASE $batch3Database CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;"
    Invoke-Batch3Sql (Get-Content -LiteralPath (Join-Path $PSScriptRoot 'baseline\20261106_schema.sql') -Raw) $batch3Database
    if ($Suite -eq 'KnowledgeIndex') {
        $legacyFixture = @'
INSERT INTO knowledge_article (id, title, content, status, published_at, created_at, updated_at)
VALUES ('00000000-0000-0000-0000-000000000997', 'Fictional legacy article', '<p>Fictional legacy source</p>',
        1, '2026-11-07 10:00:00', '2026-11-07 09:00:00', '2026-11-07 09:30:00');
'@
        Invoke-Batch3Sql $legacyFixture $batch3Database
    }
    if ($Suite -ne 'Consultation') {
        Invoke-Batch3Sql (Get-Content -LiteralPath (Join-Path $PSScriptRoot 'migrations\20261107_add_knowledge_index.sql') -Raw) $batch3Database
    }
    if ($Suite -eq 'KnowledgeQa') {
        Invoke-Batch3Sql (Get-Content -LiteralPath (Join-Path $PSScriptRoot 'migrations\20261107_allow_knowledge_qa_requests.sql') -Raw) $batch3Database
    }
    Invoke-Batch3Sql "CREATE TABLE batch3_verification_guard (marker VARCHAR(64) NOT NULL); INSERT INTO batch3_verification_guard VALUES ('isolated-target-datadir');" $batch3Database
    $env:JAVA_HOME = $JavaHome
    $env:Path = (Join-Path $JavaHome 'bin') + ';' + $env:Path
    $env:BATCH3_JDBC_URL = "jdbc:mysql://127.0.0.1:$Port/$batch3Database`?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&characterEncoding=UTF-8"
    $env:BATCH3_BACKEND_DIR = $batch3Backend
    Push-Location -LiteralPath $batch3Backend
    try {
        if ($Suite -eq 'KnowledgeQa') {
            Invoke-Batch3Test 'KnowledgeQaMysqlIT' 'knowledge-qa' @('KnowledgeQaMysqlIT')
            if ($QdrantGrpcPort -gt 0) {
                $env:BATCH6_QDRANT_PORT = [string]$QdrantGrpcPort
                Invoke-Batch3Test 'KnowledgeQaEvaluationIT' 'knowledge-qa-evaluation' @('KnowledgeQaEvaluationIT') -SummaryOnly
            }
        } elseif ($Suite -eq 'KnowledgeIndex') {
            Invoke-Batch3Test 'KnowledgeIndexMysqlIT' 'knowledge-index' @('KnowledgeIndexMysqlIT')
            if ($QdrantGrpcPort -gt 0) {
                $env:BATCH5_QDRANT_PORT = [string]$QdrantGrpcPort
                Invoke-Batch3Test 'KnowledgeIndexQdrantIT' 'knowledge-index-qdrant' @('KnowledgeIndexQdrantIT')
            }
        } else {
            Invoke-Batch3Test 'AiRequestMysqlIT,ConsultationIdempotencyMysqlIT' 'transactions' @('AiRequestMysqlIT', 'ConsultationIdempotencyMysqlIT')
            Invoke-Batch3Test 'AiRequestRestartMysqlIT#writeCheckpoint' 'checkpoint-write' @('AiRequestRestartMysqlIT')
            Stop-Batch3Server
            Start-Batch3Server
            Invoke-Batch3Test 'AiRequestRestartMysqlIT#recoverCheckpointInNewJvm' 'checkpoint-recovery' @('AiRequestRestartMysqlIT')
        }
    } finally { Pop-Location }
    Stop-Batch3Server
    $batch3VersionResult = Invoke-Batch3Native -FilePath $batch3ServerExe -ArgumentList @('--version')
    if ($batch3VersionResult.ExitCode -ne 0) { throw 'Could not read MySQL version' }
    $batch3Version = $batch3VersionResult.Output -join [Environment]::NewLine
    $didRestart = $Suite -eq 'Consultation'
    [ordered]@{ verifiedAtUtc = [DateTime]::UtcNow.ToString('o'); suite = $Suite; mysql = $batch3Version; bindAddress = '127.0.0.1'; port = $Port; database = $batch3Database; artifacts = $batch3Run; qdrantGrpcPort = $QdrantGrpcPort; noApplicationConfigurationRead = $true; fictionalDataOnly = $true; databaseRestarted = $didRestart; separateJvmCheckpoint = $didRestart; serverStopped = $true; results = $batch3Results } | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $batch3Run 'summary.json') -Encoding UTF8
    Write-Output "MySQL verification passed. Evidence: $batch3Run\summary.json"
} finally {
    try { Stop-Batch3Server }
    finally {
        $env:JAVA_HOME = $batch3OldJava
        $env:Path = $batch3OldPath
        $env:BATCH3_JDBC_URL = $batch3OldUrl
        $env:BATCH3_BACKEND_DIR = $batch3OldDir
        $env:BATCH5_QDRANT_PORT = $batch5OldQdrantPort
        $env:BATCH6_QDRANT_PORT = $batch6OldQdrantPort
        $OutputEncoding = $batch3OldEncoding
    }
}
