# Knowledge packs

A pack is a directory with everything retrieval needs, built on a computer and copied to the phone:

```
<pack>/
  manifest.json    id, name, licence, counts, text codec, embedding spec, file sizes and checksums
  corpus.sqlite    docs, chunks, FTS5 index, titles/aliases, term statistics
  vectors.kvec     optional dense index (IVF, 1-bit codes + int8 vectors)
```

Several packs can be installed at once. Retrieval queries all of them and fuses the results.

## corpus.sqlite schema

| Table | Contents |
|---|---|
| `docs(id, title, first_chunk, n_chunks, prior, words, url)` | one row per article; chunks of an article have contiguous ids; `prior` is an importance score (0-1) |
| `chunks(id, doc_id, ord, section, text)` | ~150-word passages, paragraph-aligned; `text` is zlib with a preset dictionary (`meta.zdict`), about 2x smaller |
| `chunks_fts` | FTS5, contentless, columns (title, section, body), `porter unicode61 remove_diacritics 2`; ranked with BM25 weights 6/2/1 |
| `titles(norm, doc_id, kind)` | normalised titles (0), redirects (1) and aliases (2), for entity linking |
| `term_df(term, df)` | stemmed term → number of chunks, so the engine can drop very common terms from OR queries without scanning postings |
| `meta(key, value)` | format version, compression dictionary |

The engine opens the file read-only with AndroidX's bundled SQLite, which always has FTS5.

## Building from Wikipedia

```sh
pip install pyarrow numpy requests huggingface_hub
huggingface-cli download wikimedia/wikipedia --repo-type dataset --include "20231101.en/*" --local-dir data/wikipedia
python tools/build_pack.py --format parquet --input "data/wikipedia/20231101.en/*.parquet" \
    --out packs/enwiki --id enwiki-20231101 --name "English Wikipedia (2023-11-01)"
```

Options:

* `--full-min-words N`: articles shorter than N words keep only their first two passages (the lead).
  This is the main size lever.
* `--max-docs N`: stop after N articles (for tests).
* `--format jsonl`: any corpus as `{"title", "text", "url"}` lines. Use it for Wikivoyage, manuals,
  your own notes.
* `--format plain-titles`: the legacy plain-text dump used for development (see below).

Measured on the development machine (4-core x86 VM) with the plain-text dump: the first 3.0 GB of
English Wikipedia text (418,112 articles, 3,112,806 passages) built in 15 minutes into a 3.57 GB
pack. That ratio of about 1.2x input text puts the full English Wikipedia at an estimated 25 GB.
Use `--full-min-words` to trade coverage for space.

## Dense vectors (optional)

Vectors let retrieval find passages that answer a question without sharing its words ("why does
ice float" → "ice is less dense than water"). The same GGUF embedding model must be installed on
the phone. The manifest records the model, prefixes, dimension and Matryoshka transform so that the
app encodes queries the same way.

```sh
# 1. local embedding server (loopback only; build-time tool)
llama-server -m models/nomic-embed-text-v1.5.f16.gguf --embeddings --pooling mean \
    -c 8192 -b 8192 -ub 8192 --parallel 4 --host 127.0.0.1 --port 8088
# 2. embed and index (resumable)
python tools/embed_pack.py --pack packs/enwiki --server http://127.0.0.1:8088 \
    --model nomic-embed-text-v1.5 --model-file nomic-embed-text-v1.5.f16.gguf \
    --query-prefix "search_query: " --doc-prefix "search_document: " \
    --dim 256 --transform layernorm --scope toplead:2000000
```

Scopes: `all`, `lead:N` (first N passages of every article), `top:M` (all passages of the M
highest-prior articles), `toplead:M` (lead passage of the M highest-prior articles), or
comma-separated unions.

Throughput decides the scope. On the 4-core development VM, nomic-embed Q8_0 embedded about 7
passages/s while other jobs ran [measured, contended]. A laptop GPU is 50-200x faster [estimate].
Embed lead passages first: they answer most entity questions.

`vectors.kvec` layout: IVF centroids (float32), vectors grouped by list, 1-bit sign codes for a
Hamming pre-filter, int8 vectors with per-vector scale for rescoring. Everything is
memory-mapped, so only the probed lists are read from flash. At 256 dimensions a vector costs 292
bytes (32 + 256 + 4).

## Licences

Wikipedia text is CC BY-SA 4.0. A pack built from it must carry the same licence and attribution.
The manifest's `license` field records it, the app shows it in Library, and every source in an
answer names its article.

## Development corpus

This repository was developed without access to Hugging Face or the Wikimedia dump servers. The
development pack therefore comes from a legacy plain-text English Wikipedia dump (February 2020,
13 GB) found in a public S3 bucket, `s3://datasets.huggingface.co/wikipedia_multilingual/raw/en.all`,
using its first 3 GB:

```sh
curl -r 0-2999999999 -o en_part.txt https://s3.amazonaws.com/datasets.huggingface.co/wikipedia_multilingual/raw/en.all
python tools/build_pack.py --format plain-titles --input en_part.txt --out packs/enwiki-dev --id enwiki-dev \
    --name "English Wikipedia (2020 dump, dev subset)"
```

The dump has no section headings, and articles are ordered by page id. The subset therefore
covers many major topics but misses others (for example, "Benjamin Harrison" is not in it).
Benchmark results on this pack measure the pipeline, not full-Wikipedia coverage.
