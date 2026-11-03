package fixture;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import kotlin.ResultKt;
import kotlin.coroutines.Continuation;
import kotlin.coroutines.CoroutineContext;
import kotlin.coroutines.intrinsics.IntrinsicsKt;
import ru.souz.plugins.api.PluginContext;
import ru.souz.plugins.api.PluginHttpRequest;
import ru.souz.plugins.api.PluginHttpResponse;
import ru.souz.plugins.api.PluginTool;
import ru.souz.plugins.api.SouzPlugin;

/** Java uses the same continuation ABI, including asynchronously completed host services. */
public final class JavaPlugin implements SouzPlugin {
    public List<PluginTool> getTools() {
        return List.of(new PluginTool("fetch", "Fetch through the host HTTP service",
            "{\"type\":\"object\",\"properties\":{\"url\":{\"type\":\"string\"}},\"required\":[\"url\"],\"additionalProperties\":false}",
            "MAIL", this::invoke));
    }

    private Object invoke(String input, PluginContext context, Continuation<? super String> continuation) {
        final String url;
        try { url = new ObjectMapper().readTree(input).get("url").asText(); }
        catch (Exception failure) { throw new IllegalArgumentException(failure); }
        var next = new Continuation<PluginHttpResponse>() {
            public CoroutineContext getContext() { return continuation.getContext(); }
            public void resumeWith(Object result) {
                try {
                    ResultKt.throwOnFailure(result);
                    continuation.resumeWith(content((PluginHttpResponse) result));
                } catch (Throwable failure) {
                    continuation.resumeWith(ResultKt.createFailure(failure));
                }
            }
        };
        Object response = context.getHttp().execute(new PluginHttpRequest(url, "GET", Map.of(), new byte[0]), next);
        if (response == IntrinsicsKt.getCOROUTINE_SUSPENDED()) return response;
        return content((PluginHttpResponse) response);
    }

    private static String content(PluginHttpResponse response) {
        return new String(response.getBody(), StandardCharsets.UTF_8);
    }
}
