# Orion scheduler protocol

The [Souz task tools](scheduled-tasks.md) coordinate ordinary workspace hooks and an Orion scheduler. Orion owns titles, schedules, credentials and delivery state; Souz owns hook prompts and execution receipts. There is no distributed transaction, schedule mirror or task-purpose field in Souz.

## Transport and ownership

Internal `scheduler.*` commands use the [public WebSocket transport](public-souz-contract/README.md): `tool.call.started` with `seq:null`, an independent correlation `threadId`, and a 30-second deadline. They use the bounded command queue, not the droppable notification queue. The source input ACK precedes publication; an on-time result reserves receipt before its ACK completes the waiting call.

These calls never create a target execution or durable tool-call/event record. In particular, `scheduler.create` carries a raw Bearer credential that must not enter history, logs or LLM context. Results omit credentials and target details. Reconnect and restart do not replay these commands.

Orion resolves the owner from the receiving session's trusted device/chat binding. Arguments cannot supply a user ID. Any eligible connected Orion channel belonging to the same owner sees the same schedules; foreign records are treated as absent. Management needs a live connection. Previously saved schedules fire independently of the connection.

Successful `tool.result` frames carry `status:succeeded` and `result`; failures carry `status:failed` and `error:{code,message}`. The existing envelope carries `chatId`, `threadId` and `toolCallId`. Unknown argument fields and explicit nulls are rejected.

## Operations

| Name | Arguments | Result |
|---|---|---|
| `scheduler.create` | `scheduleId`, `title`, `schedule`, `target` | `record`, `created:boolean` |
| `scheduler.get` | `scheduleId` | `found:false`, or `found:true` with `record` |
| `scheduler.list` | Optional `cursor`, `limit` | `records:[]`, `nextCursor:string|null` |
| `scheduler.update` | `scheduleId` and nonempty patch of `title`, `schedule` | `record` |
| `scheduler.delete` | `scheduleId` | `scheduleId`, `removed:boolean` |

IDs are canonical `task-<UUID>` strings. A create target is `{hookId: scheduleId, bearerToken: "..."}`; it cannot specify a URL or arbitrary headers. Orion constructs `/hooks/{hookId}` beneath its configured Souz base URL. Titles are nonblank and at most 200 characters.

Create acknowledges only after persistent storage. An identical create for the same owner and ID returns the existing record with `created:false`, preserving its state. Conflicting content is rejected. This does not authorize automatic retries after an uncertain result.

List includes terminal records, ordered by ID. The cursor is the previous page's last ID; the default limit is 100 and maximum is 200. Pages are not a snapshot. Souz reads at most 100 pages, deduplicates overlapping IDs and rejects cursor loops.

Update changes only provided fields; owner, ID and credential are immutable. Only active records can be updated. A title-only update preserves the next occurrence and last delivery. Updating a dispatching record returns `scheduler_busy`; a terminal record returns `scheduler_invalid_state`.

Delete removes the selected record and credential, returning `removed:false` if absent. It prevents future attempts but cannot revoke an HTTP request already sent. Its late result must not recreate the record. Independently created child tasks and running agents remain.

## Schedules

```json
{"kind":"once","runAt":"2030-09-25T12:10:00Z"}
{"kind":"cron","expression":"0 8 * * 1-5","timeZone":"Europe/Moscow"}
```

`runAt` is a future RFC3339 instant with an explicit offset, normalized to UTC. Souz converts its create-only `{kind:"after",seconds:600}` input to `once` before calling Orion.

Cron uses five Unix numeric fields: minute, hour, day-of-month, month, day-of-week. Numbers, `*`, lists, ranges and steps are accepted; day-of-week is 0–6 with Sunday 0. Either day-of-month or day-of-week must be exactly `*`. Names, macros, seconds, Quartz syntax and inline timezones are rejected. `timeZone` must be an explicit IANA zone. Orion validates ranges and the existence of a future occurrence. A nonexistent local time is skipped; a repeated local time runs once at its first occurrence.

## Record and delivery state

```json
{
  "scheduleId":"task-a218b4e0-7ec2-42df-b117-4a360243c882",
  "title":"Morning mail",
  "schedule":{"kind":"cron","expression":"0 8 * * 1-5","timeZone":"Europe/Moscow"},
  "state":"active",
  "nextRunAt":"2030-09-26T05:00:00Z",
  "lastDelivery":null,
  "createdAt":"2030-09-25T12:00:00Z",
  "updatedAt":"2030-09-25T12:00:00Z"
}
```

Record fields are strict. States are `active`, `dispatching`, `completed`, `missed`, `delivery_failed`, `delivery_unknown`. Terminal one-off records have `nextRunAt:null`. Cron returns to `active` after each attempt, retaining its outcome in `lastDelivery`.

A delivery contains `occurrenceId` (UUID), `scheduledAt` (instant), `status` (`sending`, `accepted`, `failed`, `unknown`) and optional `receiptId`, `errorCode`, `httpStatus`. A receipt ID is allowed only for `accepted`. The response includes no HTTP headers, raw response body or secret-bearing diagnostic text.

Orion persists the occurrence before sending one HTTP request:

```http
POST /hooks/{hookId}
Authorization: Bearer <credential>
Idempotency-Key: scheduler:<occurrenceId>
Content-Type: application/json
```

```json
{"version":1,"scheduleId":"task-a218b4e0-7ec2-42df-b117-4a360243c882","occurrenceId":"50000000-0000-4000-8000-000000000005","scheduledAt":"2030-09-26T05:00:00Z"}
```

A valid receipt from HTTP 200/202 means intake accepted the event. `completed` on Orion means that a one-off event was accepted, not that its agent succeeded or a message reached the user. Souz get/list add receipt and execution information separately.

There are no automatic HTTP retries. On restart, overdue one-off tasks become `missed`; cron advances to the next future occurrence. An interrupted delivery has an unknown outcome and is not replayed. Sunset cascades are agent workflows: a recurring task determines today's sunset and creates an independent one-off task.

## Errors and partial changes

Remote errors: `scheduler_context_missing`, `scheduler_invalid_arguments`, `scheduler_invalid_schedule`, `scheduler_not_found`, `scheduler_conflict`, `scheduler_invalid_state`, `scheduler_busy`, `scheduler_storage_unavailable`, `scheduler_not_configured`.

Only invalid arguments/schedule, missing context/configuration, and storage rejection explicitly guarantee that create saved no record. Souz can then remove the unchanged hook created by that invocation. Timeout, cancellation, malformed success or an unrecognized failure leaves the outcome uncertain and the hook available for inspection. No mutation is automatically repeated on another channel.

See [operation ordering and partial results](scheduled-tasks.md#operation-results-and-partial-failures) and [maintenance constraints](../backend/docs/pain-points/scheduled-tasks.md). Delivery retries, distributed coordination, purpose classification and orphan reconciliation remain outside this contract.
