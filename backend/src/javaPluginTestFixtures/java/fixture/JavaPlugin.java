package fixture;

import java.util.List;
import java.util.Map;
import kotlin.ResultKt;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlin.coroutines.CoroutineContext;
import kotlin.coroutines.intrinsics.IntrinsicsKt;
import ru.souz.llms.LLMMessageRole;
import ru.souz.llms.LLMRequest;
import ru.souz.llms.LLMResponse;
import ru.souz.plugins.api.PluginHttpResponse;
import ru.souz.plugins.api.PluginInvocation;
import ru.souz.plugins.api.SkillPlugin;

/** Java uses the same suspending ABI, including asynchronous completion and cancellation. */
public final class JavaPlugin implements SkillPlugin {
    public List<LLMRequest.Function> getTools() {
        return List.of(new LLMRequest.Function("plugin.java_reference.fetch", "Fetch a report",
            new LLMRequest.Parameters("object", Map.of("url", new LLMRequest.Property(
                "string", null, null, null, null, null, null)), List.of("url"), false), null, null));
    }

    public Object initialize(Continuation<? super Unit> continuation) { return Unit.INSTANCE; }
    public Object shutdown(Continuation<? super Unit> continuation) { return Unit.INSTANCE; }

    public Object invoke(LLMResponse.FunctionCall call, PluginInvocation context,
                         Continuation<? super LLMRequest.Message> continuation) {
        var next = new Continuation<PluginHttpResponse>() {
            public CoroutineContext getContext() { return continuation.getContext(); }
            public void resumeWith(Object result) {
                try {
                    ResultKt.throwOnFailure(result);
                    continuation.resumeWith(message(call, (PluginHttpResponse) result));
                } catch (Throwable failure) {
                    continuation.resumeWith(ResultKt.createFailure(failure));
                }
            }
        };
        Object response = context.getHttp().request("GET", (String) call.getArguments().get("url"), Map.of(), null, next);
        if (response == IntrinsicsKt.getCOROUTINE_SUSPENDED()) return response;
        return message(call, (PluginHttpResponse) response);
    }

    private static LLMRequest.Message message(LLMResponse.FunctionCall call, PluginHttpResponse response) {
        return new LLMRequest.Message(LLMMessageRole.function, response.getBody(), null, null, call.getName(), null);
    }
}
