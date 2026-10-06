// Builds the differential fixture for CitationGuard and GroundingDetails (docs/specs/0.3-citations-and-input-classifier.md).
// It runs the TypeScript originals from the Nox site over answers made from the site's real content, and writes
// what they returned. The Java tests replay every case and compare.
//
// Usage (Node 22+, with the site checked out next to this repository):
//   node tools/parity/citations-fixture.mjs ../marcusrdrigues.com > noxguard-core/src/test/resources/citations-parity.tsv
//
// Format: the first line is the table of passages (base64 each, UTF-8, joined by ","). Then one case per line,
// tab-separated, text fields in base64:
//   answer, question, sources (indexes into the table, joined by ","), expected text, removed, recited, empty,
//   cites (comma-separated), outcomes (comma-separated), ungrounded details of the plain answer ("KIND:key" joined by "|")
import { readFileSync, readdirSync } from "node:fs";
import { join, resolve } from "node:path";
import { pathToFileURL } from "node:url";

const site = resolve(process.argv[2] ?? "../marcusrdrigues.com");
const { checkCitations, stripCitations } = await import(pathToFileURL(join(site, "src/domain/citations.ts")).href);
const { unsupportedDetails } = await import(pathToFileURL(join(site, "src/domain/grounding.ts")).href);

// Seeded generator: the fixture is the same on every run.
let seed = 20261006;
const rand = () => ((seed = (seed * 1103515245 + 12345) % 2147483648) / 2147483648);
const pick = (list) => list[Math.floor(rand() * list.length)];

// Real prose from the site: every string long enough to be a passage. Passages about a client or a contract stay
// out: the fixture is committed to this repository, and only Marcus's own work belongs in it.
const CLIENT = /CEDAE|contrat|contract|cliente|customer/i;
const texts = [];
const walk = (v) => {
  if (typeof v === "string") {
    if (v.length > 60 && !v.startsWith("http") && !CLIENT.test(v)) texts.push(v);
  } else if (Array.isArray(v)) v.forEach(walk);
  else if (v && typeof v === "object") Object.values(v).forEach(walk);
};
for (const locale of ["pt", "en"]) {
  walk(JSON.parse(readFileSync(join(site, `src/content/${locale}/site.json`), "utf8")));
  for (const dir of ["notes", "projects"]) {
    for (const f of readdirSync(join(site, `src/content/${locale}/${dir}`))) walk(JSON.parse(readFileSync(join(site, `src/content/${locale}/${dir}/${f}`), "utf8")));
  }
}

const sentencesOf = (t) => t.split(/(?<=[.!?])\s+/).filter((s) => s.length > 20);
const FOREIGN = ["Microsoft", "Google", "Nubank", "Itaú", "AWS Lambda", "Kubernetes", "OpenShift", "Rio Grande do Sul"];
const DEPENDENT = ["Ele também trabalhou nisso", "Isso levou três meses", "He also did that", "That was in 2025"];
const b64 = (s) => Buffer.from(s, "utf8").toString("base64");

function mutate(sentence) {
  const r = rand();
  if (r < 0.15) return sentence.replace(/\d+/, (n) => String(Number(n) + 1 + Math.floor(rand() * 5)));
  if (r < 0.25) return sentence.replace(/\.$/, `, com a ${pick(FOREIGN)}.`);
  if (r < 0.32) return sentence.replace(/[.!?]$/, ": " + pick(["cuida da IA", "runs the AI layer", "em 2026"]) + ".");
  return sentence;
}

const lines = [];
for (let i = 0; i < 1500; i++) {
  const ids = Array.from({ length: 2 + Math.floor(rand() * 5) }, () => Math.floor(rand() * texts.length));
  const sources = ids.map((id) => texts[id]);
  const parts = [];
  const count = 1 + Math.floor(rand() * 3);
  for (let k = 0; k < count; k++) {
    const si = Math.floor(rand() * sources.length);
    let sentence = mutate(pick(sentencesOf(sources[si])) ?? sources[si].slice(0, 80) + ".");
    const r = rand();
    const cite = r < 0.6 ? si + 1 : r < 0.75 ? ((si + 1) % sources.length) + 1 : r < 0.85 ? sources.length + 3 : 0;
    const marks = cite ? (rand() < 0.15 ? `[${cite}][${1 + Math.floor(rand() * sources.length)}]` : `[${cite}]`) : "";
    if (marks && rand() < 0.1) sentence = `${sentence} ${marks}`; // citation after the full stop
    else if (marks) sentence = sentence.replace(/([.!?])?$/, (_, p) => ` ${marks}${p ?? ""}`);
    parts.push(sentence);
    if (k === 0 && rand() < 0.15) parts.push(`${pick(DEPENDENT)} [${si + 1}].`);
  }
  if (rand() < 0.1) parts.push(`De 2023 a 2026, ${pick(["3", "4"])} anos [1].`);
  const answer = parts.join(rand() < 0.1 ? "\n" : " ");
  const question = rand() < 0.3 ? `Ele trabalhou na ${pick(FOREIGN)} em 2024?` : "";
  const r = checkCitations(answer, sources, question);
  const ungrounded = unsupportedDetails(stripCitations(answer), sources, question).map((d) => `${d.kind.toUpperCase()}:${d.key}`);
  const outcome = (s) => (s.kept ? (s.reason === "recited" ? "RECITED" : "KEPT") : s.reason === "coherence" ? "REMOVED_WITH_PREVIOUS" : "REMOVED");
  lines.push(
    [
      b64(answer),
      b64(question),
      ids.join(","),
      b64(r.text),
      r.removed,
      r.recited,
      r.empty,
      r.cites.join(","),
      r.sentences.map(outcome).join(","),
      b64(ungrounded.join("|")),
    ].join("\t"),
  );
}
process.stdout.write([texts.map(b64).join(","), ...lines].join("\n") + "\n");
