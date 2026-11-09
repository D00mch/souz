package fixture;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.util.JsonRecyclerPools;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import kotlin.coroutines.Continuation;
import kotlin.coroutines.intrinsics.IntrinsicsKt;
import org.jetbrains.annotations.NotNull;
import ru.souz.plugins.api.PluginContext;
import ru.souz.plugins.api.PluginHttpRequest;
import ru.souz.plugins.api.PluginHttpResponse;
import ru.souz.plugins.api.PluginTool;
import ru.souz.plugins.api.SouzPlugin;

/** Java uses the same continuation ABI, including asynchronously completed host services. */
public final class JavaPlugin implements SouzPlugin {
    @Override
    public @NotNull List<PluginTool> getTools() {
        return List.of(new PluginTool("fetch", "Fetch through the host HTTP service",
            "{\"type\":\"object\",\"properties\":{\"url\":{\"type\":\"string\"}},\"required\":[\"url\"],\"additionalProperties\":false}",
            "MAIL", this::invoke));
    }

    private Object invoke(String input, PluginContext context, Continuation<? super String> continuation) {
        final String url;
        try {
            JsonFactory jf = JsonFactory.builder().recyclerPool(JsonRecyclerPools.nonRecyclingPool()).build();
            url = new ObjectMapper(jf).readTree(input).get("url").asText();
        } catch (Exception failure) {
            throw new IllegalArgumentException(failure);
        }
        var next = FixtureContinuation.mapResult(continuation, JavaPlugin::content);
        Object response = context.getHttp().execute(new PluginHttpRequest(url, "GET", Map.of(), new byte[0]), next);
        if (response == IntrinsicsKt.getCOROUTINE_SUSPENDED()) return response;
        return content(Objects.requireNonNull((PluginHttpResponse) response));
    }

    private static String content(PluginHttpResponse response) {
        return new String(response.getBody(), StandardCharsets.UTF_8);
    }
}
