$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$tools = "$env:USERPROFILE\tools"
if (-not (Get-Command java -ErrorAction SilentlyContinue) -and (Test-Path "$tools\jdk")) { $env:JAVA_HOME = "$tools\jdk"; $env:Path = "$tools\jdk\bin;$env:Path" }
if (-not (Get-Command mvn -ErrorAction SilentlyContinue) -and (Test-Path "$tools\maven")) { $env:Path = "$tools\maven\bin;$env:Path" }
foreach ($c in 'java', 'mvn', 'node', 'npm') { if (-not (Get-Command $c -ErrorAction SilentlyContinue)) { Write-Host "Missing: $c - install it first (see README.md)" -ForegroundColor Red; exit 1 } }

if (-not $env:DB_PASSWORD) {
    $secure = Read-Host 'MySQL root password' -AsSecureString
    $env:DB_PASSWORD = [Runtime.InteropServices.Marshal]::PtrToStringAuto([Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure))
}
if (-not (Test-Path "$root\frontend\node_modules")) { Push-Location "$root\frontend"; npm install; Pop-Location }

$backend = "`$env:JAVA_HOME='$env:JAVA_HOME'; `$env:Path='$env:Path'; `$env:SPRING_PROFILES_ACTIVE='mysql'; `$env:DB_USERNAME='root'; `$env:DB_PASSWORD='$($env:DB_PASSWORD -replace "'", "''")'; `$env:SEED_DEMO='true'; cd '$root\backend'; mvn spring-boot:run"
Start-Process powershell -ArgumentList '-NoExit', '-Command', $backend
Start-Process powershell -ArgumentList '-NoExit', '-Command', "`$env:Path='$env:Path'; cd '$root\frontend'; npm run dev"

Write-Host 'Waiting for the backend (first run downloads dependencies and can take a few minutes)...'
for ($i = 0; $i -lt 180; $i++) { try { Invoke-WebRequest http://localhost:8080/api/health -UseBasicParsing -TimeoutSec 2 | Out-Null; break } catch { Start-Sleep 2 } }
Start-Process 'http://localhost:5173'
Write-Host "`nOpen http://localhost:5173  (MySQL database: haven_realty)"
