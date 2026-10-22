$ErrorActionPreference = 'Stop'
$evidence50 = Join-Path $PSScriptRoot 'evidence'
New-Item -ItemType Directory -Force $evidence50 | Out-Null
$master50 = Invoke-RestMethod 'https://api.github.com/repos/Love-XiaoPeng/Jimu/commits/master' -TimeoutSec 30
if ($master50.sha -cne '777a2e2ae47c3d5be7ac545648c0d90483124ff2') { throw 'Master changed: revise the architecture baseline explicitly.' }
$masterTree50 = Invoke-RestMethod "https://api.github.com/repos/Love-XiaoPeng/Jimu/git/trees/$($master50.commit.tree.sha)?recursive=1" -TimeoutSec 30
if ($masterTree50.truncated) { throw 'Truncated Jimu tree' }
$masterTree50 | ConvertTo-Json -Depth 12 | Set-Content (Join-Path $evidence50 'jimu-tree.json') -Encoding utf8NoBOM
[ordered]@{retrievedAt=[DateTime]::UtcNow.ToString('o');master=$master50.sha;tree=$master50.commit.tree.sha;checkout='0e630f28e39c157788886694e2b16dc0831bb571';checkoutTree='8850a9d11f610257d87962585221b91a6bc19948';scope='Architecture-only source inspection. Git HTTPS unavailable; API and raw Git blob verification used.'} | ConvertTo-Json | Set-Content (Join-Path $evidence50 'master-baseline.json') -Encoding utf8NoBOM
$archive50 = Join-Path $PSScriptRoot 'jimu-master.zip'
if (-not (Test-Path (Join-Path $PSScriptRoot 'review-source'))) {
  Invoke-WebRequest "https://codeload.github.com/Love-XiaoPeng/Jimu/zip/$($master50.sha)" -OutFile $archive50 -TimeoutSec 60
  Expand-Archive -LiteralPath $archive50 -DestinationPath (Join-Path $PSScriptRoot 'review-source') -Force
}
$review50 = Join-Path $PSScriptRoot "review-source/Jimu-$($master50.sha)"
$verified50 = @()
foreach ($entry50 in $masterTree50.tree) {
  if ($entry50.type -cne 'blob') { continue }
  $file50 = Join-Path $review50 $entry50.path
  if (-not (Test-Path -LiteralPath $file50)) { throw "Archive missing $($entry50.path)" }
  $bytes50 = [IO.File]::ReadAllBytes($file50)
  $header50 = [Text.Encoding]::UTF8.GetBytes("blob $($bytes50.Length)`0")
  $blob50 = [Convert]::ToHexString([Security.Cryptography.SHA1]::HashData([byte[]]($header50 + $bytes50))).ToLowerInvariant()
  if ($blob50 -cne $entry50.sha) {
    Add-Content (Join-Path $evidence50 'archive-byte-differences.txt') "Archive $($entry50.path): $blob50 != $($entry50.sha); retrieved canonical raw blob."
    Invoke-WebRequest "https://raw.githubusercontent.com/Love-XiaoPeng/Jimu/$($master50.sha)/$($entry50.path)" -OutFile $file50 -TimeoutSec 30
    $bytes50 = [IO.File]::ReadAllBytes($file50)
    $header50 = [Text.Encoding]::UTF8.GetBytes("blob $($bytes50.Length)`0")
    $blob50 = [Convert]::ToHexString([Security.Cryptography.SHA1]::HashData([byte[]]($header50 + $bytes50))).ToLowerInvariant()
    if ($blob50 -cne $entry50.sha) { throw "Canonical Jimu blob mismatch $($entry50.path)" }
  }
  $verified50 += [ordered]@{path=$entry50.path;gitBlob=$blob50;sha256=(Get-FileHash -LiteralPath $file50 -Algorithm SHA256).Hash.ToLowerInvariant();bytes=(Get-Item -LiteralPath $file50).Length}
}
$verified50 | ConvertTo-Json -Depth 5 | Set-Content (Join-Path $evidence50 'jimu-verified-blobs.json') -Encoding utf8NoBOM
$upstream50 = '571902028a75c64c1d767f4ae5fd099485f0ddf0'
$upstreamTree50 = Invoke-RestMethod "https://api.github.com/repos/deepseek-ai/deepseek-harness/git/trees/${upstream50}?recursive=1" -TimeoutSec 30
if ($upstreamTree50.truncated) { throw 'Truncated Harness tree' }
$upstreamTree50 | ConvertTo-Json -Depth 12 | Set-Content (Join-Path $evidence50 'upstream-tree.json') -Encoding utf8NoBOM
$upstreamTree50.tree | Where-Object { $_.path -match 'todo|session.*/src/|projection|turn.*spec' } | Select-Object path,type,sha | ConvertTo-Json -Depth 4 | Set-Content (Join-Path $evidence50 'upstream-path-candidates.json') -Encoding utf8NoBOM
Write-Output "Verified $($verified50.Count) Jimu blobs at $($master50.sha); upstream tree captured."
