#!/usr/bin/env python3
"""Embed the chunks of a knowledge pack with a local llama.cpp server, then build vectors.kvec.

The same GGUF embedding model must be used on the phone for queries; the model id, prefixes,
dimension and transform are written into manifest.json so the app encodes queries identically.

1. Start a local embedding server (loopback only; this is a build-time tool on your PC):
     llama-server -m nomic-embed-text-v1.5.Q8_0.gguf --embeddings --pooling mean \
         -c 8192 -b 8192 -ub 8192 --parallel 8 --host 127.0.0.1 --port 8088
2. Embed (resumable) and build the index:
     python tools/embed_pack.py --pack packs/enwiki --server http://127.0.0.1:8088 \
         --model nomic-embed-text-v1.5 --model-file nomic-embed-text-v1.5.Q8_0.gguf \
         --query-prefix "search_query: " --doc-prefix "search_document: " \
         --dim 256 --transform layernorm --scope lead:2

Scopes: all | lead:N (first N chunks of every article) | top:M (all chunks of the M highest-prior
articles) | lead:N,top:M (union).
"""
from __future__ import annotations

import argparse
import concurrent.futures as cf
import json
import os
import sqlite3
import sys
import time
import zlib

import numpy as np
import requests

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from build_vectors import build_kvec, apply_transform  # noqa: E402


def decoder(con):
    row = con.execute("SELECT value FROM meta WHERE key='zdict'").fetchone()
    zdict = row[0] if row and row[0] else None

    def dec(b):
        if zdict is None:
            return b.decode("utf-8") if isinstance(b, bytes) else b
        d = zlib.decompressobj(15, zdict)
        return (d.decompress(b) + d.flush()).decode("utf-8")
    return dec


def select_ids(con, scope: str):
    ids = set()
    for part in scope.split(","):
        part = part.strip()
        if part == "all":
            return [r[0] for r in con.execute("SELECT id FROM chunks ORDER BY id")]
        kind, _, n = part.partition(":")
        n = int(n)
        if kind == "lead":
            ids.update(r[0] for r in con.execute("SELECT id FROM chunks WHERE ord < ?", (n,)))
        elif kind == "top":
            docs = [r[0] for r in con.execute("SELECT id FROM docs ORDER BY prior DESC, id LIMIT ?", (n,))]
            for i in range(0, len(docs), 500):
                part_ids = docs[i:i + 500]
                q = ",".join("?" * len(part_ids))
                ids.update(r[0] for r in con.execute(f"SELECT id FROM chunks WHERE doc_id IN ({q})", part_ids))
        else:
            raise SystemExit(f"bad scope {part}")
    return sorted(ids)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--pack", required=True)
    ap.add_argument("--server", default="http://127.0.0.1:8088")
    ap.add_argument("--model", required=True, help="model id recorded in the manifest")
    ap.add_argument("--model-file", default=None, help="GGUF file name the app should load")
    ap.add_argument("--query-prefix", default="")
    ap.add_argument("--doc-prefix", default="")
    ap.add_argument("--dim", type=int, default=256)
    ap.add_argument("--transform", choices=["none", "layernorm"], default="none",
                    help="Matryoshka recipe: nomic v1.5 uses layernorm before truncation")
    ap.add_argument("--scope", default="lead:2")
    ap.add_argument("--max-chars", type=int, default=1200)
    ap.add_argument("--batch", type=int, default=32)
    ap.add_argument("--workers", type=int, default=2)
    ap.add_argument("--nlist", type=int, default=0, help="IVF lists (default sqrt(n)*2)")
    args = ap.parse_args()

    db = os.path.join(args.pack, "corpus.sqlite")
    con = sqlite3.connect(f"file:{db}?mode=ro", uri=True)
    dec = decoder(con)
    ids = select_ids(con, args.scope)
    n = len(ids)
    print(f"{n} chunks to embed (scope {args.scope})", flush=True)

    work = os.path.join(args.pack, "embed-work")
    os.makedirs(work, exist_ok=True)
    np.save(os.path.join(work, "ids.npy"), np.array(ids, dtype=np.uint32))
    emb_path = os.path.join(work, f"emb_{args.dim}.f16")
    done_path = os.path.join(work, "done.txt")
    done = int(open(done_path).read()) if os.path.exists(done_path) else 0
    mm = np.memmap(emb_path, dtype=np.float16, mode="r+" if os.path.exists(emb_path) else "w+", shape=(n, args.dim))

    sess = requests.Session()

    def embed(texts):
        for attempt in range(5):
            try:
                r = sess.post(args.server + "/v1/embeddings", json={"input": texts, "model": args.model}, timeout=600)
                r.raise_for_status()
                data = sorted(r.json()["data"], key=lambda d: d["index"])
                return np.array([d["embedding"] for d in data], dtype=np.float32)
            except Exception as e:  # server busy or restarting
                print("retry:", e, flush=True)
                time.sleep(2 * (attempt + 1))
        raise SystemExit("embedding server failed")

    def load_texts(batch_ids):
        q = ",".join("?" * len(batch_ids))
        rows = {r[0]: r for r in con.execute(
            f"SELECT c.id, d.title, c.section, c.text FROM chunks c JOIN docs d ON d.id=c.doc_id WHERE c.id IN ({q})", batch_ids)}
        out = []
        for i in batch_ids:
            _, title, section, text = rows[i]
            head = title + (f" — {section}" if section else "")
            out.append(args.doc_prefix + head + "\n" + dec(text)[: args.max_chars])
        return out

    t0 = time.time()
    step = args.batch * args.workers
    with cf.ThreadPoolExecutor(args.workers) as ex:
        pos = done
        while pos < n:
            spans = [(s, min(s + args.batch, n)) for s in range(pos, min(pos + step, n), args.batch)]
            texts = [load_texts(ids[a:b]) for a, b in spans]
            results = list(ex.map(embed, texts))
            for (a, b), v in zip(spans, results):
                mm[a:b] = apply_transform(v, args.dim, args.transform).astype(np.float16)
            pos = spans[-1][1]
            if (pos // step) % 20 == 0 or pos >= n:
                mm.flush()
                with open(done_path, "w") as f:
                    f.write(str(pos))
                rate = (pos - done) / max(1e-9, time.time() - t0)
                print(f"{pos}/{n} ({rate:.1f}/s, eta {(n - pos) / max(rate, 1e-9) / 60:.0f} min)", flush=True)
    mm.flush()
    with open(done_path, "w") as f:
        f.write(str(n))

    vecs = np.asarray(np.memmap(emb_path, dtype=np.float16, mode="r", shape=(n, args.dim)), dtype=np.float32)
    out = os.path.join(args.pack, "vectors.kvec")
    build_kvec(vecs, np.array(ids, dtype=np.uint32), out, nlist=args.nlist or None)

    man_path = os.path.join(args.pack, "manifest.json")
    man = json.load(open(man_path))
    man["embedding"] = {
        "model": args.model, "file": args.model_file, "dim": args.dim, "full_dim": int(vecs.shape[1]),
        "query_prefix": args.query_prefix, "doc_prefix": args.doc_prefix, "vectors": "vectors.kvec",
        "transform": args.transform, "scope": args.scope, "count": n,
    }
    man.setdefault("files", {})["vectors.kvec"] = {"bytes": os.path.getsize(out), "sha256": ""}
    json.dump(man, open(man_path, "w"), indent=2)
    print("done", out, os.path.getsize(out) / 1e6, "MB")


if __name__ == "__main__":
    main()
