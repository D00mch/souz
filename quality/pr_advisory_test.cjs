const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { render, readReports, selectRun, publish } = require('./pr_advisory.cjs');

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
  const pr = { number: 7, state: options.closed ? 'closed' : 'open',
    head: { sha: options.stale ? 'b'.repeat(40) : SHA, ref: 'feature', repo: { id: options.fork ? 2 : 1 } },
    base: { ref: 'kmp', sha: 'd'.repeat(40), repo: { id: 1 } } };
  const context = { repo: { owner: 'o', repo: 'r' }, payload: { repository: { id: 1 }, workflow_run: {
    id: 10, run_attempt: 2, workflow_id: 3, status: 'completed', event: 'pull_request',
    path: '.github/workflows/ci.yml', repository: { id: 1 },
    head_repository: { id: options.fork ? 2 : 1 }, head_branch: 'feature',
    pull_requests: options.fork ? [] : [{ number: pr.number, base: pr.base }],
    head_sha: SHA, conclusion: 'success', html_url: 'https://github.com/o/r/actions/runs/10',
  } } };
  const checks = options.checks || [];
  const comments = options.comments || [];
  const prs = new Map([[pr.number, pr]]);
  let gets = 0;
  const record = name => async args => {
    calls.push({ name, args });
    if (name === 'createComment') comments.push({ id: 100 + comments.length,
      user: { login: 'github-actions[bot]' }, body: args.body });
    if (name === 'updateComment') comments.find(c => c.id === args.comment_id).body = args.body;
    return { data: {} };
  };
  const github = { rest: {
    pulls: { get: async ({ pull_number }) => {
      const target = prs.get(pull_number);
      assert.ok(target, `Unexpected PR: ${pull_number}`);
      return { data: { ...target, head: { ...target.head,
        sha: options.push && ++gets > 1 ? 'c'.repeat(40) : target.head.sha } } };
    },
      listFiles: 'files' },
    issues: { listComments: 'comments', createComment: record('createComment'), updateComment: record('updateComment') },
    checks: { listForRef: 'checks', create: async args => {
      calls.push({ name: 'createCheck', args });
      checks.unshift({ ...args, id: checks.length + 100, app: { slug: 'github-actions' } });
      return { data: checks[0] };
    } }, repos: { listPullRequestsAssociatedWithCommit: 'associated' },
    actions: { listWorkflowRuns: 'runs' },
  }, paginate: async endpoint => {
    if (endpoint === 'files') return [{ filename: 'src/New.kt' }];
    if (endpoint === 'comments') return comments;
    if (endpoint === 'checks') return checks;
    if (endpoint === 'associated') return options.associated || [pr];
    if (endpoint === 'runs') return options.runs || [context.payload.workflow_run];
    throw new Error(`Unexpected endpoint: ${endpoint}`);
  } };
  return { github, context, core: { info() {} }, calls, directory, pr, prs };
}

function writeReports(f, data = reports(), runId = 10) {
  for (const [artifact, files] of [
    ['souz-quality-gate', { 'gate-summary-v1.json': data.fast }],
    ['souz-duplication-ratchet', { 'gate-summary-v1.json': data.duplication }],
    ['souz-qodana-findings', { 'qodana.sarif.json': data.qodana }],
    ['souz-repowise-pr-quality', { 'base-health.json': data.base, 'pr-health.json': data.head, 'pr-risk.json': data.risk }],
  ]) {
    const folder = path.join(f.directory, `${artifact}-${runId}`);
    fs.mkdirSync(folder, { recursive: true });
    for (const [filename, report] of Object.entries(files)) fs.writeFileSync(path.join(folder, filename), JSON.stringify(report));
  }
}

test('a late stale completion recovers the current head publication displaced from the queue', async t => {
  const runs = [], f = fixture(t, { fork: true, runs });
  const current = { ...f.context.payload.workflow_run, id: 11,
    html_url: 'https://github.com/o/r/actions/runs/11' };
  f.context.payload.workflow_run = { ...current, id: 9, head_sha: 'b'.repeat(40), pull_requests: [] };
  runs.push(f.context.payload.workflow_run, current);
  await publish(f);
  assert.deepEqual(f.calls, []); // Publishing only the surviving stale event loses the advisory.
  const selected = await selectRun(f);
  assert.equal(selected.id, 11);
  assert.equal(selected.head_sha, SHA);
  // Artifacts must come from the recovered run, not the stale triggering event.
  writeReports(f, reports(), 11);
  await publish({ ...f, run: selected });
  assert.deepEqual(f.calls.map(c => c.name), ['createComment', 'createCheck']);
  assert.match(f.calls[0].args.body, /source-run:11:2/);
  assert.equal(f.calls[1].args.head_sha, SHA);
  assert.match(f.calls[1].args.external_id, /^11:2:7:/);
  assert.equal(f.calls[1].args.conclusion, 'success');
  assert.equal(f.calls[1].args.details_url, current.html_url);
});

test('recovery binds evidence to the PR and base even when two PRs share a head', async t => {
  const runs = [], f = fixture(t, { runs });
  const source = f.context.payload.workflow_run;
  const other = { number: 8, base: { ...f.pr.base, ref: 'main' } };
  f.prs.set(8, { ...f.pr, ...other });
  const wrongRuns = [
    { ...source, id: 12, pull_requests: [other] },
    { ...source, id: 13, pull_requests: [{ number: 7, base: other.base }] },
    { ...source, id: 14, pull_requests: [{ number: 7, base: { ...f.pr.base, sha: 'e'.repeat(40) } }] },
  ];
  runs.push(source, ...wrongRuns);
  assert.equal((await selectRun(f)).id, 10);
  const mainContext = { ...f.context, payload: { ...f.context.payload, workflow_run: wrongRuns[0] } };
  assert.equal((await selectRun({ ...f, context: mainContext })).id, 12);
  runs.splice(0, 1);
  assert.equal(await selectRun(f), null);
  for (const run of wrongRuns.slice(1)) await publish({ ...f, run });
  assert.deepEqual(f.calls, []);
});

test('recovered publication retains only the verified selected-run association', async t => {
  const runs = [], f = fixture(t, { runs });
  const source = f.context.payload.workflow_run;
  const association = { ...source.pull_requests[0], url: 'https://api.github.com/repos/o/r/pulls/7' };
  source.pull_requests.push({ number: 8, base: { ...f.pr.base, ref: 'main' } });
  runs.push({ ...source, id: 11, pull_requests: [association] });
  const selected = await selectRun(f);
  assert.deepEqual(selected.pull_requests, [association]);
  await publish({ ...f, run: selected });
  assert.equal(f.calls.filter(c => c.name === 'createComment').length, 1);
  assert.equal(f.calls[0].args.issue_number, 7);
});

test('missing fork run associations fail closed when commit lookup matches both main and kmp PRs', async t => {
  const f = fixture(t, { fork: true });
  const other = { ...f.pr, number: 8, base: { ...f.pr.base, ref: 'main' } };
  const paginate = f.github.paginate;
  f.github.paginate = async (endpoint, args) => endpoint === 'associated' ? [f.pr, other] : paginate(endpoint, args);
  assert.equal(await selectRun(f), null);
  await publish(f);
  assert.deepEqual(f.calls, []);
});

test('recovery rejects ambiguous metadata-free runs even when the triggering PR is known', async t => {
  const runs = [], f = fixture(t, { runs });
  const source = f.context.payload.workflow_run;
  const other = { ...f.pr, number: 8, base: { ...f.pr.base, ref: 'main' } };
  const paginate = f.github.paginate;
  f.github.paginate = async (endpoint, args) => endpoint === 'associated' ? [f.pr, other] : paginate(endpoint, args);
  runs.push(source, { ...source, id: 11, pull_requests: [] });
  assert.equal((await selectRun(f)).id, 10);
});

test('a base retarget while reading reports prevents publication', async t => {
  const f = fixture(t);
  let reads = 0;
  f.github.rest.pulls.get = async () => ({ data: {
    ...f.pr, base: ++reads > 1 ? { ...f.pr.base, ref: 'main' } : f.pr.base,
  } });
  await publish(f);
  assert.deepEqual(f.calls, []);
});

test('selects the newest completed run and attempt only for the same workflow, head and provider branch', async t => {
  const runs = [], f = fixture(t, { runs });
  const source = f.context.payload.workflow_run;
  runs.push({ ...source, id: 11, run_attempt: 1 }, source, { ...source, id: 11, run_attempt: 2 });
  for (const override of [{ status: 'in_progress' }, { head_sha: 'b'.repeat(40) }, { head_branch: 'other' },
    { head_repository: { id: 2 } }, { repository: { id: 2 } }, { workflow_id: 4 },
    { path: '.github/workflows/other.yml' }, { event: 'push' }]) {
    runs.push({ ...source, id: 12, ...override });
  }
  const selected = await selectRun(f);
  assert.equal(selected.id, 11);
  assert.equal(selected.run_attempt, 2);
  assert.deepEqual(f.calls, []);
});

test('does not select stale evidence when current-head CI is incomplete or the PR is closed', async t => {
  for (const options of [{ runs: [] }, { stale: true }, { closed: true }]) {
    await t.test(JSON.stringify(options), async child => {
      const f = fixture(child, options);
      assert.equal(await selectRun(f), null);
      assert.deepEqual(f.calls, []);
    });
  }
});

test('a push after run selection prevents publishing the recovered advisory', async t => {
  const f = fixture(t, { push: true });
  const run = await selectRun(f);
  assert.equal(run.head_sha, SHA);
  await publish({ ...f, run });
  assert.deepEqual(f.calls, []);
});

test('missing artifacts publish a neutral check and a warning comment, including fork PR lookup', async t => {
  const f = fixture(t, { fork: true });
  await publish(f);
  assert.deepEqual(f.calls.map(c => c.name), ['createComment', 'createCheck']);
  assert.equal(f.calls[1].args.head_sha, SHA);
  assert.equal(f.calls[1].args.conclusion, 'neutral');
  assert.match(f.calls[0].args.body, /Report unavailable/);
});

test('updates only the bot-owned sticky comment and supersedes legacy checks once', async t => {
  const f = fixture(t, { comments: [
    { id: 1, user: { login: 'contributor' }, body: '<!-- souz-quality-advisory -->' },
    { id: 2, user: { login: 'github-actions[bot]' }, body: '<!-- souz-quality-advisory -->\n<!-- source-run:10:1 -->' },
  ], checks: [{ id: 1, name: 'Quality advisory', app: { slug: 'github-actions' }, external_id: '10:2' }] });
  await publish(f);
  await publish(f);
  assert.deepEqual(f.calls.map(c => c.name), ['updateComment', 'createCheck', 'updateComment']);
  assert.equal(f.calls[0].args.comment_id, 2);
  assert.match(f.calls[0].args.body, /source-run:10:2/);
});

test('artifact recovery supersedes unavailable output and adds annotations exactly once', async t => {
  const f = fixture(t);
  await publish(f);
  const unavailable = f.calls.find(c => c.name === 'createCheck').args;
  assert.match(unavailable.output.summary, /Report unavailable/);
  const data = reports();
  data.qodana.runs[0].results = [{ ruleId: 'Rule', message: { text: 'Recovered finding' },
    locations: [{ physicalLocation: { artifactLocation: { uri: 'src/New.kt' }, region: { startLine: 3 } } }] }];
  writeReports(f, data);
  await publish(f);
  await publish(f);
  const checks = f.calls.filter(c => c.name === 'createCheck').map(c => c.args);
  assert.equal(checks.length, 2);
  assert.notEqual(checks[1].external_id, unavailable.external_id);
  assert.doesNotMatch(checks[1].output.summary, /Report unavailable/);
  assert.equal(checks[1].output.annotations.length, 1);
  assert.equal(checks[1].output.annotations[0].message, 'Recovered finding');
  assert.match(f.calls.filter(c => c.name === 'updateComment').at(-1).args.body, /Recovered finding/);
  assert.equal(f.calls.filter(c => c.name === 'createComment').length, 1);
  writeReports(f);
  await publish(f);
  await publish(f);
  const clear = f.calls.filter(c => c.name === 'createCheck').map(c => c.args);
  assert.equal(clear.length, 3);
  assert.equal(clear[2].conclusion, 'success');
  assert.deepEqual(clear[2].output.annotations, []);
  writeReports(f, data);
  await publish(f);
  await publish(f);
  const restored = f.calls.filter(c => c.name === 'createCheck').map(c => c.args);
  assert.equal(restored.length, 4);
  assert.equal(restored[3].external_id, checks[1].external_id);
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
  await assert.rejects(selectRun(f), /Unexpected source workflow/);
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
