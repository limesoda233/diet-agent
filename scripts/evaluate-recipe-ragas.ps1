[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$CorpusPath,
    [switch]$SkipJavaExport,
    [string]$InputPath = "target/evaluation/recipe/ragas-input.jsonl",
    [string]$OutputDir = "target/evaluation/recipe"
)

$ErrorActionPreference = 'Stop'
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))

function Resolve-RunnerPath([string]$PathValue) {
    if ([IO.Path]::IsPathRooted($PathValue)) {
        return [IO.Path]::GetFullPath($PathValue)
    }
    return [IO.Path]::GetFullPath((Join-Path $repoRoot $PathValue))
}

function Get-WriteTime([string]$PathValue) {
    if (Test-Path -LiteralPath $PathValue -PathType Leaf) {
        return (Get-Item -LiteralPath $PathValue).LastWriteTimeUtc
    }
    return [DateTime]::MinValue
}

function Assert-File([string]$PathValue, [string]$Description) {
    if (-not (Test-Path -LiteralPath $PathValue -PathType Leaf)) {
        throw "$Description was not produced: $PathValue"
    }
    if ((Get-Item -LiteralPath $PathValue).Length -eq 0) {
        throw "$Description is empty: $PathValue"
    }
}

function Assert-ExitCode([string]$Stage) {
    if ($LASTEXITCODE -ne 0) {
        throw "$Stage failed with exit code $LASTEXITCODE"
    }
}

$inputFile = Resolve-RunnerPath $InputPath
$javaReport = Resolve-RunnerPath 'target/evaluation/recipe/report.json'
$outputDirectory = Resolve-RunnerPath $OutputDir
$defaultInput = Resolve-RunnerPath 'target/evaluation/recipe/ragas-input.jsonl'
$ragasReport = Join-Path $outputDirectory 'ragas-report.json'
$ragasCases = Join-Path $outputDirectory 'ragas-cases.csv'

if (-not $SkipJavaExport -and $inputFile -ne $defaultInput) {
    throw 'A fresh Java export writes the default InputPath; use -SkipJavaExport for a custom frozen input.'
}
foreach ($reserved in @($javaReport, $ragasReport, $ragasCases, "$ragasReport.tmp", "$ragasCases.tmp")) {
    if ($inputFile -eq $reserved) {
        throw 'InputPath overlaps a report output path.'
    }
}
foreach ($reserved in @($ragasReport, $ragasCases, "$ragasReport.tmp", "$ragasCases.tmp")) {
    if ($javaReport -eq $reserved) {
        throw 'The Java report overlaps a Ragas output path.'
    }
}
if (Test-Path -LiteralPath $outputDirectory -PathType Leaf) {
    throw 'OutputDir names a file.'
}

Push-Location $repoRoot
try {
    if (-not $SkipJavaExport) {
        if (-not (Test-Path -LiteralPath $CorpusPath -PathType Container)) {
            throw "CorpusPath is not a directory: $CorpusPath"
        }
        $previousJavaWrite = Get-WriteTime $javaReport

        & mvn "-Dtest=RecipeRetrievalEvaluationTest" `
            "-Ddiet.recipe-rag.live=true" `
            "-Ddiet.recipe-rag.corpus-path=$CorpusPath" test
        Assert-ExitCode 'Java retrieval evaluation'
        Assert-File $javaReport 'Java retrieval report'
        if ((Get-WriteTime $javaReport) -le $previousJavaWrite) {
            throw 'Java retrieval evaluation did not refresh its report.'
        }
        try {
            $parsedJavaReport = Get-Content -LiteralPath $javaReport -Raw | ConvertFrom-Json -ErrorAction Stop
            if ($parsedJavaReport -isnot [pscustomobject]) {
                throw 'not an object'
            }
        } catch {
            throw 'Java retrieval report is not a valid JSON object.'
        }

        $previousInputWrite = Get-WriteTime $inputFile
        & mvn "-Dtest=RecipeAnswerEvaluationExportTest" `
            "-Ddiet.recipe-ragas.export.live=true" `
            "-Ddiet.recipe-rag.corpus-path=$CorpusPath" test
        Assert-ExitCode 'Java answer export'
        Assert-File $inputFile 'Ragas input'
        if ((Get-WriteTime $inputFile) -le $previousInputWrite) {
            throw 'Java answer export did not refresh Ragas input.'
        }
    }

    Assert-File $inputFile 'Ragas input'
    Assert-File $javaReport 'Java retrieval report'
    $previousRagasWrite = Get-WriteTime $ragasReport
    $previousCasesWrite = Get-WriteTime $ragasCases

    & uv sync --frozen --project evals/recipe-ragas
    Assert-ExitCode 'Frozen Python dependency sync'

    & uv run --frozen --project evals/recipe-ragas diet-recipe-ragas `
        --input $inputFile --java-report $javaReport --output-dir $outputDirectory
    Assert-ExitCode 'Ragas scoring'

    Assert-File $ragasReport 'Ragas JSON report'
    Assert-File $ragasCases 'Ragas case CSV'
    if ((Get-WriteTime $ragasReport) -le $previousRagasWrite -or
        (Get-WriteTime $ragasCases) -le $previousCasesWrite) {
        throw 'Ragas scoring did not refresh both expected artifacts.'
    }
    $summary = Get-Content -LiteralPath $ragasReport -Raw | ConvertFrom-Json
    $validCount = @($summary.metrics.PSObject.Properties | ForEach-Object { $_.Value.valid_count } |
        Measure-Object -Sum).Sum
    if ($summary.status -notin @('complete', 'partial') -or $validCount -le 0) {
        throw 'Ragas report has no valid scores or an unsuccessful status.'
    }
    Write-Output "Ragas status: $($summary.status); input cases: $($summary.total_input_count); valid metric values: $validCount"
    Write-Output "Ragas report: $ragasReport"
    Write-Output "Ragas cases: $ragasCases"
} finally {
    Pop-Location
}
