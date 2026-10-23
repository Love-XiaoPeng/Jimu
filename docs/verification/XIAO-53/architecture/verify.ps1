$ErrorActionPreference = 'Stop'
$probeRoot = $PSScriptRoot
$buildRoot = Join-Path (Get-Location).Path 'target/xiao53-architecture'
New-Item -ItemType Directory -Force (Join-Path $buildRoot 'classes') | Out-Null
& .\mvnw.cmd -o -B -ntp dependency:build-classpath '-DincludeScope=runtime' "-Dmdep.outputFile=$buildRoot/classpath.txt" *> (Join-Path $buildRoot 'dependency-classpath.log')
if ($LASTEXITCODE -ne 0) { throw "Classpath failed: $LASTEXITCODE" }
$probeCp = (Get-Content -Raw (Join-Path $buildRoot 'classpath.txt')).Trim()
& javac -encoding UTF-8 -cp $probeCp -d (Join-Path $buildRoot 'classes') (Join-Path $probeRoot 'ContractProbe.java') 'src/main/java/io/github/lovexiaopeng/jimu/adapters/runtime/contract/SchemaContract.java' *> (Join-Path $probeRoot 'evidence/probe-compile.txt')
if ($LASTEXITCODE -ne 0) { throw "Probe compile failed: $LASTEXITCODE" }
& java -cp "$(Join-Path $buildRoot 'classes');$probeCp" ContractProbe $probeRoot *> (Join-Path $probeRoot 'evidence/jvm-probe.txt')
if ($LASTEXITCODE -ne 0) { throw "Probe failed: $LASTEXITCODE" }
& java -version *> (Join-Path $probeRoot 'evidence/java-version.txt')
if ($LASTEXITCODE -ne 0) { throw "Java version failed: $LASTEXITCODE" }
Get-Content (Join-Path $probeRoot 'evidence/jvm-probe.txt')
