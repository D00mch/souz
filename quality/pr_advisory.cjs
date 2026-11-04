const fs = require('node:fs');
const path = require('node:path');
const { createHash } = require('node:crypto');

const MARKER = '<!-- souz-quality-advisory -->';
const CHECK_NAME = 'Quality advisory';
const GATE_STATUSES = ['pass', 'warning', 'fail', 'error', 'skipped', 'not_authoritative'];
const KPIS = {
  average_health: 'Defect health · average',
  hotspot_health: 'Defect health · hotspots',
  worst_performer_score: 'Defect health · worst file',
  maintainability_average: 'Maintainability · average',
  maintainability_hotspot: 'Maintainability · hotspots',
  performance_average: 'Performance · average',
  performance_hotspot: 'Performance · hotspots',
};

// Artifact strings are untrusted: render plain text, without mentions or Markdown.
const text = value => String(value ?? '').slice(0, 700)
  .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
  .replace(/@/g, '@\u200b').replace(/[\\`*_[\]|]/g, '\\$&').replace(/[\r\n]/g, ' ');

function repoPath(value) {
  if (typeof value !== 'string') return null;
  try { value = decodeURIComponent(value); } catch { return null; }
  return value && value.length <= 1000 && !/[:\\\x00-\x1f]/.test(value) &&
    value.split('/').every(part => part && part !== '.' && part !== '..') ? value : null;
}

function score(value, maximum = 10) {
  if (typeof value !== 'number' || !Number.isFinite(value) || value < 0 || value > maximum) {
    throw new Error('Invalid score');
  }
  return value;
}

function render({ reports, sha, runUrl, repository, changedFiles, conclusion }) {
  const rows = [], findings = [], problems = [], regressions = [];
  let highPriority = false, gateAttention = false;
  function source(name, read) {
    try { read(); } catch {
      problems.push(`${name}: report missing or invalid; see the CI run.`);
      rows.push(`| ${name} | ⚠️ Report unavailable |`);
    }
  }
  for (const [key, name] of [['fast', 'Quality Gate'], ['duplication', 'Duplicate Code Ratchet']]) {
    source(name, () => {
      const report = reports[key];
      if (report.schemaVersion !== 1 || report.prHeadSha !== sha || !GATE_STATUSES.includes(report.status) ||
          !Array.isArray(report.checks) || !report.checks.length) {
        throw new Error('Invalid gate identity');
      }
      const diagnostics = report.checks.flatMap(check => {
        if (!GATE_STATUSES.includes(check.status) || typeof check.id !== 'string' ||
            !Array.isArray(check.diagnostics)) throw new Error('Invalid check');
        if (check.status === 'pass') return [];
        if (check.diagnostics.some(d => typeof d.message !== 'string')) throw new Error('Invalid diagnostic');
        return check.diagnostics.map(d => ({
          title: check.id, message: d.message, path: repoPath(d.path), line: d.line, level: 'warning',
        }));
      });
      gateAttention ||= report.status !== 'pass' || report.checks.some(check => check.status !== 'pass');
      rows.push(`| ${name} | ${report.status === 'pass' ? '✅' : '⚠️'} ${text(report.status)}; ${diagnostics.length} findings |`);
      findings.push(...diagnostics);
    });
  }
  source('Qodana', () => {
    if (reports.qodana.version !== '2.1.0' || !reports.qodana.runs.length) throw new Error('Invalid SARIF');
    const diagnostics = reports.qodana.runs.flatMap(run => (run.results ?? []).filter(result =>
      result.baselineState !== 'absent' && result.kind !== 'pass' && result.kind !== 'notApplicable' &&
      !result.suppressions?.some(s => s.status === 'accepted') && result.level !== 'none'
    ).map(result => {
      const location = result.locations?.[0]?.physicalLocation;
      const message = result.message?.text ?? result.message?.markdown;
      if (typeof message !== 'string') throw new Error('Invalid SARIF message');
      return {
        title: `Qodana: ${result.ruleId ?? 'Inspection'}`, message,
        path: repoPath(location?.artifactLocation?.uri), line: location?.region?.startLine,
        level: result.level === 'note' ? 'notice' : 'warning',
      };
    }));
    rows.push(`| Qodana | ${diagnostics.length ? '⚠️' : '✅'} ${diagnostics.length} findings |`);
    findings.push(...diagnostics);
  });
  source('RepoWise', () => {
    const comparisons = Object.entries(KPIS).map(([key, label]) => {
      const base = score(reports.base.kpis[key]), head = score(reports.head.kpis[key]);
      return { label, base, head, delta: head - base };
    });
    const risk = reports.risk;
    score(risk.score); score(risk.risk_percentile, 100);
    if (typeof risk.review_priority !== 'string' || !risk.review_priority ||
        typeof risk.classification !== 'string' || !risk.classification) {
      throw new Error('Invalid risk');
    }
    regressions.push(...comparisons.filter(c => c.delta < 0));
    highPriority = /high|critical/i.test(risk.review_priority);
    rows.push(`| RepoWise KPIs | ${regressions.length ? '⚠️' : '✅'} ${regressions.length} regressions |`);
    rows.push(`| PR review priority | ${highPriority ? '⚠️' : ''} **${text(risk.review_priority)}**; ` +
      `${text(risk.classification)}, risk ${risk.score.toFixed(1)}/10, percentile ${risk.risk_percentile.toFixed(1)} |`);
  });
  const attention = gateAttention || findings.length > 0 || regressions.length > 0 || highPriority || problems.length > 0;
  const body = [MARKER, `## ${attention ? '⚠️' : '✅'} Souz quality advisory`, '',
    `Commit: \`${sha}\` · [CI run](${runUrl}) · CI result: **${text(conclusion)}**`, '',
    'Advisory findings do not block merging. Required CI checks retain their own result.', '',
    '| Analysis | Result |', '| --- | --- |', ...rows, '',
  ];
  if (regressions.length) {
    body.push('### RepoWise regressions', '', '| KPI | Base | PR | Delta |', '| --- | ---: | ---: | ---: |');
    for (const c of regressions) body.push(`| ${c.label} | ${c.base.toFixed(2)} | ${c.head.toFixed(2)} | ${c.delta.toFixed(2)} |`);
    body.push('');
  }
  if (findings.length) {
    body.push('### Findings', '', '“Outside PR diff” findings are not attributed to this PR as new issues.', '');
    let shown = 0;
    for (const f of findings.slice(0, 40)) {
      const location = f.path && Number.isSafeInteger(f.line) && f.line > 0;
      const url = location && `https://github.com/${repository}/blob/${sha}/${f.path.split('/').map(encodeURIComponent).join('/')}#L${f.line}`;
      const label = location ? `[${text(f.path)}:${f.line}](${url})` : 'No source location';
      const detail = `- **${text(f.title)}** — ${text(f.message)} — ${label}` +
        (f.path && !changedFiles.has(f.path) ? ' (outside PR diff)' : '');
      if (Buffer.byteLength(body.join('\n') + detail, 'utf8') > 55000) break;
      body.push(detail);
      shown++;
    }
    if (findings.length > shown) body.push(`- Showing ${shown} of ${findings.length} findings; full reports are in the CI run.`);
    body.push('');
  }
  body.push(...problems.map(p => `- ⚠️ ${p}`));
  const annotations = findings.filter(f => changedFiles.has(f.path) && Number.isSafeInteger(f.line) && f.line > 0)
    .slice(0, 50).map(f => ({
      path: f.path, start_line: f.line, end_line: f.line, annotation_level: f.level,
      title: String(f.title).slice(0, 255), message: String(f.message).slice(0, 2000),
    }));
  return { body: body.join('\n'), annotations, attention };
}

function readReports(directory, runId) {
  const files = {
    fast: ['souz-quality-gate', 'gate-summary-v1.json'],
    duplication: ['souz-duplication-ratchet', 'gate-summary-v1.json'],
    qodana: ['souz-qodana-findings', 'qodana.sarif.json'],
    base: ['souz-repowise-pr-quality', 'base-health.json'],
    head: ['souz-repowise-pr-quality', 'pr-health.json'],
    risk: ['souz-repowise-pr-quality', 'pr-risk.json'],
  };
  return Object.fromEntries(Object.entries(files).map(([key, [artifact, file]]) => {
    let report;
    try {
      const filename = path.join(directory, `${artifact}-${runId}`, file);
      const stat = fs.lstatSync(filename);
      if (stat.isFile() && stat.size <= 16 * 1024 * 1024) report = JSON.parse(fs.readFileSync(filename, 'utf8'));
    } catch { /* Render missing and malformed reports as unavailable, never as passes. */ }
    return [key, report];
  }));
}

function matchesProvider(run, pr) {
  return pr.state === 'open' && pr.base.repo.id === run.repository.id &&
    pr.head.repo?.id === run.head_repository?.id && pr.head.ref === run.head_branch;
}

function sameBase(candidate, pr) {
  return candidate.base?.repo?.id === pr.base.repo.id && candidate.base.ref === pr.base.ref &&
    typeof candidate.base.sha === 'string' && candidate.base.sha === pr.base.sha;
}

async function candidatesForRun(github, context, run) {
  if (run.event !== 'pull_request' || run.path !== '.github/workflows/ci.yml' ||
      run.repository.id !== context.payload.repository.id) throw new Error('Unexpected source workflow');
  if (run.pull_requests.length) return run.pull_requests;
  const associated = await github.paginate(
    github.rest.repos.listPullRequestsAssociatedWithCommit, { ...context.repo, commit_sha: run.head_sha, per_page: 100 });
  // Fork runs can omit PR metadata. A commit shared by multiple open PRs cannot
  // identify which base was analyzed, so never guess between those PRs.
  const candidates = associated.filter(pr => matchesProvider(run, pr));
  return candidates.length === 1 ? candidates : [];
}

async function selectRun({ github, context }) {
  const source = context.payload.workflow_run;
  const candidates = await candidatesForRun(github, context, source);
  if (!source.head_repository) return null;
  for (const candidate of candidates) {
    const { data: pr } = await github.rest.pulls.get({ ...context.repo, pull_number: candidate.number });
    if (pr.number !== candidate.number || !matchesProvider(source, pr) || !sameBase(candidate, pr)) continue;
    // Actions can replace the pending current-head publisher with a late stale event.
    // Reconcile the live head before choosing which run's artifacts to download.
    const runs = await github.paginate(github.rest.actions.listWorkflowRuns, {
      ...context.repo, workflow_id: source.workflow_id, head_sha: pr.head.sha,
      event: 'pull_request', status: 'completed', per_page: 100,
    });
    const eligible = runs.filter(run => run.status === 'completed' && run.head_sha === pr.head.sha &&
      run.workflow_id === source.workflow_id && run.event === 'pull_request' && run.path === source.path &&
      run.repository.id === source.repository.id && run.head_repository?.id === source.head_repository.id &&
      run.head_branch === source.head_branch)
      .sort((a, b) => b.id - a.id || b.run_attempt - a.run_attempt);
    for (const selected of eligible) {
      const associations = await candidatesForRun(github, context, selected);
      const association = associations.find(c => c.number === pr.number && sameBase(c, pr));
      if (association) return { ...selected, pull_requests: [association] };
    }
  }
  return null;
}

async function publish({ github, context, core, run = context.payload.workflow_run }) {
  const repo = context.repo;
  const candidates = await candidatesForRun(github, context, run);
  for (const candidate of candidates) {
    const { data: pr } = await github.rest.pulls.get({ ...repo, pull_number: candidate.number });
    if (pr.number !== candidate.number || pr.head.sha !== run.head_sha ||
        !matchesProvider(run, pr) || !sameBase(candidate, pr)) continue;
    const comments = await github.paginate(github.rest.issues.listComments, { ...repo, issue_number: pr.number, per_page: 100 });
    const previous = comments.find(c => c.user?.login === 'github-actions[bot]' && c.body.startsWith(MARKER));
    const stamp = previous?.body.match(/<!-- source-run:(\d+):(\d+) -->/);
    if (stamp && (Number(stamp[1]) > run.id ||
        (Number(stamp[1]) === run.id && Number(stamp[2]) > run.run_attempt))) continue;
    const files = await github.paginate(github.rest.pulls.listFiles, { ...repo, pull_number: pr.number, per_page: 100 });
    const result = render({
      reports: readReports(process.env.SOUZ_ADVISORY_REPORTS, run.id), sha: pr.head.sha,
      runUrl: `https://github.com/${repo.owner}/${repo.repo}/actions/runs/${run.id}/attempts/${run.run_attempt}`,
      repository: `${repo.owner}/${repo.repo}`, changedFiles: new Set(files.map(f => f.filename)), conclusion: run.conclusion,
    });
    const body = `${result.body}\n\n<!-- source-run:${run.id}:${run.run_attempt} -->`;
    // A push or base retarget while reading artifacts must not replace the advisory.
    const { data: current } = await github.rest.pulls.get({ ...repo, pull_number: pr.number });
    if (current.head.sha !== run.head_sha || !matchesProvider(run, current) || !sameBase(candidate, current)) continue;
    if (previous) await github.rest.issues.updateComment({ ...repo, comment_id: previous.id, body });
    else await github.rest.issues.createComment({ ...repo, issue_number: pr.number, body });
    const check = {
      status: 'completed', conclusion: result.attention ? 'neutral' : 'success', details_url: run.html_url,
      output: { title: result.attention ? 'Quality findings need review' : 'No quality findings',
        summary: result.body, annotations: result.annotations },
    };
    // GitHub appends annotations on updates. Supersede changed output instead,
    // including recovered downloads, and reuse the latest check on identical retries.
    const digest = createHash('sha256').update(JSON.stringify(check)).digest('hex');
    const externalId = `${run.id}:${run.run_attempt}:${pr.number}:${digest}`;
    const checks = await github.paginate(github.rest.checks.listForRef, { ...repo, ref: pr.head.sha, per_page: 100 });
    const latest = checks.filter(c => c.name === CHECK_NAME && c.app?.slug === 'github-actions')
      .sort((a, b) => b.id - a.id)[0];
    if (latest?.external_id !== externalId) {
      await github.rest.checks.create({
        ...repo, name: CHECK_NAME, head_sha: pr.head.sha, external_id: externalId,
        ...check,
      });
    }
    core.info(`Published quality advisory for PR #${pr.number} at ${pr.head.sha}`);
  }
}

module.exports = { render, readReports, selectRun, publish };
