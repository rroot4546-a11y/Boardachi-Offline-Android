#!/usr/bin/env node
/**
 * condense_corpus.js
 * Reads corpus.json.gz, replaces every question's long clinical vignette
 * with a condensed "core idea" summary, then writes back corpus.json.gz.
 *
 * Port of the Kotlin condenseStem() logic from ShortQ.kt — same rules:
 *   age/sex + comorbidities + chief complaint + abnormal vitals +
 *   positive exam/labs/imaging discriminators + question intent.
 *   Normal values, normal labs, and filler phrasing are dropped.
 */

const fs = require('fs');
const zlib = require('zlib');
const path = require('path');

const CORPUS_PATH = path.join(__dirname, '..', 'app', 'src', 'main', 'assets', 'corpus.json.gz');

// ---- helpers ----
function plain(s) {
  return s.replace(/<[^>]+>/g, '').replace(/&[^;]+;/g, ' ').replace(/\s+/g, ' ').trim();
}

const AGE_SEX = /(\d{1,3})\s?-\s?year\s?-\s?old\s+(woman|man|female|male|girl|boy)/i;

const COMORBID = [
  [/hypertension/i, 'HTN'],
  [/hyperlipidemia/i, 'HL'],
  [/diabetes( mellitus)?/i, 'DM'],
  [/coronary artery disease|ischemic heart disease/i, 'CAD'],
  [/heart failure/i, 'HF'],
  [/chronic kidney disease/i, 'CKD'],
  [/\bcopd\b/i, 'COPD'],
  [/\basthma\b/i, 'Asthma'],
  [/atrial fibrillation/i, 'AF'],
  [/\bobesity\b|\bobese\b/i, 'Obese'],
  [/smok\w*/i, 'Smoker'],
  [/\bhiv\b|human immunodeficiency/i, 'HIV'],
  [/syphilis/i, 'Syphilis'],
  [/tuberculosis|\btb\b/i, 'TB'],
];

const DURATION = /(\d+)\s?-\s?(day|week|month|year)s?\s+(?:history\s+)?of\s+([^.,;]+)/i;

function abnormalVitals(s) {
  const out = [];
  let m;
  m = s.match(/fever of ([\d.]+)/i);
  if (m) out.push(`T ${m[1]}°F`);
  else if (/\bfebrile\b/i.test(s)) out.push('Febrile');

  m = s.match(/heart rate (\d+)/i);
  if (m) { const hr = +m[1]; if (hr >= 100 || (hr >= 1 && hr <= 59)) out.push(`HR${hr}`); }

  m = s.match(/blood pressure (\d+)\/(\d+)/i);
  if (m) { const sys = +m[1]; if (sys < 100 || sys >= 180) out.push(`BP ${m[1]}/${m[2]}`); }

  m = s.match(/respiratory rate (\d+)/i);
  if (m) { const rr = +m[1]; if (rr >= 22 || (rr >= 1 && rr <= 11)) out.push(`RR${rr}`); }

  m = s.match(/oxygen saturation (\d+)[^.,;]*?(?:and (\d+)[^.,;]*?with ambulation)?/i);
  if (m) {
    const o2 = +m[1];
    if (o2 < 95) {
      out.push(m[2] ? `O2 ${o2}%→${m[2]}% ambul` : `O2 ${o2}% RA`);
    }
  }
  return out;
}

const KEEP_PATTERNS = [
  /(crackles|rales)[^.,;]*/i,
  /(wheezes|wheezing)[^.,;]*/i,
  /(decreased breath sounds|bronchial breathing|egophony|dullness to percussion)[^.,;]*/i,
  /((new |systolic |diastolic )?(regurgitant )?murmur[^.,;]*)/i,
  /(S3|S4|gallop|rub|JVD|jugular[^.,;]*)/i,
  /((tender|guarding|rebound|rigid)[^.,;]{0,40})/i,
  /((hepatomegaly|splenomegaly|lymphadenopathy)[^.,;]*)/i,
  /((edema|clubbing|cyanosis|pallor|icterus|jaundice)[^.,;]*)/i,
  /((weakness|deficit|palsy|aphasia|neglect|confusion|cachectic|chronically ill)[^.,;]*)/i,
  /((rash|purpura|petechiae|ulcer|nodule|white plaques)[^.,;]*)/i,
  /(white blood cell count[^.,;]*elevated[^.,;]*|WBC[^.,;]*\d[\d,]*[^.,;]*)/i,
  /((leukocytosis|neutrophil\w* predominance|lymphocytosis)[^.,;]*)/i,
  /((anemia|thrombocyt\w+|hematuria|proteinuria|hyperkalemia|hyponatremia)[^.,;]*)/i,
  /((HIV|CD4|viral load)[^.,;]{0,50})/i,
  /((chest x-?ray|CXR|CT|MRI|ultrasound|echocardiogram|ECG)[^.,;]{0,80})/i,
  /((infiltrate|consolidation|effusion|pneumothorax|interstitial[^.,;]*|vegetation|ejection fraction[^.,;]*)[^.,;]*)/i,
];

const NOISE = [
  /basic laboratory testing reveals normal[^.]*\./i,
  /(hematocrit|platelets?|electrolytes?|creatinine|liver function)[^.,;]*normal[^.,;]*/i,
  /no pleural effusion/i,
  /on physical examination, (he|she) (is|has) (afebrile|comfortable)[^.,;]*/i,
];

const QUESTION_TAIL = [
  [/most likely diagnosis|diagnosis[^?]{0,40}\?/i, '→ Most likely Dx?'],
  [/most likely pathogen|causative organism|etiologic agent[^?]{0,40}\?/i, '→ Pathogen?'],
  [/appropriate (treatment|management|therapy)|treatment of choice[^?]{0,40}\?/i, '→ Best Rx?'],
  [/most appropriate next step|next step in management[^?]{0,40}\?/i, '→ Next step?'],
  [/should this patient be admitted[^?]*\?/i, '→ Admit?'],
  [/should not raise[^?]*\?/i, '→ NOT suggestive?'],
  [/which of the following[^?]*\?/i, '→ ?'],
];

function condenseStem(raw) {
  const s = plain(raw);
  if (s.length <= 140) return s;
  const out = [];

  const ageSex = AGE_SEX.exec(s);
  if (ageSex) {
    const age = ageSex[1];
    const w = ageSex[2].toLowerCase();
    out.push(age + (['w','f','g'].some(c => w.startsWith(c)) || w === 'female' ? 'F' : 'M'));
  }

  const comor = COMORBID.filter(([rx]) => rx.test(s)).map(([, label]) => label);
  const unique = [...new Set(comor)].slice(0, 5);
  if (unique.length) out.push(unique.join('+'));

  const dur = DURATION.exec(s);
  if (dur) {
    const unit = { d: 'd', w: 'w', m: 'mo', y: 'y' }[dur[2].toLowerCase()[0]] || dur[2];
    let complaint = dur[3].trim().replace(/^(a|an|the)\s+/i, '');
    if (complaint.length > 70) complaint = complaint.slice(0, 70).trimEnd() + '…';
    out.push(`${dur[1]}${unit} ${complaint}`);
  }

  out.push(...abnormalVitals(s));

  const seen = new Set();
  for (const rx of KEEP_PATTERNS) {
    let m;
    const g = new RegExp(rx.source, 'gi');
    while ((m = g.exec(s)) !== null) {
      let hit = m[0].trim().replace(/[.,;]+$/, '')
        .replace(/^(On lung examination, |Chest radiography demonstrates |Basic laboratory[^,]*, )/i, '')
        .replace(/\s+/g, ' ');
      if (hit.length > 65) hit = hit.slice(0, 65).trimEnd() + '…';
      const key = hit.slice(0, 14).toLowerCase();
      if (hit.length >= 4 && !seen.has(key) && out.length < 9) {
        seen.add(key);
        out.push(hit);
      }
    }
  }

  let tail = '→ ?';
  for (const [rx, label] of QUESTION_TAIL) {
    if (rx.test(s)) { tail = label; break; }
  }
  out.push(tail);
  return out.join(' • ');
}

// ---- main ----
console.log('Reading corpus.json.gz ...');
const buf = fs.readFileSync(CORPUS_PATH);
const json = zlib.gunzipSync(buf).toString('utf8');
const corpus = JSON.parse(json);

console.log(`Total questions: ${corpus.questions.length}`);

let condensed = 0;
for (const q of corpus.questions) {
  const orig = q.question;
  const short = condenseStem(orig);
  if (short !== plain(orig)) {
    q.question = short;
    condensed++;
  }
}

console.log(`Condensed: ${condensed} questions`);
console.log('Sample condensed questions:');
for (let i = 0; i < Math.min(5, corpus.questions.length); i++) {
  console.log(`  [${i}] ${corpus.questions[i].question.slice(0, 120)}`);
}

const outJson = JSON.stringify(corpus);
const outGz = zlib.gzipSync(Buffer.from(outJson, 'utf8'), { level: 9 });
fs.writeFileSync(CORPUS_PATH, outGz);

const origMB = (buf.length / 1048576).toFixed(2);
const newMB = (outGz.length / 1048576).toFixed(2);
console.log(`Done. ${origMB} MB → ${newMB} MB`);
