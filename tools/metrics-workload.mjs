// Node 22+. Uses the real configured provider; creates four chats for a fresh synthetic user.
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { setTimeout as delay } from 'node:timers/promises';

const base = process.env.SOUZ_URL ?? 'http://127.0.0.1:8080';
const model = process.env.SOUZ_MODEL ?? 'gpt-5.2';
const userId = randomUUID();
const waitMs = 10_000; // Two Prometheus scrapes while a client tool is pending.
console.log(JSON.stringify({ userId, base, model }));

async function run(scenario) {
  const response = await fetch(`${base}/v1/chats`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ userId, requestId: randomUUID(), clientType: 'backend', title: `Metrics: ${scenario}` }),
    signal: AbortSignal.timeout(10_000),
  });
  const body = await response.json();
  assert.equal(response.status, 201, JSON.stringify(body));
  const chatId = body.chat.id;
  const url = new URL(`${base}/v1/chats/${chatId}/ws?clientType=backend`);
  url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:';
  const socket = new WebSocket(url);
  const started = Date.now();
  let threadId, terminal, duplicate = false, toolCalls = 0;
  const timers = new Set();
  const send = frame => socket.send(JSON.stringify({ chatId, ...frame }));
  const submit = {
    kind: 'message.submit', requestId: randomUUID(),
    payload: {
      device: { userId, deviceId: 'metrics-workload', deviceType: 'smartphone', capabilities: ['screen', 'device_tools'] },
      content: { type: 'text', source: 'text', text: scenario === 'reply'
        ? 'Reply with exactly: Metrics workload ready. Do not use tools.'
        : 'Use the user-ask Skill to ask me to choose tea or coffee. Invoke RunSkillCommand once to ask and wait for my answer. Do not use other Skills. After my answer, reply in one sentence. If the client reports a timeout, state that no choice was received and do not retry.' },
      meta: { model, locale: 'en-US', timeZone: 'UTC' },
    },
  };
  const cancel = () => send({ kind: 'thread.cancel', requestId: randomUUID(), threadId, reason: 'user_requested' });
  try {
    await new Promise((resolve, reject) => {
      const schedule = (action, ms) => {
        const timer = setTimeout(() => { timers.delete(timer); action(); }, ms);
        timers.add(timer);
      };
      schedule(() => reject(new Error(`${scenario}: no terminal event within 180 seconds`)), 180_000);
      socket.addEventListener('error', () => reject(new Error(`${scenario}: WebSocket error`)));
      socket.addEventListener('close', () => { if (!terminal) reject(new Error(`${scenario}: socket closed early`)); });
      socket.addEventListener('open', () => send(submit));
      socket.addEventListener('message', event => {
        try {
          const frame = JSON.parse(event.data);
          if (frame.kind === 'error' || frame.status === 'rejected') throw new Error(JSON.stringify(frame));
          threadId ??= frame.thread?.id ?? frame.threadId;
          if (frame.kind === 'ack' && frame.requestId === submit.requestId) {
            if (frame.duplicate) duplicate = true;
            else if (scenario === 'reply') send(submit); // Idempotent retry must reuse the execution.
          }
          if (frame.type === 'tool.call.started') {
            assert.equal(frame.payload.name, 'user.ask', 'Only the clarification Skill is expected');
            toolCalls++;
            assert.equal(toolCalls, 1, 'The workload must not retry client calls');
            console.log(JSON.stringify({ scenario, chatId, threadId, event: 'client_wait' }));
            schedule(() => {
              if (scenario === 'cancel') cancel();
              else send({
                kind: 'tool.result', threadId: frame.threadId, toolCallId: frame.payload.toolCallId,
                ...(scenario === 'timeout'
                  ? { status: 'timed_out', error: { code: 'client_tool_timed_out', message: 'Emulated device deadline expired.' } }
                  : { status: 'succeeded', result: { answer: 'Tea, please.' } }),
              });
            }, waitMs);
          }
          if (['thread.completed', 'thread.failed', 'thread.cancelled'].includes(frame.type)) {
            terminal = frame.type;
            resolve();
          }
        } catch (error) { reject(error); }
      });
    });
    assert.equal(terminal, scenario === 'cancel' ? 'thread.cancelled' : 'thread.completed');
    assert.equal(toolCalls, scenario === 'reply' ? 0 : 1);
    if (scenario === 'reply') assert.ok(duplicate, 'Idempotent retry acknowledgement missing');
    console.log(JSON.stringify({ scenario, chatId, threadId, terminal, toolCalls, duplicate, seconds: (Date.now() - started) / 1000 }));
  } finally {
    for (const timer of timers) clearTimeout(timer);
    if (!terminal && threadId && socket.readyState === WebSocket.OPEN) {
      cancel();
      await delay(1000);
    }
    socket.close();
  }
}

// Keep provider concurrency and cost bounded; there are no automatic scenario retries.
for (const scenarios of [['reply', 'answer'], ['timeout', 'cancel']]) {
  const results = await Promise.allSettled(scenarios.map(run));
  for (const result of results) {
    if (result.status === 'rejected') { console.error(result.reason); process.exitCode = 1; }
  }
  if (process.exitCode) break;
}
