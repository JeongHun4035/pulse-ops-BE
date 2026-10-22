# Local development only. Kubernetes should run the one-shot Worker with a CronJob.
[CmdletBinding()]
param(
    [ValidateRange(1, 86400)] [int] $DelaySeconds = 60,
    # Zero means run until Ctrl+C. A finite value is useful for smoke tests.
    [ValidateRange(0, 1000000)] [int] $Iterations = 0
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$jarPath = Join-Path $projectRoot 'build/libs/app.jar'
if (!(Test-Path -LiteralPath $jarPath)) {
    throw 'Build the JAR first: .\gradlew.bat bootJar'
}
$javaPath = (Get-Command java -ErrorAction Stop).Source
$completedRuns = 0
while ($true) {
    Write-Host "[$([DateTimeOffset]::Now.ToString('o'))] Starting one-shot PulseOps Worker"
    & $javaPath -jar $jarPath '--spring.profiles.active=worker'
    if ($LASTEXITCODE -ne 0) {
        Write-Warning "Worker exited with code $LASTEXITCODE; the next iteration will retry due sites."
    }
    $completedRuns++
    if ($Iterations -gt 0 -and $completedRuns -ge $Iterations) { break }
    Start-Sleep -Seconds $DelaySeconds
}
