package ru.souz.tool.telegram

import ru.souz.service.telegram.TelegramChatCandidate

typealias TelegramChatSelectionRequest = SelectionRequest<Long, TelegramChatCandidate>

class TelegramChatSelectionBroker : SelectionBroker<Long, TelegramChatCandidate>()
