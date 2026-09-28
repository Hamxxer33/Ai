#!/usr/bin/env python3
"""Writes the KVEC0001 index read by io.kestrel.engine.vector.VectorIndex.

Layout (little-endian): magic "KVEC0001", u32 dim, u32 nlist, u64 n, u32 flags, u32 reserved,
f32 centroids[nlist][dim], u64 offsets[nlist+1], u32 ids[n], u8 codes[n][dim/8] (sign bits,
MSB first), f32 scales[n], i8 vecs[n][dim]. Vectors are grouped by IVF list.
"""
from __future__ import annotations

import struct

import numpy as np


def apply_transform(v: np.ndarray, dim: int, transform: str) -> np.ndarray:
    """Matryoshka truncation. 'layernorm' = nomic-embed v1.5 recipe (layer norm, truncate, L2)."""
    v = v.astype(np.float32)
    if transform == "layernorm":
        mu = v.mean(axis=1, keepdims=True)
        sd = v.std(axis=1, keepdims=True) + 1e-5
        v = (v - mu) / sd
    v = v[:, :dim]
    return v / (np.linalg.norm(v, axis=1, keepdims=True) + 1e-12)


def kmeans(x: np.ndarray, k: int, iters: int = 12, sample: int = 200_000, seed: int = 0) -> np.ndarray:
    rng = np.random.default_rng(seed)
    s = x[rng.choice(len(x), size=min(sample, len(x)), replace=False)]
    c = s[rng.choice(len(s), size=k, replace=False)].copy()
    for _ in range(iters):
        a = assign(s, c)
        for j in range(k):
            m = s[a == j]
            if len(m):
                c[j] = m.mean(axis=0)
            else:
                c[j] = s[rng.integers(len(s))]
        c /= np.linalg.norm(c, axis=1, keepdims=True) + 1e-12
    return c


def assign(x: np.ndarray, c: np.ndarray, block: int = 65536) -> np.ndarray:
    out = np.empty(len(x), dtype=np.int32)
    for i in range(0, len(x), block):
        out[i:i + block] = np.argmax(x[i:i + block] @ c.T, axis=1)
    return out


def build_kvec(vecs: np.ndarray, ids: np.ndarray, path: str, nlist: int | None = None) -> None:
    n, dim = vecs.shape
    assert dim % 8 == 0
    vecs = vecs / (np.linalg.norm(vecs, axis=1, keepdims=True) + 1e-12)
    if nlist is None:
        nlist = int(max(1, min(65536, round(2 * np.sqrt(n)))))
    nlist = min(nlist, n)
    cents = kmeans(vecs, nlist).astype(np.float32)
    lists = assign(vecs, cents)
    order = np.argsort(lists, kind="stable")
    counts = np.bincount(lists, minlength=nlist)
    offsets = np.zeros(nlist + 1, dtype=np.uint64)
    offsets[1:] = np.cumsum(counts)
    v = vecs[order]
    codes = np.packbits(v > 0, axis=1, bitorder="big")
    scales = (np.abs(v).max(axis=1) / 127.0).astype(np.float32) + 1e-12
    q = np.clip(np.round(v / scales[:, None]), -127, 127).astype(np.int8)
    with open(path, "wb") as f:
        f.write(b"KVEC0001")
        f.write(struct.pack("<IIQII", dim, nlist, n, 0, 0))
        f.write(cents.astype("<f4").tobytes())
        f.write(offsets.astype("<u8").tobytes())
        f.write(ids[order].astype("<u4").tobytes())
        f.write(codes.tobytes())
        f.write(scales.astype("<f4").tobytes())
        f.write(q.tobytes())
    print(f"kvec: n={n} dim={dim} nlist={nlist} -> {path}")
