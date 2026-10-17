<#
  Daily logical backup of the Aiven MySQL database.

  - Reads DB credentials from the project's .env file (never hard-coded here).
  - Writes a timestamped, zipped SQL dump to $BackupDir (default: C:\Users\Shivani\crm-backups).
  - Keeps the last $RetentionDays days of backups (default: 90) and deletes older ones.
  - Appends a line to backup.log in the backup folder.

  No secrets are stored in this file. Safe to commit.

  Manual run:
      powershell -ExecutionPolicy Bypass -File scripts\backup-db.ps1
#>

param(
    [string]$BackupDir = "C:\Users\Shivani\crm-backups",
    [int]$RetentionDays = 90,
    [string]$EnvFile = "",
    [string]$MysqldumpPath = "C:\Program Files\MySQL\MySQL Server 5.7\bin\mysqldump.exe"
)

$ErrorActionPreference = "Stop"

# Resolve .env relative to this script's parent (the backend project root) if not given
if ([string]::IsNullOrWhiteSpace($EnvFile)) {
    $projectRoot = Split-Path -Parent $PSScriptRoot
    $EnvFile = Join-Path $projectRoot ".env"
}

function Write-Log($msg) {
    $line = "{0}  {1}" -f (Get-Date -Format "yyyy-MM-dd HH:mm:ss"), $msg
    Write-Output $line
    if (Test-Path $BackupDir) {
        Add-Content -Path (Join-Path $BackupDir "backup.log") -Value $line
    }
}

try {
    if (-not (Test-Path $EnvFile)) { throw ".env not found at $EnvFile" }
    if (-not (Test-Path $MysqldumpPath)) { throw "mysqldump not found at $MysqldumpPath" }
    New-Item -ItemType Directory -Force -Path $BackupDir | Out-Null

    # --- Parse .env ---
    $lines = Get-Content $EnvFile
    function Get-EnvVal($key) {
        $raw = ($lines | Where-Object { $_ -match "^$key=" } | Select-Object -First 1)
        if ($null -eq $raw) { return $null }
        return ($raw -replace "^$key=", "").Trim()
    }
    $hostPort = Get-EnvVal "DB_HOST"
    $parts    = $hostPort -split ":"
    $dbHost   = $parts[0]
    $dbPort   = if ($parts.Count -gt 1) { $parts[1] } else { "3306" }
    $dbUser   = Get-EnvVal "DB_USER"
    $dbPass   = Get-EnvVal "DB_PASSWORD"
    $dbName   = Get-EnvVal "DB_DATABASE"
    if ([string]::IsNullOrWhiteSpace($dbName)) { $dbName = "defaultdb" }

    if ([string]::IsNullOrWhiteSpace($dbHost) -or [string]::IsNullOrWhiteSpace($dbUser) -or [string]::IsNullOrWhiteSpace($dbPass)) {
        throw "Missing DB_HOST / DB_USER / DB_PASSWORD in .env"
    }

    # --- Write a temporary option file so the password is NOT on the command line ---
    $cnf = New-TemporaryFile
    @(
        "[mysqldump]",
        "host=$dbHost",
        "port=$dbPort",
        "user=$dbUser",
        "password=$dbPass",
        "ssl-mode=REQUIRED"
    ) | Set-Content -Path $cnf -Encoding ASCII

    $stamp   = Get-Date -Format "yyyyMMdd_HHmmss"
    $sqlFile = Join-Path $BackupDir ("crm_{0}_{1}.sql" -f $dbName, $stamp)
    $zipFile = [System.IO.Path]::ChangeExtension($sqlFile, ".zip")

    Write-Log "Starting backup of '$dbName' on ${dbHost}:${dbPort} -> $zipFile"

    # --- Run mysqldump ---
    & $MysqldumpPath "--defaults-extra-file=$cnf" `
        --single-transaction `
        --set-gtid-purged=OFF `
        --default-character-set=utf8mb4 `
        --routines --events --triggers `
        $dbName | Out-File -FilePath $sqlFile -Encoding utf8

    if ($LASTEXITCODE -ne 0) { throw "mysqldump exited with code $LASTEXITCODE" }
    if (-not (Test-Path $sqlFile) -or (Get-Item $sqlFile).Length -eq 0) { throw "Dump file is empty" }

    # --- Compress and remove the raw .sql ---
    Compress-Archive -Path $sqlFile -DestinationPath $zipFile -Force
    Remove-Item $sqlFile -Force

    $sizeMB = [math]::Round((Get-Item $zipFile).Length / 1MB, 2)
    Write-Log "Backup OK: $zipFile ($sizeMB MB)"

    $cutoff = (Get-Date).AddDays(-$RetentionDays)
    Get-ChildItem -Path $BackupDir -Filter "crm_*.zip" |
        Where-Object { $_.LastWriteTime -lt $cutoff } |
        ForEach-Object { Remove-Item $_.FullName -Force; Write-Log "Deleted old backup: $($_.Name)" }
}
catch {
    Write-Log "BACKUP FAILED: $($_.Exception.Message)"
    exit 1
}
finally {
    if ($cnf -and (Test-Path $cnf)) { Remove-Item $cnf -Force }
}
