# Chat execution

`ChatUseCase` owns one request session from the first user message through the final assistant response. Skills active-run input belongs to that session; it must not call `sendChatMessage`, reset the agent, or create another observability request.

Accepted active-run input is shown as another user message. The provisional assistant message is removed and its streaming accumulator is reset before replacement output is rendered. Cancellation removes every user message associated with the session together with its pending assistant message.

The UI exposes active-run input only while the Skills graph is processing and no tool review is awaiting a decision. Send and Stop remain separate actions. A rejected submission does not fall through to a new request because the current run may be crossing its final-response boundary.
