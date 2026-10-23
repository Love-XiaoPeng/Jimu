$ErrorActionPreference = 'Stop'
$scopeProbeRoot = $PSScriptRoot
$scopeBuildRoot = Join-Path (Get-Location).Path 'target/xiao55'
New-Item -ItemType Directory -Force (Join-Path $scopeBuildRoot 'classes') | Out-Null
& .\mvnw.cmd -o -B -ntp dependency:build-classpath '-DincludeScope=runtime' "-Dmdep.outputFile=$scopeBuildRoot/classpath.txt" *> (Join-Path $scopeBuildRoot 'dependency-classpath.log')
if ($LASTEXITCODE -ne 0) { throw "Classpath failed: $LASTEXITCODE" }
$scopeProbeCp = (Get-Content -Raw (Join-Path $scopeBuildRoot 'classpath.txt')).Trim()
& javac -encoding UTF-8 -cp $scopeProbeCp -d (Join-Path $scopeBuildRoot 'classes') (Join-Path $scopeProbeRoot 'ContractProbe.java') 'src/main/java/io/github/lovexiaopeng/jimu/adapters/runtime/contract/SchemaContract.java' *> (Join-Path $scopeProbeRoot 'evidence/probe-compile.txt')
if ($LASTEXITCODE -ne 0) { throw "Compile failed: $LASTEXITCODE" }
& java -cp "$(Join-Path $scopeBuildRoot 'classes');$scopeProbeCp" ContractProbe $scopeProbeRoot *> (Join-Path $scopeProbeRoot 'evidence/jvm-probe.txt')
if ($LASTEXITCODE -ne 0) { throw "Probe failed: $LASTEXITCODE" }
& java -version *> (Join-Path $scopeProbeRoot 'evidence/java-version.txt')
if ($LASTEXITCODE -ne 0) { throw "Java failed: $LASTEXITCODE" }
Get-Content (Join-Path $scopeProbeRoot 'evidence/jvm-probe.txt')
