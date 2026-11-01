# Qwen3.7 Flash через AITunnel — 01.11.2026

`GraphAgentToolScenariosIntegrationTest`: **71/94 успешно (75,53%), 23 падения, 0 пропусков**.
Исходные статусы сохранены без ручной переоценки.

Это измерение commit `fec4e39e99024837485ff702437dc70d87e50b39` ветки
`codex/deepseek-chat-503-retry`: каждому сценарию были доступны только его объявленные
моки, файловый реестр Skills был пуст. Полный каталог и закреплённые Skills из текущей
[обвязки](../../desktopApp/docs/agent-scenario-tests.md) требуют отдельного измерения.

## Конфигурация и метрики

| Параметр | Значение |
| --- | --- |
| API | `https://api.aitunnel.ru/v1` |
| Запрошенная и возвращённая модель | `qwen3.7-flash` |
| Ключ | `AITUNNEL_KEY` из окружения, передан процессу через `OPENAI_API_KEY` |
| Агент | `SkillsGraphBasedAgent`, `skills` |
| Reasoning | Передан `reasoning_effort=high`; применение уровня сервером отдельно не проверялось |
| Адаптер | `openai-compatible-custom`; `max_completion_tokens`; temperature не отправлялась |
| Контекст / streaming | 32 000 / выключен |
| Лимиты | 24 вызова модели / 4 минуты на сценарий; HTTP timeout 60 секунд |
| Java | Homebrew OpenJDK 21.0.13 |
| Время МСК | 17:53:59–18:29:51 |
| Gradle wall time / JUnit | 2152,004 / 2150,017 секунды |
| Gradle exit code | 1 из-за упавших случаев |
| Вызовы модели / инструментов | 403 / 340 |
| HTTP requests / среднее время | 403 / 5302,65 мс |
| Prompt / completion tokens | 1 001 201 / 61 098 |
| Total / cached prompt tokens | 1 062 299 / 826 880 |
| HTTP 503 / повторы после 503 | В сохранённом журнале не обнаружены |

Cached входят в prompt и не прибавляются повторно. Сумма 94 `Scenario metrics` совпала с
session usage. Это usage разобранных ответов, без сверки биллинга.

Модель была найдена в [каталоге AITunnel](https://aitunnel.ru/models/qwen3-7-flash) и
авторизованном `GET /v1/models`. Фикстуры: 33/33. Короткий запуск: 1/3, два HTTP-тайм-аута;
успешный случай завершил цепочку discovery → инструмент → финальный ответ. Метрики выше
относятся только к полному запуску.

## Причины падений

**16 случаев:** HTTP-запрос превысил 60 секунд. При формировании сообщения об этом
`ComposeAgentErrorMessages.timeout` вызывал `HeadlessToolkit.getScreenResolution` и
завершался `HeadlessException`. Все 16 стеков и ошибок API совпали по этой причине.
Они не устанавливают качество модели на этих случаях и сохранены в исходном знаменателе.
Тестовая обвязка использует собственные сообщения ошибок без Compose.

**7 случаев — проверки сценариев:**

- `scenario14_deleteFile[2]`: нужный файл остался в фикстуре.
- `scenario16_extractTextFromFile[2]`: выбран неверный путь, `Test content` не прочитан.
- `scenario15_moveFile[1]`: два вызова перемещения вместо одного.
- `scenario2_openWebsite[1]`: два вызова открытия вместо одного.
- `scenario9_findCalendarEvent[3]`: в ответе отсутствует «Планирование спринта».
- `scenario14_modifyFile[1]`: строка не добавлена в исходный файл.
- `scenario14_createFile[3]`: файл создан в `/home/user/tmp` вместо пользовательского `~/tmp`.

XML/HTML, логи, `summary.json` и `cases.csv` сохранены локально под
`build/reports/qwen37-flash-aitunnel-20261101-175040/full/`; они не входят в Git.
Результат и подробное примечание записаны в строку 32
[таблицы](https://docs.google.com/spreadsheets/d/1Mn1bjM2pIfXvz81b1ZUsvHStuT0ueRdsfhs7cAYMDkM/edit#gid=0&range=A32:O32).

Команды воспроизведения и проверки результата: [live evaluation runbook](live-agent-scenarios.md).
