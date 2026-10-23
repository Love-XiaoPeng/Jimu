$ErrorActionPreference = 'Stop'
$traceProbeRoot = $PSScriptRoot
$traceBuildRoot = Join-Path (Get-Location).Path 'target/xiao54'
New-Item -ItemType Directory -Force (Join-Path $traceBuildRoot 'classes') | Out-Null
& .\mvnw.cmd -o -B -ntp dependency:build-classpath '-DincludeScope=runtime' "-Dmdep.outputFile=$traceBuildRoot/classpath.txt" *> (Join-Path $traceBuildRoot 'dependency-classpath.log')
if ($LASTEXITCODE -ne 0) { throw "Classpath failed: $LASTEXITCODE" }
$traceProbeCp = (Get-Content -Raw (Join-Path $traceBuildRoot 'classpath.txt')).Trim()
& javac -encoding UTF-8 -cp $traceProbeCp -d (Join-Path $traceBuildRoot 'classes') (Join-Path $traceProbeRoot 'ContractProbe.java') 'src/main/java/io/github/lovexiaopeng/jimu/adapters/runtime/contract/SchemaContract.java' *> (Join-Path $traceProbeRoot 'evidence/probe-compile.txt')
if ($LASTEXITCODE -ne 0) { throw "Compile failed: $LASTEXITCODE" }
& java -cp "$(Join-Path $traceBuildRoot 'classes');$traceProbeCp" ContractProbe $traceProbeRoot *> (Join-Path $traceProbeRoot 'evidence/jvm-probe.txt')
if ($LASTEXITCODE -ne 0) { throw "Probe failed: $LASTEXITCODE" }
& java -version *> (Join-Path $traceProbeRoot 'evidence/java-version.txt')
if ($LASTEXITCODE -ne 0) { throw "Java failed: $LASTEXITCODE" }
Get-Content (Join-Path $traceProbeRoot 'evidence/jvm-probe.txt')
