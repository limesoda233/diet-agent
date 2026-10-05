[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$ReportPath,
    [Parameter(Mandatory = $true)][string]$CasesPath
)

$summary = Get-Content -LiteralPath $ReportPath -Raw | ConvertFrom-Json -ErrorAction Stop
$expectedCsvHash = $summary.cases_csv_sha256
$actualCsvHash = (Get-FileHash -LiteralPath $CasesPath -Algorithm SHA256).Hash.ToLowerInvariant()
if ($expectedCsvHash -notmatch '^[0-9a-f]{64}$' -or $expectedCsvHash -cne $actualCsvHash) {
    throw 'Ragas report is incomplete: case CSV SHA-256 differs from JSON.'
}
$summary
