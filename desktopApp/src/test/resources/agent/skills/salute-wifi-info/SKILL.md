---
name: salute-wifi-info
description: Reads the current Wi-Fi network name, frequency band (2.4/5/6 GHz), and local IPv4 address directly from the connected smart speaker device via its MCP `get_network_info` tool. Use when the user asks what Wi-Fi network the speaker/column is connected to, what frequency band it's using, or what its IP address is — even if the question comes from a different conversation/channel than the speaker's own (e.g. Telegram, VK).
---

# Salute Wi-Fi Info

Скилл читает название текущей Wi-Fi сети и её диапазон прямо с самой колонки — не с сервера — через её собственный MCP-инструмент `get_network_info` (обнаружен через `device.mcp.list_tools`: без аргументов, `inputSchema = {"type":"object","properties":{}}`).

**Вызывай инструмент `RunSkillCommand` с `skillId = "device.mcp.call_tool"`** (не с id этого скилла — это описание, а не исполняемый скилл) и такими аргументами:

```json
{"skillId":"device.mcp.call_tool","arguments":{"name":"get_network_info","arguments":{}}}
```

- `name` — всегда буквально `"get_network_info"`.
- `arguments` — пустой объект, у инструмента нет параметров.
- `target` не указывай — интересует именно колонка, которая сейчас на связи (LAN-сосед не нужен).

## Если вопрос пришёл не из чата самой колонки

Если это сообщение пришло из другого канала/разговора (например, Telegram или VK), а не из чата, к которому подключена сама колонка — колонки "на этом звонке" нет, и без дополнительного шага вызов не сработает.

В этом случае:
1. Сначала вызови `ListActiveChannels`, чтобы получить список каналов пользователя.
2. Найди в списке канал, соответствующий колонке Салют (по `label`/названию).
3. Добавь его `channelId` в `arguments` вызова `device.mcp.call_tool`:

```json
{"skillId":"device.mcp.call_tool","arguments":{"name":"get_network_info","arguments":{},"channelId":"<channelId колонки>"}}
```

Если подходящего канала в списке нет — колонка сейчас не подключена ни в одном канале; сообщи об этом пользователю, не изобретай `channelId`.

## Когда использовать

Пользователь спрашивает, к какой Wi-Fi сети подключена колонка, на какой частоте (2.4/5/6 ГГц) она работает, или какой у неё локальный IP-адрес — независимо от того, из какого чата задан вопрос.

## Формат ответа инструмента

Результат `device.mcp.call_tool` — это MCP `CallToolResult`. При успехе (`isError` отсутствует или `false`) единственный текстовый элемент `content[0].text` — это одна строка вида:

```
ip=192.168.2.113, ssid="Tesla Over VPN", band=5 GHz
```

Поля: `ip` (локальный IPv4-адрес колонки в домашней сети), `ssid` (имя сети, в кавычках), `band` (`2.4 GHz`/`5 GHz`/`6 GHz`, может отсутствовать, если не удалось определить).

Отвечай только на то, что спросил пользователь:
- Спросил про сеть/частоту — озвучь **только** `ssid` и `band` (например: "Колонка подключена к сети Tesla Over VPN на частоте 5 ГГц"). Не добавляй IP, если не просили.
- Спросил про IP-адрес — озвучь **только** `ip` (например: "IP-адрес колонки — 192.168.2.113"). Не добавляй сеть/частоту, если не просили.
- Спросил и то, и другое ("к какой сети подключена и какой у неё IP") — озвучь оба.

Ни в одном случае не добавляй лишнего: ни уровень сигнала, ни тип защиты, ни сырую строку `ключ=значение`. Если `band` в строке отсутствует, просто не упоминай частоту, не говори, что она "неизвестна". Пароль/ключ сети инструмент никогда не возвращает.

Если `isError: true` — сообщи, что не удалось определить сеть, не зачитывай `content[].text` дословно.

Если `RunSkillCommand` вернул `ClientError` (например, недоступен клиентский контекст, либо `mcp_tool_not_found`/`mcp_target_not_found`) — колонка сейчас не на связи или инструмент недоступен; сообщи об этом, не путай с `isError` из самого результата.
