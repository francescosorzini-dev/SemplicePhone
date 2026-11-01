<#
.SYNOPSIS
  Crea la chiave di firma release di SemplicePhone e la carica come secrets del repository GitHub.

.DESCRIPTION
  La chiave resta tua: il file .jks va conservato in un posto sicuro (password manager / backup).
  Senza la stessa chiave non si possono più installare aggiornamenti sui telefoni.

  Richiede: keytool (JDK 17 o Android Studio: <Studio>\jbr\bin\keytool.exe) e GitHub CLI (gh) autenticato.

.EXAMPLE
  .\new-keystore.ps1 -Repo francescosorzini-dev/SemplicePhone
#>
param(
    [string]$Repo = "francescosorzini-dev/SemplicePhone",
    [string]$Alias = "semplicephone",
    [string]$OutFile = "$HOME\SemplicePhone-release.jks",
    [string]$Keytool = "keytool"
)
$ErrorActionPreference = "Stop"

if (Test-Path $OutFile) { throw "$OutFile esiste già: non lo sovrascrivo." }
if (-not (Get-Command $Keytool -ErrorAction SilentlyContinue)) {
    $studio = "$env:ProgramFiles\Android\Android Studio\jbr\bin\keytool.exe"
    if (Test-Path $studio) { $Keytool = $studio } else { throw "keytool non trovato: passa -Keytool <percorso>" }
}
if (-not (Get-Command gh -ErrorAction SilentlyContinue)) { throw "GitHub CLI (gh) non trovato" }

# Password casuale di 32 caratteri (stessa per store e chiave, formato PKCS12).
$bytes = New-Object byte[] 24
[System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
$pass = [Convert]::ToBase64String($bytes).Replace('+', 'A').Replace('/', 'B')

& $Keytool -genkeypair -v -storetype PKCS12 -keystore $OutFile -alias $Alias `
    -keyalg RSA -keysize 4096 -validity 36500 -storepass $pass -keypass $pass `
    -dname "CN=SemplicePhone, O=SemplicePhone, C=IT"
if ($LASTEXITCODE -ne 0) { throw "keytool fallito" }

$b64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes($OutFile))
$b64 | gh secret set SIGNING_KEYSTORE_B64 -R $Repo
$pass | gh secret set SIGNING_STORE_PASSWORD -R $Repo
$Alias | gh secret set SIGNING_KEY_ALIAS -R $Repo
$pass | gh secret set SIGNING_KEY_PASSWORD -R $Repo

Write-Host ""
Write-Host "Keystore: $OutFile" -ForegroundColor Green
Write-Host "Password: $pass" -ForegroundColor Yellow
Write-Host "Salva file e password nel password manager. Poi: git tag v0.2.0; git push origin v0.2.0"
