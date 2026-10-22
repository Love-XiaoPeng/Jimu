"""Validate source bytes and package only reviewable architecture artifacts."""
import hashlib
import json
from datetime import datetime, timezone
from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED

ROOT = Path(__file__).resolve().parent
read = lambda p: json.loads(p.read_text(encoding="utf-8-sig"))
for item in read(ROOT / "evidence/sources.json"):
    data = (ROOT / "evidence" / item["file"]).read_bytes()
    assert len(data) == item["bytes"]
    assert hashlib.sha256(data).hexdigest() == item["sha256"]
    assert hashlib.sha1(b"blob " + str(len(data)).encode("ascii") + b"\0" + data).hexdigest() == item["gitBlob"]
for item in read(ROOT / "contracts/schema-index.json")["files"]:
    assert hashlib.sha256((ROOT / "contracts" / item["path"]).read_bytes()).hexdigest() == item["sha256"]
assert read(ROOT / "contracts/catalog.json")["schemaDigest"] == hashlib.sha256((ROOT / "contracts/schema-index.json").read_bytes()).hexdigest()
files = sorted(p for p in ROOT.rglob("*") if p.is_file()
               and "classes" not in p.relative_to(ROOT).parts
               and "__pycache__" not in p.relative_to(ROOT).parts
               and p.name not in ("classpath-local.txt", "PACKAGE-MANIFEST.json"))
manifest = {
    "version": 1, "createdAt": datetime.now(timezone.utc).isoformat(),
    "scope": "XIAO-49 architecture handoff only; no product implementation or acceptance",
    "jimuCommit": "0e630f28e39c157788886694e2b16dc0831bb571",
    "jimuTree": "8850a9d11f610257d87962585221b91a6bc19948",
    "upstreamCommit": "571902028a75c64c1d767f4ae5fd099485f0ddf0",
    "files": [{"path": p.relative_to(ROOT).as_posix(), "bytes": p.stat().st_size,
               "sha256": hashlib.sha256(p.read_bytes()).hexdigest()} for p in files]
}
manifest_path = ROOT / "PACKAGE-MANIFEST.json"
manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
archive = ROOT.with_suffix(".zip")
with ZipFile(archive, "w", ZIP_DEFLATED) as z:
    for file in [*files, manifest_path]:
        z.write(file, ROOT.name + "/" + file.relative_to(ROOT).as_posix())
with ZipFile(archive) as z:
    assert z.testzip() is None
    for entry in manifest["files"]:
        data = z.read(ROOT.name + "/" + entry["path"])
        assert len(data) == entry["bytes"] and hashlib.sha256(data).hexdigest() == entry["sha256"]
receipt = {"archive": archive.name, "bytes": archive.stat().st_size,
           "sha256": hashlib.sha256(archive.read_bytes()).hexdigest(),
           "filesIncludingManifest": len(files) + 1, "sourceBlobsChecked": len(read(ROOT / "evidence/sources.json"))}
ROOT.with_suffix(".package-receipt.json").write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8")
print(json.dumps(receipt, indent=2))
