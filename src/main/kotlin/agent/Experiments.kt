package com.dumch.agent

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.ext.tool.AskUser
import ai.koog.agents.ext.tool.SayToUser
import ai.koog.agents.features.eventHandler.feature.EventHandler
import ai.koog.prompt.executor.clients.anthropic.AnthropicModels
import ai.koog.prompt.executor.llms.all.simpleAnthropicExecutor

suspend fun main() {
    val apiKey = System.getenv("ANTHROPIC_API_KEY")

    val toolRegistry = ToolRegistry {
        tool(SayToUser)
        tool(AskUser)
    }

    val agent = AIAgent(
        executor = simpleAnthropicExecutor(apiKey), // or Anthropic, Google, OpenRouter, etc.
        systemPrompt = "You are a helpful assistant. Answer user questions concisely.",
        toolRegistry = toolRegistry,
        llmModel = AnthropicModels.Haiku_3_5,
    ) {
        install(EventHandler) {
            onBeforeLLMCall { beforeLLMCallContext ->
                println("Request to LLM: ")
                beforeLLMCallContext.prompt.messages
            }
        }
    }
    
    val result = agent.run("Hello! How old am I?")
    println(result)
}