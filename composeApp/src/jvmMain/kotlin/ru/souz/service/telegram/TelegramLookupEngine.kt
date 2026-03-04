package ru.souz.service.telegram

import com.ibm.icu.text.Transliterator
import it.tdlight.jni.TdApi

private const val TELEGRAM_CONTACT_FUZZY_TOP_K = 40
private const val TELEGRAM_CHAT_FUZZY_TOP_K = 25

private val LOOKUP_TRANSLITERATOR = ThreadLocal.withInitial {
    Transliterator.getInstance("Russian-Latin/BGN; Latin-ASCII")
}

internal data class TelegramLookupSnapshot(
    val contactsByUserId: Map<Long, TelegramCachedContact>,
    val usersById: Map<Long, TdApi.User>,
    val chatsById: Map<Long, TelegramCachedChat>,
    val privateChatByUserId: Map<Long, Long>,
    val orderedChatIds: List<Long>,
    val meUserId: Long?,
)

internal class TelegramLookupEngine {

    fun findContactCandidates(
        rawName: String,
        snapshot: TelegramLookupSnapshot,
    ): List<TelegramContactCandidate> {
        val queryVariants = lookupVariants(rawName)
        if (queryVariants.isEmpty()) return emptyList()
        val chatRanks = orderedChatRanks(snapshot)

        val candidateIds = linkedSetOf<Long>()
        candidateIds += snapshot.contactsByUserId.keys
        candidateIds += snapshot.privateChatByUserId.keys
        snapshot.meUserId?.let(candidateIds::add)

        val drafts = candidateIds
            .mapNotNull { userId -> buildContactCandidateDraft(userId, chatRanks, snapshot) }
        if (drafts.isEmpty()) return emptyList()

        val draftsById = drafts.associateBy { it.userId }
        val cheapScored = drafts
            .mapNotNull { draft ->
                val score = aliasScore(
                    aliases = draft.aliases,
                    queryVariants = queryVariants,
                    includeFuzzy = false,
                ) + draft.recencyBoost
                if (score <= 0) null else draft.toCandidate(score)
            }
        if (cheapScored.isEmpty()) return emptyList()

        val refineIds = cheapScored
            .sortedWith(contactCandidatesComparator())
            .take(TELEGRAM_CONTACT_FUZZY_TOP_K)
            .map { it.userId }
            .toHashSet()

        return cheapScored
            .map { candidate ->
                if (candidate.userId !in refineIds) {
                    return@map candidate
                }
                val draft = draftsById[candidate.userId] ?: return@map candidate
                val refinedScore = aliasScore(
                    aliases = draft.aliases,
                    queryVariants = queryVariants,
                    includeFuzzy = true,
                ) + draft.recencyBoost
                candidate.copy(score = refinedScore)
            }
            .sortedWith(contactCandidatesComparator())
    }

    fun findChatCandidates(
        rawName: String,
        snapshot: TelegramLookupSnapshot,
    ): List<TelegramChatCandidate> {
        val queryVariants = lookupVariants(rawName)
        if (queryVariants.isEmpty()) return emptyList()
        val chatRanks = orderedChatRanks(snapshot)

        val drafts = snapshot.chatsById.values
            .mapNotNull { chat -> buildChatCandidateDraft(chat, chatRanks, snapshot) }
        if (drafts.isEmpty()) return emptyList()

        val draftsById = drafts.associateBy { it.chatId }
        val cheapScored = drafts
            .mapNotNull { draft ->
                val score = aliasScore(
                    aliases = draft.aliases,
                    queryVariants = queryVariants,
                    includeFuzzy = false,
                ) + draft.recencyBoost
                if (score <= 0) null else draft.toCandidate(score)
            }
        if (cheapScored.isEmpty()) return emptyList()

        val refineIds = cheapScored
            .sortedWith(chatCandidatesComparator())
            .take(TELEGRAM_CHAT_FUZZY_TOP_K)
            .map { it.chatId }
            .toHashSet()

        return cheapScored
            .map { candidate ->
                if (candidate.chatId !in refineIds) {
                    return@map candidate
                }
                val draft = draftsById[candidate.chatId] ?: return@map candidate
                val refinedScore = aliasScore(
                    aliases = draft.aliases,
                    queryVariants = queryVariants,
                    includeFuzzy = true,
                ) + draft.recencyBoost
                candidate.copy(score = refinedScore)
            }
            .sortedWith(chatCandidatesComparator())
    }

    fun resolveChatByName(rawName: String, snapshot: TelegramLookupSnapshot): TelegramCachedChat? {
        val asId = rawName.trim().toLongOrNull()
        if (asId != null) {
            snapshot.chatsById[asId]?.let { return it }
        }
        val best = findChatCandidates(rawName, snapshot).firstOrNull() ?: return null
        return snapshot.chatsById[best.chatId]
    }

    private data class ContactCandidateDraft(
        val userId: Long,
        val displayName: String,
        val username: String?,
        val phoneMasked: String?,
        val isContact: Boolean,
        val chatId: Long?,
        val lastMessageText: String?,
        val aliases: Set<String>,
        val recencyBoost: Int,
    ) {
        fun toCandidate(score: Int): TelegramContactCandidate {
            return TelegramContactCandidate(
                userId = userId,
                displayName = displayName,
                username = username,
                phoneMasked = phoneMasked,
                isContact = isContact,
                chatId = chatId,
                lastMessageText = lastMessageText,
                score = score,
            )
        }
    }

    private data class ChatCandidateDraft(
        val chatId: Long,
        val title: String,
        val unreadCount: Int,
        val linkedUserId: Long?,
        val lastMessageText: String?,
        val aliases: Set<String>,
        val recencyBoost: Int,
    ) {
        fun toCandidate(score: Int): TelegramChatCandidate {
            return TelegramChatCandidate(
                chatId = chatId,
                title = title,
                unreadCount = unreadCount,
                linkedUserId = linkedUserId,
                lastMessageText = lastMessageText,
                score = score,
            )
        }
    }

    private fun buildContactCandidateDraft(
        userId: Long,
        chatRanks: Map<Long, Int>,
        snapshot: TelegramLookupSnapshot,
    ): ContactCandidateDraft? {
        val cachedContact = snapshot.contactsByUserId[userId]
        val user = snapshot.usersById[userId]
        val displayName = cachedContact?.displayName ?: userDisplayName(user)
        if (displayName.isBlank() || displayName == "Unknown") return null

        val linkedChat = snapshot.privateChatByUserId[userId]?.let(snapshot.chatsById::get)
        val aliases = linkedSetOf<String>()
        aliases += cachedContact?.aliases.orEmpty()
        user?.let { aliases += userAliases(it) }
        aliases += normalizeLookup(displayName)
        linkedChat?.title?.let { aliases += normalizeLookup(it) }
        if (aliases.isEmpty()) return null

        return ContactCandidateDraft(
            userId = userId,
            displayName = displayName,
            username = user?.usernames?.activeUsernames?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() },
            phoneMasked = maskPhone(user?.phoneNumber?.takeIf { it.isNotBlank() }),
            isContact = user?.isContact == true || cachedContact != null,
            chatId = linkedChat?.chatId,
            lastMessageText = linkedChat?.lastMessageText,
            aliases = aliases,
            recencyBoost = chatRecencyBoost(linkedChat, chatRanks),
        )
    }

    private fun buildChatCandidateDraft(
        chat: TelegramCachedChat,
        chatRanks: Map<Long, Int>,
        snapshot: TelegramLookupSnapshot,
    ): ChatCandidateDraft? {
        val aliases = linkedSetOf<String>()
        aliases += normalizeLookup(chat.title)
        chat.linkedUserId
            ?.let(snapshot.usersById::get)
            ?.let(::userAliases)
            ?.let(aliases::addAll)
        if (aliases.isEmpty()) return null
        return ChatCandidateDraft(
            chatId = chat.chatId,
            title = chat.title,
            unreadCount = chat.unreadCount,
            linkedUserId = chat.linkedUserId,
            lastMessageText = chat.lastMessageText,
            aliases = aliases,
            recencyBoost = chatRecencyBoost(chat, chatRanks),
        )
    }

    private fun aliasScore(
        aliases: Set<String>,
        queryVariants: Set<String>,
        includeFuzzy: Boolean,
    ): Int {
        var best = 0

        for (alias in aliases) {
            if (alias.isBlank()) continue
            val aliasVariants = lookupVariants(alias)
            for (query in queryVariants) {
                val score = aliasVariants.maxOfOrNull { variant ->
                    if (includeFuzzy) {
                        fullSimilarityScore(variant, query)
                    } else {
                        cheapSimilarityScore(variant, query)
                    }
                } ?: 0
                if (score > best) {
                    best = score
                }
            }
        }
        return best
    }

    private fun fullSimilarityScore(alias: String, query: String): Int {
        val cheap = cheapSimilarityScore(alias, query)
        if (cheap > 0) return cheap
        val compactAlias = alias.replace(" ", "")
        val compactQuery = query.replace(" ", "")
        return fuzzySimilarityScore(compactAlias, compactQuery)
    }

    private fun cheapSimilarityScore(alias: String, query: String): Int {
        if (alias.isBlank() || query.isBlank()) return 0
        val compactAlias = alias.replace(" ", "")
        val compactQuery = query.replace(" ", "")
        val queryTokens = query.split(" ").filter { it.isNotBlank() }
        val aliasTokens = alias.split(" ").filter { it.isNotBlank() }

        return when {
            compactAlias == compactQuery -> 1_000
            compactAlias.length >= 4 && compactQuery.startsWith(compactAlias) -> 930
            compactQuery.length >= 3 && compactAlias.startsWith(compactQuery) -> 900
            compactQuery.length >= 3 && (compactAlias.contains(compactQuery) || compactQuery.contains(compactAlias)) -> 760
            queryTokens.isNotEmpty() && queryTokens.all { token ->
                aliasTokens.any { aliasToken ->
                    aliasToken == token ||
                        aliasToken.startsWith(token) ||
                        token.startsWith(aliasToken)
                }
            } -> 720

            else -> 0
        }
    }

    private fun fuzzySimilarityScore(alias: String, query: String): Int {
        val maxLen = maxOf(alias.length, query.length)
        if (maxLen < 4) return 0

        val distance = levenshteinDistance(alias, query)
        val similarity = 1.0 - (distance.toDouble() / maxLen.toDouble())
        return when {
            distance == 1 -> 700
            similarity >= 0.90 -> 680
            similarity >= 0.84 -> 640
            else -> 0
        }
    }

    private fun orderedChatRanks(snapshot: TelegramLookupSnapshot): Map<Long, Int> {
        return snapshot.orderedChatIds.withIndex().associate { (index, chatId) -> chatId to index }
    }

    private fun chatRecencyBoost(chat: TelegramCachedChat?, chatRanks: Map<Long, Int>): Int {
        if (chat == null) return 0
        val rank = chatRanks[chat.chatId] ?: -1
        return when {
            rank == -1 -> 10
            rank < 10 -> 30 - rank
            rank < 30 -> 15
            else -> 5
        }
    }

    private fun contactCandidatesComparator(): Comparator<TelegramContactCandidate> {
        return compareByDescending<TelegramContactCandidate> { it.score }
            .thenByDescending { it.isContact }
            .thenBy { it.displayName.lowercase() }
    }

    private fun chatCandidatesComparator(): Comparator<TelegramChatCandidate> {
        return compareByDescending<TelegramChatCandidate> { it.score }
            .thenByDescending { it.unreadCount }
            .thenBy { it.title.lowercase() }
    }

    private fun lookupVariants(value: String): Set<String> {
        val normalized = normalizeLookup(value)
        if (normalized.isBlank()) return emptySet()

        return buildSet {
            add(normalized)
            add(normalized.replace(" ", ""))
            val latinized = transliterateToLatin(normalized)
            if (latinized.isNotBlank()) {
                add(latinized)
                add(latinized.replace(" ", ""))
            }
        }
    }

    private fun transliterateToLatin(value: String): String {
        val transliterated = LOOKUP_TRANSLITERATOR.get().transliterate(value.lowercase())
        return normalizeLookup(transliterated)
    }

    private fun levenshteinDistance(left: String, right: String): Int {
        if (left == right) return 0
        if (left.isEmpty()) return right.length
        if (right.isEmpty()) return left.length

        var previous = IntArray(right.length + 1) { it }
        var current = IntArray(right.length + 1)

        for (i in left.indices) {
            current[0] = i + 1
            for (j in right.indices) {
                val substitution = if (left[i] == right[j]) 0 else 1
                current[j + 1] = minOf(
                    current[j] + 1,
                    previous[j + 1] + 1,
                    previous[j] + substitution,
                )
            }
            val temp = previous
            previous = current
            current = temp
        }

        return previous[right.length]
    }

    private fun userDisplayName(user: TdApi.User?): String {
        user ?: return "Unknown"
        val first = user.firstName.orEmpty().trim()
        val last = user.lastName.orEmpty().trim()
        val full = listOf(first, last).filter { it.isNotBlank() }.joinToString(" ")
        if (full.isNotBlank()) {
            return full
        }

        val username = user.usernames?.activeUsernames?.firstOrNull()?.trim()
        if (!username.isNullOrEmpty()) {
            return "@$username"
        }

        if (user.phoneNumber.orEmpty().isNotBlank()) {
            return "+${user.phoneNumber}"
        }

        return user.id.toString()
    }

    private fun userAliases(user: TdApi.User): Set<String> {
        val aliases = linkedSetOf<String>()
        val first = user.firstName.orEmpty().trim()
        val last = user.lastName.orEmpty().trim()
        val full = listOf(first, last).filter { it.isNotBlank() }.joinToString(" ")

        if (full.isNotBlank()) {
            aliases += normalizeLookup(full)
        }
        if (first.isNotBlank()) {
            aliases += normalizeLookup(first)
        }
        if (last.isNotBlank()) {
            aliases += normalizeLookup(last)
        }

        user.usernames?.activeUsernames?.forEach { rawUsername ->
            val username = rawUsername ?: return@forEach
            val normalized = normalizeLookup(username)
            if (normalized.isNotBlank()) {
                aliases += normalized
                aliases += normalizeLookup("@$username")
            }
        }

        val phoneAlias = normalizeLookup("+${user.phoneNumber.orEmpty()}")
        if (phoneAlias.isNotBlank()) {
            aliases += phoneAlias
        }

        return aliases
    }

    private fun normalizeLookup(value: String): String = value
        .trim()
        .lowercase()
        .replace(Regex("[^\\p{L}\\p{N}@+]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun maskPhone(phone: String?): String? {
        if (phone.isNullOrBlank()) return null
        val normalized = if (phone.startsWith("+")) phone else "+$phone"
        if (normalized.length <= 9) return normalized.take(5) + "***"
        return normalized.take(5) + "***" + normalized.takeLast(4)
    }
}
