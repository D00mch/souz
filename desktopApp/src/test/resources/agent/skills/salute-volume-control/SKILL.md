---
name: salute-volume-control
description: Sets the absolute volume level (0-100) on the connected smart speaker device via its MCP `set_volume` tool. Use when the user asks to set the volume to a specific level (e.g. "поставь громкость 35", "сделай громкость на максимум").
---

# Salute Volume Control

Скилл выставляет абсолютный уровень громкости колонки (0..100) через её собственный MCP-инструмент `set_volume` (обнаружен через `device.mcp.list_tools`: `inputSchema = {"type":"object","required":["level"],"properties":{"level":{"type":"integer","minimum":0,"maximum":100}}}`).

**Вызывай инструмент `RunSkillCommand` с `skillId = "device.mcp.call_tool"`** (не с id этого скилла — это описание, а не исполняемый скилл) и такими аргументами:

```json
{"skillId":"device.mcp.call_tool","arguments":{"name":"set_volume","arguments":{"level":35}}}
```

- `name` — всегда буквально `"set_volume"`.
- `arguments.level` — целое число 0..100, желаемый уровень громкости.
- `target` не указывай — колонка, которой нужно управлять, это устройство, которое сейчас на связи.

## Когда использовать

Пользователь просит выставить громкость колонки на конкретное значение (число от 0 до 100). Для относительных команд ("сделай тише/громче", "заглуши") этот скилл не подходит — он только для абсолютного уровня.

## Определение уровня

Извлеки из запроса пользователя целое число 0..100 и передай его как `arguments.level`. Если пользователь называет уровень словами ("на максимум", "потише" без числа) — переспроси конкретное число, не угадывай.

## Формат ответа

Результат `device.mcp.call_tool` — это MCP `CallToolResult`. Если `isError` отсутствует или `false` — коротко подтверди, что громкость выставлена на нужный уровень. Если `isError: true` — прочитай `content[].text` (например, при выходе `level` за диапазон) и сообщи пользователю, что не удалось изменить громкость, не зачитывай технические детали дословно.

Если `RunSkillCommand` вернул `ClientError` (например, недоступен клиентский контекст, либо `mcp_tool_not_found`/`mcp_target_not_found`) — колонка сейчас не на связи или инструмент недоступен; сообщи об этом, не путай с `isError` из самого результата.
