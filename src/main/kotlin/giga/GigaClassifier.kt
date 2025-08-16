package com.dumch.giga

import com.dumch.giga.GigaAgent.ToolCategory

fun interface GigaClassifier {
    suspend fun classify(body: GigaRequest.Chat): ToolCategory?
}
