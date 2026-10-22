$ErrorActionPreference = 'Stop'
$probeRoot = $PSScriptRoot
$buildRoot = Join-Path (Get-Location).Path 'target/xiao52-architecture'
$evidenceRoot = Join-Path $probeRoot 'evidence'
New-Item -ItemType Directory -Force (Join-Path $buildRoot 'classes') | Out-Null
& .\mvnw.cmd -o -B -ntp dependency:build-classpath '-DincludeScope=runtime' "-Dmdep.outputFile=$buildRoot/classpath.txt" *> (Join-Path $evidenceRoot 'dependency-classpath.txt')
if ($LASTEXITCODE -ne 0) { throw "Dependency classpath failed: $LASTEXITCODE" }
$probeCp = (Get-Content -Raw (Join-Path $buildRoot 'classpath.txt')).Trim()
& javac -encoding UTF-8 -cp $probeCp -d (Join-Path $buildRoot 'classes') (Join-Path $probeRoot 'ContractProbe.java') 'src/main/java/io/github/lovexiaopeng/jimu/adapters/runtime/contract/SchemaContract.java' *> (Join-Path $evidenceRoot 'probe-compile.txt')
if ($LASTEXITCODE -ne 0) { throw "Probe compile failed: $LASTEXITCODE" }
& java -cp "$(Join-Path $buildRoot 'classes');$probeCp" ContractProbe $probeRoot *> (Join-Path $evidenceRoot 'jvm-probe.txt')
if ($LASTEXITCODE -ne 0) { throw "Probe failed: $LASTEXITCODE" }
& java -version *> (Join-Path $evidenceRoot 'java-version.txt')
if ($LASTEXITCODE -ne 0) { throw "Java version failed: $LASTEXITCODE" }
$jars = @($probeCp.Split(';') | ForEach-Object {
  [ordered]@{artifact=($_.Replace('\','/') -replace '^.*?/\.m2/repository/', '');sha256=(Get-FileHash -LiteralPath $_ -Algorithm SHA256).Hash.ToLowerInvariant()}
})
[ordered]@{scope='Architecture-only runtime dependencies, not product build';jars=$jars} | ConvertTo-Json -Depth 5 | Set-Content -Encoding utf8NoBOM (Join-Path $evidenceRoot 'probe-dependencies.json')
Get-Content (Join-Path $evidenceRoot 'jvm-probe.txt')
