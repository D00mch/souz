package ru.souz.tool.telegram

import ru.souz.service.telegram.TelegramContactCandidate

typealias TelegramContactSelectionRequest = SelectionRequest<Long, TelegramContactCandidate>

class TelegramContactSelectionBroker : SelectionBroker<Long, TelegramContactCandidate>()
