$ErrorActionPreference = 'Stop'
$root50 = Join-Path $PSScriptRoot 'evidence'
$tree50 = Get-Content (Join-Path $root50 'upstream-tree.json') -Raw | ConvertFrom-Json
$paths50 = @(
 'LICENSE','THIRD_PARTY_NOTICES.md',
 'packages/todo/tool-todo/package.json',
 'packages/todo/tool-todo/src/index.ts','packages/todo/tool-todo/src/types.ts','packages/todo/tool-todo/src/client.ts',
 'packages/todo/tool-todo/tests/tool-todo.spec.ts','packages/todo/tool-todo/tests/projection.spec.ts',
 'packages/todo/tool-todo/tests/integration.spec.ts','packages/todo/tool-todo/tests/loader-composition.spec.ts',
 'packages/session/session-projection/src/index.ts','packages/session/session-projection/src/types.ts',
 'packages/session/session-persistence/src/index.ts','packages/session/session-persistence-jsonl/src/index.ts',
 'packages/core/agent/src/index.ts','packages/core/agent/src/types.ts','packages/core/agent/src/projection.ts',
 'packages/core/session/src/index.ts','packages/core/session/src/types.ts',
 'packages/core/tools/src/index.ts','packages/core/tools/src/types.ts',
 'snapshots/session/todo-write/session.v4.jsonl',
 '.agents/notes/archived/architecture/2026-07-20-todo-event-ownership.md',
 '.agents/notes/archived/feature/2026-07-28-todo-plan-clears-on-next-turn.md'
)
$entries50 = foreach ($path50 in $paths50) {
 $entry50=$tree50.tree | Where-Object path -CEQ $path50
 if (-not $entry50) { throw "Missing upstream $path50" }
 [pscustomobject]@{path=$path50;sha=$entry50.sha;root=$root50}
}
$manifest50 = $entries50 | ForEach-Object -ThrottleLimit 5 -Parallel {
 $ErrorActionPreference='Stop'
 $item50=$_
 $file50=Join-Path $item50.root ($item50.path.Replace('/','__'))
 $url50="https://raw.githubusercontent.com/deepseek-ai/deepseek-harness/571902028a75c64c1d767f4ae5fd099485f0ddf0/$($item50.path)"
 if (-not (Test-Path -LiteralPath $file50)) { Invoke-WebRequest $url50 -OutFile $file50 -TimeoutSec 30 }
 $bytes50=[IO.File]::ReadAllBytes($file50)
 $header50=[Text.Encoding]::UTF8.GetBytes("blob $($bytes50.Length)`0")
 $blob50=[Convert]::ToHexString([Security.Cryptography.SHA1]::HashData([byte[]]($header50+$bytes50))).ToLowerInvariant()
 if ($blob50 -cne $item50.sha) { throw "Upstream blob mismatch $($item50.path)" }
 [ordered]@{repo='deepseek-ai/deepseek-harness';commit='571902028a75c64c1d767f4ae5fd099485f0ddf0';path=$item50.path;file=[IO.Path]::GetFileName($file50);gitBlob=$blob50;sha256=(Get-FileHash $file50 -Algorithm SHA256).Hash.ToLowerInvariant();bytes=$bytes50.Length;url=$url50}
}
if (@($manifest50).Count -ne $paths50.Count) { throw 'Incomplete upstream evidence' }
$manifest50 | Sort-Object path | ConvertTo-Json -Depth 5 | Set-Content (Join-Path $root50 'sources.json') -Encoding utf8NoBOM
Write-Output "Verified $($manifest50.Count) upstream source blobs."
