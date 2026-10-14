#!/usr/bin/env node
/**
 * rewrite_corpus.js
 * Synthesizes long clinical vignettes into concise, natural, coherent medical questions.
 * NO robotic "Findings:" or disjointed word-tags.
 */

const fs = require('fs');
const zlib = require('zlib');
const path = require('path');
const { execSync } = require('child_process');

const CORPUS_PATH = path.join(__dirname, '..', 'app', 'src', 'main', 'assets', 'corpus.json.gz');

function plain(s) {
  return s.replace(/<[^>]+>/g, '').replace(/&[^;]+;/g, ' ').replace(/\s+/g, ' ').trim();
}

function cut(str, max) {
  if (!str) return '';
  let s = str.length <= max ? str : str.slice(0, max).replace(/\s+\S*$/, '');
  s = s.replace(/\s+(?:and|or|with|of|the|on|at|in|to|a|an)\s*$/i, '').trim();
  return s;
}

// =========================================================================
// STEP 1: Extract Question Lead-in / Tail
// =========================================================================
function extractQuestionTail(text) {
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
    /(?:The most likely|The most appropriate|The best|What is the|Which of the following|The next best step|The correct|The diagnosis)[^.:]*:\s*$/i
  ];

  for (const p of patterns) {
    const m = text.match(p);
    if (m && m.index > text.length * 0.35) {
      return { tail: m[0], bodyEnd: m.index };
    }
  }

  const lastQ = text.lastIndexOf('?');
  if (lastQ > text.length * 0.35) {
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
// STEP 2: Extract Demographics (Age, Sex, Medical History)
// =========================================================================
function extractDemographic(text) {
  const m = text.match(/(?:A|An)\s+(\d{1,3})\s*-?\s*year\s*-?\s*old\s+(woman|man|female|male|girl|boy|patient|infant|neonate|child|primigravida|multigravida)(.*?)(?:presents|comes|is brought|is admitted|is referred|is seen|visits|arrives|was admitted|is evaluated|undergoes evaluation)/is);
  if (!m) return null;

  const age = m[1];
  const sex = m[2];
  const middle = m[3].trim();

  let comorbidities = '';
  if (middle) {
    comorbidities = middle
      .replace(/^with\s+/i, '')
      .replace(/\s*,?\s*who\s+(?:is|has|was)\s+/i, ', ')
      .replace(/\s+/g, ' ')
      .trim()
      .replace(/,\s*$/, '');
    if (comorbidities.length > 70) comorbidities = cut(comorbidities, 65);
  }

  return { age, sex, comorbidities };
}

// =========================================================================
// STEP 3: Extract Chief Complaint
// =========================================================================
function extractChiefComplaint(text) {
  const patterns = [
    /presents?\s+(?:to\s+[^.]*?\s+)?with\s+(?:(?:a|an)\s+)?(?:complaints?\s+of\s+)?(?:(\d+)[\s-]+(day|week|month|year)s?\s+(?:history\s+of\s+)?)?(.+?)(?:\.\s|$)/is,
    /is evaluated for\s+(?:(?:a|an)\s+)?(?:(\d+)[\s-]+(day|week|month|year)s?\s+(?:history\s+of\s+)?)?(.+?)(?:\.\s|$)/is,
    /(?:complain(?:ing|t|s)\s+of|because of|for evaluation of|for)\s+(.+?)(?:\.\s|$)/is,
  ];

  for (const p of patterns) {
    const m = text.match(p);
    if (m) {
      if (m[1] && m[2]) {
        let complaint = m[3].trim().replace(/\s+/g, ' ').replace(/^of\s+/i, '');
        return `${m[1]} ${m[2]}${parseInt(m[1]) > 1 ? 's' : ''} of ${cut(complaint, 80)}`;
      }
      const idx = m[3] ? 3 : 1;
      let complaint = m[idx].trim().replace(/\s+/g, ' ');
      return cut(complaint, 90);
    }
  }
  return '';
}

// =========================================================================
// STEP 4: Extract Exam Findings and Diagnostic Findings (Separated!)
// =========================================================================
function extractStructuredFindings(text) {
  const examFindings = [];
  const diagnosticFindings = [];
  const seen = new Set();

  function addExam(s) {
    const key = s.slice(0, 20).toLowerCase();
    if (!seen.has(key)) { seen.add(key); examFindings.push(s); }
  }

  function addDiag(s) {
    const key = s.slice(0, 20).toLowerCase();
    if (!seen.has(key)) { seen.add(key); diagnosticFindings.push(s); }
  }

  // --- Vitals ---
  const feverM = text.match(/(?:fever|temperature)\s+(?:of\s+)?([\d.]+)\s*°?\s*[FC]/i);
  if (feverM) addExam(`fever ${feverM[1]}°F`);
  else if (/\bfebrile\b/i.test(text) && !/\bafebrile\b/i.test(text)) addExam('febrile');

  const hrM = text.match(/heart rate\s+(?:of\s+)?(\d+)/i);
  if (hrM) { const hr = +hrM[1]; if (hr >= 100 || hr <= 55) addExam(`HR ${hr}`); }

  const bpM = text.match(/blood pressure\s+(?:of\s+)?(\d+)\/(\d+)/i);
  if (bpM) { const sys = +bpM[1]; if (sys < 100 || sys >= 160) addExam(`BP ${bpM[1]}/${bpM[2]}`); }

  const rrM = text.match(/respiratory rate\s+(?:of\s+)?(\d+)/i);
  if (rrM) { const rr = +rrM[1]; if (rr >= 22 || rr <= 10) addExam(`RR ${rr}`); }

  const o2M = text.match(/oxygen saturation\s+(?:of\s+)?(\d+)%?\s*/i);
  if (o2M) { const o2 = +o2M[1]; if (o2 < 95) addExam(`SpO2 ${o2}%`); }

  // --- Physical Exam Signs ---
  const examPatterns = [
    [/(crackles|rales)\s*(?:(?:in|at|over|on)\s+)?([^.,;]{0,35}?)(?=\s+(?:and|with)|\.|\,|$)/i, m => `${m[1]}${m[2] ? ' ' + m[2].trim() : ''}`],
    [/(wheezes|wheezing)\s*([^.,;]{0,30}?)(?=\s+(?:and|with)|\.|\,|$)/i, m => `${m[1]}${m[2] ? ' ' + m[2].trim() : ''}`],
    [/(decreased breath sounds|bronchial breathing|egophony|dullness to percussion)\s*([^.,;]{0,30}?)(?=\s+(?:and|with)|\.|\,|$)/i, m => `${m[1]}${m[2] ? ' ' + m[2].trim() : ''}`],
    [/((?:new |systolic |diastolic |holosystolic )?(?:regurgitant )?murmur[^.,;]{0,35}?)(?=\s+(?:and|with)|\.|\,|$)/i, m => m[0].trim()],
    [/(S3|S4|gallop|pericardial rub)\b/i, m => m[0]],
    [/(JVD|jugular venous distension|elevated JVP)/i, () => 'JVD'],
    [/((?:tender|guarding|rebound|rigidity)\s+[^.,;]{0,30}?)(?=\s+(?:and|with)|\.|\,|$)/i, m => m[0].trim()],
    [/(hepatomegaly|splenomegaly|lymphadenopathy)\s*([^.,;]{0,25}?)(?=\s+(?:and|with)|\.|\,|$)/i, m => `${m[1]}${m[2] ? ' ' + m[2].trim() : ''}`],
    [/(edema|clubbing|cyanosis|pallor|icterus|jaundice)\s*([^.,;]{0,25}?)(?=\s+(?:and|with)|\.|\,|$)/i, m => `${m[1]}${m[2] ? ' ' + m[2].trim() : ''}`],
    [/(weakness|focal deficit|palsy|aphasia|neglect|altered mental status|confusion|lethargy|obtunded)\s*([^.,;]{0,30}?)(?=\s+(?:and|with)|\.|\,|$)/i, m => `${m[1]}${m[2] ? ' ' + m[2].trim() : ''}`],
    [/(rash|purpura|petechiae|vesicles|ulcer|nodule|white plaques|erythema|dermatitis)\s*([^.,;]{0,30}?)(?=\s+(?:and|with)|\.|\,|$)/i, m => `${m[1]}${m[2] ? ' ' + m[2].trim() : ''}`],
  ];

  for (const [rx, fmt] of examPatterns) {
    const m = text.match(rx);
    if (m && examFindings.length < 5) {
      let f = fmt(m).replace(/\s+/g, ' ').trim().replace(/[.,;]+$/, '');
      addExam(cut(f, 40));
    }
  }

  // --- Labs & Imaging ---
  const labPatterns = [
    [/(WBC|white blood cell count)\s*[^.,;]*?(elevated|[\d,]+\s*(?:cells|\/)?[^.,;]{0,20})/i, m => `elevated WBC (${m[2].trim()})`],
    [/(leukocytosis|neutrophilia|lymphocytosis|pancytopenia|eosinophilia)/i, m => m[1]],
    [/(anemia|thrombocytopenia|thrombocytosis)/i, m => m[1]],
    [/(hemoglobin|Hb|Hgb)\s+(?:of\s+)?([\d.]+)/i, m => `Hb ${m[2]}`],
    [/(creatinine)\s+(?:of\s+)?([\d.]+)/i, m => { const v = +m[2]; return v > 1.5 ? `Cr ${m[2]}` : null; }],
    [/(potassium|K\+?)\s+(?:of\s+)?([\d.]+)/i, m => { const v = +m[2]; return (v > 5.5 || v < 3.5) ? `K ${m[2]}` : null; }],
    [/(hyperkalemia|hyponatremia|hypokalemia|hypernatremia|hypercalcemia|hypocalcemia|metabolic acidosis)/i, m => m[1]],
    [/(HIV|CD4)\s*([^.,;]{0,35})/i, m => `${m[1]} ${m[2].trim()}`],
  ];

  for (const [rx, fmt] of labPatterns) {
    const m = text.match(rx);
    if (m && diagnosticFindings.length < 4) {
      const f = fmt(m);
      if (f) addDiag(cut(f.replace(/\s+/g, ' ').trim().replace(/[.,;]+$/, ''), 40));
    }
  }

  // Imaging
  const imgPatterns = [
    [/(chest\s*(?:x-?ray|radiograph)|CXR)\s+(?:shows?|demonstrates?|reveals?)\s+([^.]{0,70})/i, m => `CXR showing ${cut(m[2].trim(), 50)}`],
    [/(CT\s+(?:scan)?|MRI|ultrasound|echocardiogram|echo)\s+(?:shows?|demonstrates?|reveals?)\s+([^.]{0,70})/i, m => `${m[1].trim()} showing ${cut(m[2].trim(), 50)}`],
    [/(ECG|EKG|electrocardiogram)\s+(?:shows?|demonstrates?|reveals?)\s+([^.]{0,70})/i, m => `ECG showing ${cut(m[2].trim(), 50)}`],
  ];

  for (const [rx, fmt] of imgPatterns) {
    const m = text.match(rx);
    if (m && diagnosticFindings.length < 5) {
      let f = fmt(m).replace(/\s+/g, ' ').trim().replace(/[.,;]+$/, '');
      addDiag(f);
    }
  }

  return { examFindings, diagnosticFindings };
}

// =========================================================================
// STEP 5: Clean Lead-In
// =========================================================================
function cleanTail(tail) {
  return tail
    .replace(/Which of the following is the (?:most )?appropriate next step in (?:the )?(?:management|evaluation|diagnosis|treatment)(?: of this patient)?\?/i, 'What is the most appropriate next step?')
    .replace(/Which of the following is the (?:most )?appropriate (?:initial )?(?:treatment|therapy|management|intervention)(?: for this patient)?\?/i, 'What is the most appropriate treatment?')
    .replace(/Which of the following (?:is|are) the (?:most )?likely (?:diagnosis|cause|etiology|pathogen|organism)(?: for this patient)?\?/i, 'What is the most likely diagnosis?')
    .replace(/Which of the following is the (?:most )?appropriate /i, 'What is the most appropriate ')
    .replace(/Which of the following (?:is|are) the (?:most )?likely /i, 'What is the most likely ')
    .replace(/Which of the following would be the (?:most )?appropriate /i, 'What is the most appropriate ')
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
// STEP 6: Coherent Logical Reconstruction
// =========================================================================
function rewriteQuestion(raw) {
  const text = plain(raw);
  if (text.length <= 160) return text;

  const { tail, bodyEnd } = extractQuestionTail(text);
  if (!tail) return text;

  const body = text.slice(0, bodyEnd);
  const demo = extractDemographic(body);
  const complaint = extractChiefComplaint(body);
  const { examFindings, diagnosticFindings } = extractStructuredFindings(body);
  const cleanedTail = cleanTail(tail);

  // If no demo found, we cannot reliably synthesize a tight vignette without losing structure
  if (!demo) return text;

  const sentences = [];

  // Sentence 1: Patient presentation
  let s1 = `A ${demo.age}-year-old ${demo.sex}`;
  if (demo.comorbidities) {
    s1 += ` with ${demo.comorbidities}`;
  }
  if (complaint) {
    s1 += ` presents with ${complaint}`;
  }
  sentences.push(s1 + '.');

  // Sentence 2: Clinical findings (fluent prose, no "Findings:")
  if (examFindings.length > 0 && diagnosticFindings.length > 0) {
    const examStr = examFindings.join(', ');
    const diagStr = diagnosticFindings.join(', ');
    sentences.push(`Exam reveals ${examStr}, and diagnostics show ${diagStr}.`);
  } else if (examFindings.length > 0) {
    const examStr = examFindings.join(', ');
    sentences.push(`Physical exam reveals ${examStr}.`);
  } else if (diagnosticFindings.length > 0) {
    const diagStr = diagnosticFindings.join(', ');
    sentences.push(`Diagnostic evaluation shows ${diagStr}.`);
  }

  // Sentence 3: The question lead-in
  sentences.push(cleanedTail);

  let result = sentences.join(' ').replace(/\.\./g, '.').replace(/\s+/g, ' ').trim();

  // If result is somehow not shorter or broken, keep original
  if (result.length >= text.length * 0.95 || result.length < 40) return text;

  return result;
}

// =========================================================================
// MAIN EXECUTION
// =========================================================================
console.log('Reading base original corpus from commit 2b0e118 ...');
const rawGz = execSync('git cat-file -p 2b0e1189e26fa1a65fde1000789097930bd06837:app/src/main/assets/corpus.json.gz', { maxBuffer: 50 * 1024 * 1024 });
const corpus = JSON.parse(zlib.gunzipSync(rawGz).toString('utf8'));

console.log(`Total questions in corpus: ${corpus.questions.length}`);

let rewritten = 0;
let unchanged = 0;

for (const q of corpus.questions) {
  const orig = plain(q.question);
  const neu = rewriteQuestion(q.question);
  if (neu !== orig) {
    q.question = neu.replace(/<[^>]+>/g, ' ').replace(/\s{2,}/g, ' ').trim();
    rewritten++;
  } else {
    q.question = orig;
    unchanged++;
  }
}

console.log(`Rewritten: ${rewritten} | Unchanged: ${unchanged}`);
console.log('\n--- SAMPLE REWRITES ---');
for (let i = 0; i < Math.min(8, corpus.questions.length); i++) {
  console.log(`\n[Q${i}]\n${corpus.questions[i].question}`);
}

const outJson = JSON.stringify(corpus);
const outGz = zlib.gzipSync(Buffer.from(outJson, 'utf8'), { level: 9 });
fs.writeFileSync(CORPUS_PATH, outGz);

const origMB = (rawGz.length / 1048576).toFixed(2);
const newMB = (outGz.length / 1048576).toFixed(2);
console.log(`\nSaved updated corpus: ${origMB} MB → ${newMB} MB`);
