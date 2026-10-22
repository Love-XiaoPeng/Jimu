param([Parameter(Mandatory=$true)][string]$RepoRoot)
$ErrorActionPreference = 'Stop'
$artifactRoot = $PSScriptRoot
$repoPath = (Resolve-Path -LiteralPath $RepoRoot).Path
$evidenceRoot = Join-Path $artifactRoot 'evidence'
New-Item -ItemType Directory -Force (Join-Path $artifactRoot 'classes') | Out-Null
Push-Location $repoPath
try {
    & .\mvnw.cmd -o -B -ntp dependency:build-classpath '-DincludeScope=runtime' "-Dmdep.outputFile=$evidenceRoot/classpath-local.txt" *> (Join-Path $evidenceRoot 'dependency-classpath-log.txt')
    if ($LASTEXITCODE -ne 0) { throw "dependency classpath failed: $LASTEXITCODE" }
} finally { Pop-Location }
$linuxCheck = (& wsl --exec wslpath -a -u (Join-Path $artifactRoot 'verify-contracts.py')).Trim()
if ($LASTEXITCODE -ne 0) { throw 'wslpath failed' }
& wsl --exec python3 $linuxCheck *> (Join-Path $evidenceRoot 'python-contract-probe.txt')
if ($LASTEXITCODE -ne 0) { throw "schema examples failed: $LASTEXITCODE" }
$probeCp = (Get-Content -LiteralPath (Join-Path $evidenceRoot 'classpath-local.txt') -Raw).Trim()
$productionSource = Join-Path $repoPath 'src/main/java/io/github/lovexiaopeng/jimu/adapters/runtime/contract/SchemaContract.java'
& javac -encoding UTF-8 -cp $probeCp -d (Join-Path $artifactRoot 'classes') (Join-Path $artifactRoot 'ContractProbe.java') (Join-Path $artifactRoot 'SuspensionProbe.java') $productionSource *> (Join-Path $evidenceRoot 'probe-compile-log.txt')
if ($LASTEXITCODE -ne 0) { throw "probe compile failed: $LASTEXITCODE" }
$runtimeCp = (Join-Path $artifactRoot 'classes') + ';' + $probeCp
& java -cp $runtimeCp ContractProbe $artifactRoot *> (Join-Path $evidenceRoot 'jvm-contract-probe.txt')
if ($LASTEXITCODE -ne 0) { throw "JVM SchemaContract failed: $LASTEXITCODE" }
& java -cp $runtimeCp SuspensionProbe *> (Join-Path $evidenceRoot 'framework-probe.txt')
if ($LASTEXITCODE -ne 0) { throw "framework suspension failed: $LASTEXITCODE" }
$jarManifest = @()
foreach ($jar in $probeCp.Split(';')) {
    $relativeArtifact = $jar.Replace('\','/') -replace '^.*?/\.m2/repository/', ''
    $jarManifest += [ordered]@{artifact=$relativeArtifact;sha256=(Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash.ToLowerInvariant()}
}
[ordered]@{scope='Offline runtime dependency classpath, not a product build or dependency-convergence gate';jars=$jarManifest} | ConvertTo-Json -Depth 6 | Set-Content (Join-Path $evidenceRoot 'probe-dependencies.json') -Encoding utf8NoBOM
& java -version *> (Join-Path $evidenceRoot 'java-version.txt')
if ($LASTEXITCODE -ne 0) { throw "java version failed: $LASTEXITCODE" }
Write-Output 'PASS architecture schemas and Spring AI call/stream suspension probes; product runtime/migration not executed'
