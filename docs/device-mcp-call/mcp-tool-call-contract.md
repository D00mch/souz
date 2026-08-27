# Контракт tool.call для MCP (Souz WebSocket API) — черновик этапа 1

Статус: черновик, для согласования с souz-backend перед реализацией.

## Зачем

Сейчас единственный поддерживаемый эмулятором tool-call клиентского скилла — `device.exec`:
произвольная shell/process-команда, которая пушится на физическое устройство по его
WebSocket-соединению (`ru.souz.orion.deviceclient.DeviceExecArguments`, см.
`BackendChatSession.handleToolCallStarted`). Это заглушка вместо целевой интеграции: клиентское
устройство (и/или другие устройства в его локальной сети) поднимает MCP-сервер, который даёт
бэкенду вызывать структурированные, обнаруживаемые инструменты — включая функции ОС — вместо
произвольных shell-команд.

Этот документ описывает **только контракт со стороны бэкенда**: три новых имени `tool.call`, их
аргументы и результаты, передаваемые поверх существующего Public Client WebSocket-контракта
(`clientType=backend`). Он не описывает, как эмулятор будет релеить эти вызовы на физическое
устройство по `/salute/ws` — это отдельный, более поздний этап (сейчас у протокола `DeviceMessage`
есть только `exec`/`exec_result`; ему понадобятся аналогичные типы фреймов).

## Конверт (без изменений)

Новых kind-ов фреймов не вводится. Все три инструмента используют тот же конверт
`tool.call.started` / `tool.result`, что уже использует `device.exec`
(`publicclient/PublicClientContract.kt`):

- Backend → эмулятор: `event`-фрейм, `type: "tool.call.started"`, `payload: { toolCallId, name,
  arguments }`.
- Эмулятор → backend: `ToolResultFrame` (`kind: "tool.result"`, `chatId`, `threadId`, `toolCallId`,
  `status`, `result`, `error`).

`status` остаётся одним из тех же трёх значений, что уже использует `device.exec`:
`"succeeded"` | `"failed"` | `"timed_out"`.

Целевое *Salute*-устройство (физическая колонка/станция, привязанная к чату) остаётся неявным и
берётся из привязки устройства к чату — точно как у `device.exec`. Оно никогда не передаётся как
аргумент tool-вызова.

Таймауты в аргументы tool-вызовов **не выносятся**. У агента нет данных, чтобы осмысленно выбрать
бюджет времени на discovery/list_tools/call_tool — это знание про сеть, устройство и конкретную
операцию, то есть зона ответственности исполняющего кода (эмулятор → устройство → MCP-хост), а не
входной параметр от бэкенда. Каждый из трёх вызовов получает свой внутренний дефолт (по аналогии с
`DEFAULT_EXEC_TIMEOUT_MS` у `device.exec`), настраиваемый на стороне эмулятора, а не в теле
запроса. Агент по-прежнему видит исход через `status: "timed_out"` — он просто им не управляет.

## Именование

Три новых имени инструментов в неймспейсе `device.mcp.*`:

- `device.mcp.list_devices`
- `device.mcp.list_tools`
- `device.mcp.call_tool`

## 1. `device.mcp.list_devices`

Список MCP-хостов, откликнувшихся при discovery в локальной сети клиентского устройства,
**включая само устройство**.

```jsonc
// backend → эмулятор
{
  "kind": "event",
  "chatId": "chat-9f2e",
  "threadId": "thread-771a",
  "type": "tool.call.started",
  "payload": {
    "toolCallId": "call-3d1c",
    "name": "device.mcp.list_devices",
    "arguments": {}
  }
}
```

```jsonc
// эмулятор → backend
{
  "kind": "tool.result",
  "chatId": "chat-9f2e",
  "threadId": "thread-771a",
  "toolCallId": "call-3d1c",
  "status": "succeeded",
  "result": {
    "devices": [
      {
        "id": "device-4471",       // тот же deviceId, что уже уходит в ClientDevice.deviceId при message.submit
        "name": "Кухонная станция",
        "self": true
      },
      {
        "id": "a1b2c3",
        "name": "home-server.local",
        "self": false
      }
    ]
  }
}
```

`id` каждой записи — стабильный идентификатор, а не одноразовый токен на время сессии: один и тот
же физический прибор должен отдавать один и тот же `id` при повторных вызовах `list_devices`, в
том числе в других разговорах/днях. Это принципиально в доме с несколькими станциями — если бы
собственная запись устройства всегда возвращалась под общим литералом вроде `"self"`, две разные
колонки в одном хозяйстве отдавали бы один и тот же id, и по контексту (истории чатов,
кросс-канальным пушам и т.п.) их легко было бы перепутать как один прибор. Поэтому специального
зарезервированного значения нет — собственная запись устройства получает такой же настоящий
устойчивый `id`, как и любой сосед по сети, просто помечена `self: true`. Для устройства, которое
сейчас на связи, это тот же `deviceId`, что уже передаётся бэкенду в `ClientDevice.deviceId` при
каждом `message.submit` — новой сущности не заводим.

Обращаться к «себе», не проходя через `list_devices`, всё ещё можно — но не магической строкой, а
просто не указывая `target` в `list_tools`/`call_tool`: отсутствие `target` означает «устройство,
которое сейчас на связи», ровно как сегодня неявно работает `device.exec`.

Ни сетевой адрес, ни статус доступности в результат не выносятся — агент никогда не обращается по
адресу напрямую, только по `id`, а доступность по своей природе устаревает мгновенно (сеть могла
измениться между discovery и следующим вызовом). `list_devices` просто не включает в список
устройства, не откликнувшиеся на discovery; актуальность конкретного `id` перепроверяется в момент
реального обращения — `device.mcp.list_tools`/`device.mcp.call_tool` вернут `mcp_target_not_found`
(id не найден вовсе) либо `mcp_server_unreachable` (устройство есть, но недоступно сейчас), если
оно успело пропасть.

Ошибки (`ClientError.code`): `device_not_connected`, `device_disconnected`, `device_timeout`,
`mcp_discovery_failed`.

## 2. `device.mcp.list_tools`

Список MCP-инструментов, которые предоставляет заданное устройство (`id` из
`device.mcp.list_devices`).

```jsonc
// backend → эмулятор
{
  "kind": "event",
  "chatId": "chat-9f2e",
  "threadId": "thread-771a",
  "type": "tool.call.started",
  "payload": {
    "toolCallId": "call-5e7b",
    "name": "device.mcp.list_tools",
    "arguments": {
      "target": "a1b2c3"   // опционально; id из device.mcp.list_devices — если опущен, значит устройство, которое сейчас на связи
    }
  }
}
```

```jsonc
// эмулятор → backend — result 1:1 повторяет ListToolsResult самого MCP, без трансформаций
{
  "kind": "tool.result",
  "chatId": "chat-9f2e",
  "threadId": "thread-771a",
  "toolCallId": "call-5e7b",
  "status": "succeeded",
  "result": {
    "tools": [
      {
        "name": "set_volume",
        "description": "Set the device's output volume",
        "inputSchema": { "type": "object", "properties": { "level": { "type": "integer" } }, "required": ["level"] }
      }
    ]
  }
}
```

`inputSchema` — это сырая MCP JSON Schema, без преобразований, чтобы бэкенд мог напрямую
валидировать/собирать аргументы по ней.

Ошибки: `device_not_connected`, `device_disconnected`, `device_timeout`, а также
`mcp_target_not_found` (id неизвестен/устарел с момента discovery), `mcp_server_unreachable`,
`mcp_protocol_error` (некорректный MCP-ответ).

## 3. `device.mcp.call_tool`

Вызов конкретного инструмента на конкретном устройстве.

```jsonc
// backend → эмулятор
{
  "kind": "event",
  "chatId": "chat-9f2e",
  "threadId": "thread-771a",
  "type": "tool.call.started",
  "payload": {
    "toolCallId": "call-8a2f",
    "name": "device.mcp.call_tool",
    "arguments": {
      "target": "a1b2c3",   // опционально, та же семантика, что у list_tools
      "name": "set_volume",
      "arguments": { "level": 7 }
    }
  }
}
```

```jsonc
// эмулятор → backend — result повторяет CallToolResult самого MCP
{
  "kind": "tool.result",
  "chatId": "chat-9f2e",
  "threadId": "thread-771a",
  "toolCallId": "call-8a2f",
  "status": "succeeded",
  "result": {
    "content": [
      { "type": "text", "text": "Volume set to 7" }
    ],
    "isError": false
  }
}
```

`content[].type` соответствует типам контента самого MCP (`text` | `image` | `resource`);
`data` (base64) и `mimeType` сопровождают не-текстовые элементы.

**Разделение ошибок (важно):** здесь сохраняется двухуровневая модель ошибок самого MCP, и её
стоит пронести сквозь весь стек, а не схлопывать в одно:

- **Ошибка выполнения инструмента** (сама ОС-функция не выполнилась — например, запрошенная
  громкость вне диапазона, файл не найден) → `tool.result` со `status: "succeeded"` и
  `result.isError: true`, ровно как это репортит сам MCP. Вызов *как протокольный обмен* прошёл
  успешно; не выполнилась сама операция.

  ```jsonc
  {
    "kind": "tool.result",
    "chatId": "chat-9f2e",
    "threadId": "thread-771a",
    "toolCallId": "call-8a2f",
    "status": "succeeded",
    "result": {
      "content": [
        { "type": "text", "text": "level must be between 0 and 10" }
      ],
      "isError": true
    }
  }
  ```

- **Ошибка протокола/транспорта** (не удалось достучаться до цели вообще, инструмент с таким
  именем не существует, аргументы не проходят `inputSchema`, таймаут) → `tool.result` со
  `status: "failed"` и `ClientError`.

  ```jsonc
  {
    "kind": "tool.result",
    "chatId": "chat-9f2e",
    "threadId": "thread-771a",
    "toolCallId": "call-8a2f",
    "status": "failed",
    "error": {
      "code": "mcp_tool_not_found",
      "message": "Tool 'set_volume' is not exposed by device a1b2c3."
    }
  }
  ```

Ошибки для второго случая: `device_not_connected`, `device_disconnected`, `device_timeout`,
`mcp_target_not_found`, `mcp_tool_not_found`, `mcp_invalid_arguments`.

## Незакрытая зависимость (вне рамок этого этапа)

Чтобы эмулятор реально мог выполнить эти три вызова, ему всё равно нужен способ достучаться до
MCP-хоста — либо in-process на самом клиентском устройстве, либо релеем по локальной сети
клиентского устройства. Для этого понадобится расширить протокол `/salute/ws`
(`ru.souz.orion.protocol.DeviceMessage`, сейчас только `exec`/`exec_result`) аналогичными парами
запрос/ответ, коррелируемыми так же, как `DeviceExecRegistry` сегодня коррелирует
`exec`/`exec_result`. Оставлено на следующий этап.

Отдельно нужно решить (на той же следующей стадии), откуда для устройств-соседей по сети (не
`self`) брать `id`, устойчивый не только в рамках сессии, но и между отдельными discovery-сканами —
контракт требует такой стабильности, но сам её не обеспечивает. Кандидаты: собственный устойчивый
идентификатор, который отдаёт сам MCP-сервер соседа при handshake, либо аппаратный fingerprint
(например, MAC), к которому клиентское устройство привязывает и кеширует сгенерированный `id`
локально.
