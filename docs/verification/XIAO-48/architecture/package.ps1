$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath($PSScriptRoot)
$files = @(Get-ChildItem -LiteralPath $root -Recurse -File | Where-Object { $_.Name -ne 'PACKAGE-MANIFEST.json' -and $_.FullName -notlike '*\probe-classes\*' } | Sort-Object FullName)
$entries = @($files | ForEach-Object {
  [ordered]@{path=[IO.Path]::GetRelativePath($root,$_.FullName).Replace('\','/');bytes=$_.Length;sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()}
})
$manifest = [ordered]@{version=1;issue='XIAO-48';kind='architecture-handoff';baseline='a04bc49a047827b292c7929312ea8d1ae9e31afc';tree='74f134b2dbaa0c984256129fab2c41a9fb70a15d';files=$entries}
$manifest | ConvertTo-Json -Depth 5 | Set-Content (Join-Path $root 'PACKAGE-MANIFEST.json') -Encoding utf8NoBOM
$zipPath = Join-Path (Split-Path $root) 'XIAO-48-mcp-architecture-v1.zip'
$stream = [IO.File]::Open($zipPath,[IO.FileMode]::Create,[IO.FileAccess]::ReadWrite)
$archive = [IO.Compression.ZipArchive]::new($stream,[IO.Compression.ZipArchiveMode]::Create,$false)
try {
  foreach ($file in @($files.FullName)+(Join-Path $root 'PACKAGE-MANIFEST.json')) {
    $relative=[IO.Path]::GetRelativePath($root,$file).Replace('\','/')
    [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive,$file,$relative,[IO.Compression.CompressionLevel]::Optimal) | Out-Null
  }
} finally { $archive.Dispose(); $stream.Dispose() }
$check = [IO.Compression.ZipFile]::OpenRead($zipPath)
try {
  if ($check.Entries.Count -ne ($entries.Count+1)) { throw 'Zip entry count mismatch' }
  foreach ($expected in $entries) {
    $entry=$check.GetEntry($expected.path)
    if(-not $entry -or $entry.Length -ne $expected.bytes){throw 'Zip bytes mismatch'}
    $reader=$entry.Open()
    try {$hash=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($reader)).ToLowerInvariant()}
    finally {$reader.Dispose()}
    if($hash -cne $expected.sha256){throw 'Zip SHA256 mismatch'}
  }
} finally { $check.Dispose() }
Write-Output "Verified $($entries.Count+1) archive files; SHA256=$((Get-FileHash -LiteralPath $zipPath -Algorithm SHA256).Hash.ToLowerInvariant()); bytes=$((Get-Item -LiteralPath $zipPath).Length)"
