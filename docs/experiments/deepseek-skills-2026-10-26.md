# DeepSeek: повтор агентных сценариев и результат 26.10.2026

Инструкция для ветки `codex/deepseek-chat-503-retry`, основанной на `kmp`.
Ветка содержит фикс повторов HTTP 503 и эту инструкцию. Поддержка прямого DeepSeek API в ней не дорабатывалась.

## Что запускается

`agent.GraphAgentToolScenariosIntegrationTest` — 94 проверки с реальной LLM и замоканными инструментами.
Режим `skills` выбирает `ru.souz.SkillsGraphBasedAgent` (`AgentId.SKILLS_GRAPH`).
Название тестового класса одинаково для режимов `skills` и `graph`.

Браузер, почта, календарь и файлы представлены фикстурами; реальные письма не отправляются.
Для этого набора не нужны запущенные desktop-приложение, backend, Orion и Docker.
Последний запуск выполнен на macOS с Java 21. Другие ОС этим запуском не проверены.
Общие правила набора: [agent-scenario-tests.md](../../desktopApp/docs/agent-scenario-tests.md).

## 1. Забрать ветку и проверить окружение

Команды ниже рассчитаны на Bash или Zsh, запускаются из корня репозитория.

```bash
git fetch origin
git switch codex/deepseek-chat-503-retry
git rev-parse HEAD
java -version
./gradlew --version
```

Нужны Java 21 и Gradle wrapper из репозитория. При необходимости настрой `JAVA_HOME` под установленный JDK 21.

Перед live-прогоном можно проверить фикстуры без обращения к LLM:

```bash
./gradlew :desktopApp:test \
  --tests 'agent.AgentScenario*Test' \
  --tests 'agent.ScenarioCodexAuthTest' \
  --no-parallel --console=plain
```

## 2. Настроить прямой DeepSeek API

Введи токен в терминале без сохранения его значения в истории команд:

```bash
printf 'DeepSeek API key: '
read -r -s DEEPSEEK_API_KEY
printf '\n'
export DEEPSEEK_API_KEY

export OPENAI_API_KEY="$DEEPSEEK_API_KEY"
export OPENAI_BASE_URL='https://api.deepseek.com'
export OPENAI_MODEL='deepseek-flash'

export SOUZ_AGENT_INTEGRATION_TESTS_ON='true'
export SOUZ_AGENT_INTEGRATION_TEST_MODEL='openai-compatible-custom'
export SOUZ_AGENT_INTEGRATION_TEST_AGENT='skills'
export SOUZ_AGENT_INTEGRATION_TEST_REASONING_EFFORT='high'
export SOUZ_CLASSIFIER='llm'
```

Имена `OPENAI_*` используются существующим совместимым адаптером Souz; адрес назначения задаёт `OPENAI_BASE_URL`.
Для прямого API нужен идентификатор `deepseek-flash`.
`deepseek/deepseek-v4.1-flash-1017` — идентификатор маршрута Polza.

На 26.10.2026 официальный alias `deepseek-flash` обслуживает DeepSeek V4.1 Flash-1017.
Alias может измениться: записывай дату, запрошенную модель и доступные сведения о версии вместе с результатом.
Источники: [первый запрос](https://api-docs.deepseek.com/), [модели](https://api-docs.deepseek.com/quick_start/pricing/).

### Непроверенная совместимость прямого API

Прямого ключа на машине предыдущего запуска не было; все 94 проверки выполнялись через Polza.
Команды ниже описывают настройку существующего адаптера и не являются подтверждением успешного прямого прогона.

Два известных момента для диагностики:

- DeepSeek в thinking-режиме требует возвращать `reasoning_content` в последующих запросах с инструментами.
  Текущий адаптер не переносит это поле через историю запроса; поэтому на продолжении цепочки возможен HTTP 400.
  Это требование описано в [Thinking Mode](https://api-docs.deepseek.com/guides/thinking_mode/).
- Адаптер для `LlmProvider.OPENAI` отправляет `max_completion_tokens`, тогда как прямой DeepSeek документирует `max_tokens`.
  Принятие и применение этого поля напрямую не проверены. Не считай установленный в Souz лимит гарантированно применённым сервером без проверки.
  См. [Chat Completions](https://api-docs.deepseek.com/api/create-chat-completion/).

Эти вопросы не исправлены фиксом 503. При HTTP 400 сначала сохрани тело ошибки и остановись на коротком прогоне.
Изменение `high` на `none` будет отдельным экспериментом с другими условиями.

## 3. Сначала короткий прогон

Три формулировки запуска приложения проверяют discovery навыка, исполнение и финальный ответ:

```bash
./gradlew :desktopApp:test --rerun \
  --tests 'agent.GraphAgentToolScenariosIntegrationTest.scenario1_launchApplication*' \
  --no-parallel --console=plain
```

Проверь, что выполнены три случая, выбран `[agent=skills]`, запросы направлены на нужный endpoint и есть завершённая цепочка инструментов.
Успешный одиночный текстовый ответ от API ещё не проверяет продолжение после tool call.

## 4. Полный прогон с сохранением результата

Этот блок сохраняет exit code даже при падениях тестов. Путь результата выводится перед запуском.
Файл `progress.init.gradle` добавляет строки `LIVE_CASE` в консольный лог.

```bash
run_dir="$(pwd)/build/reports/deepseek-direct-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$run_dir"
printf 'Results: %s\n' "$run_dir"

git rev-parse HEAD > "$run_dir/commit.txt"
git diff > "$run_dir/source.patch"
printf 'model=%s\nbase_url=%s\nagent=skills\nreasoning_effort=high\n' \
  "$OPENAI_MODEL" "$OPENAI_BASE_URL" > "$run_dir/config.txt"

cat > "$run_dir/progress.init.gradle" <<'GRADLE'
allprojects {
    tasks.withType(Test).configureEach {
        afterTest { descriptor, result ->
            println("LIVE_CASE " + result.resultType + " " + descriptor.name + " durationMs=" + (result.endTime - result.startTime))
        }
    }
}
GRADLE

if ./gradlew :desktopApp:test --rerun \
  --tests agent.GraphAgentToolScenariosIntegrationTest \
  --no-parallel --console=plain \
  -I "$run_dir/progress.init.gradle" > "$run_dir/gradle.log" 2>&1
then
  run_status=0
else
  run_status=$?
fi

printf '%s\n' "$run_status" > "$run_dir/exit-code.txt"
cp -R desktopApp/build/test-results/test "$run_dir/junit"
cp -R desktopApp/build/reports/tests/test "$run_dir/junit-html"
printf 'Gradle exit code: %s\nResults: %s\n' "$run_status" "$run_dir"
```

Открой `junit-html/index.html` в каталоге результата. Исходный stdout тестов и `Scenario metrics` находятся также в JUnit XML.
При ошибке сборки до выполнения тестов старые JUnit-файлы не подтверждают новый запуск; проверь дату и число случаев.
Ожидаются 94 случая, без skipped. Exit code 1 при упавших сценариях — сохранённый результат проверки, а не обязательно ошибка сборки.

Сохраняй отчёты перед любым следующим `:desktopApp:test`: Gradle перезаписывает их.
Не сохраняй полный вывод `env`: там находится токен. Ветка и инструкция токенов не содержат.

Для точечного повторения замени фильтр `--tests` на нужный метод с `*` в конце, как в коротком прогоне.
Чтобы отдельно сравнить реализации, выбери `SOUZ_AGENT_INTEGRATION_TEST_AGENT=graph` и сохрани новый каталог результата.

## Условия предыдущего полного запуска

| Параметр | Значение |
| --- | --- |
| Дата и время | 26.10.2026, 17:29:26–20:11:07, Europe/Moscow |
| База исходников | `a48283d7bd356f1acda8cfe982b36a0b9cb2ad25`, `origin/kmp` |
| Фикс повторов, применённый при запуске | `6d00fac7` |
| API | `https://polza.ai/api/v1` |
| Модель | `deepseek/deepseek-v4.1-flash-1017` |
| Агент | `SkillsGraphBasedAgent`, `skills` |
| Reasoning | `high` |
| Streaming | выключен |
| Контекст Souz в обвязке | 32 000 токенов |
| Ограничения вывода в коде | агент: 4 096; классификация: 512; summary: 1 024 |
| HTTP timeout | 60 секунд на попытку |
| Лимит сценария | 4 минуты, до 24 вызовов модели |
| Часы фикстур | 01.06.2026, 10:00, Europe/Moscow |

`SkillsGraphBasedAgent` работает через фиксированные discovery/Knowledge/RunSkillCommand-инструменты.
У него отсутствует отдельный узел классификации прямых инструментов классического `GraphBasedAgent`.
Воспроизведение использует тот же `SOUZ_CLASSIFIER=llm`.

Конфигурация фикстур задаёт temperature 0, но совместимый адаптер с `LlmProvider.OPENAI` не отправляет это поле.
Системный промпт, фикстуры и проверки не менялись ради улучшения результата.
Это текущий набор из 94 случаев; старый прогон `GraphBasedAgent` использовал 93 случая и другие проверки.

## Результат и метрики

**73/94 успешны — 77,66%; 21 падение; 2 ч 41 мин 41 с.**
Ручной переоценки не было.

| Метрика | Значение |
| --- | ---: |
| Вызовы модели по обвязке | 351 |
| Вызовы инструментов | 317 |
| Prompt tokens | 968 502 |
| Completion tokens | 51 711 |
| Total tokens | 1 020 213 |
| Cached prompt tokens | 743 596 |
| Дополнительные HTTP-попытки после 503 | 85 |
| Случаи с окончательным HTTP 503 | 5 |

Cached tokens входят в prompt tokens; не прибавляй их повторно к total.
Это нормализованный usage разобранных ответов в обвязке, а не сверка биллинга провайдера.
HTTP-повторы происходят внутри одного обращения обвязки к модели и не увеличивают `modelCalls`.
Время сценария включает ожидание API и паузы повторов; тайм-аут сценария не изолирует поведение модели.

### Что выяснилось о 503

Polza возвращала `SERVICE_UNAVAILABLE`, преимущественно `metadata.reason=limitsCheckUnavailable`:
сервис не смог проверить лимиты запроса. Был также общий ответ о временной недоступности.
Это не подтверждённое исчерпание квоты и не ошибка выбора агентом инструмента; внутренняя причина на сервере без его логов неизвестна.

Четыре коротких самостоятельных запроса вне агента прошли: с исходным форматом, с `max_tokens`, с закреплённым DeepSeek и повтор исходного формата.
Закрепление провайдера этим единичным запросом не доказало устранение сбоя.

Фикс повторяет только chat HTTP-запрос с тем же payload: до двух повторов после первой попытки,
экспоненциальная пауза с jitter и нижняя граница из числового `Retry-After`.
Инструменты и весь граф заново не запускаются. Другие статусы, сетевые исключения и уже начатый поток этим HTTP-правилом не повторяются.
См. [правила транспорта](../../sharedLogic/docs/provider-http-lifecycle.md) и [рекомендации Polza](https://polza.ai/docs/api-reference/introduction).

Из 90 зафиксированных ответов 503 после 85 начались повторные HTTP-попытки, ещё 5 стали окончательными ошибками.
Фикс смягчает сбой; удалённый сервис после него продолжал возвращать ошибки.

### Все 21 падение

Индекс `[n]` — вариант формулировки параметризованного сценария.

| Сценарий | Причина |
| --- | --- |
| `scenario14_deleteFile[3]` | Два вызова удаления: сначала `/tmp/...`, затем `~/tmp/...`; ожидался один |
| `scenario11_missingChartColumns` | Корректное `do not exist` не распознано регулярным выражением проверки |
| `scenario19_notesFindCreateDeleteList[1]` | Незавершённый/обрезанный финальный ответ, повторяющиеся `<...>` |
| `scenario19_notesFindCreateDeleteList[2]` | Тайм-аут 240 с; повторялись создания и удаления заметки |
| `scenario19_notesFindCreateDeleteList[3]` | Финальный ответ не прошёл проверку завершения |
| `scenario7_createCalendarEvent[3]` | HTTP 503 после исчерпания попыток |
| `excelReport_newFile[2]` | Тайм-аут сценария 240 с |
| `excelRead_overview[3]` | HTTP 503 после исчерпания попыток |
| `scenario16_extractTextFromFile[2]` | Неверная интерпретация домашнего пути, нужное содержимое не прочитано |
| `excelRead_query[2]` | Текст с DSML-разметкой вместо результата |
| `excelRead_cell[1]` | HTTP 503 после исчерпания попыток |
| `excelRead_sort[1]` | Требуемый вызов `ExcelRead` отсутствует |
| `scenario21_sendEmail[3]` | Некорректный/незавершённый финальный ответ с разметкой вызовов |
| `excelRead_lookup[1]` | Требуемый вызов `ExcelRead` отсутствует |
| `excelReport_withData[2]` | Тайм-аут одного HTTP-запроса, 60 с |
| `scenario2_openWebsite[1]` | Внутренний текст `<analysis>` вместо пользовательского результата |
| `scenario9_findCalendarEvent[1]` | DSML-разметка вместо ответа с событиями |
| `scenario14_modifyFile[1]` | HTTP 503 после исчерпания попыток |
| `scenario14_modifyFile[3]` | Тайм-аут сценария 240 с |
| `scenario6_todayCalendarEvents[2]` | HTTP 503 после исчерпания попыток |
| `scenario14_createFile[1]` | Нет пользовательского финального ответа |

Одна проверка отсутствующих колонок — подтверждённое ограничение проверки: она принимает `missing/unavailable`,
но не `do not exist`. График при этом корректно не подменялся другим. Тест и исходный score оставлены без изменений.

DSML-разметка или `<analysis>` в `content` сами по себе не выполняют инструменты.
Ошибки путей и лишние вызовы нельзя автоматически списывать на 503.
Три тайм-аута сценариев включали и работу модели, и задержки API.

## Проверки фикса

- Детерминированные проверки обвязки: 33 успешны.
- `:sharedLogic:jvmTest`: 379 успешны, 11 пропущены, падений нет.
- Regression-тесты покрывают восстановление после 503, одинаковый payload, `Retry-After`, предел попыток, отмену и отсутствие повторов других ошибок.
- `souzGateFast` завершился успешно с существующим предупреждением `RepeatedComputationInGetter` в `sharedUI/.../MemoryUiState.kt:53`.
- Проверка дублирования совпала с baseline без новых дублей; локальный запуск не обладает полномочиями exact-checkout проверки GitHub Actions.
- `git diff --check` прошёл.

Прямой прогон через DeepSeek будет отдельным измерением: меняются endpoint, возможная обработка thinking и серверные лимиты.
Сохраняй исходные статусы, причины ошибок и версии фикстур; ручную переоценку, если она понадобится, записывай отдельно.

