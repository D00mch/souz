package ru.souz.tool.telegram

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.jetbrains.compose.resources.getString
import ru.souz.tool.SelectionApprovalCandidate
import ru.souz.tool.SelectionApprovalRequest
import ru.souz.tool.SelectionApprovalSource
import souz.composeapp.generated.resources.Res
import souz.composeapp.generated.resources.*

class TelegramContactSelectionApprovalSource(
    private val broker: TelegramContactSelectionBroker,
) : SelectionApprovalSource {

    override val sourceId: String = SOURCE_ID

    override val requests: Flow<SelectionApprovalRequest> = broker.requests.map { request ->
        SelectionApprovalRequest(
            sourceId = sourceId,
            requestId = request.id,
            title = getString(Res.string.telegram_contact_select_title),
            message = getString(Res.string.telegram_contact_select_message).format(request.query),
            confirmText = getString(Res.string.telegram_contact_select_choose),
            cancelText = getString(Res.string.telegram_contact_select_cancel),
            candidates = request.candidates.map { candidate ->
                SelectionApprovalCandidate(
                    id = candidate.userId,
                    title = candidate.displayName,
                    badge = if (candidate.isContact) getString(Res.string.telegram_contact_select_contact_badge) else null,
                    meta = buildList {
                        candidate.username?.let { add("@$it") }
                        candidate.phoneMasked?.let { add(it) }
                    }.takeIf { it.isNotEmpty() }?.joinToString("  •  "),
                    preview = candidate.lastMessageText?.takeIf { it.isNotBlank() },
                )
            },
        )
    }

    override fun resolve(requestId: Long, selectedId: Long?) {
        broker.resolve(requestId, selectedId)
    }

    private companion object {
        const val SOURCE_ID = "telegram-contact"
    }
}

class TelegramChatSelectionApprovalSource(
    private val broker: TelegramChatSelectionBroker,
) : SelectionApprovalSource {

    override val sourceId: String = SOURCE_ID

    override val requests: Flow<SelectionApprovalRequest> = broker.requests.map { request ->
        SelectionApprovalRequest(
            sourceId = sourceId,
            requestId = request.id,
            title = getString(Res.string.telegram_chat_select_title),
            message = getString(Res.string.telegram_chat_select_message).format(request.query),
            confirmText = getString(Res.string.telegram_chat_select_choose),
            cancelText = getString(Res.string.telegram_chat_select_cancel),
            candidates = request.candidates.map { candidate ->
                SelectionApprovalCandidate(
                    id = candidate.chatId,
                    title = candidate.title,
                    badge = if (candidate.linkedUserId != null) {
                        getString(Res.string.telegram_chat_select_private_badge)
                    } else {
                        null
                    },
                    meta = getString(Res.string.telegram_chat_select_unread).format(candidate.unreadCount),
                    preview = candidate.lastMessageText?.takeIf { it.isNotBlank() },
                )
            },
        )
    }

    override fun resolve(requestId: Long, selectedId: Long?) {
        broker.resolve(requestId, selectedId)
    }

    private companion object {
        const val SOURCE_ID = "telegram-chat"
    }
}
