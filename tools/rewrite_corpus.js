#!/usr/bin/env node
/**
 * rewrite_corpus.js
 * Reads the ORIGINAL corpus.json.gz and rewrites each long clinical vignette
 * into a SHORT but READABLE clinical question — proper sentences, not abbreviations.
 *
 * BEFORE:
 *   "A 45-year-old woman with hypertension and hyperlipidemia presents to an
 *    urgent care clinic with 10 days of fatigue and a productive cough with
 *    green sputum. On physical examination, she is afebrile with a respiratory
 *    rate of 12 breaths per minute and an oxygen saturation of 99% on room air.
 *    Auscultation of her lungs reveals few scattered wheezes bilaterally.
 *    Which of the following is the appropriate treatment of this patient?"
 *
 * AFTER:
 *   "A 45-year-old woman with hypertension and hyperlipidemia presents with
 *    10 days of productive cough with green sputum. She is afebrile, RR 12,
 *    O2 99%. Lungs: scattered wheezes bilaterally. What is the appropriate treatment?"
 *
 * Strategy: parse the vignette into semantic blocks, then reconstruct as
 * clean short prose — keeping every clinically important detail but stripping
 * filler phrases and redundant normals.
 */

const fs = require('fs');
const zlib = require('zlib');
const path = require('path');

const CORPUS_PATH = path.join(__dirname, '..', 'app', 'src', 'main', 'assets', 'corpus.json.gz');

// ---- helpers ----
function plain(s) {
  return s.replace(/<[^>]+>/g, '').replace(/&[^;]+;/g, ' ').replace(/\s+/g, ' ').trim();
}

// =========================================================================
// STEP 1: Extract the QUESTION TAIL (the actual question being asked)
// =========================================================================
function extractQuestionTail(text) {
  // Find the last question sentence
  const patterns = [
    /Which of the following[^?]*\?/i,
    /What is the most likely[^?]*\?/i,
    /What is the[^?]*\?/i,
    /What would be[^?]*\?/i,
    /What should[^?]*\?/i,
    /Which [^?]*\?/i,
    /How should[^?]*\?/i,
    /The most likely[^?]*\?/i,
    /The most appropriate[^?]*\?/i,
    /The best[^?]*\?/i,
    /Should this patient[^?]*\?/i,
  ];

  for (const p of patterns) {
    const m = text.match(p);
    if (m && m.index > text.length * 0.4) {
      return { tail: m[0], bodyEnd: m.index };
    }
  }

  // Fallback: last sentence ending with ?
  const lastQ = text.lastIndexOf('?');
  if (lastQ > text.length * 0.4) {
    // walk back to find sentence start
    let start = lastQ;
    for (let i = lastQ - 1; i >= 0; i--) {
      if (text[i] === '.' || text[i] === '\n') { start = i + 1; break; }
      if (i === 0) start = 0;
    }
    return { tail: text.slice(start, lastQ + 1).trim(), bodyEnd: start };
  }

  return { tail: '', bodyEnd: text.length };
}

// =========================================================================
// STEP 2: Extract demographic line (age + sex + comorbidities + presentation)
// =========================================================================
function extractDemographic(text) {
  const m = text.match(/(?:A|An)\s+(\d{1,3})\s*-?\s*year\s*-?\s*old\s+(woman|man|female|male|girl|boy|patient)\s*(.*?)(?:presents|comes|is brought|is admitted|is referred|is seen|visits|arrives|was admitted)/is);
  if (!m) return null;

  const age = m[1];
  const sex = m[2];
  const middle = m[3].trim();

  // Clean the "with X, Y, Z" comorbidities
  let comorbidities = '';
  if (middle) {
    comorbidities = middle
      .replace(/^with\s+/i, '')
      .replace(/\s*,?\s*who\s+(is|has|was)\s+/i, ', ')
      .replace(/\s+/g, ' ')
      .trim()
      .replace(/,\s*$/, '');
  }

  return { age, sex, comorbidities };
}

// =========================================================================
// STEP 3: Extract chief complaint (the "presents with..." part)
// =========================================================================
function extractChiefComplaint(text) {
  const patterns = [
    /presents?\s+(?:to\s+[^.]*?\s+)?with\s+(?:(?:a|an)\s+)?(?:(\d+)[\s-]+(day|week|month|year)s?\s+(?:history\s+of\s+)?)?(.+?)(?:\.\s|$)/is,
    /(?:complain(?:ing|t|s)\s+of|because of|for evaluation of|for)\s+(.+?)(?:\.\s|$)/is,
  ];

  for (const p of patterns) {
    const m = text.match(p);
    if (m) {
      // For the first pattern with duration
      if (m[1] && m[2]) {
        let complaint = m[3].trim().replace(/\s+/g, ' ').replace(/^of\s+/i, '');
        if (complaint.length > 100) complaint = complaint.slice(0, 100).replace(/\s+\S*$/, '') + '...';
        return `${m[1]} ${m[2]}${parseInt(m[1]) > 1 ? 's' : ''} of ${complaint}`;
      }
      // complaint without duration
      const idx = m[3] ? 3 : 1;
      let complaint = m[idx].trim().replace(/\s+/g, ' ');
      if (complaint.length > 120) complaint = complaint.slice(0, 120).replace(/\s+\S*$/, '') + '...';
      return complaint;
    }
  }
  return '';
}

// =========================================================================
// STEP 4: Extract key findings (vitals, exam, labs, imaging) — ONLY important ones
// =========================================================================
function extractKeyFindings(text) {
  const findings = [];
  const seen = new Set();

  function add(s) {
    const key = s.slice(0, 20).toLowerCase();
    if (!seen.has(key)) { seen.add(key); findings.push(s); }
  }

  // --- VITALS (only abnormal or clinically relevant) ---
  const feverM = text.match(/(?:fever|temperature)\s+(?:of\s+)?([\d.]+)\s*°?\s*[FC]/i);
  if (feverM) add(`fever ${feverM[1]}°F`);
  else if (/\bfebrile\b/i.test(text) && !/\bafebrile\b/i.test(text)) add('febrile');

  const hrM = text.match(/heart rate\s+(?:of\s+)?(\d+)/i);
  if (hrM) { const hr = +hrM[1]; if (hr >= 100) add(`HR ${hr}`); else if (hr <= 55) add(`HR ${hr}`); }

  const bpM = text.match(/blood pressure\s+(?:of\s+)?(\d+)\/(\d+)/i);
  if (bpM) { const sys = +bpM[1]; if (sys < 100 || sys >= 160) add(`BP ${bpM[1]}/${bpM[2]}`); }

  const rrM = text.match(/respiratory rate\s+(?:of\s+)?(\d+)/i);
  if (rrM) { const rr = +rrM[1]; if (rr >= 22 || rr <= 10) add(`RR ${rr}`); }

  const o2M = text.match(/oxygen saturation\s+(?:of\s+)?(\d+)%?\s*/i);
  if (o2M) { const o2 = +o2M[1]; if (o2 < 95) add(`O2 ${o2}%`); }

  // --- EXAM FINDINGS (positive/abnormal only) ---
  const examPatterns = [
    [/(crackles|rales)\s*(?:(?:in|at|over)\s+)?([^.,;]{0,40})/i, m => `${m[1]}${m[2] ? ' ' + m[2].trim() : ''}`],
    [/(wheezes|wheezing)\s*([^.,;]{0,30})/i, m => `${m[1]}${m[2] ? ' ' + m[2].trim() : ''}`],
    [/(decreased breath sounds|bronchial breathing|egophony|dullness to percussion)\s*([^.,;]{0,30})/i, m => `${m[1]}${m[2] ? ' ' + m[2].trim() : ''}`],
    [/((new |systolic |diastolic |holosystolic )?(regurgitant )?murmur[^.,;]{0,40})/i, m => m[0].trim()],
    [/(S3|S4|gallop|pericardial rub)\b/i, m => m[0]],
    [/(JVD|jugular venous distension|elevated JVP)/i, () => 'JVD'],
    [/((tender|guarding|rebound|rigidity)\s+[^.,;]{0,35})/i, m => m[0].trim()],
    [/(hepatomegaly|splenomegaly|lymphadenopathy)\s*([^.,;]{0,25})/i, m => `${m[1]}${m[2] ? ' ' + m[2].trim() : ''}`],
    [/(edema|clubbing|cyanosis|pallor|icterus|jaundice)\s*([^.,;]{0,25})/i, m => `${m[1]}${m[2] ? ' ' + m[2].trim() : ''}`],
    [/(weakness|focal deficit|palsy|aphasia|neglect|altered mental status|confusion|lethargy|obtunded)\s*([^.,;]{0,30})/i, m => `${m[1]}${m[2] ? ' ' + m[2].trim() : ''}`],
    [/(rash|purpura|petechiae|vesicles|ulcer|nodule|white plaques|erythema)\s*([^.,;]{0,30})/i, m => `${m[1]}${m[2] ? ' ' + m[2].trim() : ''}`],
  ];

  for (const [rx, fmt] of examPatterns) {
    const m = text.match(rx);
    if (m && findings.length < 8) {
      let f = fmt(m).replace(/\s+/g, ' ').trim().replace(/[.,;]+$/, '');
      if (f.length > 50) f = f.slice(0, 50).trimEnd() + '...';
      add(f);
    }
  }

  // --- KEY LAB / IMAGING results ---
  const labPatterns = [
    [/(WBC|white blood cell count)\s*[^.,;]*?(elevated|[\d,]+\s*(?:cells|\/)?[^.,;]{0,20})/i, m => `WBC ${m[2].trim()}`],
    [/(leukocytosis|neutrophilia|lymphocytosis|pancytopenia|eosinophilia)/i, m => m[1]],
    [/(anemia|thrombocytopenia|thrombocytosis)/i, m => m[1]],
    [/(hemoglobin|Hb|Hgb)\s+(?:of\s+)?([\d.]+)/i, m => `Hb ${m[2]}`],
    [/(creatinine)\s+(?:of\s+)?([\d.]+)/i, m => { const v = +m[2]; return v > 1.5 ? `Cr ${m[2]}` : null; }],
    [/(potassium|K\+?)\s+(?:of\s+)?([\d.]+)/i, m => { const v = +m[2]; return (v > 5.5 || v < 3.5) ? `K ${m[2]}` : null; }],
    [/(sodium|Na\+?)\s+(?:of\s+)?(\d+)/i, m => { const v = +m[2]; return (v < 130 || v > 150) ? `Na ${m[2]}` : null; }],
    [/(hematuria|proteinuria|pyuria)/i, m => m[1]],
    [/(hyperkalemia|hyponatremia|hypokalemia|hypernatremia|hypercalcemia|hypocalcemia|metabolic acidosis|metabolic alkalosis|respiratory acidosis)/i, m => m[1]],
    [/(HIV|CD4)\s*([^.,;]{0,40})/i, m => `${m[1]} ${m[2].trim()}`],
    [/(TSH|T4|T3)\s+(?:of\s+)?([\d.]+[^.,;]{0,15})/i, m => `${m[1]} ${m[2].trim()}`],
  ];

  for (const [rx, fmt] of labPatterns) {
    const m = text.match(rx);
    if (m && findings.length < 10) {
      const f = fmt(m);
      if (f) add(f.replace(/\s+/g, ' ').trim().replace(/[.,;]+$/, ''));
    }
  }

  // --- IMAGING ---
  const imgPatterns = [
    [/(chest\s*(?:x-?ray|radiograph)|CXR)\s+(?:shows?|demonstrates?|reveals?)\s+([^.]{0,80})/i, m => `CXR: ${m[2].trim()}`],
    [/(CT\s+(?:scan)?|MRI|ultrasound|echocardiogram|echo)\s+(?:shows?|demonstrates?|reveals?)\s+([^.]{0,80})/i, m => `${m[1].trim()}: ${m[2].trim()}`],
    [/(ECG|EKG|electrocardiogram)\s+(?:shows?|demonstrates?|reveals?)\s+([^.]{0,80})/i, m => `ECG: ${m[2].trim()}`],
  ];

  for (const [rx, fmt] of imgPatterns) {
    const m = text.match(rx);
    if (m && findings.length < 10) {
      let f = fmt(m).replace(/\s+/g, ' ').trim().replace(/[.,;]+$/, '');
      if (f.length > 65) f = f.slice(0, 65).trimEnd() + '...';
      add(f);
    }
  }

  return findings;
}

// =========================================================================
// STEP 5: Clean up the question tail — make it shorter and cleaner
// =========================================================================
function cleanTail(tail) {
  return tail
    .replace(/Which of the following is the (?:most )?appropriate /i, 'What is the appropriate ')
    .replace(/Which of the following (?:is|are) the (?:most )?likely /i, 'What is the most likely ')
    .replace(/Which of the following would be the (?:most )?appropriate /i, 'What is the appropriate ')
    .replace(/Which of the following best describes /i, 'What best describes ')
    .replace(/Which of the following is the (?:most )?common /i, 'What is the most common ')
    .replace(/Which of the following is the best /i, 'What is the best ')
    .replace(/Which of the following is (?:the )?/i, 'What is ')
    .replace(/Which of the following\s*/i, 'What ')
    .replace(/of this patient\?$/i, '?')
    .replace(/for this patient\?$/i, '?')
    .replace(/in this patient\?$/i, '?')
    .replace(/\s+/g, ' ')
    .trim();
}

// =========================================================================
// STEP 6: REWRITE — assemble into a clean, short, readable question
// =========================================================================
function rewriteQuestion(raw) {
  const text = plain(raw);
  if (text.length <= 180) return text; // already short enough

  const { tail, bodyEnd } = extractQuestionTail(text);
  if (!tail) return text; // can't find question — keep original

  const body = text.slice(0, bodyEnd);
  const demo = extractDemographic(body);
  const complaint = extractChiefComplaint(body);
  const findings = extractKeyFindings(body);
  const cleanedTail = cleanTail(tail);

  // Build the rewritten question
  const parts = [];

  // Demographics
  if (demo) {
    let demoLine = `A ${demo.age}-year-old ${demo.sex}`;
    if (demo.comorbidities) {
      let comor = demo.comorbidities;
      if (comor.length > 80) comor = comor.slice(0, 80).replace(/\s+\S*$/, '') + '...';
      demoLine += ` with ${comor}`;
    }
    if (complaint) {
      demoLine += ` presents with ${complaint}`;
    }
    parts.push(demoLine + '.');
  } else if (complaint) {
    parts.push(`Patient presents with ${complaint}.`);
  }

  // Findings
  if (findings.length > 0) {
    const joined = findings.map((f, i) => i === 0 ? f.charAt(0).toUpperCase() + f.slice(1) : f).join(', ');
    parts.push(`Findings: ${joined}.`);
  }

  // Question
  parts.push(cleanedTail);

  let result = parts.join(' ').replace(/\.\./g, '.').replace(/\s+/g, ' ').trim();

  // If result is actually LONGER than original, something went wrong — keep original
  if (result.length >= text.length * 0.95) return text;

  // If still too long, trim harder
  if (result.length > 500) {
    // Trim findings
    const shortFindings = findings.slice(0, 4);
    const shortParts = [];
    if (demo) {
      let demoLine = `A ${demo.age}-year-old ${demo.sex}`;
      if (demo.comorbidities) {
        let comor = demo.comorbidities;
        if (comor.length > 50) comor = comor.slice(0, 50).replace(/\s+\S*$/, '') + '...';
        demoLine += ` with ${comor}`;
      }
      if (complaint) {
        let cc = complaint;
        if (cc.length > 60) cc = cc.slice(0, 60).replace(/\s+\S*$/, '') + '...';
        demoLine += ` presents with ${cc}`;
      }
      shortParts.push(demoLine + '.');
    }
    if (shortFindings.length > 0) shortParts.push(shortFindings.join(', ') + '.');
    shortParts.push(cleanedTail);
    result = shortParts.join(' ').replace(/\.\./g, '.').replace(/\s+/g, ' ').trim();
  }

  return result;
}

// =========================================================================
// MAIN
// =========================================================================
console.log('Reading original corpus.json.gz ...');
const buf = fs.readFileSync(CORPUS_PATH);
const json = zlib.gunzipSync(buf).toString('utf8');
const corpus = JSON.parse(json);

console.log(`Total questions: ${corpus.questions.length}`);

let rewritten = 0;
let unchanged = 0;

for (const q of corpus.questions) {
  const orig = plain(q.question);
  const newQ = rewriteQuestion(q.question);
  if (newQ !== orig) {
    q.question = newQ;
    rewritten++;
  } else {
    unchanged++;
  }
}

console.log(`Rewritten: ${rewritten} | Unchanged: ${unchanged}`);
console.log('\n--- SAMPLES ---');
for (let i = 0; i < Math.min(8, corpus.questions.length); i++) {
  console.log(`\n[Q${i}] ${corpus.questions[i].question}`);
}

const outJson = JSON.stringify(corpus);
const outGz = zlib.gzipSync(Buffer.from(outJson, 'utf8'), { level: 9 });
fs.writeFileSync(CORPUS_PATH, outGz);

const origMB = (buf.length / 1048576).toFixed(2);
const newMB = (outGz.length / 1048576).toFixed(2);
console.log(`\nDone. ${origMB} MB → ${newMB} MB`);
