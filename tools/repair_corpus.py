#!/usr/bin/env python3
"""Repair known source-record corruptions in the shipped corpus.

Fixes (from each question's own source explanation):
  * qid 13312  -> mark the leaked/truncated "STM management" choice as the answer,
                  complete its truncated text, and remove the answer spoiler from the stem.
  * qid 14047  -> explanation names LADA -> mark that choice correct.
  * qid 15471  -> explanation names "Peak expiratory flow monitoring twice daily for
                  2 weeks is the correct next step" -> mark that choice correct.

Identical input elsewhere is preserved byte-for-byte so book/question counts stay valid.
"""
import gzip
import json
import pathlib

ROOT = pathlib.Path(__file__).resolve().parents[1]
CORPUS = ROOT / "app/src/main/assets/corpus.json.gz"

FIXES = {
    13312: {
        "stem_bad": "<br><br>STM management: Reteplase should be delivered within 12 hours of "
                    "symptom onset if primary PCI cannot be delivered within 120 minutes of diagnosis.",
        "choice_fix": {
            3: ("Start STM management: Reteplase should be delivered within 12 hours of symptom onset "
                "if primary PCI cannot be delivered within 120 minutes of diagnosis", True),
        },
    },
    14047: {"choice_fix": {2: (None, True)}},
    15471: {"choice_fix": {1: (None, True)}},
}


def main() -> None:
    with gzip.open(CORPUS, "rt", encoding="utf-8") as f:
        payload = json.load(f)

    by_id = {q["id"]: q for q in payload["questions"]}
    changed = []

    for qid, fix in FIXES.items():
        q = by_id[qid]
        if q["type"] != "sba" or any(c["correct"] for c in q["choices"]):
            continue

        for order, (new_text, correct) in fix.get("choice_fix", {}).items():
            for c in q["choices"]:
                if c.get("order") == order:
                    if new_text is not None and c["text"] != new_text:
                        c["text"] = new_text
                    if correct:
                        c["correct"] = True
        if "stem_bad" in fix:
            q["question"] = q["question"].replace(fix["stem_bad"], "")
        changed.append(qid)

    with gzip.open(CORPUS, "wt", encoding="utf-8", compresslevel=9) as f:
        json.dump(payload, f, ensure_ascii=False, separators=(",", ":"))

    print(f"Fixed {len(changed)} questions: {sorted(changed)}")


if __name__ == "__main__":
    main()