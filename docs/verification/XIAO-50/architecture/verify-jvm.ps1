$ErrorActionPreference='Stop'
$root50=$PSScriptRoot
$e50=Join-Path $root50 'evidence'
$cp50=(Get-Content (Join-Path $e50 'classpath-local.txt') -Raw).Trim()
$source50=Join-Path $root50 'review-source/Jimu-777a2e2ae47c3d5be7ac545648c0d90483124ff2/src/main/java/io/github/lovexiaopeng/jimu/adapters/runtime/contract/SchemaContract.java'
$classes50=Join-Path $root50 'classes'
New-Item -ItemType Directory -Force $classes50 | Out-Null
& javac -encoding UTF-8 -cp $cp50 -d $classes50 (Join-Path $root50 'ContractProbe.java') $source50 *> (Join-Path $e50 'probe-compile-log.txt')
if ($LASTEXITCODE -ne 0) { Get-Content (Join-Path $e50 'probe-compile-log.txt'); throw "Probe compile: $LASTEXITCODE" }
& java -cp ($classes50+';'+$cp50) ContractProbe $root50 *> (Join-Path $e50 'jvm-contract-probe.txt')
if ($LASTEXITCODE -ne 0) { Get-Content (Join-Path $e50 'jvm-contract-probe.txt'); throw "Probe run: $LASTEXITCODE" }
Get-Content (Join-Path $e50 'jvm-contract-probe.txt')
$dependencies50=foreach ($jar50 in $cp50.Split(';')) {
 [ordered]@{artifact=($jar50.Replace('\','/') -replace '^.*?/\.m2/repository/','');sha256=(Get-FileHash -LiteralPath $jar50 -Algorithm SHA256).Hash.ToLowerInvariant()}
}
$dependencies50 | ConvertTo-Json -Depth 4 | Set-Content (Join-Path $e50 'probe-dependencies.json') -Encoding utf8NoBOM
& java -version *> (Join-Path $e50 'java-version.txt')
if ($LASTEXITCODE -ne 0) { throw 'Java version failed' }
