"""Native secure file-open/CAS process evidence; never runs a provider or public socket."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import zipfile


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("jar", type=Path)
    parser.add_argument("test_classes", type=Path)
    parser.add_argument("evidence", type=Path)
    args = parser.parse_args()
    args.evidence.mkdir(parents=True, exist_ok=True)
    root = Path(tempfile.mkdtemp(prefix="jimu-workspace-it-"))
    receipt = {"jarSha256": hashlib.sha256(args.jar.read_bytes()).hexdigest(), "root": str(root)}
    exit_code = 1
    try:
        with zipfile.ZipFile(args.jar) as jar:
            for name in jar.namelist():
                if name.startswith("BOOT-INF/classes/") or name.startswith("BOOT-INF/lib/"):
                    jar.extract(name, root)
        shutil.copytree(args.test_classes / "io/github/lovexiaopeng/jimu/adapters/storage",
                        root / "test/io/github/lovexiaopeng/jimu/adapters/storage")
        java = os.environ.get("JIMU_IT_JAVA") or shutil.which("java")
        version = subprocess.run([java, "-version"], text=True, capture_output=True, check=True).stderr
        if 'version "21.' not in version:
            packages = root / "jdk"
            packages.mkdir()
            subprocess.run(["apt-get", "-o", "Acquire::Retries=2", "download", "openjdk-21-jre-headless=21.0.12.1+1-1~24.04.4"], cwd=packages, check=True)
            package = next(packages.glob("*.deb"))
            subprocess.run(["dpkg-deb", "-x", str(package), str(packages / "unpacked")], check=True)
            for link in (packages / "unpacked/usr/lib/jvm").rglob("*"):
                if link.is_symlink() and os.readlink(link).startswith("/etc/java-21-openjdk/"):
                    target = packages / "unpacked" / os.readlink(link).lstrip("/")
                    link.unlink(); link.symlink_to(target)
            java = str(next((packages / "unpacked/usr/lib/jvm").glob("*/bin/java")))
        workspace = root / "workspace"
        workspace.mkdir(mode=0o700)
        receipt["filesystem"] = subprocess.run(["findmnt", "-n", "-o", "FSTYPE", "-T", str(root)], capture_output=True, text=True, check=True).stdout.strip()
        receipt["java"] = subprocess.run([java, "-version"], text=True, capture_output=True, check=True).stderr
        command = [java, "-Xmx768m", "-cp", ":".join(str(root / p) for p in ("test", "BOOT-INF/classes", "BOOT-INF/lib/*")),
                   "io.github.lovexiaopeng.jimu.adapters.storage.WorkspaceScenario", str(workspace), str(args.evidence.resolve())]
        receipt["command"] = command
        with (args.evidence / "workspace.log").open("w") as log:
            exit_code = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, check=False).returncode
        receipt["exit"] = exit_code
        if exit_code:
            print((args.evidence / "workspace.log").read_text(), flush=True)
    finally:
        shutil.rmtree(root)
        receipt["nativeRootRemoved"] = not root.exists()
        (args.evidence / "cleanup.json").write_text(json.dumps(receipt, indent=2))
    return exit_code


if __name__ == "__main__":
    raise SystemExit(main())
