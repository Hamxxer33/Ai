#!/usr/bin/env python3
"""Download the GGUF models used by Kestrel, verify their SHA-256, and write models/models.lock.json.

This is a build-time tool for your computer; the phone never downloads anything.

Sources:
  * Docker Hub model artifacts (ai/<model>:<tag>) — OCI registry, content-addressed: the layer
    digest *is* the SHA-256 of the GGUF file, so the pinned digests below verify the download.
  * Hugging Face (any repo/file), pinned by the SHA-256 you record after the first download.

  python tools/fetch_models.py --out models                   # default set (fast, strong, embed)
  python tools/fetch_models.py --out models --with gemma4-e4b  # add optional models by key
  python tools/fetch_models.py --list

Deep-tier MoE files (12-14 GB) are listed in docs/MODELS.md with their Hugging Face sources;
download them with `--hf repo/file` (the file is verified against --sha256 when given).
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import sys
import urllib.request

# key: (docker repo, tag, file name, layer sha256 of the GGUF, role, licence)
CATALOG = {
    "qwen3.5-2b": ("ai/qwen3.5", "2b-q4_K_M", "Qwen3.5-2B-Q4_K_M.gguf",
                   "aaf42c8b7c3cab2bf3d69c355048d4a0ee9973d48f16c731c0520ee914699223", "fast", "Apache-2.0"),
    "qwen3.5-4b": ("ai/qwen3.5", "4b-q4_K_M", "Qwen3.5-4B-Q4_K_M.gguf",
                   "00fe7986ff5f6b463e62455821146049db6f9313603938a70800d1fb69ef11a4", "strong", "Apache-2.0"),
    "gemma4-e4b": ("ai/gemma4", "e4b-q4_K_M", "gemma-4-E4B-it-Q4_K_M.gguf",
                   "85a896a047553e842f25297ee5b031d64ff30147d9c4af17b1e4b394cd1fab87", "strong", "Apache-2.0"),
    "nomic-embed": ("ai/nomic-embed-text-v1.5", "latest", "nomic-embed-text-v1.5.f16.gguf",
                    "f7af6f66802f4df86eda10fe9bbcfc75c39562bed48ef6ace719a251cf1c2fdb", "embed", "Apache-2.0"),
    "embeddinggemma": ("ai/embeddinggemma", "q8_0", "embeddinggemma-300M-Q8_0.gguf",
                       "a0f7b4e13c397a6e1b32c2de75b1f65a14c92ec524d5f674d94a4290a1c4969b", "embed", "Gemma Terms of Use"),
}
DEFAULT = ["qwen3.5-2b", "qwen3.5-4b", "nomic-embed"]


def token(repo):
    u = f"https://auth.docker.io/token?service=registry.docker.io&scope=repository:{repo}:pull"
    return json.load(urllib.request.urlopen(u))["token"]


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for b in iter(lambda: f.read(1 << 22), b""):
            h.update(b)
    return h.hexdigest()


def download(url, dest, headers=None):
    req = urllib.request.Request(url, headers=headers or {})
    tmp = dest + ".part"
    with urllib.request.urlopen(req) as r, open(tmp, "wb") as f:
        total = int(r.headers.get("Content-Length", 0))
        got, shown = 0, -1
        while True:
            b = r.read(1 << 22)
            if not b:
                break
            f.write(b)
            got += len(b)
            if total and int(20 * got / total) != shown:
                shown = int(20 * got / total)
                print(f"\r  {got / 1e9:.2f}/{total / 1e9:.2f} GB", end="", flush=True)
    print()
    os.replace(tmp, dest)


def fetch_docker(key, out):
    repo, tag, name, digest, role, lic = CATALOG[key]
    dest = os.path.join(out, name)
    if os.path.exists(dest) and sha256(dest) == digest:
        print(f"{name}: present, verified")
    else:
        print(f"{name}: downloading {repo}:{tag}")
        download(f"https://registry-1.docker.io/v2/{repo}/blobs/sha256:{digest}", dest,
                 {"Authorization": "Bearer " + token(repo)})
        got = sha256(dest)
        if got != digest:
            os.remove(dest)
            sys.exit(f"{name}: SHA-256 mismatch ({got})")
        print(f"{name}: verified {digest}")
    return {"file": name, "sha256": digest, "bytes": os.path.getsize(dest), "role": role, "license": lic,
            "source": f"docker.io/{repo}:{tag}"}


def fetch_hf(spec, out, expect):
    repo, _, path = spec.partition("/")
    repo2, _, fpath = path.partition("/")
    repo = f"{repo}/{repo2}"
    name = os.path.basename(fpath)
    dest = os.path.join(out, name)
    if not os.path.exists(dest):
        download(f"https://huggingface.co/{repo}/resolve/main/{fpath}", dest)
    got = sha256(dest)
    if expect and got != expect:
        sys.exit(f"{name}: SHA-256 mismatch ({got} != {expect})")
    print(f"{name}: sha256 {got}")
    return {"file": name, "sha256": got, "bytes": os.path.getsize(dest), "role": "deep", "source": f"hf.co/{repo}/{fpath}"}


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--out", default="models")
    ap.add_argument("--with", dest="extra", nargs="*", default=[])
    ap.add_argument("--only", nargs="*")
    ap.add_argument("--hf", help="owner/repo/path/in/repo.gguf")
    ap.add_argument("--sha256")
    ap.add_argument("--list", action="store_true")
    a = ap.parse_args()
    if a.list:
        for k, v in CATALOG.items():
            print(f"{k:16} {v[4]:7} {v[2]:36} {v[0]}:{v[1]}  ({v[5]})")
        return
    os.makedirs(a.out, exist_ok=True)
    lock_path = os.path.join(a.out, "models.lock.json")
    lock = json.load(open(lock_path)) if os.path.exists(lock_path) else {}
    keys = a.only if a.only is not None else DEFAULT + a.extra
    for k in keys:
        lock[k] = fetch_docker(k, a.out)
    if a.hf:
        lock[os.path.basename(a.hf)] = fetch_hf(a.hf, a.out, a.sha256)
    json.dump(lock, open(lock_path, "w"), indent=2)
    print(f"wrote {lock_path}")


if __name__ == "__main__":
    main()
