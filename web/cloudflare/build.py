"""Build the independent Workers Static Assets project from the Safari site."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
import tempfile
from urllib.parse import urlsplit
import zipfile

HERE = Path(__file__).resolve().parent
WEB = HERE.parent
ROOT = WEB.parent


def checked_origin(value):
    if not value:
        return ""
    url = urlsplit(value)
    if (not re.fullmatch(r"https://(?:[a-zA-Z0-9.-]+|\[[a-fA-F0-9:]+\])/?", value)
            or url.scheme != "https" or not url.hostname or url.username is not None or url.password is not None
            or url.port is not None or url.path not in ("", "/") or url.query or url.fragment):
        raise ValueError("--upstream requires one HTTPS origin without credentials, port, path or query")
    return "https://" + url.netloc.lower()


def package(source, output, upstream):
    if not source.is_dir() or source.is_symlink():
        raise ValueError("--site requires an existing regular build directory")
    expected = {p.relative_to(WEB / "public").as_posix() for p in (WEB / "public").rglob("*") if p.is_file()}
    expected.add("version.json")
    actual = {p.relative_to(source).as_posix() for p in source.rglob("*") if not p.is_dir()}
    if any(p.is_symlink() for p in source.rglob("*")) or expected != actual:
        raise ValueError("Static site has missing, linked, or unexpected files; rebuild it with web/build.py")
    assets = {"site/" + name: (source / name).read_bytes() for name in sorted(expected)}
    version = json.loads(assets["site/version.json"])
    head = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    if version.get("mode") != "static" or version.get("commit") != head:
        raise ValueError("Static site must come from web/build.py at the current commit")
    api_origin = checked_origin(upstream)
    version.update(mode="server", api=True)
    version.pop("publicApiOrigin", None)
    if api_origin:
        version["publicApiOrigin"] = api_origin
    assets["site/version.json"] = (json.dumps(version, ensure_ascii=False) + "\n").encode()
    config = json.loads((HERE / "wrangler.json").read_text(encoding="utf8"))
    config["vars"]["UPSTREAM_ORIGIN"] = api_origin
    assets.update({
        "wrangler.json": (json.dumps(config, indent=2) + "\n").encode(),
        "worker.mjs": (HERE / "worker.mjs").read_bytes(),
        "README.md": (HERE / "README.md").read_bytes(),
        "LICENSE": (ROOT / "LICENSE").read_bytes(),
    })
    assets["SHA256SUMS"] = "".join(
        hashlib.sha256(data).hexdigest() + "  " + name + "\n" for name, data in sorted(assets.items())
    ).encode()
    project = output / "worker"
    if project.is_symlink():
        raise ValueError("Output project is a symlink")
    project.mkdir(parents=True, exist_ok=True)
    for existing in project.rglob("*"):
        if existing.is_symlink() or (existing.is_file() and existing.relative_to(project).as_posix() not in assets):
            raise ValueError("Output project has unexpected files; choose a fresh --output directory")
    for name, blob in assets.items():
        target = project / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(blob)
    archive = output / "campus-reader-cloudflare-worker.zip"
    with tempfile.NamedTemporaryFile(dir=output, delete=False) as staged:
        tmp = Path(staged.name)
    try:
        with zipfile.ZipFile(tmp, "w") as z:
            for name, blob in sorted(assets.items()):
                entry = zipfile.ZipInfo(name)
                entry.external_attr = 0o100644 << 16
                z.writestr(entry, blob, compress_type=zipfile.ZIP_DEFLATED)
        tmp.replace(archive)
    finally:
        tmp.unlink(missing_ok=True)
    print(json.dumps({"version": version["version"], "commit": head, "dirty": version.get("dirty"),
                      "project": str(project), "archive": str(archive),
                      "archiveSHA256": hashlib.sha256(archive.read_bytes()).hexdigest()}))


def main():
    cli = argparse.ArgumentParser(description=__doc__)
    cli.add_argument("--site", type=Path, help="Existing site output of web/build.py at this commit")
    cli.add_argument("--output", type=Path, default=ROOT / "dist/cloudflare")
    cli.add_argument("--upstream", default="", help="Fixed HTTPS origin for browser API calls and the compatibility proxy")
    args = cli.parse_args()
    upstream = checked_origin(args.upstream)
    if args.site:
        package(args.site.resolve(), args.output.resolve(), upstream)
    else:
        with tempfile.TemporaryDirectory() as tmp:
            target = Path(tmp) / "safari"
            subprocess.run([sys.executable, str(WEB / "build.py"), "--output", str(target)], check=True, cwd=ROOT)
            package(target / "site", args.output.resolve(), upstream)


if __name__ == "__main__":
    main()
