# Creates deploy/cloud-secrets.env (git-ignored) with fresh random secrets for the cloud
# deployment (deploy/README.md). Never reuses the local .env values. Refuses to overwrite an
# existing file: the Aadhaar keys must never change once real data is stored.
#   powershell -File deploy\generate-secrets.ps1
$ErrorActionPreference = 'Stop'
$out = Join-Path $PSScriptRoot 'cloud-secrets.env'
if (Test-Path $out) { throw "$out already exists; not overwriting it." }

$rng = New-Object System.Security.Cryptography.RNGCryptoServiceProvider
function RandomBytes([int] $n) { $b = New-Object byte[] $n; $rng.GetBytes($b); return $b }
function Base64([int] $n) { [Convert]::ToBase64String((RandomBytes $n)) }
# Letters and digits only: safe to paste anywhere (dashboards, URLs, shells).
#   The app's password policy also needs at least one letter and one digit.
function Password([int] $n) {
  do { $p = (Base64 ($n * 2)).Replace('+', '').Replace('/', '').Replace('=', '').Substring(0, $n) }
  until ($p -cmatch '[A-Za-z]' -and $p -match '\d')
  return $p
}

$lines = @(
  '# Cloud secrets for Render / Cloudflare Pages (deploy/README.md). KEEP PRIVATE: never commit,'
  '# never paste into chat. Keep a copy in a password manager; the Aadhaar keys also offline.'
  "# Generated $(Get-Date -Format 'yyyy-MM-dd HH:mm')"
  ''
  '# ---- Render (backend) and Cloudflare Pages (website): the same value in both ----'
  "PROXY_SECRET=$(Password 40)"
  ''
  '# ---- Render (backend) ----'
  "JWT_SECRET=$(Password 64)"
  "AADHAAR_ENC_KEY=$(Base64 32)"
  "AADHAAR_HASH_KEY=$(Base64 32)"
  "BOOTSTRAP_ADMIN_PASSWORD=$(Password 20)"
  "DEV_SAMPLE_PASSWORD=$(Password 16)"
  ''
  '# ---- From Neon (fill in): Dashboard -> Connect -> Java/JDBC ----'
  'DB_URL=jdbc:postgresql://<endpoint>.<region>.aws.neon.tech/<db>?sslmode=require'
  'DB_USER='
  'DB_PASSWORD='
  ''
  '# ---- The website address (fill in after creating the Pages project) ----'
  'CORS_ALLOWED_ORIGINS=https://<project>.pages.dev'
)
[IO.File]::WriteAllLines($out, $lines)
Write-Host "Created $out (values not shown)."
