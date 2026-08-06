#!/usr/bin/env python3
"""Create the compact, offline app corpus from the verified private export."""
import gzip, json, pathlib, re, sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
EXPORT = ROOT / "export"
OUT = pathlib.Path(__file__).resolve().parents[1] / "app/src/main/assets/corpus.json.gz"
EXPECTED = {55:681, 78:1200, 20:1246, 30:1256, 32:1554, 21:5429, 29:214}

def clean(value):
    return value if value is not None else ""

def main():
    books_raw = {b["id"]: b for b in json.load(open(EXPORT / "books.json", encoding="utf-8"))["data"]}
    books=[]; questions=[]; seen=set()
    for book_id, expected in EXPECTED.items():
        source = EXPORT / "books" / str(book_id) / "all.json"
        rows=json.load(open(source, encoding="utf-8"))
        assert len(rows)==expected, (book_id,len(rows),expected)
        b=books_raw[book_id]
        books.append({"id":book_id,"title":b["title"],"edition":b.get("edition"),"year":b.get("year"),"authors":b.get("authors") or [],"count":expected})
        for q in rows:
            assert q["id"] not in seen, f"duplicate id {q['id']}"; seen.add(q["id"])
            assert clean(q.get("question")).strip(), f"missing question {q['id']}"
            assert q.get("choices"), f"missing choices {q['id']}"
            # Match questions encode answers using matchesWithId rather than isCorrect.
            if q.get("type") == "match":
                assert any(c.get("matchesWithId") for c in q["choices"]), f"missing match answer {q['id']}"
            else:
                # Three known source records have no isCorrect flag but retain explanatory answer text.
                assert any(c.get("isCorrect") for c in q["choices"]) or clean(q.get("explanation")).strip(), f"missing answer {q['id']}"
            questions.append({
                "id":q["id"], "bookId":book_id, "sectionId":q.get("bookSectionId"),
                "section":clean((q.get("bookSection") or {}).get("title")), "type":q.get("type") or "sba",
                "difficulty":q.get("difficulty") or "unknown", "question":q["question"],
                "explanation":clean(q.get("explanation")), "notes":clean(q.get("expertNotes")),
                "images":q.get("images") or [],
                "choices":[{"id":c.get("id"),"order":c.get("order",0),"text":clean(c.get("choice")),
                            "correct":bool(c.get("isCorrect")),"kind":c.get("type") or "regular",
                            "match":c.get("matchesWithId"),"image":c.get("imagePath")}
                           for c in q["choices"]]
            })
    assert len(questions)==11580 and len(seen)==11580
    payload={"version":1,"specialty":"Internal Medicine","count":len(questions),"books":books,"questions":questions}
    OUT.parent.mkdir(parents=True,exist_ok=True)
    with gzip.open(OUT,"wt",encoding="utf-8",compresslevel=9) as f: json.dump(payload,f,ensure_ascii=False,separators=(",",":"))
    print(f"Wrote {OUT}: {OUT.stat().st_size:,} bytes, {len(questions):,} unique questions")
if __name__ == "__main__": main()
