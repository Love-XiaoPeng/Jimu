$ErrorActionPreference = 'Stop'
$root = Join-Path $PSScriptRoot 'evidence'
New-Item -ItemType Directory -Force $root | Out-Null
$upstream = '571902028a75c64c1d767f4ae5fd099485f0ddf0'
$repo = 'deepseek-ai/deepseek-harness'
$tree = Invoke-RestMethod "https://api.github.com/repos/$repo/git/trees/${upstream}?recursive=1" -TimeoutSec 30
if ($tree.truncated) { throw 'truncated upstream tree' }
$paths = @('LICENSE','THIRD_PARTY_NOTICES.md','packages/mcp/mcp-resources/package.json','packages/mcp/mcp-resources/src/tools.ts','packages/mcp/mcp-resources/src/index.ts','packages/mcp/mcp-resources/src/render.ts','packages/mcp/mcp-resources/tests/resources.spec.ts','packages/mcp/mcp-client/package.json','packages/mcp/mcp-client/src/transport.ts','packages/mcp/mcp-client/src/connection.ts','packages/mcp/mcp-client/src/server-context.ts','packages/mcp/mcp-client/tests/fixtures/resources-server.ts')
$manifest = @()
foreach ($path in $paths) {
  $entry = $tree.tree | Where-Object path -CEQ $path
  if (-not $entry) { throw "absent: $path" }
  $file = Join-Path $root ($path.Replace('/','__'))
  $url = "https://raw.githubusercontent.com/$repo/$upstream/$path"
  Invoke-WebRequest $url -OutFile $file -TimeoutSec 30
  $blob = (& git hash-object --no-filters $file).Trim()
  if ($blob -cne $entry.sha) { throw "blob mismatch: $path" }
  $manifest += [ordered]@{repo=$repo;commit=$upstream;path=$path;file=[IO.Path]::GetFileName($file);gitBlob=$blob;sha256=(Get-FileHash $file -Algorithm SHA256).Hash.ToLowerInvariant();bytes=(Get-Item $file).Length;url=$url}
}
$master = Invoke-RestMethod 'https://api.github.com/repos/Love-XiaoPeng/Jimu/commits/master' -TimeoutSec 30
if ($master.sha -cne 'a04bc49a047827b292c7929312ea8d1ae9e31afc') { throw 'Actual master changed; create a new evidence revision instead of overwriting this handoff baseline.' }
$baseline = [ordered]@{retrievedAt=[DateTime]::UtcNow.ToString('o');actualMaster=$master.sha;actualTree=$master.commit.tree.sha;checkout='fdb38138f60b92a556b276e6c3ef96d287a8d0f6';checkoutTree='10b993f03fb45a5e334739c5b6227aa60ccdd80d';changedFiles=@($master.files | ForEach-Object { [ordered]@{path=$_.filename;status=$_.status;blob=$_.sha} });failureHistory=@('multica repo checkout --output json: unknown flag, exit 1; retried supported command successfully','git ls-remote upstream: connection reset, shell exit 1; native exit not captured','git schannel fetch origin master: connect timeout, shell exit 1; native exit not captured');scope='Architecture source/API evidence only; no product MCP execution or deployment'}
$baseline | ConvertTo-Json -Depth 8 | Set-Content (Join-Path $root 'master-baseline.json') -Encoding utf8NoBOM
foreach ($item in $master.files) {
  $file = Join-Path $root ('jimu-master__'+$item.filename.Replace('/','__'))
  Invoke-WebRequest "https://raw.githubusercontent.com/Love-XiaoPeng/Jimu/$($master.sha)/$($item.filename)" -OutFile $file -TimeoutSec 30
  if ((& git hash-object --no-filters $file).Trim() -cne $item.sha) { throw 'master blob mismatch' }
}
$specRepo = 'modelcontextprotocol/modelcontextprotocol'
$spec = Invoke-RestMethod "https://api.github.com/repos/$specRepo/commits/80041ab0a139a8f4fb3adbc99f7ae9113ea59671" -TimeoutSec 30
$specTree = Invoke-RestMethod "https://api.github.com/repos/$specRepo/git/trees/$($spec.sha)?recursive=1" -TimeoutSec 30
$specPaths = @('schema/2025-06-18/schema.ts','docs/specification/2025-06-18/basic/transports.mdx','docs/specification/2025-06-18/basic/lifecycle.mdx','docs/specification/2025-06-18/basic/utilities/cancellation.mdx','docs/specification/2025-06-18/server/resources.mdx','LICENSE')
foreach ($path in $specPaths) {
  $entry = $specTree.tree | Where-Object path -CEQ $path
  if (-not $entry) { throw "absent spec: $path" }
  $file = Join-Path $root ('mcp-spec__'+$path.Replace('/','__'))
  $url = "https://raw.githubusercontent.com/$specRepo/$($spec.sha)/$path"
  Invoke-WebRequest $url -OutFile $file -TimeoutSec 30
  $blob = (& git hash-object --no-filters $file).Trim()
  if ($blob -cne $entry.sha) { throw "spec blob mismatch: $path" }
  $manifest += [ordered]@{repo=$specRepo;commit=$spec.sha;path=$path;file=[IO.Path]::GetFileName($file);gitBlob=$blob;sha256=(Get-FileHash $file -Algorithm SHA256).Hash.ToLowerInvariant();bytes=(Get-Item $file).Length;url=$url}
}
$manifest | ConvertTo-Json -Depth 6 | Set-Content (Join-Path $root 'sources.json') -Encoding utf8NoBOM
Write-Output "Verified $($manifest.Count) source blobs. Master $($master.sha), tree $($master.commit.tree.sha)."
