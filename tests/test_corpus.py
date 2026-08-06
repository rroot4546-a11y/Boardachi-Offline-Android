#!/usr/bin/env python3
import gzip, json, pathlib, re, unittest
ROOT=pathlib.Path(__file__).resolve().parents[1]
CORPUS=ROOT/'app/src/main/assets/corpus.json.gz'
EXPECTED={55:681,78:1200,20:1246,30:1256,32:1554,21:5429,29:214}

class CorpusTests(unittest.TestCase):
 @classmethod
 def setUpClass(cls):
  with gzip.open(CORPUS,'rt',encoding='utf-8') as f: cls.data=json.load(f)
 def test_exact_unique_total(self):
  qs=self.data['questions']; self.assertEqual(11580,len(qs)); self.assertEqual(11580,len({q['id'] for q in qs})); self.assertEqual(11580,self.data['count'])
 def test_book_totals(self):
  totals={k:0 for k in EXPECTED}
  for q in self.data['questions']: totals[q['bookId']]+=1
  self.assertEqual(EXPECTED,totals); self.assertEqual(EXPECTED,{b['id']:b['count'] for b in self.data['books']})
 def test_required_fields_and_answers(self):
  for q in self.data['questions']:
   self.assertTrue(q['question'].strip(),q['id']); self.assertTrue(q['choices'],q['id'])
   for c in q['choices']: self.assertTrue(c['text'].strip() or c['image'],(q['id'],c['id']))
   has_answer=any(c['correct'] or c['match'] is not None for c in q['choices'])
   self.assertTrue(has_answer or q['explanation'].strip(),q['id'])
 def test_no_secrets_in_project(self):
  secret=re.compile(r'(?i)(bearer\s+[a-z0-9._-]{20,}|api[_-]?key\s*[:=]|access[_-]?token\s*[:=]|password\s*[:=])')
  for p in ROOT.rglob('*'):
   if p.is_file() and 'build' not in p.parts and '.gradle' not in p.parts and p.suffix in {'.kt','.kts','.xml','.properties','.md','.py','.json'}:
    self.assertIsNone(secret.search(p.read_text(errors='ignore')),str(p))

if __name__=='__main__': unittest.main()
