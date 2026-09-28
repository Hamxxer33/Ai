#!/usr/bin/env python3
"""Summarise benchmark result files (JSONL from `kestrel-cli bench` or the app's Benchmark screen).

  python tools/score_bench.py results.jsonl                 # summary table (markdown)
  python tools/score_bench.py a.jsonl b.jsonl               # side-by-side comparison of two runs
  python tools/score_bench.py results.jsonl --grading-sheet grading.csv
                                                            # CSV for blind human/LLM grading

Automatic metrics (all lenient string matching, see engine/.../bench/Benchmark.kt):
  answer_match      gold answer (or an accepted alternative) appears in the answer
  key_fact_recall   share of key facts that appear in the answer
  abstain_correct   says "not in the sources" exactly when it should
  grounding         supported claims / checkable claims (claim verifier)
Automatic matching under-counts paraphrases and over-counts answers that mention the right
words in a wrong statement, so the grading sheet is provided for manual review of every answer.
"""
from __future__ import annotations

import argparse
import csv
import json
import statistics
import sys
from collections import defaultdict

CATEGORY_ORDER = ["factual", "obscure", "multi_hop", "comparison", "explanation", "historical_analysis",
                  "scientific", "technical", "numerical", "synthesis", "contradictory", "long_context",
                  "hallucination_probe", "multi_source", "long_tail"]


def load(path):
    with open(path, encoding="utf-8") as f:
        return [json.loads(l) for l in f if l.strip()]


def pct(xs):
    xs = [x for x in xs if x is not None]
    return f"{100 * sum(1 for x in xs if x) / len(xs):.0f}%" if xs else "–"


def mean(xs):
    xs = [x for x in xs if x is not None]
    return f"{statistics.mean(xs):.2f}" if xs else "–"


def median_s(xs):
    xs = [x for x in xs if x is not None]
    return f"{statistics.median(xs) / 1000:.1f}s" if xs else "–"


def summarize(rows, title):
    by = defaultdict(list)
    for r in rows:
        by[r["category"]].append(r)
    cats = [c for c in CATEGORY_ORDER if c in by] + sorted(c for c in by if c not in CATEGORY_ORDER)
    out = [f"### {title}", "",
           "| Category | n | Answer match | Key-fact recall | Abstention correct | Grounded claims | Median latency | Median TTFT | Decode tok/s | Errors |",
           "|---|---|---|---|---|---|---|---|---|---|"]

    def line(name, rs):
        sup = sum(r["claims_supported"] for r in rs)
        chk = sum(r["claims_checkable"] for r in rs)
        return (f"| {name} | {len(rs)} | {pct(r['answer_match'] for r in rs)} | {mean(r['key_fact_recall'] for r in rs)} | "
                f"{pct(r['abstain_correct'] for r in rs)} | {f'{100 * sup / chk:.0f}%' if chk else '–'} | "
                f"{median_s(r['total_ms'] for r in rs)} | {median_s(r.get('answer_ttft_ms') for r in rs)} | "
                f"{mean(r.get('answer_decode_tps') for r in rs)} | {sum(1 for r in rs if r.get('error'))} |")

    for c in cats:
        out.append(line(c, by[c]))
    out.append(line("**all**", rows))
    peak = [r.get("peak_rss_mb") for r in rows if r.get("peak_rss_mb")]
    models = sorted({m for r in rows for m in r.get("models_used", [])})
    out += ["", f"Peak process RSS: {max(peak) if peak else '–'} MB. Models: {', '.join(models) or '–'}."]
    return "\n".join(out)


def compare(a, b, na, nb):
    ia = {r["id"]: r for r in a}
    ib = {r["id"]: r for r in b}
    common = [i for i in ia if i in ib]
    out = [f"### {na} vs {nb} ({len(common)} common questions)", "",
           "| Metric | " + na + " | " + nb + " |", "|---|---|---|"]
    for key, fn in [("answer_match", pct), ("key_fact_recall", mean), ("abstain_correct", pct)]:
        out.append(f"| {key} | {fn(ia[i][key] for i in common)} | {fn(ib[i][key] for i in common)} |")
    out.append(f"| median latency | {median_s(ia[i]['total_ms'] for i in common)} | {median_s(ib[i]['total_ms'] for i in common)} |")
    return "\n".join(out)


def grading_sheet(rows, path, questions_path=None):
    gold = {}
    if questions_path:
        for q in load(questions_path):
            gold[q["id"]] = q
    with open(path, "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["id", "category", "question", "gold_answer", "key_facts", "answer", "sources",
                    "grade_0_10", "factually_correct(y/n)", "citations_correct(y/n)", "hallucination(y/n)", "notes"])
        for r in rows:
            g = gold.get(r["id"], {})
            srcs = " | ".join(f"[{s['n']}] {s['title']}" for s in r["sources"])
            w.writerow([r["id"], r["category"], r["question"], g.get("answer", ""), "; ".join(g.get("key_facts", [])),
                        r["answer"], srcs, "", "", "", "", ""])
    print(f"wrote {path}")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("files", nargs="+")
    ap.add_argument("--grading-sheet")
    ap.add_argument("--questions", default="benchmark/questions.jsonl")
    args = ap.parse_args()
    runs = [load(p) for p in args.files]
    for p, rows in zip(args.files, runs):
        print(summarize(rows, p))
        print()
    if len(runs) == 2:
        print(compare(runs[0], runs[1], args.files[0], args.files[1]))
    if args.grading_sheet:
        grading_sheet(runs[0], args.grading_sheet, args.questions)


if __name__ == "__main__":
    sys.exit(main())
