package ru.souz.backend.testutil

import ru.souz.backend.events.model.RawAgentEventPayload

internal fun rawEventPayload(
    values: Map<String, String>,
): RawAgentEventPayload = RawAgentEventPayload(LinkedHashMap(values))

internal fun rawEventPayload(
    vararg pairs: Pair<String, String>,
): RawAgentEventPayload = RawAgentEventPayload(linkedMapOf(*pairs))
