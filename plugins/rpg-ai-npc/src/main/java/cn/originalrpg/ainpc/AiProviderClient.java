package cn.originalrpg.ainpc;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

final class AiProviderClient implements AutoCloseable {
    private final Plugin plugin;
    private final String protocol;
    private final String baseUrl;
    private final String model;
    private final String apiKey;
    private final String systemPrompt;
    private final String publicFacts;
    private final int requestTimeoutSeconds;
    private final int maximumOutputTokens;
    private final int maximumOutputCharacters;
    private final int maximumConcurrent;
    private final int circuitFailures;
    private final long circuitOpenNanos;
    private final boolean configured;
    private final HttpClient httpClient;
    private final ThreadPoolExecutor executor;
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private final AtomicLong circuitOpenUntil = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final java.util.Set<CompletableFuture<String>> pending = ConcurrentHashMap.newKeySet();

    AiProviderClient(Plugin plugin, FileConfiguration config) {
        this.plugin = plugin;
        protocol = setting(config, "provider.protocol", "provider.environment.protocol", "responses")
                .toLowerCase(Locale.ROOT);
        baseUrl = trimTrailingSlash(setting(config, "provider.base-url", "provider.environment.base-url", ""));
        model = setting(config, "provider.model", "provider.environment.model", "gpt-5.4-mini");
        String keyEnvironment = config.getString("provider.environment.api-key", "SIVITA_AI_API_KEY");
        apiKey = environment(keyEnvironment);
        boolean requireKey = config.getBoolean("provider.require-api-key", true);
        boolean secureTransport = secureEndpoint(baseUrl);
        configured = config.getBoolean("provider.enabled", true)
                && !baseUrl.isBlank()
                && (!requireKey || !apiKey.isBlank())
                && (protocol.equals("responses") || protocol.equals("chat-completions"))
                && secureTransport;
        systemPrompt = config.getString("prompt.system", "You are a read-only Minecraft gameplay guide.");
        publicFacts = config.getString("prompt.public-facts", "");
        int connectTimeout = Math.clamp(config.getInt("provider.connect-timeout-seconds", 4), 2, 20);
        requestTimeoutSeconds = Math.clamp(config.getInt("provider.request-timeout-seconds", 12), 4, 45);
        maximumOutputTokens = Math.clamp(config.getInt("provider.maximum-output-tokens", 300), 64, 800);
        maximumOutputCharacters = Math.clamp(
                config.getInt("conversation.maximum-output-characters", 650), 160, 1_500);
        maximumConcurrent = Math.clamp(config.getInt("provider.maximum-concurrent-requests", 2), 1, 4);
        int queueCapacity = Math.clamp(config.getInt("provider.queue-capacity", 16), 1, 64);
        circuitFailures = Math.clamp(config.getInt("provider.circuit-failures", 3), 1, 10);
        circuitOpenNanos = Duration.ofSeconds(
                Math.clamp(config.getInt("provider.circuit-open-seconds", 60), 10, 600)).toNanos();
        AtomicInteger threadId = new AtomicInteger();
        ThreadFactory factory = task -> {
            Thread thread = new Thread(task, "rpg-ai-npc-" + threadId.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        executor = new ThreadPoolExecutor(maximumConcurrent, maximumConcurrent, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity), factory, new ThreadPoolExecutor.AbortPolicy());
        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(connectTimeout))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        if (!baseUrl.isBlank() && !secureTransport) {
            plugin.getLogger().warning("AI remote provider disabled: credentials require HTTPS or an explicit "
                    + "loopback HTTP tunnel; deterministic state analysis and local guidance remain active.");
        }
    }

    CompletableFuture<String> ask(AiRequestContext context) {
        if (closed.get()) return unavailable("client closed");
        if (!configured) return unavailable("not configured");
        if (circuitOpen()) return CompletableFuture.failedFuture(new ProviderUnavailableException("circuit open"));
        CompletableFuture<String> result = new CompletableFuture<>();
        pending.add(result);
        result.whenComplete((ignored, error) -> pending.remove(result));
        try {
            executor.execute(() -> {
                if (closed.get()) {
                    result.completeExceptionally(new ProviderUnavailableException("client closed"));
                    return;
                }
                try {
                    String response = send(context);
                    if (closed.get()) {
                        result.completeExceptionally(new ProviderUnavailableException("client closed"));
                        return;
                    }
                    consecutiveFailures.set(0);
                    circuitOpenUntil.set(0L);
                    result.complete(response);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    fail(result, exception);
                } catch (IOException | RuntimeException exception) {
                    fail(result, exception);
                }
            });
        } catch (RejectedExecutionException exception) {
            String reason = closed.get() ? "client closed" : "queue full";
            result.completeExceptionally(new ProviderUnavailableException(reason, exception));
        }
        return result;
    }

    private static CompletableFuture<String> unavailable(String reason) {
        return CompletableFuture.failedFuture(new ProviderUnavailableException(reason));
    }

    boolean configured() {
        return configured && !closed.get();
    }

    boolean circuitOpen() {
        return circuitOpenUntil.get() > System.nanoTime();
    }

    int activeRequests() {
        return executor.getActiveCount();
    }

    int maximumConcurrent() {
        return maximumConcurrent;
    }

    private String send(AiRequestContext context) throws IOException, InterruptedException {
        String body = protocol.equals("chat-completions") ? chatBody(context) : responsesBody(context);
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint())
                .timeout(Duration.ofSeconds(requestTimeoutSeconds))
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Accept", "application/json")
                .header("User-Agent", "SivitaWilds-AiNpc/1")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (!apiKey.isBlank()) builder.header("Authorization", "Bearer " + apiKey);
        HttpResponse<String> response = httpClient.send(builder.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("provider HTTP " + response.statusCode());
        }
        String answer = protocol.equals("chat-completions")
                ? parseChatResponse(response.body()) : parseResponsesResponse(response.body());
        if (answer.isBlank()) throw new IOException("provider returned no text");
        return truncate(answer.replace('\u00a7', ' ').strip(), maximumOutputCharacters);
    }

    private URI endpoint() {
        String suffix = protocol.equals("chat-completions") ? "/chat/completions" : "/responses";
        return URI.create(baseUrl.endsWith(suffix) ? baseUrl : baseUrl + suffix);
    }

    private String responsesBody(AiRequestContext context) {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("store", false);
        body.addProperty("instructions", systemPrompt + "\n\nAuthoritative public facts:\n" + publicFacts);
        body.addProperty("input", context.userPrompt());
        body.addProperty("max_output_tokens", maximumOutputTokens);
        return body.toString();
    }

    private String chatBody(AiRequestContext context) {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("max_completion_tokens", maximumOutputTokens);
        JsonArray messages = new JsonArray();
        JsonObject system = new JsonObject();
        system.addProperty("role", "system");
        system.addProperty("content", systemPrompt + "\n\nAuthoritative public facts:\n" + publicFacts);
        messages.add(system);
        JsonObject user = new JsonObject();
        user.addProperty("role", "user");
        user.addProperty("content", context.userPrompt());
        messages.add(user);
        body.add("messages", messages);
        return body.toString();
    }

    static String parseResponsesResponse(String json) throws IOException {
        JsonObject root = parseObject(json);
        JsonElement direct = root.get("output_text");
        if (direct != null && direct.isJsonPrimitive()) return direct.getAsString();
        JsonArray output = array(root.get("output"));
        StringBuilder text = new StringBuilder();
        for (JsonElement itemElement : output) {
            if (!itemElement.isJsonObject()) continue;
            JsonArray content = array(itemElement.getAsJsonObject().get("content"));
            for (JsonElement contentElement : content) {
                if (!contentElement.isJsonObject()) continue;
                JsonObject block = contentElement.getAsJsonObject();
                String type = string(block.get("type"));
                if (!type.equals("output_text") && !type.equals("text")) continue;
                String value = string(block.get("text"));
                if (!value.isBlank()) {
                    if (!text.isEmpty()) text.append('\n');
                    text.append(value);
                }
            }
        }
        return text.toString();
    }

    static String parseChatResponse(String json) throws IOException {
        JsonObject root = parseObject(json);
        JsonArray choices = array(root.get("choices"));
        if (choices.isEmpty() || !choices.get(0).isJsonObject()) return "";
        JsonElement message = choices.get(0).getAsJsonObject().get("message");
        if (message == null || !message.isJsonObject()) return "";
        return string(message.getAsJsonObject().get("content"));
    }

    private void fail(CompletableFuture<String> result, Exception exception) {
        if (closed.get()) {
            result.completeExceptionally(new ProviderUnavailableException("client closed", exception));
            return;
        }
        int failures = consecutiveFailures.incrementAndGet();
        if (failures >= circuitFailures) circuitOpenUntil.set(System.nanoTime() + circuitOpenNanos);
        plugin.getLogger().warning("AI guidance request failed (" + exception.getClass().getSimpleName()
                + "); local knowledge will answer.");
        result.completeExceptionally(new ProviderUnavailableException("remote request failed", exception));
    }

    private static JsonObject parseObject(String json) throws IOException {
        try {
            JsonElement element = JsonParser.parseString(json);
            if (!element.isJsonObject()) throw new IOException("provider response is not an object");
            return element.getAsJsonObject();
        } catch (RuntimeException exception) {
            throw new IOException("provider returned invalid JSON", exception);
        }
    }

    private static JsonArray array(JsonElement element) {
        return element != null && element.isJsonArray() ? element.getAsJsonArray() : new JsonArray();
    }

    private static String string(JsonElement element) {
        return element != null && element.isJsonPrimitive() ? element.getAsString() : "";
    }

    private static String setting(FileConfiguration config, String path, String environmentPath, String fallback) {
        String environmentName = config.getString(environmentPath, "");
        String environmentValue = environment(environmentName);
        if (!environmentValue.isBlank()) return environmentValue.strip();
        return Objects.requireNonNullElse(config.getString(path), fallback).strip();
    }

    private static String environment(String name) {
        if (name == null || name.isBlank()) return "";
        return Objects.requireNonNullElse(System.getenv(name.strip()), "");
    }

    private static String trimTrailingSlash(String value) {
        String result = value.strip();
        while (result.endsWith("/")) result = result.substring(0, result.length() - 1);
        return result;
    }

    static boolean secureEndpoint(String value) {
        try {
            URI uri = URI.create(value);
            if (uri.getHost() == null || uri.getRawUserInfo() != null
                    || uri.getRawQuery() != null || uri.getRawFragment() != null) return false;
            String scheme = uri.getScheme();
            if (scheme == null) return false;
            if (scheme.equalsIgnoreCase("https")) return true;
            if (!scheme.equalsIgnoreCase("http")) return false;
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            return host.equals("localhost") || host.equals("127.0.0.1")
                    || host.equals("::1") || host.equals("[::1]");
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static String truncate(String value, int maximumCodePoints) {
        int count = value.codePointCount(0, value.length());
        if (count <= maximumCodePoints) return value;
        int end = value.offsetByCodePoints(0, Math.max(1, maximumCodePoints - 1));
        return value.substring(0, end).stripTrailing() + "…";
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        executor.shutdownNow();
        httpClient.shutdownNow();
        ProviderUnavailableException cancelled = new ProviderUnavailableException("client closed");
        for (CompletableFuture<String> request : pending) request.completeExceptionally(cancelled);
        pending.clear();
    }

    private static final class ProviderUnavailableException extends IOException {
        private static final long serialVersionUID = 1L;

        private ProviderUnavailableException(String message) {
            super(message);
        }

        private ProviderUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
