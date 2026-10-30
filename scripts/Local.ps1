param([ValidateSet('Init','Start','Stop','Status')][string]$Action = 'Status')

$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$infra = [IO.Path]::GetFullPath((Join-Path $root '../siga-infrastructure'))
$local = Join-Path $root '.local'
$configPath = Join-Path $local 'reporting.json'
$compose = @(
    'compose', '--project-name', 'siga-local',
    '--env-file', (Join-Path $infra 'compose/.env.local'),
    '-f', (Join-Path $infra 'compose/compose.local.yml')
)

function Assert-ExitCode {
    if ($LASTEXITCODE -ne 0) {
        throw 'Command failed; existing data and credentials were preserved.'
    }
}

function Assert-ExistingStack {
    docker @compose config --quiet
    Assert-ExitCode
    foreach ($containerName in @('siga-local-postgres-1', 'siga_rabbitmq')) {
        $container = docker inspect $containerName | ConvertFrom-Json
        Assert-ExitCode
        if ($container[0].Config.Labels.'com.docker.compose.project' -ne 'siga-local') {
            throw "Unexpected Compose ownership for $containerName."
        }
        if ($container[0].State.Status -ne 'running') {
            throw "$containerName is not running."
        }
    }
}

function Invoke-AdminSql([string]$query) {
    $query | docker exec -i siga-local-postgres-1 psql -X -v ON_ERROR_STOP=1 -U postgres -d siga
    Assert-ExitCode
}

function Read-InfraEnvironment {
    $values = @{}
    foreach ($line in Get-Content (Join-Path $infra 'compose/.env.local')) {
        if ($line -match '^([A-Za-z_][A-Za-z0-9_]*)=(.*)$') {
            $values[$Matches[1]] = $Matches[2]
        }
    }
    return $values
}

function Import-ReportingEnvironment {
    if (!(Test-Path $configPath)) {
        throw 'Run scripts/Local.ps1 -Action Init first.'
    }
    $config = Get-Content $configPath -Raw | ConvertFrom-Json
    if ($config.password -notmatch '^[a-f0-9]{64}$') {
        throw 'Invalid stored Reporting configuration.'
    }
    $infraEnvironment = Read-InfraEnvironment
    if (!$infraEnvironment['LOCAL_RABBITMQ_USER'] -or !$infraEnvironment['LOCAL_RABBITMQ_PASSWORD']) {
        throw 'RabbitMQ credentials are missing from the existing local Compose configuration.'
    }

    $env:SPRING_PROFILES_ACTIVE = 'local'
    $env:REPORTING_PORT = '8086'
    $env:REPORTING_DB_URL = "jdbc:postgresql://127.0.0.1:$($config.dbPort)/siga"
    $env:REPORTING_DB_USER = 'siga_reporting'
    $env:REPORTING_DB_PASSWORD = $config.password
    $env:REPORTING_RABBIT_HOST = '127.0.0.1'
    $env:REPORTING_RABBIT_PORT = '5672'
    $env:REPORTING_RABBIT_USER = $infraEnvironment['LOCAL_RABBITMQ_USER']
    $env:REPORTING_RABBIT_PASSWORD = $infraEnvironment['LOCAL_RABBITMQ_PASSWORD']
    $env:REPORTING_JWKS_URI = 'http://127.0.0.1:8081/.well-known/jwks.json'
    $env:REPORTING_JWT_ISSUER = 'siga-identity'
    $env:REPORTING_JWT_AUDIENCE = 'siga-api'
    $env:REPORTING_CATALOG_BASE_URL = 'http://127.0.0.1:8082/api/v1'
    $env:REPORTING_INVENTORY_BASE_URL = 'http://127.0.0.1:8083/api/v1'
    $env:REPORTING_CONSUMER_ENABLED = 'true'
}

Push-Location $root
try {
    switch ($Action) {
        'Init' {
            Assert-ExistingStack
            New-Item -ItemType Directory -Force $local | Out-Null
            if (!(Test-Path $configPath)) {
                $existing = @'
SELECT
    (SELECT count(*) FROM pg_roles WHERE rolname = 'siga_reporting')
  + (SELECT count(*) FROM pg_namespace WHERE nspname = 'analytics');
'@ | docker exec -i siga-local-postgres-1 psql -X -At -v ON_ERROR_STOP=1 -U postgres -d siga
                Assert-ExitCode
                if ([int]$existing -ne 0) {
                    throw 'Reporting exists without its local credentials. Recover them; do not rotate automatically.'
                }
                $port = (docker inspect siga-local-postgres-1 | ConvertFrom-Json)[0].HostConfig.PortBindings.'5432/tcp'[0].HostPort
                Assert-ExitCode
                $bytes = New-Object byte[] 32
                $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
                try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
                $password = [BitConverter]::ToString($bytes).Replace('-', '').ToLowerInvariant()
                @{ dbPort = $port; password = $password } | ConvertTo-Json | Set-Content $configPath
            }
            $config = Get-Content $configPath -Raw | ConvertFrom-Json
            if ($config.password -notmatch '^[a-f0-9]{64}$') {
                throw 'Invalid stored Reporting configuration.'
            }
            $sql = @'
SELECT format(
    'CREATE ROLE siga_reporting LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT PASSWORD %L',
    '__PASSWORD__'
) WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'siga_reporting')
\gexec
GRANT CONNECT ON DATABASE siga TO siga_reporting;
SELECT 'CREATE SCHEMA analytics AUTHORIZATION siga_reporting'
WHERE NOT EXISTS (SELECT FROM pg_namespace WHERE nspname = 'analytics')
\gexec
REVOKE ALL ON SCHEMA analytics FROM PUBLIC;
'@
            Invoke-AdminSql ($sql.Replace('__PASSWORD__', $config.password))
            Write-Host 'Reporting local role and schema are ready; no existing credentials were rotated.'
        }
        'Start' {
            Assert-ExistingStack
            Import-ReportingEnvironment
            $history = docker exec siga-local-postgres-1 psql -X -At -v ON_ERROR_STOP=1 -U postgres -d siga -c "SELECT to_regclass('analytics.flyway_schema_history');"
            Assert-ExitCode
            $temporaryCreateGranted = [string]::IsNullOrWhiteSpace(($history | Out-String).Trim())
            if ($temporaryCreateGranted) {
                Invoke-AdminSql 'GRANT CREATE ON DATABASE siga TO siga_reporting;'
            }
            try {
                $pidFile = Join-Path $local 'reporting.pid'
                if (Test-Path $pidFile) {
                    $running = Get-Process -Id ([int](Get-Content $pidFile)) -ErrorAction SilentlyContinue
                    if ($running) { throw 'Recorded Reporting PID is running.' }
                }
                $listener = New-Object Net.Sockets.TcpListener([Net.IPAddress]::Loopback, 8086)
                try { $listener.Start() } finally { $listener.Stop() }

                $previousPreference = $ErrorActionPreference
                $ErrorActionPreference = 'Continue'
                ./mvnw.cmd -B -DskipTests package
                $buildExitCode = $LASTEXITCODE
                $ErrorActionPreference = $previousPreference
                if ($buildExitCode -ne 0) { throw 'Reporting package failed; no runtime was started.' }

                $built = Join-Path $root 'target/siga-reporting-analytics-service-0.0.1-SNAPSHOT.jar'
                if (!(Test-Path $built)) { throw 'Reporting JAR was not produced.' }
                $hash = (Get-FileHash $built -Algorithm SHA256).Hash
                $runtime = Join-Path $local 'runtime'
                New-Item -ItemType Directory -Force $runtime | Out-Null
                $jar = Join-Path $runtime "reporting-$hash.jar"
                if (!(Test-Path $jar)) { Copy-Item $built $jar }

                $process = Start-Process -FilePath (Get-Command java).Source `
                    -ArgumentList @('-jar', ('"{0}"' -f $jar)) `
                    -WorkingDirectory $root -WindowStyle Hidden -PassThru `
                    -RedirectStandardOutput (Join-Path $local 'reporting.stdout.log') `
                    -RedirectStandardError (Join-Path $local 'reporting.stderr.log')
                $process.Id | Set-Content $pidFile
                $jar | Set-Content (Join-Path $local 'reporting.jar')

                $deadline = [DateTime]::UtcNow.AddSeconds(60)
                do {
                    $process.Refresh()
                    if ($process.HasExited) { throw 'Reporting exited; inspect the private .local logs.' }
                    try {
                        $health = Invoke-RestMethod 'http://127.0.0.1:8086/actuator/health/readiness' -TimeoutSec 2
                        if ($health.status -eq 'UP') {
                            Write-Host 'Reporting UP on 127.0.0.1:8086'
                            return
                        }
                    } catch { }
                    Start-Sleep -Milliseconds 500
                } while ([DateTime]::UtcNow -lt $deadline)
                throw 'Reporting readiness timeout; process and private logs were preserved.'
            } finally {
                if ($temporaryCreateGranted) {
                    Invoke-AdminSql 'REVOKE CREATE ON DATABASE siga FROM siga_reporting;'
                }
            }
        }
        'Stop' {
            $pidFile = Join-Path $local 'reporting.pid'
            if (!(Test-Path $pidFile)) { return }
            $reportingPid = [int](Get-Content $pidFile)
            $process = Get-CimInstance Win32_Process -Filter "ProcessId=$reportingPid"
            if (!$process) { return }
            $jar = Get-Content (Join-Path $local 'reporting.jar')
            if ($process.Name -ne 'java.exe' -or -not $process.CommandLine.Contains($jar)) {
                throw 'Reporting PID does not match the recorded runtime JAR.'
            }
            Stop-Process -Id $reportingPid
        }
        'Status' {
            Assert-ExistingStack
            try {
                $health = Invoke-RestMethod 'http://127.0.0.1:8086/actuator/health/readiness' -TimeoutSec 2
                Write-Host "Reporting readiness: $($health.status)"
            } catch {
                Write-Host 'Reporting readiness: DOWN'
            }
        }
    }
} finally {
    Pop-Location
}
