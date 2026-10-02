#!/usr/bin/env python3
"""Prepare an ARM64 QEMU/musl runtime from official Alpine APK packages.

Run on Linux with Python 3 and patchelf. No package install scripts are executed.
Only QEMU and its ELF library dependencies are bundled. QEMU is GPL-2.0;
package origin/version/license/source commit are preserved in the manifest.
"""
import argparse
import concurrent.futures
import hashlib
import json
import pathlib
import shutil
import subprocess
import tarfile
import urllib.request

BASE = "https://dl-cdn.alpinelinux.org/alpine/v3.22"


def fetch(url, path):
    if not path.exists():
        with urllib.request.urlopen(url, timeout=90) as src, path.open("wb") as dst:
            shutil.copyfileobj(src, dst)
    return path


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--cache", type=pathlib.Path, required=True)
    parser.add_argument("--out", type=pathlib.Path, required=True)
    args = parser.parse_args()
    args.cache.mkdir(parents=True, exist_ok=True)
    args.out.mkdir(parents=True, exist_ok=True)
    packages, providers = {}, {}
    for repo in ("main", "community"):
        archive = fetch(f"{BASE}/{repo}/aarch64/APKINDEX.tar.gz", args.cache / f"APKINDEX-{repo}.tar.gz")
        with tarfile.open(archive) as tar:
            for block in tar.extractfile("APKINDEX").read().decode().split("\n\n"):
                item = dict(line.split(":", 1) for line in block.splitlines() if ":" in line)
                if "P" not in item:
                    continue
                item["repo"] = repo
                packages[item["P"]] = item
                for provided in item.get("p", "").split():
                    providers[provided.split("=", 1)[0]] = item["P"]

    selected = {}

    def include(name):
        if name in selected:
            return
        package = packages[name]
        selected[name] = package
        for dep in package.get("D", "").split():
            if dep.startswith("so:"):
                include(providers[dep.split("=", 1)[0]])

    include("qemu-system-aarch64")
    records = []

    def download(item):
        name = f"{item['P']}-{item['V']}.apk"
        url = f"{BASE}/{item['repo']}/aarch64/{name}"
        path = fetch(url, args.cache / name)
        return item, path, url

    root = args.cache / "root"
    root.mkdir(exist_ok=True)
    with concurrent.futures.ThreadPoolExecutor(max_workers=6) as pool:
        for item, archive, url in pool.map(download, selected.values()):
            records.append({"name": item["P"], "version": item["V"], "license": item["L"],
                            "origin": item.get("o"), "source_commit": item.get("c"),
                            "url": url, "sha256": hashlib.sha256(archive.read_bytes()).hexdigest()})
            with tarfile.open(archive, "r:gz", ignore_zeros=True) as tar:
                for member in tar:
                    path = pathlib.PurePosixPath(member.name)
                    if path.is_absolute() or ".." in path.parts:
                        raise ValueError(f"Unsafe package path: {member.name}")
                    if not member.isfile() or not (member.name.startswith(("usr/lib/", "lib/")) or member.name == "usr/bin/qemu-system-aarch64"):
                        continue
                    target = root / member.name
                    target.parent.mkdir(parents=True, exist_ok=True)
                    with tar.extractfile(member) as source, target.open("wb") as dest:
                        shutil.copyfileobj(source, dest)
                    target.chmod(0o755)

    binaries, sonames = [], {}
    for path in sorted(root.rglob("*")):
        if not path.is_file() or path.read_bytes()[:4] != b"\x7fELF":
            continue
        if path.name == "qemu-system-aarch64":
            new_name = "libqemu-system-aarch64.so"
        elif path.name.startswith("ld-musl-"):
            new_name = "libc.so"
            sonames["libc.musl-aarch64.so.1"] = new_name
        else:
            soname = subprocess.run(["patchelf", "--print-soname", str(path)], text=True, capture_output=True, check=True).stdout.strip()
            if not soname:
                continue
            new_name = soname.split(".so")[0] + ".so"
            sonames[soname] = new_name
        target = args.out / new_name
        shutil.copy2(path, target)
        binaries.append(target)

    for binary in binaries:
        if binary.name == "libc.so":
            continue  # The self-relocating musl loader must retain its original ELF layout.
        needed = subprocess.check_output(["patchelf", "--print-needed", str(binary)], text=True).splitlines()
        for dependency in needed:
            if dependency not in sonames:
                raise RuntimeError(f"Missing dependency {dependency} of {binary.name}")
            subprocess.run(["patchelf", "--replace-needed", dependency, sonames[dependency], str(binary)], check=True)
        subprocess.run(["patchelf", "--set-rpath", "$ORIGIN", str(binary)], check=True)
        binary.chmod(0o755)

    manifest = {"architecture": "aarch64", "alpine": "3.22", "packages": sorted(records, key=lambda p: p["name"]),
                "binaries": [{"name": p.name, "sha256": hashlib.sha256(p.read_bytes()).hexdigest()} for p in binaries]}
    (args.out.parent / "runtime-manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    print(f"Prepared {len(binaries)} ELF files from {len(records)} packages in {args.out}")


if __name__ == "__main__":
    main()
