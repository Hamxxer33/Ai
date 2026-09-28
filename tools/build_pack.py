#!/usr/bin/env python3
"""Build a Kestrel knowledge pack (corpus.sqlite + manifest.json) from an encyclopedia dump.

Inputs (--format):
  parquet       Hugging Face `wikimedia/wikipedia` shards (columns: id, url, title, text), e.g.
                20231101.en/train-*.parquet. Needs `pyarrow`.
  jsonl         one JSON object per line: {"title": ..., "text": ..., "url": optional}
  plain-titles  plain text where each article starts with a title line followed by its paragraphs
                (the legacy `wikipedia_multilingual/raw/en.all` dump used for development).

Output directory:
  corpus.sqlite   docs, chunks (zlib + preset dictionary), FTS5 index, titles, term_df
  manifest.json   pack metadata (counts, licence, codec, file checksums)

The build is deterministic for a given input and options (stable ordering, fixed dictionary
construction), so two builds of the same dump produce identical databases.

Example:
  python tools/build_pack.py --format parquet --input 'data/20231101.en/*.parquet' \
      --out packs/enwiki-20231101 --id enwiki-20231101 --name "English Wikipedia (2023-11-01)"
"""
from __future__ import annotations

import argparse
import collections
import glob
import hashlib
import json
import math
import os
import re
import sqlite3
import sys
import time
import unicodedata
import zlib

WORD = re.compile(r"[^\W_]+(?:['’][^\W\d_]+)?")
SENT_END = re.compile(r"(?<=[.!?])[\"')\]]*\s+(?=[A-Z0-9\"(])")
CODEC = "zlib-dict-v1"


# --------------------------------------------------------------------------- normalisation

def fold(s: str) -> str:
    s = unicodedata.normalize("NFD", s)
    return "".join(c for c in s if unicodedata.category(c) != "Mn").lower()


def norm_title(s: str) -> str:
    """Must match io.kestrel.engine.text.Text.normTitle."""
    out = []
    for m in WORD.finditer(fold(s.replace("_", " "))):
        w = m.group(0)
        q = min([i for i in (w.find("'"), w.find("’")) if i > 0], default=-1)
        out.append(w[:q] if q > 0 else w)
    return " ".join(out)


# --------------------------------------------------------------------------- readers

def read_plain_titles(paths, limit_bytes=None):
    """Articles separated only by title lines. A title is a short line without final
    punctuation whose next line is a paragraph that mentions the title's first word."""
    for path in paths:
        read = 0
        with open(path, encoding="utf-8", errors="replace") as f:
            prev = None
            title, paras = None, []
            for line in f:
                read += len(line)
                if limit_bytes and read > limit_bytes:
                    break
                line = line.rstrip("\n")
                if prev is not None:
                    if is_title(prev, line):
                        if title is not None:
                            yield title, paras, None
                        title, paras = prev, []
                    elif title is not None and prev.strip():
                        paras.append(prev)
                prev = line
            if prev is not None and title is not None and prev.strip():
                paras.append(prev)
            if title is not None:
                yield title, paras, None


TRAILING_FUNCTION_WORDS = {"is", "are", "was", "were", "of", "the", "a", "an", "and", "or", "by", "to", "as", "in", "on",
                           "at", "for", "with", "having", "that", "which", "from", "be", "has", "had", "its", "their"}


def is_title(line: str, nxt: str) -> bool:
    s = line.strip()
    if not s or len(s) > 90 or len(nxt) < 40 or len(s.split()) > 10:
        return False
    if s.count(",") > 1 or s.count("(") != s.count(")") or ";" in s or '"' in s:
        return False
    if s.split()[-1].lower() in TRAILING_FUNCTION_WORDS:
        return False
    if s[-1] in ".,:;!?" or not (s[0].isupper() or s[0].isdigit()):
        return False
    if s.startswith(("formula_", "]]", "|", "{")):
        return False
    head = fold(nxt[:250])
    first = norm_title(s).split(" ")
    key = next((w for w in first if len(w) > 2 and w not in ("the", "and", "list")), first[0] if first else "")
    if key and key in head:
        return True
    return bool(re.search(r"\b(is|was|are|were) (a|an|the)\b|\brefers? to\b|\bmay refer to\b", head))


def read_jsonl(paths):
    for path in paths:
        with open(path, encoding="utf-8") as f:
            for line in f:
                if line.strip():
                    o = json.loads(line)
                    yield o["title"], split_paragraphs(o["text"]), o.get("url")


def read_parquet(paths):
    import pyarrow.parquet as pq
    for path in paths:
        t = pq.ParquetFile(path)
        for batch in t.iter_batches(columns=["title", "text", "url"], batch_size=2048):
            d = batch.to_pydict()
            for title, text, url in zip(d["title"], d["text"], d["url"]):
                yield title, split_paragraphs(text), url


def split_paragraphs(text: str):
    return [p.strip() for p in re.split(r"\n\s*\n|\n", text) if p.strip()]


# --------------------------------------------------------------------------- chunking

SKIP_SECTIONS = {"references", "external links", "see also", "further reading", "notes", "bibliography",
                 "sources", "citations", "footnotes", "works cited"}


def is_heading(p: str) -> bool:
    return len(p) < 80 and not p.endswith((".", ":", "?", "!", ",", ";")) and len(p.split()) <= 10 and p[0].isupper()


def chunk_article(paras, target_words=150, max_words=230):
    """Yields (section, text). Paragraph-aligned chunks of ~target_words; long paragraphs are cut at
    sentence boundaries. Headings (short unpunctuated lines) become the section of later chunks."""
    section = ""
    buf, n = [], 0
    for p in paras:
        if is_heading(p):
            if buf:
                yield section, " ".join(buf)
                buf, n = [], 0
            section = p
            continue
        if section.lower() in SKIP_SECTIONS:
            continue
        words = len(p.split())
        if words > max_words:
            if buf:
                yield section, " ".join(buf)
                buf, n = [], 0
            sents = SENT_END.split(p)
            cur, cn = [], 0
            for s in sents:
                sw = len(s.split())
                if cn + sw > target_words and cur:
                    yield section, " ".join(cur)
                    cur, cn = [], 0
                cur.append(s)
                cn += sw
            if cur:
                yield section, " ".join(cur)
            continue
        if n + words > max_words and buf:
            yield section, " ".join(buf)
            buf, n = [], 0
        buf.append(p)
        n += words
        if n >= target_words:
            yield section, " ".join(buf)
            buf, n = [], 0
    if buf:
        yield section, " ".join(buf)


# --------------------------------------------------------------------------- compression dictionary

def build_zdict(samples, size=32768):
    """Preset dictionary: frequent word n-grams, most frequent last (zlib favours recent bytes)."""
    counts = collections.Counter()
    for s in samples:
        toks = s.split()
        for n in (3, 2):
            for i in range(0, max(0, len(toks) - n + 1)):
                counts[" ".join(toks[i:i + n])] += 1
    items = [k for k, c in counts.most_common(20000) if c > 3]
    out, total = [], 0
    for k in items:
        b = (k + " ").encode("utf-8")
        if total + len(b) > size:
            break
        out.append(b)
        total += len(b)
    return b"".join(reversed(out))


# --------------------------------------------------------------------------- build

SCHEMA = """
PRAGMA page_size = 4096;
CREATE TABLE meta(key TEXT PRIMARY KEY, value BLOB);
CREATE TABLE docs(id INTEGER PRIMARY KEY, title TEXT NOT NULL, first_chunk INTEGER NOT NULL, n_chunks INTEGER NOT NULL,
                  prior REAL NOT NULL DEFAULT 0, words INTEGER NOT NULL DEFAULT 0, url TEXT);
CREATE TABLE chunks(id INTEGER PRIMARY KEY, doc_id INTEGER NOT NULL, ord INTEGER NOT NULL, section TEXT, text BLOB NOT NULL);
CREATE TABLE titles(norm TEXT NOT NULL, doc_id INTEGER NOT NULL, kind INTEGER NOT NULL);
CREATE VIRTUAL TABLE chunks_fts USING fts5(title, section, body, content='', tokenize='porter unicode61 remove_diacritics 2');
"""


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--format", choices=["parquet", "jsonl", "plain-titles"], required=True)
    ap.add_argument("--input", nargs="+", required=True, help="files or glob patterns")
    ap.add_argument("--out", required=True)
    ap.add_argument("--id", required=True)
    ap.add_argument("--name", required=True)
    ap.add_argument("--version", default="1.0.0")
    ap.add_argument("--license", default="CC BY-SA 4.0 (Wikipedia text)")
    ap.add_argument("--source", default="")
    ap.add_argument("--url-template", default="https://en.wikipedia.org/wiki/{title}")
    ap.add_argument("--min-words", type=int, default=40, help="skip articles shorter than this")
    ap.add_argument("--max-docs", type=int, default=0)
    ap.add_argument("--limit-bytes", type=int, default=0, help="plain-titles: stop after this many bytes")
    ap.add_argument("--lead-only-after", type=int, default=0,
                    help="keep only the first 2 chunks of articles after the first N (0 = keep all)")
    ap.add_argument("--no-compress", action="store_true")
    args = ap.parse_args()

    paths = sorted(p for pat in args.input for p in glob.glob(pat))
    if not paths:
        sys.exit("no input files")
    os.makedirs(args.out, exist_ok=True)
    db_path = os.path.join(args.out, "corpus.sqlite")
    if os.path.exists(db_path):
        os.remove(db_path)
    con = sqlite3.connect(db_path)
    con.executescript(SCHEMA)
    con.execute("PRAGMA journal_mode = OFF")
    con.execute("PRAGMA synchronous = OFF")
    con.execute("PRAGMA cache_size = -1000000")

    reader = {"parquet": read_parquet, "jsonl": read_jsonl}.get(args.format)
    articles = reader(paths) if reader else read_plain_titles(paths, args.limit_bytes or None)

    t0 = time.time()
    zdict = None
    comp = None
    sample_buf = []
    pending = []  # chunks buffered until the dictionary exists
    doc_id = 0
    chunk_id = 0
    seen_titles = set()
    n_words_total = 0

    def compress(text: str) -> bytes:
        if args.no_compress:
            return text.encode("utf-8")
        c = zlib.compressobj(9, zlib.DEFLATED, 15, 9, zlib.Z_DEFAULT_STRATEGY, zdict)
        return c.compress(text.encode("utf-8")) + c.flush()

    def flush_rows(rows):
        con.executemany("INSERT INTO chunks(id, doc_id, ord, section, text) VALUES (?,?,?,?,?)",
                        [(cid, did, o, sec, compress(txt)) for cid, did, o, sec, txt, _ in rows])
        con.executemany("INSERT INTO chunks_fts(rowid, title, section, body) VALUES (?,?,?,?)",
                        [(cid, title, sec, txt) for cid, did, o, sec, txt, title in rows])

    rows = []
    for title, paras, url in articles:
        title = title.strip()
        if not title or title in seen_titles:
            continue
        words = sum(len(p.split()) for p in paras)
        if words < args.min_words:
            continue
        if paras and re.search(r"\bmay (also )?refer to\b", paras[0][:300]) and words < 400:
            continue  # disambiguation page
        seen_titles.add(title)
        doc_id += 1
        chunks = list(chunk_article(paras))
        if args.lead_only_after and doc_id > args.lead_only_after:
            chunks = chunks[:2]
        if not chunks:
            doc_id -= 1
            continue
        first = chunk_id + 1
        for o, (sec, txt) in enumerate(chunks):
            chunk_id += 1
            rows.append((chunk_id, doc_id, o, sec, txt, title))
            if zdict is None and len(sample_buf) < 20000:
                sample_buf.append(txt)
        prior = min(1.0, math.log10(max(10, words)) / 4.5)
        n_words_total += words
        con.execute("INSERT INTO docs(id, title, first_chunk, n_chunks, prior, words, url) VALUES (?,?,?,?,?,?,?)",
                    (doc_id, title, first, len(chunks), prior, words, url))
        nt = norm_title(title)
        con.execute("INSERT INTO titles VALUES (?,?,0)", (nt, doc_id))
        base = re.sub(r"\s*\([^)]*\)$", "", title)
        if base != title:
            con.execute("INSERT INTO titles VALUES (?,?,2)", (norm_title(base), doc_id))
        if title.lower().startswith("the ") and len(title) > 6:
            con.execute("INSERT INTO titles VALUES (?,?,2)", (norm_title(title[4:]), doc_id))
        if zdict is None and (len(sample_buf) >= 20000):
            zdict = b"" if args.no_compress else build_zdict(sample_buf)
        if zdict is not None and len(rows) >= 5000:
            flush_rows(rows)
            rows = []
        if doc_id % 20000 == 0:
            el = time.time() - t0
            print(f"{doc_id} docs, {chunk_id} chunks, {el:.0f}s ({doc_id / el:.0f} docs/s)", flush=True)
        if args.max_docs and doc_id >= args.max_docs:
            break
    if zdict is None:
        zdict = b"" if args.no_compress else build_zdict(sample_buf)
    flush_rows(rows)

    con.execute("INSERT INTO meta VALUES ('zdict', ?)", (zdict,))
    con.execute("INSERT INTO meta VALUES ('format', 'kestrel-pack/1')")
    print("indexing titles and terms...", flush=True)
    con.execute("CREATE INDEX titles_norm ON titles(norm)")
    con.execute("CREATE INDEX chunks_doc ON chunks(doc_id, ord)")
    con.execute("CREATE VIRTUAL TABLE temp.vocab USING fts5vocab(main, chunks_fts, 'row')")
    con.execute("CREATE TABLE term_df(term TEXT PRIMARY KEY, df INTEGER NOT NULL) WITHOUT ROWID")
    con.execute("INSERT INTO term_df SELECT term, doc FROM temp.vocab WHERE doc >= 2")
    con.execute("INSERT INTO chunks_fts(chunks_fts) VALUES ('optimize')")
    con.commit()
    con.execute("VACUUM")
    con.close()

    size = os.path.getsize(db_path)
    manifest = {
        "format": "kestrel-pack/1",
        "id": args.id,
        "name": args.name,
        "version": args.version,
        "license": args.license,
        "source": args.source,
        "created": time.strftime("%Y-%m-%d"),
        "language": "en",
        "docs": doc_id,
        "chunks": chunk_id,
        "words": n_words_total,
        "text_codec": "plain" if args.no_compress else CODEC,
        "url_template": args.url_template,
        "embedding": None,
        "files": {"corpus.sqlite": {"bytes": size, "sha256": sha256(db_path)}},
    }
    with open(os.path.join(args.out, "manifest.json"), "w") as f:
        json.dump(manifest, f, indent=2)
    print(f"done: {doc_id} docs, {chunk_id} chunks, {size / 1e9:.2f} GB, {time.time() - t0:.0f}s")


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for b in iter(lambda: f.read(1 << 20), b""):
            h.update(b)
    return h.hexdigest()


if __name__ == "__main__":
    main()
