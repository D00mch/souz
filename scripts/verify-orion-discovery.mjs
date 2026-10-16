// Node 22+. Requires local Souz (tool events enabled), Orion in regex mode,
// DIALOG_INACTIVITY_TIMEOUT=0s, and speech disabled or available.
// SOUZ_TEST_HTTP, ORION_TEST_WS, SOUZ_TEST_PROXY_TOKEN, SOUZ_TEST_MODEL override defaults.
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { setTimeout as delay } from 'node:timers/promises';

const base = process.env.SOUZ_TEST_HTTP ?? 'http://127.0.0.1:8081';
const user = randomUUID();
const deviceId = `discovery-${randomUUID()}`;
const deadline = Date.now() + Number(process.env.SOUZ_TEST_TIMEOUT_MS ?? 180_000);
const sockets = [];
const headers = {
  'Content-Type': 'application/json', 'X-User-Id': user,
  'X-Souz-Proxy-Auth': process.env.SOUZ_TEST_PROXY_TOKEN ?? 'local-dev-proxy-token',
};

async function request(path, body, method = body === undefined ? 'GET' : 'POST') {
  const response = await fetch(`${base}${path}`, {
    method, headers, body: body === undefined ? undefined : JSON.stringify(body),
    signal: AbortSignal.timeout(Math.max(1, deadline - Date.now())),
  });
  const value = await response.json();
  assert(response.ok, `${method} ${path}: HTTP ${response.status}: ${JSON.stringify(value)}`);
  return value;
}

async function until(description, read) {
  while (Date.now() < deadline) {
    const value = await read();
    if (value) return value;
    await delay(200);
  }
  throw new Error(`Timed out: ${description}`);
}

async function socket(url, onMessage = () => {}) {
  const ws = new WebSocket(url);
  sockets.push(ws);
  ws.addEventListener('message', onMessage);
  await new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error(`WebSocket connection timed out: ${new URL(url).host}`)), 5000);
    ws.addEventListener('open', () => { clearTimeout(timer); resolve(); }, { once: true });
    ws.addEventListener('error', () => { clearTimeout(timer); reject(new Error(`WebSocket connection failed: ${new URL(url).host}`)); }, { once: true });
  });
  return ws;
}

// Minimal VPS protobuf encoding; field numbers follow Orion api/proto/vps.
function varint(value) {
  let n = BigInt.asUintN(64, BigInt(value));
  const bytes = [];
  do { bytes.push(Number(n & 127n) | (n > 127n ? 128 : 0)); n >>= 7n; } while (n);
  return Buffer.from(bytes);
}
function number(field, value) { return Buffer.concat([varint(field * 8), varint(value)]); }
function bytes(field, value) {
  const data = Buffer.isBuffer(value) ? value : Buffer.from(value);
  return Buffer.concat([varint(field * 8 + 2), varint(data.length), data]);
}
function frame(...fields) {
  const data = Buffer.concat(fields);
  const size = Buffer.alloc(4);
  size.writeUInt32LE(data.length);
  return Buffer.concat([size, data]);
}
function unpack(value) {
  if (typeof value === 'string') {
    try { return unpack(JSON.parse(value)); } catch { return value; }
  }
  if (Array.isArray(value)) return value.map(unpack);
  if (value && typeof value === 'object') return Object.fromEntries(Object.entries(value).map(([key, item]) => [key, unpack(item)]));
  return value;
}
function find(value, predicate) {
  if (predicate(value)) return value;
  if (value && typeof value === 'object') {
    for (const child of Object.values(value)) { const found = find(child, predicate); if (found) return found; }
  }
}

try {
  await request('/health');
  const device = await socket(process.env.ORION_TEST_WS ?? 'ws://127.0.0.1:8080/ws/ask');
  const settings = bytes(18, Buffer.concat([
    bytes(1, user), bytes(2, 'TEST'), bytes(5, 'en-US'),
    bytes(3, Buffer.concat([bytes(1, 'test'), bytes(3, 'SBERBOOM_R2'), bytes(7, deviceId)])),
    bytes(4, number(1, -1)), // VPS tri-state false: do not invoke speech synthesis.
  ]));
  device.send(frame(number(2, 1), number(15, 3), settings));
  device.send(frame(number(2, 2), number(3, 1), number(15, 3),
    bytes(6, bytes(1, 'Reply READY. Do not call tools.'))));
  const target = await until('Orion device chat registration (use NLP_MODE=regex)', async () =>
    (await request('/v1/chats?limit=100')).items[0]);
  await until('Orion device initial turn completion', async () => {
    const events = (await request(`/v1/chats/${target.id}/events?limit=100`)).items;
    const failed = events.find(event => event.type === 'thread.failed');
    assert(!failed, `Device bootstrap failed: ${JSON.stringify(failed?.payload)}`);
    return events.some(event => event.type === 'thread.completed');
  });
  console.log(JSON.stringify({ stage: 'device_connected', userId: user, targetChatId: target.id, deviceId }));

  // Observe the live client commands. Only Orion replies to them.
  const observed = [];
  const observer = await socket(`${base.replace(/^http/, 'ws')}/v1/ws?clientType=backend`, event => observed.push(JSON.parse(event.data)));
  const subscribe = randomUUID();
  observer.send(JSON.stringify({ kind: 'chat.subscribe', chatId: target.id, requestId: subscribe }));
  await until('target subscription acknowledgement', () => {
    const ack = observed.find(event => event.kind === 'ack' && event.requestId === subscribe);
    if (ack) assert.equal(ack.status, 'accepted', JSON.stringify(ack.error));
    return ack;
  });
  const source = (await request('/v1/chats', { userId: user, requestId: randomUUID(), clientType: 'backend', title: 'Orion discovery acceptance', tools: [] })).chat.id;
  await request('/v1/me/settings', { showToolEvents: true }, 'PATCH');
  const prompt = `Use the connected Orion channel ${target.id}. First inspect and invoke orion.tools.list with channelId ${target.id} and no other arguments. Then inspect and invoke the discovered orion.function.current_time on that channel with its declared arguments. Do not delegate to a subagent. Return its actual date and time. Do not infer the time yourself.`;
  const submission = await request(`/v1/chats/${source}/messages`, {
    content: prompt, clientMessageId: randomUUID(),
    options: process.env.SOUZ_TEST_MODEL ? { model: process.env.SOUZ_TEST_MODEL } : {},
  });
  const events = await until('source execution completion', async () => {
    const items = (await request(`/v1/chats/${source}/events?limit=500`)).items;
    const terminal = items.find(event => event.executionId === submission.execution.id &&
      ['execution.finished', 'execution.failed', 'execution.cancelled'].includes(event.type));
    if (terminal) assert.equal(terminal.type, 'execution.finished', JSON.stringify(terminal.payload));
    return terminal && items;
  });
  const commands = observed.filter(event => event.type === 'tool.call.started' && event.seq === null);
  assert(commands.some(event => event.payload.name === 'orion.tools.list'), 'No actual discovery command reached Orion');
  assert(commands.some(event => event.payload.name === 'orion.function.current_time'), 'No discovered current_time command reached Orion');
  assert.deepEqual(commands.find(event => event.payload.name === 'orion.tools.list').payload.arguments, {});
  assert(commands.every(event => !Object.hasOwn(event.payload.arguments, 'channelId')), 'Routing metadata leaked into device arguments');
  assert(commands.findIndex(event => event.payload.name === 'orion.tools.list') < commands.findIndex(event => event.payload.name === 'orion.function.current_time'));
  const starts = events.filter(event => event.type === 'tool.call.started');
  const timeStart = starts.find(event => event.payload.argumentsPreview?.skillId === 'orion.function.current_time');
  assert(timeStart, 'Source did not invoke the discovered Skill; enable SOUZ_FEATURE_TOOL_EVENTS=true');
  const timeFinish = events.find(event => event.type === 'tool.call.finished' && event.payload.toolCallId === timeStart.payload.toolCallId);
  const time = find(unpack(timeFinish?.payload.resultPreview), value => value && typeof value.date === 'string' && typeof value.time === 'string');
  assert(time, 'No successful date/time tool result was recorded');
  console.log(JSON.stringify({ stage: 'passed', sourceChatId: source, targetChatId: target.id,
    executionId: submission.execution.id, actualClientCalls: commands.map(event => event.payload.name), result: time }));
} finally {
  for (const ws of sockets) ws.close();
}
