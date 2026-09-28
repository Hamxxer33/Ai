# Demo plan (bounty submission)

**Goal:** show on real hardware, in Airplane Mode, that Kestrel answers hard research questions with
local citations. Show that it refuses when the library does not support an answer. Show where the
time goes. Show the same questions answered badly by a 1B-2B model alone.

## Setup to show on camera

1. Phone model and RAM (Settings → About phone); GrapheneOS version if applicable.
2. Library screen: models with roles, pack with article/passage counts, total storage (< 50 GB).
3. Settings → Offline guarantee: no INTERNET permission.
4. Airplane Mode on, app force-stopped and reopened ([OFFLINE_TESTING.md](OFFLINE_TESTING.md)).

## Questions (from benchmark/questions.jsonl), in this order

| # | Mode | Question | Why it is hard | Expected behaviour |
|---|---|---|---|---|
| 1 | Research | What is the capital of the country where Angkor Wat is located? | two hops; the bridge (Cambodia) is not named | plan → hop 1 (Cambodia) → hop 2 (Phnom Penh), cited |
| 2 | Research | Who was the President of the United States when the Eiffel Tower was completed? | temporal join across two articles | 1889 → Benjamin Harrison, both hops cited |
| 3 | Research | Compare nuclear fission and nuclear fusion as sources of energy. | comparison with per-aspect retrieval | parallel structure, both sides cited |
| 4 | Research | How old was Albert Einstein when he published his theory of special relativity? | arithmetic on retrieved dates | 1879 and 1905 quoted, 26 computed |
| 5 | Quick | What is the name of the parliament of Andorra? | long-tail fact | General Council, cited, in seconds |
| 6 | Research | In what year did Albert Einstein win his second Nobel Prize? | false premise | corrects: one Nobel Prize (1921) |
| 7 | Research | Summarize the plot of Ernest Hemingway's novel The Silver Horizon of Gdańsk. | non-existent book | "not in the offline library", no invented plot |
| 8 | Deep | What are the main theories about the origin of the Moon? | synthesis across sources | giant impact (Theia) and alternatives, cited |
| 9 | Research | What is the tallest mountain on Earth? | contested framing | Everest above sea level, Mauna Kea base to peak |
| 10 | Research | Which countries border both France and Germany? | set intersection across articles | Belgium, Luxembourg, Switzerland |

For each: expand **Research steps** (timings per step), tap one citation to show the passage, and
point at the **Grounding** line.

## The small-model contrast

Run the same ten questions with the fast model alone and no retrieval
(`bench --baseline memory` on the desktop, or the app's *Answer from model memory (unverified)*
button on the refusal case). Show the answers side by side with Kestrel's. The benchmark files make
this comparison reproducible.

## Numbers to show (from the phone, not estimates)

* Runtime benchmark: prompt and generation speed of each model tier, load time.
* Per question: time to first word, total time, prompt tokens (cached vs. read), model used.
* Benchmark summary over all 165 questions: accuracy per category, abstention accuracy, grounded
  claim rate, median latency, peak RSS.
