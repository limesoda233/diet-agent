Describe 'Ragas report pair integrity' {
    BeforeEach {
        $reportPath = Join-Path $TestDrive 'ragas-report.json'
        $csvPath = Join-Path $TestDrive 'ragas-cases.csv'
        Set-Content -LiteralPath $csvPath -Value 'query_id,status' -Encoding UTF8
        $hash = (Get-FileHash -LiteralPath $csvPath -Algorithm SHA256).Hash.ToLowerInvariant()
        @{ status = 'complete'; cases_csv_sha256 = $hash } |
            ConvertTo-Json -Compress | Set-Content -LiteralPath $reportPath -Encoding UTF8
    }

    It 'accepts a matching JSON and CSV pair' {
        $summary = & "$PSScriptRoot/Assert-RecipeRagasReport.ps1" -ReportPath $reportPath -CasesPath $csvPath
        $summary.status | Should Be 'complete'
    }

    It 'rejects an old JSON next to a replaced CSV' {
        Set-Content -LiteralPath $csvPath -Value 'query_id,status`nnew,complete' -Encoding UTF8
        { & "$PSScriptRoot/Assert-RecipeRagasReport.ps1" -ReportPath $reportPath -CasesPath $csvPath } |
            Should Throw 'incomplete'
    }
}
