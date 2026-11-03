const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { render, readReports, publish } = require('./pr_advisory.cjs');

const SHA = 'a'.repeat(40);
const KPIS = ['average_health', 'hotspot_health', 'worst_performer_score', 'maintainability_average',
  'maintainability_hotspot', 'performance_average', 'performance_hotspot'];
const gate = () => ({ schemaVersion: 1, prHeadSha: SHA, status: 'pass',
  checks: [{ id: 'layered-mutability', status: 'pass', diagnostics: [] }] });
const health = () => ({ kpis: Object.fromEntries(KPIS.map(key => [key, 8])) });
const reports = () => ({ fast: gate(), duplication: gate(), qodana: { version: '2.1.0', runs: [{ results: [] }] },
  base: health(), head: health(), risk: { score: 7.2, risk_percentile: 60, review_priority: 'moderate', classification: 'Typical' } });
const renderReport = data => render({ reports: data, sha: SHA, runUrl: 'https://github.com/o/r/actions/runs/10',
  repository: 'o/r', changedFiles: new Set(['src/New.kt']), conclusion: 'success' });

test('all clear replaces warning content with explicit successful results', () => {
  const data = reports();
  data.duplication.checks[0].diagnostics = [{ message: 'production: duplicated tokens 131 baseline -> 131 current' }];
  const result = renderReport(data);
  assert.equal(result.attention, false);
  assert.match(result.body, /✅ Souz quality advisory/);
  assert.match(result.body, /0 regressions/);
  assert.deepEqual(result.annotations, []);
});

test('reports all four signals while keeping unchanged-file debt out of annotations', () => {
  const data = reports();
  data.fast.status = data.fast.checks[0].status = 'warning';
  data.fast.checks[0].diagnostics = [{ path: 'backend/WaitingPhrases.kt', line: 20, message: 'Mutable records' }];
  data.qodana.runs[0].results = [51, 75].map(line => ({ ruleId: 'ConvertLongToDuration', level: 'warning',
    message: { text: 'Use Duration' }, locations: [{ physicalLocation: {
      artifactLocation: { uri: 'src/New.kt', uriBaseId: 'SRCROOT' }, region: { startLine: line } } }] }));
  data.head.kpis.average_health = 7.98;
  data.head.kpis.hotspot_health = 7.94;
  data.risk.review_priority = 'high';
  const result = renderReport(data);
  assert.equal(result.attention, true);
  for (const expected of ['2 findings', 'layered-mutability', 'outside PR diff', '-0.02', '-0.06', '**high**']) {
    assert.ok(result.body.includes(expected), expected);
  }
  assert.deepEqual(result.annotations.map(a => [a.path, a.start_line, a.annotation_level]),
    [['src/New.kt', 51, 'warning'], ['src/New.kt', 75, 'warning']]);
});

test('high review priority alone is visible even without KPI regressions', () => {
  const data = reports();
  data.risk.review_priority = 'high';
  assert.equal(renderReport(data).attention, true);
});

test('missing, malformed and mismatched reports never produce an all-clear advisory', () => {
  for (const mutate of [d => { delete d.fast; }, d => { d.fast.prHeadSha = 'b'.repeat(40); },
    d => { d.fast.status = 'invalid'; },
    d => { d.qodana.runs = []; }, d => { d.head.kpis.average_health = 'bad'; },
    d => { d.risk.score = Infinity; }]) {
    const data = reports(); mutate(data);
    const result = renderReport(data);
    assert.equal(result.attention, true);
    assert.match(result.body, /report missing or invalid/);
  }
  const data = reports();
  data.fast.status = data.fast.checks[0].status = 'error';
  assert.equal(renderReport(data).attention, true);
});

test('SARIF resolved/suppressed findings are omitted; notes retain notice severity', () => {
  const data = reports();
  const result = { ruleId: 'Rule', message: { text: 'Note' }, level: 'note',
    locations: [{ physicalLocation: { artifactLocation: { uri: 'src/New.kt' }, region: { startLine: 3 } } }] };
  data.qodana.runs[0].results = [result, { ...result, baselineState: 'absent' }, { ...result, kind: 'pass' },
    { ...result, suppressions: [{ status: 'accepted' }] }, { ...result, level: 'none' }];
  assert.deepEqual(renderReport(data).annotations.map(a => a.annotation_level), ['notice']);
});

test('artifact content cannot inject Markdown, mentions or unsafe source paths; output is bounded', () => {
  const data = reports();
  data.fast.status = data.fast.checks[0].status = 'warning';
  data.fast.checks[0].diagnostics = Array.from({ length: 80 }, (_, i) => ({ path: 'src/New.kt', line: i + 1,
    message: '@reviewer [click](https://evil.test) <img src=x>\n<!-- source-run:999:1 -->' }));
  const badPaths = ['../src/New.kt', '/src/New.kt', 'file:///secret', '%2e%2e/src/New.kt', 'C:\\secret'];
  data.fast.checks[0].diagnostics.push(...badPaths.map(p => ({ path: p, line: 1, message: 'Bad path' })));
  const result = renderReport(data);
  assert.equal(result.annotations.length, 50);
  assert.match(result.body, /Showing 40 of 85/);
  assert.ok(result.body.length < 65535);
  assert.ok(!result.body.includes('@reviewer'));
  assert.ok(!result.body.includes('<img'));
  assert.ok(!result.body.includes('<!-- source-run:999:1 -->'));
  assert.ok(!result.body.includes('[click](https://evil.test)'));
  data.fast.checks[0].diagnostics = Array.from({ length: 80 }, () => ({ path: '很'.repeat(900), line: 1,
    message: '<'.repeat(700) }));
  assert.ok(Buffer.byteLength(renderReport(data).body, 'utf8') < 65535);
});

function fixture(t, options = {}) {
  const calls = [], directory = fs.mkdtempSync(path.join(os.tmpdir(), 'souz-advisory-'));
  const oldDirectory = process.env.SOUZ_ADVISORY_REPORTS;
  process.env.SOUZ_ADVISORY_REPORTS = directory;
  t.after(() => {
    fs.rmSync(directory, { recursive: true, force: true });
    if (oldDirectory === undefined) delete process.env.SOUZ_ADVISORY_REPORTS;
    else process.env.SOUZ_ADVISORY_REPORTS = oldDirectory;
  });
  const context = { repo: { owner: 'o', repo: 'r' }, payload: { repository: { id: 1 }, workflow_run: {
    id: 10, run_attempt: 2, event: 'pull_request', path: '.github/workflows/ci.yml', repository: { id: 1 },
    pull_requests: options.fork ? [] : [{ number: 7 }], head_sha: SHA, conclusion: 'success', html_url: 'https://github.com/o/r/actions/runs/10',
  } } };
  const pr = { number: 7, state: 'open', head: { sha: options.stale ? 'b'.repeat(40) : SHA }, base: { repo: { id: 1 } } };
  let gets = 0;
  const record = name => async args => { calls.push({ name, args }); return { data: {} }; };
  const github = { rest: {
    pulls: { get: async () => ({ data: { ...pr, head: { sha: options.push && ++gets > 1 ? 'c'.repeat(40) : pr.head.sha } } }),
      listFiles: 'files' },
    issues: { listComments: 'comments', createComment: record('createComment'), updateComment: record('updateComment') },
    checks: { listForRef: 'checks', create: record('createCheck') }, repos: { listPullRequestsAssociatedWithCommit: 'associated' },
  }, paginate: async endpoint => {
    if (endpoint === 'files') return [{ filename: 'src/New.kt' }];
    if (endpoint === 'comments') return options.comments || [];
    if (endpoint === 'checks') return options.checks || [];
    if (endpoint === 'associated') return [{ number: 7 }];
    throw new Error(`Unexpected endpoint: ${endpoint}`);
  } };
  return { github, context, core: { info() {} }, calls, directory };
}

test('missing artifacts publish a neutral check and a warning comment, including fork PR lookup', async t => {
  const f = fixture(t, { fork: true });
  await publish(f);
  assert.deepEqual(f.calls.map(c => c.name), ['createComment', 'createCheck']);
  assert.equal(f.calls[1].args.head_sha, SHA);
  assert.equal(f.calls[1].args.conclusion, 'neutral');
  assert.match(f.calls[0].args.body, /Report unavailable/);
});

test('updates only the bot-owned sticky comment and does not duplicate annotations on retry', async t => {
  const f = fixture(t, { comments: [
    { id: 1, user: { login: 'contributor' }, body: '<!-- souz-quality-advisory -->' },
    { id: 2, user: { login: 'github-actions[bot]' }, body: '<!-- souz-quality-advisory -->\n<!-- source-run:10:1 -->' },
  ], checks: [{ name: 'Quality advisory', app: { slug: 'github-actions' }, external_id: '10:2' }] });
  await publish(f);
  assert.deepEqual(f.calls.map(c => c.name), ['updateComment']);
  assert.equal(f.calls[0].args.comment_id, 2);
  assert.match(f.calls[0].args.body, /source-run:10:2/);
});

test('stale heads, concurrent pushes and older runs/attempts cannot overwrite the advisory', async t => {
  for (const options of [{ stale: true }, { push: true },
    ...['11:1', '10:3'].map(stamp => ({ comments: [{ id: 2, user: { login: 'github-actions[bot]' },
      body: `<!-- souz-quality-advisory -->\n<!-- source-run:${stamp} -->` }] }))]) {
    await t.test(JSON.stringify(options), async child => {
      const f = fixture(child, options);
      await publish(f);
      assert.deepEqual(f.calls, []);
    });
  }
});

test('unexpected source workflows are rejected before publishing', async t => {
  const f = fixture(t);
  f.context.payload.workflow_run.path = '.github/workflows/other.yml';
  await assert.rejects(publish(f), /Unexpected source workflow/);
  assert.deepEqual(f.calls, []);
});

test('reads only expected JSON files, treating invalid JSON and symlinks as unavailable', t => {
  const f = fixture(t), folder = path.join(f.directory, 'souz-quality-gate-10');
  fs.mkdirSync(folder);
  const filename = path.join(folder, 'gate-summary-v1.json');
  fs.writeFileSync(filename, JSON.stringify(gate()));
  assert.equal(readReports(f.directory, 10).fast.prHeadSha, SHA);
  fs.writeFileSync(filename, 'invalid');
  assert.equal(readReports(f.directory, 10).fast, undefined);
  fs.unlinkSync(filename);
  fs.symlinkSync(__filename, filename);
  assert.equal(readReports(f.directory, 10).fast, undefined);
});
