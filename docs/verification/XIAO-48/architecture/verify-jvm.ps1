$ErrorActionPreference = 'Stop'
$m2 = Join-Path $env:USERPROFILE '.m2/repository'
$jars = @(
'com/networknt/json-schema-validator/3.0.1/json-schema-validator-3.0.1.jar',
'tools/jackson/core/jackson-core/3.1.7/jackson-core-3.1.7.jar',
'tools/jackson/core/jackson-databind/3.1.7/jackson-databind-3.1.7.jar',
'tools/jackson/dataformat/jackson-dataformat-yaml/3.1.7/jackson-dataformat-yaml-3.1.7.jar',
'com/fasterxml/jackson/core/jackson-core/2.21.7/jackson-core-2.21.7.jar',
'com/fasterxml/jackson/core/jackson-databind/2.21.7/jackson-databind-2.21.7.jar',
'com/fasterxml/jackson/core/jackson-annotations/2.21/jackson-annotations-2.21.jar',
'org/slf4j/slf4j-api/2.0.18/slf4j-api-2.0.18.jar',
'com/ethlo/time/itu/1.14.0/itu-1.14.0.jar',
'org/yaml/snakeyaml/2.6/snakeyaml-2.6.jar'
)
$classpath = ($jars | ForEach-Object { $file=Join-Path $m2 $_; if(-not(Test-Path $file)){throw "Missing $file"}; $file }) -join ';'
$classes = Join-Path $PSScriptRoot 'probe-classes'
New-Item -ItemType Directory -Force $classes | Out-Null
$source = Join-Path (Split-Path $PSScriptRoot) 'Jimu/src/main/java/io/github/lovexiaopeng/jimu/adapters/runtime/contract/SchemaContract.java'
& javac -encoding UTF-8 -cp $classpath -d $classes $source (Join-Path $PSScriptRoot 'ContractProbe.java')
if($LASTEXITCODE -ne 0){exit $LASTEXITCODE}
& java -cp "$classes;$classpath" ContractProbe (Join-Path $PSScriptRoot 'contracts')
if($LASTEXITCODE -ne 0){exit $LASTEXITCODE}
$receipt = [ordered]@{schemaContractSha256=(Get-FileHash $source -Algorithm SHA256).Hash.ToLowerInvariant();jars=@($jars|ForEach-Object{[ordered]@{artifact=$_;sha256=(Get-FileHash (Join-Path $m2 $_) -Algorithm SHA256).Hash.ToLowerInvariant()}});scope='Only source compilation and contract probe; not Maven dependency convergence, product build or runtime acceptance'}
$receipt|ConvertTo-Json -Depth 5|Set-Content (Join-Path $PSScriptRoot 'evidence/jvm-probe-dependencies.json') -Encoding utf8NoBOM
