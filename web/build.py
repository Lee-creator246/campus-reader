"""Build the static Safari site and the server ZIP without third-party packages."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import tempfile
import zipfile

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent


def git(*args):
    return subprocess.check_output(["git", *args], cwd=ROOT, text=True).strip()


def build(output):
    source = HERE / "public"
    files = {}
    for file in sorted(source.rglob("*")):
        if file.is_symlink():
            raise ValueError(f"Unexpected symlink in static site: {file.name}")
        if file.is_dir():
            continue
        name = file.relative_to(source).as_posix()
        if file.suffix not in {".html", ".css", ".js", ".json", ".svg", ".webmanifest", ".txt"} or name == "version.json":
            raise ValueError(f"Unexpected static asset: {name}")
        files[name] = file.read_bytes()
    if not files.get("index.html"):
        raise ValueError("Missing web/public/index.html")
    match = re.search(r"MARKETING_VERSION:\s*'([\w.]+)'", (ROOT / "ios/project.yml").read_text(encoding="utf8"))
    if not match:
        raise ValueError("Missing version in ios/project.yml")
    common = {
        "version": match[1],
        "commit": git("rev-parse", "HEAD"),
        "dirty": bool(git("status", "--porcelain", "--", "web", "ios/project.yml")),
        "feedbackUrl": "https://github.com/Lee-creator246/campus-reader/issues/new",
    }
    def metadata(mode):
        return (json.dumps({**common, "mode": mode, "api": mode == "server"}) + "\n").encode()
    output = output.resolve()
    site = output / "site"
    if site.is_symlink():
        raise ValueError("Output site must not be a symlink")
    site.mkdir(parents=True, exist_ok=True)
    files["version.json"] = metadata("static")
    for current in site.rglob("*"):
        if current.is_symlink() or (current.is_file() and current.relative_to(site).as_posix() not in files):
            raise ValueError("Output site has unexpected content; use a fresh --output directory")
    for name, data in files.items():
        target = site / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
    archive_files = {"site/" + k: v for k, v in files.items()}
    archive_files["site/version.json"] = metadata("server")
    for name in ("Caddyfile", "compose.yaml", "server.mjs", "push.mjs"):
        archive_files[name] = (HERE / "server" / name).read_bytes()
    for name in ("probe_portal.py", "DEPLOY.md"):
        archive_files[name] = (HERE / name).read_bytes()
    archive_files["LICENSE"] = (ROOT / "LICENSE").read_bytes()
    archive_files["data/README.txt"] = b"Optional public-notices.json; see DEPLOY.md. Only publish material cleared for public anonymous access.\n"
    archive_files["SHA256SUMS"] = "".join(
        f"{hashlib.sha256(data).hexdigest()}  {name}\n"
        for name, data in sorted(archive_files.items())
    ).encode()
    with tempfile.NamedTemporaryFile(dir=output, delete=False) as temp:
        staging = Path(temp.name)
    archive = output / "campus-reader-safari-server.zip"
    try:
        with zipfile.ZipFile(staging, "w") as packed:
            for name, data in sorted(archive_files.items()):
                info = zipfile.ZipInfo(name)
                info.external_attr = 0o100644 << 16
                packed.writestr(info, data, compress_type=zipfile.ZIP_DEFLATED)
        staging.replace(archive)
    finally:
        staging.unlink(missing_ok=True)
    print(json.dumps({**common, "site": str(site), "serverArchive": str(archive),
                      "archiveSHA256": hashlib.sha256(archive.read_bytes()).hexdigest()}))


if __name__ == "__main__":
    cli = argparse.ArgumentParser(description=__doc__)
    cli.add_argument("--output", type=Path, default=ROOT / "dist/safari")
    build(cli.parse_args().output)
