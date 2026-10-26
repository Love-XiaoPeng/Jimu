param([string]$Base = '77e48a1a1d03b3849786c3d2edadb1aec9bb3ad8')
$ErrorActionPreference = 'Stop'
$originalPath = 'src/main/java/io/github/lovexiaopeng/jimu/bootstrap/DurableConfiguration.java'

function BeanBlocks([string]$source) {
    $source = $source.Replace("`r`n", "`n")
    $start = $source.IndexOf('  @Bean')
    if ($start -lt 0) { return @() }
    [regex]::Split($source.Substring($start, $source.LastIndexOf('}') - $start).TrimEnd(), '(?m)(?=^  @Bean)') |
        Where-Object { $_.Trim() }
}

function Tokens([string]$source) {
    # Keep complete string/char literals, including whitespace within them.
    ([regex]::Matches($source, '"(?:\\.|[^"\\])*"|''(?:\\.|[^''\\])*''|[A-Za-z_$][\w$]*|\d+|[^\s]') |
        ForEach-Object Value) -join "`n"
}

function FactoryName([string]$block) {
    $signature = ($block -split '\{', 2)[0]
    $calls = [regex]::Matches($signature, '(\w+)\(')
    # Annotations occur before the factory signature; parameter annotations can follow it.
    ($calls | Where-Object { $_.Groups[1].Value -notin @('Bean', 'ConditionalOnProperty', 'Qualifier') } |
        Select-Object -First 1).Groups[1].Value
}

$oldSource = (git show "${Base}:$originalPath") -join "`n"
if ($LASTEXITCODE -ne 0) { throw 'Cannot read exact baseline' }
$old = @{}
foreach ($block in (BeanBlocks $oldSource)) { $old[(FactoryName $block)] = Tokens $block }
$current = @{}
foreach ($file in (Get-ChildItem 'src/main/java/io/github/lovexiaopeng/jimu/bootstrap/config/Durable*Configuration.java')) {
    foreach ($block in (BeanBlocks ([IO.File]::ReadAllText($file.FullName)))) {
        $name = FactoryName $block
        if ($current.ContainsKey($name)) { throw "Duplicate factory $name" }
        $current[$name] = @{ owner = $file.Name; tokens = (Tokens $block) }
    }
}
if ($old.Count -ne 20 -or $current.Count -ne 20) { throw 'Expected exactly 20 old and relocated factories' }
$rows = foreach ($name in ($old.Keys | Sort-Object)) {
    if (!$current.ContainsKey($name) -or $old[$name] -cne $current[$name].tokens) {
        throw "Changed annotations/signature/body: $name"
    }
    [ordered]@{
        bean = $name
        owner = $current[$name].owner
        identicalTokens = $true
        sha256 = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($old[$name]))).ToLowerInvariant()
    }
}
$root = [IO.File]::ReadAllText((Join-Path $PWD $originalPath))
if (@(BeanBlocks $root).Count -ne 0) { throw 'Root must not retain bean factories' }
$entries = @(git ls-tree -r $Base src/main pom.xml)
if ($LASTEXITCODE -ne 0) { throw 'Cannot list baseline blobs' }
$frozen = @($entries | ForEach-Object {
    $parts = $_ -split "`t", 2
    if ($parts[1] -ne $originalPath) {
        @{ path = $parts[1]; blob = ($parts[0] -split ' ')[2] }
    }
})
$actualBlobs = @($frozen.path | git hash-object --stdin-paths)
if ($LASTEXITCODE -ne 0 -or $actualBlobs.Count -ne $frozen.Count) { throw 'Cannot hash frozen files' }
for ($i = 0; $i -lt $frozen.Count; $i++) {
    if ($frozen[$i].blob -cne $actualBlobs[$i]) { throw "Frozen source changed: $($frozen[$i].path)" }
}
[ordered]@{
    baseline = $Base
    factories = $rows
    frozenProductionAndPomBlobs = $frozen.Count
    result = 'pass'
} | ConvertTo-Json -Depth 6
