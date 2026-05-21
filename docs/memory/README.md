# Спецификация: долговременная память агента

## 1. Статус документа

Этот документ фиксирует целевую архитектуру долговременной памяти для Souz с учетом текущего состояния кодовой базы на май 2026.

Документ описывает:

- каноническую модель памяти;
- правила записи и чтения памяти;
- механизм формирования контекста для LLM;
- построение и визуализацию графа памяти;
- storage-стратегию для desktop и backend;
- ограничения, защищающие систему от мусорной памяти и перерасхода токенов;
- план внедрения и набор тестов, которые должны появиться раньше production-реализации.

Документ не означает, что фича уже реализована. Это проектная спецификация для дальнейшей реализации.

---

## 2. Контекст и текущие реалии

В текущем проекте уже существуют смежные механизмы, на которые нужно опереться:

- `GraphBasedAgent` и явный граф исполнения в `:agent`;
- history summarization в `agent/src/main/kotlin/ru/souz/agent/nodes/NodesSummarization.kt`;
- prompt enrichment через `NodesCommon.nodeAppendAdditionalData(...)`;
- desktop retrieval по локальным данным через `DesktopInfoRepository` и `VectorDB`;
- сохранение graph sessions и существующий UI-экран визуализации graph sessions;
- backend storage pattern с режимами `memory`, `filesystem`, `postgres`.

В прошлой попытке памяти уже использовались полезные идеи:

- факт как отдельная запись;
- provenance/evidence;
- timeline;
- `slot_key` для разрешения противоречий;
- визуализация memory graph;
- fallback snapshot без ожидания summarization.

Главная проблема прошлой попытки была не в модели данных, а в жизненном цикле записи: память была слишком сильно привязана к summarization, а summarization срабатывает редко.

Ключевой вывод для новой реализации:

- запись памяти нельзя привязывать только к переполнению context window;
- граф памяти нельзя строить из сырых сообщений или из embedding similarity без нормализованных фактов;
- в prompt нельзя подмешивать "всю память" или "весь граф";
- модель не должна иметь право напрямую писать произвольные записи в хранилище.

---

## 3. Цели

### 3.1 Что должна уметь система

Новая долговременная память должна:

- сохранять устойчивые пользовательские предпочтения, ограничения и договоренности;
- хранить проектные и task-level факты, важные для продолжения работы;
- хранить эпизодические summaries по задачам и рабочим сессиям;
- разрешать противоречия между версиями фактов;
- позволять объяснить, откуда взялся конкретный факт;
- позволять выборочно подмешивать в prompt только релевантный и компактный срез памяти;
- визуализировать текущее состояние памяти как граф и как timeline;
- работать в desktop-first режиме сейчас и иметь понятный путь к backend parity;
- быть устойчивой к шуму, дубликатам и "memory spam".

### 3.2 Что не является целью v1

В первой полноценной реализации не требуется:

- полноценная graph database как canonical storage;
- автоматическое доверие assistant output как фактам без evidence;
- бесконтрольная запись каждого хода в память;
- сложный multi-hop reasoning по графу внутри storage layer;
- cross-user collaborative memory;
- вложенный permission system для отдельных memory records.

---

## 4. Ключевые принципы

### 4.1 Разделение storage и prompt context

Память как хранилище может быть большой. Контекст, подмешиваемый в LLM, должен быть маленьким.

Система должна разделять:

- `memory storage`: все сохраненные факты, episodes, evidence, transitions;
- `context injection`: ограниченный набор memory packets, выбранный под текущий ход.

### 4.2 Каноническое хранилище не равно графу UI

Каноническая модель хранения должна быть реляционной и временной. Граф в UI является вычисляемой проекцией над памятью.

### 4.3 Модель предлагает, код решает

LLM может:

- предлагать memory candidates;
- извлекать сущности и факты;
- строить short summaries.

Но только код должен:

- валидировать candidate;
- решать, сохранять ли его;
- разрешать конфликты по `slot_key`;
- дедуплицировать факты;
- ограничивать объем записей;
- ограничивать prompt budget.

### 4.4 Evidence-first

Каждый принятый факт должен быть связан хотя бы с одним evidence source.

Допустимые источники:

- user message;
- tool output;
- file excerpt;
- browser/search excerpt;
- explicit system/runtime metadata;
- approved episode summary.

Недопустимый источник по умолчанию:

- assistant response без внешнего подтверждения.

### 4.5 Temporal semantics

Факты не должны "перетираться". Если приходит новая версия факта, старая запись должна быть либо:

- `SUPERSEDED`;
- `FORGOTTEN`;
- `INVALIDATED`.

Это нужно и для объяснимости, и для timeline, и для UI-графа.

---

## 5. Термины

### 5.1 Entity

Каноническая сущность, которую можно использовать как узел графа.

Примеры:

- пользователь;
- проект Souz;
- модуль `:agent`;
- файл;
- конкретная задача;
- человек;
- календарь;
- чат;
- эпизод работы.

### 5.2 Fact

Нормализованное утверждение вида:

```text
subject -> predicate -> object/value
```

Примеры:

- `User -> prefers_language -> ru`
- `Project(Souz) -> active_initiative -> LongTermMemory`
- `Task(memory-redesign) -> status -> in_progress`
- `User -> works_on -> Project(Souz)`

### 5.3 Episode

Краткое описание отдельной рабочей сессии, задачи или диалогового эпизода.

### 5.4 Evidence

Первичный источник, из которого был извлечен факт.

### 5.5 Slot key

Логический ключ конфликтов. Позволяет системе понять, что новый факт заменяет старую версию, а не является независимой новой записью.

Примеры:

- `user.profile.language`
- `user.preferences.timezone`
- `project.souz.active_initiative`
- `task.memory-redesign.status`

### 5.6 Memory packet

Компактный блок памяти, сформированный для подмешивания в prompt. Это read model для LLM, а не raw DB row.

---

## 6. Таксономия памяти

Память делится на четыре слоя.

### 6.1 Profile memory

Самые устойчивые и всегда полезные пользовательские свойства:

- язык;
- timezone;
- стиль работы;
- долгоживущие ограничения;
- explicit preferences;
- проектные инварианты.

Примеры:

- "Пиши по-русски";
- "Перед реализацией сначала тесты";
- "UI без business logic в composables".

### 6.2 Semantic memory

Нормализованные факты о сущностях и отношениях:

- что за проект;
- какие модули участвуют;
- какие решения уже приняты;
- какие ограничения действуют;
- какие сущности связаны между собой.

### 6.3 Episodic memory

Итоги конкретных задач и сессий:

- что делали;
- к чему пришли;
- что осталось следующим шагом;
- почему было принято решение.

### 6.4 Working memory

Краткоживущая task/thread memory. Может жить поверх текущего контекста и не обязана становиться долгоживущей semantic memory.

---

## 7. Области видимости

Память должна храниться с явной областью видимости.

```text
GLOBAL
USER
PROJECT
WORKSPACE
CHAT
THREAD
EPISODE
```

Ожидаемое поведение:

- `USER` для предпочтений и персональных ограничений;
- `PROJECT` для Souz-level фактов;
- `WORKSPACE` для конкретной локальной рабочей копии;
- `CHAT` и `THREAD` для контекста конкретного разговора;
- `EPISODE` для summaries отдельного фрагмента работы.

Desktop может работать как single-user runtime, но модель данных должна изначально поддерживать user-scoped записи. Это нужно для дальнейшей backend parity.

---

## 8. Каноническая модель данных

### 8.1 Storage layout

Для desktop canonical storage должен жить под:

```text
~/.local/state/souz/memory.db
```

Возможна дополнительная derived vector projection:

```text
~/.local/state/souz/memory-vector-index/
```

### 8.2 Таблицы

#### 8.2.1 `memory_entity`

Канонические сущности.

Рекомендуемые поля:

- `id`
- `scope_type`
- `scope_id`
- `entity_type`
- `canonical_name`
- `display_name`
- `normalized_key`
- `status`
- `created_at`
- `updated_at`

#### 8.2.2 `memory_entity_alias`

Алиасы сущностей для resolution.

Поля:

- `id`
- `entity_id`
- `alias`
- `normalized_alias`
- `source`
- `created_at`

#### 8.2.3 `memory_fact`

Основная таблица фактов.

Поля:

- `id`
- `scope_type`
- `scope_id`
- `subject_entity_id`
- `predicate`
- `object_kind`
- `object_entity_id`
- `object_value_text`
- `object_value_json`
- `slot_key`
- `confidence`
- `status`
- `reason_to_store`
- `created_at`
- `valid_from`
- `invalidated_at`
- `invalidated_by_fact_id`
- `origin_episode_id`
- `writer_version`

`object_kind`:

- `ENTITY`
- `TEXT`
- `NUMBER`
- `BOOLEAN`
- `JSON`

`status`:

- `ACTIVE`
- `SUPERSEDED`
- `FORGOTTEN`
- `INVALIDATED`
- `REJECTED`

`REJECTED` не обязателен в основной таблице фактов. Для rejected candidates лучше использовать отдельный diagnostics log.

#### 8.2.4 `memory_fact_evidence`

Привязка факта к evidence source.

Поля:

- `id`
- `fact_id`
- `evidence_id`
- `support_type`
- `weight`
- `created_at`

#### 8.2.5 `memory_evidence`

Первичный источник информации.

Поля:

- `id`
- `scope_type`
- `scope_id`
- `evidence_type`
- `source_ref`
- `source_hash`
- `content_excerpt`
- `content_json`
- `created_at`

`evidence_type`:

- `USER_MESSAGE`
- `ASSISTANT_MESSAGE`
- `TOOL_OUTPUT`
- `FILE_EXCERPT`
- `WEB_EXCERPT`
- `SYSTEM_METADATA`
- `EPISODE_SUMMARY`

`ASSISTANT_MESSAGE` допускается хранить как evidence, но такие evidence должны иметь низкий trust level и не должны в одиночку делать факт accepted.

#### 8.2.6 `memory_episode`

Эпизодическая память.

Поля:

- `id`
- `scope_type`
- `scope_id`
- `title`
- `summary`
- `status`
- `started_at`
- `ended_at`
- `last_touched_at`
- `next_action`
- `importance`

#### 8.2.7 `memory_embedding_doc`

Документы для retrieval index.

Поля:

- `id`
- `doc_type`
- `source_record_type`
- `source_record_id`
- `scope_type`
- `scope_id`
- `text`
- `status`
- `embedding_model_fingerprint`
- `created_at`
- `updated_at`

`doc_type`:

- `FACT`
- `EPISODE`
- `PROFILE`

#### 8.2.8 `memory_write_attempt`

Диагностика работы memory pipeline.

Поля:

- `id`
- `scope_type`
- `scope_id`
- `turn_ref`
- `trigger_type`
- `input_excerpt`
- `candidates_json`
- `accepted_count`
- `rejected_count`
- `rejection_reasons_json`
- `created_at`

#### 8.2.9 `memory_injection_log`

Диагностика чтения памяти в prompt.

Поля:

- `id`
- `scope_type`
- `scope_id`
- `turn_ref`
- `query_excerpt`
- `selected_record_ids_json`
- `rendered_packet`
- `estimated_tokens`
- `created_at`

### 8.3 Индексы

Минимально нужны индексы по:

- `memory_fact(scope_type, scope_id, status)`
- `memory_fact(slot_key, status)`
- `memory_fact(subject_entity_id, predicate, status)`
- `memory_entity(normalized_key)`
- `memory_entity_alias(normalized_alias)`
- `memory_episode(scope_type, scope_id, last_touched_at desc)`
- `memory_embedding_doc(status, embedding_model_fingerprint)`

### 8.4 Почему canonical storage должен быть реляционным

Причины:

- удобная temporal semantics;
- простые conflict rules;
- удобная объяснимость;
- понятный путь к `memory/filesystem/postgres` для backend;
- граф UI можно вычислять поверх этих таблиц;
- тестируемость выше, чем у ad-hoc graph blob storage.

---

## 9. Формирование памяти: write pipeline

### 9.1 Общее правило

Память не должна формироваться только во время summarization. Запись памяти должна быть отдельным post-turn pipeline.

### 9.2 Момент запуска

Write pipeline должен запускаться после завершения agent turn.

Desktop integration point:

- после `AgentFacade.execute(...)` и обновления `AgentContext`.

В будущем backend integration point:

- после завершения execution в `AgentExecutionService`.

### 9.3 Trigger types

Pipeline запускается не всегда. Нужен trigger classifier.

Рекомендуемые trigger types:

- `EXPLICIT_REMEMBER_REQUEST`
- `USER_PROFILE_SIGNAL`
- `PROJECT_DECISION`
- `TASK_STATE_CHANGE`
- `TOOL_CONFIRMED_WORLD_CHANGE`
- `EPISODE_COMPLETED`
- `PERIODIC_EPISODE_SNAPSHOT`
- `CORRECTION_OF_PREVIOUS_FACT`

Pipeline не должен запускаться на каждом casual сообщении.

### 9.4 Evidence bundle

Перед вызовом memory extractor формируется компактный `EvidenceBundle`.

Состав:

- текущее user message;
- релевантные tool outputs текущего хода;
- при необходимости последний assistant answer;
- scope metadata;
- link на текущий chat/thread/episode;
- список недавних memory facts для conflict awareness, но не вся база.

### 9.5 LLM memory extractor

LLM получает строгое задание выдать не более `N` кандидатов.

Рекомендуемое ограничение:

- максимум `3` candidates на ход;
- по умолчанию `0` или `1` для обычных ходов.

Extractor должен возвращать JSON со структурой:

```json
{
  "candidates": [
    {
      "subject": {
        "entity_type": "USER",
        "canonical_name": "current_user"
      },
      "predicate": "prefers_language",
      "object": {
        "kind": "TEXT",
        "value": "ru"
      },
      "scope_type": "USER",
      "scope_id": "local-user",
      "slot_key": "user.profile.language",
      "confidence": 0.96,
      "reason_to_store": "Stable explicit user preference",
      "evidence_refs": ["bundle:user_message:0"]
    }
  ]
}
```

### 9.6 Deterministic validation

После extractor срабатывает жесткий кодовый validator.

Candidate отклоняется, если:

- нет evidence;
- единственный evidence это assistant message;
- candidate дублирует уже активный факт без смыслового изменения;
- candidate слишком ephemeral;
- predicate не входит в допустимый allowlist;
- scope явно неверный;
- confidence ниже минимального порога;
- candidate конфликтует с hard policy;
- на ход уже принят лимит записей.

### 9.7 Predicate allowlist

В первой реализации нужно использовать allowlist предикатов.

Примеры:

- `prefers_language`
- `prefers_timezone`
- `requires`
- `works_on`
- `active_initiative`
- `status`
- `depends_on`
- `located_in_file`
- `uses_module`
- `prohibits`
- `approved_decision`

Нельзя разрешать модели изобретать бесконечное число новых predicates без контроля.

### 9.8 Entity resolution

Entity resolver отвечает за:

- поиск существующей сущности по `normalized_key` и alias;
- merge aliases;
- создание новой сущности, если стабильного совпадения нет.

Resolver должен быть mostly deterministic.

LLM может предлагать canonical names, но merge decision должен контролироваться кодом.

### 9.9 Conflict resolution через `slot_key`

Если новый fact имеет тот же `slot_key`, что и активный старый fact:

- новый fact становится `ACTIVE`;
- старый fact получает `SUPERSEDED`;
- в `invalidated_by_fact_id` указывается новый fact;
- в timeline появляется transition.

Если новый fact не противоречит старому и является отдельным утверждением, он сохраняется как independent fact.

### 9.10 Periodic episode snapshots

Отдельный механизм должен собирать эпизодические summaries без привязки к `historyIsTooBig()`.

Рекомендуемое поведение:

- snapshot после каждых `N` user turns;
- snapshot при закрытии задачи;
- snapshot при явной смене темы.

Эти snapshots идут в `memory_episode`, а не напрямую в `memory_fact`.

### 9.11 Consolidation

Фоновая консолидация нужна отдельно от hot path.

Задачи consolidation:

- дедуп semantic facts;
- compress episodes;
- mark stale low-value thread facts as forgotten;
- rebuild embedding projection;
- merge aliases при безопасном совпадении.

Consolidation может выполняться:

- по расписанию;
- при idle;
- вручную из diagnostics UI.

---

## 10. Защита от memory spam

### 10.1 Hard quotas

Нужны жесткие ограничения:

- максимум `3` candidates на ход;
- максимум `1` активный факт на `slot_key`;
- максимум `K` активных profile facts;
- максимум `K` active thread facts на thread scope;
- cooldown на повторную запись одинакового факта.

### 10.2 Relevance gate

В долговременную память не должны попадать:

- chit-chat;
- одноразовые вежливые фразы;
- повтор последних user questions без устойчивой ценности;
- текущая временная формулировка задачи, если она уже есть как episode state;
- случайный assistant reasoning.

### 10.3 Trust model

Different evidence types должны иметь разный вес.

Пример:

- `USER_MESSAGE`: high trust для user preferences;
- `TOOL_OUTPUT`: high trust для world state;
- `FILE_EXCERPT`: medium-high trust;
- `WEB_EXCERPT`: medium trust;
- `ASSISTANT_MESSAGE`: low trust.

### 10.4 Forgetting policy

Нужно явно предусмотреть controlled forgetting:

- stale thread facts можно переводить в `FORGOTTEN`;
- superseded facts не удаляются сразу;
- profile facts удаляются только вручную или явным новым evidence;
- эпизоды могут быть compressed в summaries.

---

## 11. Чтение памяти: retrieval и формирование контекста

### 11.1 Общий принцип

В контекст не кладутся:

- весь граф;
- все facts;
- все episodes;
- все соседние nodes.

В контекст кладется компактный и ранжированный набор `memory packets`.

### 11.2 Injection point

Память должна добавляться на этапе prompt enrichment рядом с текущим `NodesCommon.nodeAppendAdditionalData(...)`, но через отдельный сервис памяти.

### 11.3 Этапы retrieval

#### 11.3.1 Query understanding

Из текущего user input извлекаются:

- упомянутые сущности;
- scope hints;
- intent category;
- keywords;
- file/module references;
- explicit recall/debug requests.

#### 11.3.2 Always-on retrieval

Всегда доступны:

- language and locale;
- explicit user constraints;
- active project invariants;
- active episode summary, если он есть.

#### 11.3.3 Candidate generation

Кандидаты собираются из четырех источников:

- active profile facts;
- active episode summary;
- semantic search по active facts и episodes;
- graph expansion на `1-hop` от явно релевантных entities.

#### 11.3.4 Ranking

Для ranking учитываются:

- совпадение сущностей;
- lexical relevance;
- vector similarity;
- scope match;
- confidence;
- freshness;
- trust level evidence;
- active status;
- redundancy penalty.

Примерно:

```text
score =
  entity_overlap
  + lexical_score
  + vector_score
  + scope_boost
  + confidence_boost
  + freshness_boost
  - stale_penalty
  - redundancy_penalty
```

#### 11.3.5 Packetization

Релевантные facts и episodes превращаются в human-readable memory packets.

Примеры packets:

- `User language: Russian`
- `Project: Souz`
- `Active initiative: redesign long-term memory`
- `Important constraint: write tests before implementation`
- `Related modules: agent, sharedUI, backend`

### 11.4 Token budget

Контекст памяти должен иметь жесткий budget.

Рекомендуемые границы:

- `profile/constraints`: до `150` токенов;
- `active episode`: до `250` токенов;
- `semantic recall`: до `300` токенов;
- общий budget памяти: обычно `300-700` токенов;
- hard cap: `900` токенов.

### 11.5 Rendering rules

В prompt должны попадать:

- только `ACTIVE` записи;
- только одна запись на `slot_key`;
- только факты, прошедшие ranking;
- без внутренних ID;
- без низкоуровневой сериализации БД;
- без evidence details по умолчанию.

Evidence details должны добавляться только если запрос требует explainability.

### 11.6 Injection format

Рекомендуемый формат:

```text
<memory>
Use only if relevant to the user request. Do not mention memory unless needed.
- User language: Russian
- Project: Souz
- Active initiative: redesign long-term memory
- Important constraint: write tests before implementation
- Related modules: agent, sharedUI, backend
</memory>
```

### 11.7 Diagnostics

Каждый memory injection должен логироваться в `memory_injection_log`.

Это нужно для ответа на вопросы:

- почему конкретный факт попал в prompt;
- почему другой факт не попал;
- сколько токенов занял memory block.

---

## 12. Построение графа памяти

### 12.1 Главное правило

Граф строится не из сырых сообщений и не из embedding similarity. Граф строится из нормализованных entities и facts.

### 12.2 Узлы графа

В основном режиме узлами являются:

- `memory_entity`
- optionally `memory_episode`

Literal values не обязаны быть полноценными узлами. Для компактности UI их лучше показывать как атрибуты сущности, если они не участвуют в богатых отношениях.

### 12.3 Ребра графа

Ребра вычисляются из `memory_fact`.

Типы:

- `semantic edge`: `entity -> predicate -> entity`
- `attribute edge`: `entity -> predicate -> literal`
- `temporal edge`: `fact A -> superseded_by -> fact B`
- `evidence edge`: `fact -> supported_by -> evidence`
- `episode edge`: `episode -> observed_fact -> fact`

### 12.4 Основной режим UI

Основной граф UI должен показывать:

- только `ACTIVE` semantic state;
- фильтры по scope;
- фильтры по type;
- скрытие literal-only шума;
- раскрытие provenance в side panel.

### 12.5 Дополнительные режимы

UI памяти должен поддерживать несколько режимов:

- `Graph`: текущее активное состояние;
- `Timeline`: история фактов и transitions;
- `Evidence`: откуда взялся выбранный факт;
- `Diagnostics`: accepted/rejected write attempts и injection logs.

### 12.6 Reuse существующего graph UI

Существующий экран graph sessions уже содержит:

- canvas layout;
- node/edge rendering;
- side panel;
- timeline strip;
- keyboard navigation;
- resize panel.

Новая memory visualization должна по возможности переиспользовать эти паттерны, но работать не с `GraphSession`, а с `MemoryGraphSnapshot`.

### 12.7 Read model для UI

Нужен отдельный DTO:

```text
MemoryGraphSnapshot
├── entities
├── facts
├── activeEdges
├── timelineEvents
├── evidenceRefs
├── diagnosticsSummary
└── generatedAt
```

Это должен быть explicit read model, а не прямое чтение из всех таблиц UI-компонентами.

---

## 13. Архитектура модулей

### 13.1 `:agent`

Должен содержать memory domain contracts и orchestration layer:

- memory DTOs;
- retrieval/write pipeline interfaces;
- scope models;
- candidate validator contracts;
- packet renderer contracts;
- memory integration with prompt enrichment;
- post-turn memory orchestration contracts.

### 13.2 `:sharedLogic`

Должен содержать desktop/shared runtime implementations:

- SQLite repositories для desktop;
- derived vector index projection;
- embedding index rebuild logic;
- memory maintenance services;
- diagnostics support.

### 13.3 `:desktopApp`

Должен содержать:

- DI wiring;
- desktop-specific lifecycle hooks;
- runtime integration points;
- startup maintenance scheduling.

### 13.4 `:sharedUI`

Должен содержать:

- memory screens;
- memory graph view model;
- diagnostics UI;
- timeline and evidence panels;
- integration in settings/support area.

### 13.5 `:backend`

Должен содержать backend parity:

- repository interfaces or adapters under existing storage mode pattern;
- memory/filesystem/postgres implementations;
- optional API for memory inspection and management;
- runtime integration after chat execution.

---

## 14. Storage strategy

### 14.1 Desktop canonical storage

Desktop source of truth:

- SQLite `memory.db`

Причины:

- транзакционность;
- индексы;
- temporal updates;
- diagnostics tables;
- удобные migrations.

### 14.2 Desktop retrieval projection

Для retrieval предлагается derived index:

- Lucene-based index, аналогичный текущему `VectorDB`, но уже над memory docs, а не над raw desktop facts.

Причины:

- в проекте уже есть рабочий Lucene vector path;
- можно не тащить SQLite vector extension;
- canonical state и search projection отделены.

### 14.3 Backend storage

Backend должен поддерживать тот же домен через:

- `memory`;
- `filesystem`;
- `postgres`.

Рекомендуемая стратегия:

- `postgres` как полноценная production реализация;
- `filesystem` как JSON/append-log storage с проекциями;
- `memory` как bounded in-process repo для тестов и local/dev.

### 14.4 Migrations

SQLite и Postgres реализации должны иметь versioned schema migrations.

Нельзя хранить всю память единым JSON blob без миграционной стратегии.

---

## 15. Политика обновления embeddings

### 15.1 Что индексируется

Индексируются:

- active semantic facts;
- profile facts;
- active episode summaries.

### 15.2 Что не индексируется

Не индексируются:

- superseded facts по умолчанию;
- rejected candidates;
- raw evidence, если оно слишком большое;
- шумные transient diagnostics.

### 15.3 Когда переиндексировать

Переиндексация должна происходить:

- после accepted write;
- после fact invalidation;
- после episode consolidation;
- после смены embeddings model fingerprint.

### 15.4 Model fingerprint

Как и в текущем `DesktopInfoRepository`, индекс должен хранить fingerprint embeddings model, чтобы не смешивать векторные представления разных моделей.

---

## 16. Безопасность и приватность

### 16.1 User controls

UI должен позволять:

- смотреть сохраненные факты;
- вручную удалять или забывать facts;
- видеть provenance;
- видеть diagnostics причин записи и отказа;
- очищать scope-level memory;
- отключать auto-write memory pipeline.

### 16.2 Sensitive data

В memory storage могут попасть чувствительные данные. Поэтому нужны:

- explicit policy, что считается допустимой memory записью;
- redaction hooks для logs/diagnostics;
- осторожное обращение с tool outputs;
- ограничение на копирование больших личных данных в memory facts.

### 16.3 Assistant hallucinations

Меры защиты:

- assistant-only evidence не дает accepted fact;
- low confidence facts отбрасываются;
- profile facts принимаются только при explicit user signal;
- world state facts принимаются только при tool/file evidence.

---

## 17. Реализация UI

### 17.1 Экран памяти

Рекомендуемое место:

- `Settings -> Support -> Memory`

### 17.2 Основные секции

- overview counters;
- memory graph;
- timeline;
- evidence inspector;
- rejected writes;
- recent injections;
- manual actions.

### 17.3 Manual actions

UI должен поддерживать:

- `Forget fact`
- `Delete fact`
- `Mark invalid`
- `Rebuild embeddings`
- `Run consolidation`
- `Copy evidence`
- `Open source ref`, если source_ref доступен.

### 17.4 Diagnostics first

Без diagnostics memory system быстро станет непрозрачной.

Минимально нужно показывать:

- last write attempt;
- accepted candidates;
- rejected candidates;
- rejection reasons;
- last memory injection;
- rendered memory packet;
- estimated token cost.

---

## 18. Контракты сервисов

### 18.1 `MemoryWriteService`

Ответственность:

- принимать `MemoryWriteInput`;
- запускать extractor;
- валидировать candidates;
- писать facts/entities/evidence/episodes;
- публиковать diagnostics result.

### 18.2 `MemoryRetrievalService`

Ответственность:

- принимать текущий turn request;
- возвращать `MemoryInjectionResult`;
- ранжировать candidates;
- соблюдать token budget;
- логировать injection.

### 18.3 `MemoryGraphQueryService`

Ответственность:

- собирать `MemoryGraphSnapshot`;
- фильтровать по scope;
- подготавливать данные для UI.

### 18.4 `MemoryMaintenanceService`

Ответственность:

- consolidation;
- forgetting;
- dedup;
- reindexing;
- alias merge maintenance.

---

## 19. Формат ключевых DTO

### 19.1 `MemoryWriteInput`

```text
MemoryWriteInput
├── userMessage
├── assistantMessage
├── toolOutputs
├── scope
├── turnRef
├── triggerType
├── recentFacts
└── recentEpisodeSummary
```

### 19.2 `MemoryCandidate`

```text
MemoryCandidate
├── subject
├── predicate
├── object
├── scope
├── slotKey
├── confidence
├── reasonToStore
├── evidenceRefs
└── suggestedStatus
```

### 19.3 `MemoryInjectionResult`

```text
MemoryInjectionResult
├── packets
├── renderedBlock
├── selectedRecordIds
├── estimatedTokens
└── debugSummary
```

### 19.4 `MemoryGraphSnapshot`

```text
MemoryGraphSnapshot
├── entities
├── edges
├── attributes
├── timelineEvents
├── evidenceIndex
├── diagnostics
└── generatedAt
```

---

## 20. Порядок внедрения

### 20.1 Общий принцип

Сначала тесты, потом реализация.

### 20.2 Этап 0: контракты и failing tests

Добавить тесты до production-кода:

- extraction gate tests;
- validator tests;
- conflict resolution tests;
- retrieval ranking tests;
- token budget tests;
- graph projection tests;
- diagnostics tests;
- UI view model tests.

### 20.3 Этап 1: canonical storage и diagnostics

Реализовать:

- SQLite schema;
- repositories;
- write attempts log;
- injection log;
- graph snapshot query.

На этом этапе допускается manual population without full auto-write.

### 20.4 Этап 2: write pipeline

Реализовать:

- triggers;
- extractor integration;
- validation;
- conflict resolution;
- episode snapshots.

### 20.5 Этап 3: retrieval pipeline

Реализовать:

- memory retrieval service;
- ranking;
- token budget;
- prompt rendering;
- integration with prompt enrichment node.

### 20.6 Этап 4: UI memory inspector

Реализовать:

- graph mode;
- timeline mode;
- evidence mode;
- diagnostics mode;
- manual maintenance actions.

### 20.7 Этап 5: backend parity

Реализовать backend storage abstractions и persistence modes.

---

## 21. Набор обязательных тестов

### 21.1 Domain tests

- explicit remember request creates fact;
- casual chit-chat does not create fact;
- assistant-only statement is rejected;
- duplicate fact is ignored;
- same `slot_key` supersedes old fact;
- low-confidence fact is rejected;
- evidence-less fact is rejected.

### 21.2 Retrieval tests

- only `ACTIVE` facts appear in prompt;
- only one fact per `slot_key` appears in prompt;
- stale superseded fact is excluded;
- explicit entity mention boosts connected facts;
- token budget truncates lower-ranked facts;
- profile facts are injected before weak semantic facts.

### 21.3 Storage tests

- SQLite migration creates full schema;
- accepted write persists fact + evidence + diagnostics;
- conflict update writes transition fields;
- reindex removes superseded facts from vector projection;
- model fingerprint mismatch blocks stale index usage.

### 21.4 Graph tests

- graph projection includes entity edges from facts;
- literal facts become attributes instead of noisy nodes;
- timeline contains supersede transitions;
- evidence panel resolves linked sources correctly.

### 21.5 UI tests

- memory screen opens from settings/support flow;
- graph filters update snapshot query;
- diagnostics screen shows rejection reason;
- manual forget action triggers maintenance use case.

---

## 22. Риски

### 22.1 Overfitting to the current chat model

Если memory extractor слишком зависит от текущего chat model behavior, качество памяти будет сильно плавать.

Смягчение:

- strict JSON schema;
- allowlist predicates;
- validator in code;
- conservative acceptance policy.

### 22.2 Token creep

Если retrieval станет слишком щедрым, prompt memory block разрастется.

Смягчение:

- hard budget;
- ranking;
- one fact per slot;
- packet renderer with strict size limits.

### 22.3 Graph clutter

Если literal values и evidence nodes всегда рисовать как first-class graph nodes, UI быстро станет нечитаемым.

Смягчение:

- compact graph mode;
- evidence in side panel;
- timeline as separate mode.

### 22.4 Storage divergence desktop/backend

Если desktop и backend получат разные memory semantics, поведение будет расходиться.

Смягчение:

- общий memory domain;
- storage-specific adapters под одинаковые контракты;
- shared test suite.

---

## 23. Открытые вопросы

- Нужен ли отдельный configurable model для memory extraction, или достаточно текущего active chat model?
- Нужно ли давать пользователю fine-grained toggle по scopes памяти?
- Нужна ли синхронизация desktop memory и backend memory в гибридном режиме?
- Нужен ли явный API для "pin fact" и "never forget"?
- Нужен ли отдельный lightweight lexical retrieval alongside embeddings для локальных моделей низкого качества?

До реализации эти вопросы должны быть либо закрыты решением, либо зафиксированы как explicit follow-up items.

---

## 24. Рекомендуемое решение

Для Souz рекомендуется следующий вариант:

- canonical relational temporal memory в SQLite/Postgres;
- derived vector retrieval projection;
- graph UI как read model над entities/facts/evidence/timeline;
- post-turn memory writing;
- retrieval с жестким token budget;
- diagnostics-first UI;
- backend-compatible contracts с самого начала.

Это дает:

- объяснимость;
- контроль над шумом;
- устойчивость к конфликтам;
- адекватный token footprint;
- понятный путь к production backend storage;
- reuse уже существующих графовых и retrieval паттернов проекта.
