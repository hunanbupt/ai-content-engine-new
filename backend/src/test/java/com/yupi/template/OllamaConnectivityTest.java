package com.yupi.template;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Ollama 本地模型连通性测试
 * 纯 HTTP 调用，不依赖 Spring 上下文，验证 Ollama 服务和模型是否正常
 */
public class OllamaConnectivityTest {

    private static final String OLLAMA_BASE_URL = "http://localhost:11434";
    private static final String MODEL = "qwen2.5-coder:14b";

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final Gson gson = new Gson();
    @Test
    public void testOllamaConnection() throws Exception {
        // 构建请求 JSON
        JsonObject body = new JsonObject();
        body.addProperty("model", MODEL);
        body.addProperty("stream", false);

        JsonArray messages = new JsonArray();
        JsonObject msg = new JsonObject();
        msg.addProperty("role", "user");
        msg.addProperty("content", "测试连接，返回一条简单消息");
        messages.add(msg);
        body.add("messages", messages);

        // 构建 HttpRequest
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(OLLAMA_BASE_URL + "/api/chat"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body)))
                .timeout(Duration.ofSeconds(30))
                .build();

        // 发送请求
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        // 打印状态码和返回体
        System.out.println("HTTP 状态码: " + response.statusCode());
        System.out.println("返回内容: " + response.body());

        // 简单断言，检查是否 200
        assert response.statusCode() == 200 : "Ollama 接口未返回 200";
    }
    @Test
    public void testOllamaIsRunning() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(OLLAMA_BASE_URL))
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        System.out.println("Ollama 状态码: " + response.statusCode());
        assert response.statusCode() == 200 : "Ollama 服务未运行，请执行 ollama serve";
        System.out.println("Ollama 服务运行中 ✓");
    }

    @Test
    public void testListModels() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(OLLAMA_BASE_URL + "/api/tags"))
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        JsonObject json = gson.fromJson(response.body(), JsonObject.class);
        JsonArray models = json.getAsJsonArray("models");

        System.out.println("已安装的模型:");
        boolean found = false;
        for (int i = 0; i < models.size(); i++) {
            String name = models.get(i).getAsJsonObject().get("name").getAsString();
            System.out.println("  - " + name);
            if (name.equals(MODEL)) found = true;
        }
        if (!found) {
            System.out.println("警告: 未找到 " + MODEL + "，请执行 ollama pull " + MODEL);
        } else {
            System.out.println("目标模型 " + MODEL + " 已安装 ✓");
        }
    }

    @Test
    public void testChatSync() throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("model", MODEL);
        body.addProperty("stream", false);

        JsonArray messages = new JsonArray();
        JsonObject msg = new JsonObject();
        msg.addProperty("role", "user");
        msg.addProperty("content", "你好，请用一句话介绍你自己");
        messages.add(msg);
        body.add("messages", messages);

        JsonObject options = new JsonObject();
        options.addProperty("temperature", 0.7);
        options.addProperty("num_predict", 200);
        body.add("options", options);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(OLLAMA_BASE_URL + "/api/chat"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body)))
                .timeout(Duration.ofSeconds(120))
                .build();

        long start = System.currentTimeMillis();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        long elapsed = System.currentTimeMillis() - start;

        System.out.println("耗时: " + elapsed + "ms");
        JsonObject result = gson.fromJson(response.body(), JsonObject.class);
        String content = result.getAsJsonObject("message").get("content").getAsString();
        System.out.println("模型回复: " + content);

        assert content != null && !content.isEmpty() : "模型未返回有效回复";
        System.out.println("同步对话测试通过 ✓");
    }

    @Test
    public void testChatStream() throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("model", MODEL);
        body.addProperty("stream", true);

        JsonArray messages = new JsonArray();
        JsonObject msg = new JsonObject();
        msg.addProperty("role", "user");
        msg.addProperty("content", "用一句话介绍 Spring AI");
        messages.add(msg);
        body.add("messages", messages);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(OLLAMA_BASE_URL + "/api/chat"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body)))
                .timeout(Duration.ofSeconds(120))
                .build();

        System.out.print("流式输出: ");
        HttpResponse<java.io.InputStream> resp = client.send(request,
                HttpResponse.BodyHandlers.ofInputStream());

        StringBuilder fullContent = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(resp.body()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) continue;
                JsonObject chunk = gson.fromJson(line, JsonObject.class);
                if (chunk.has("message") && chunk.getAsJsonObject("message").has("content")) {
                    String token = chunk.getAsJsonObject("message").get("content").getAsString();
                    System.out.print(token);
                    fullContent.append(token);
                }
                if (chunk.has("done") && chunk.get("done").getAsBoolean()) {
                    System.out.println("\n[流式完成]");
                    break;
                }
            }
        }
        assert !fullContent.isEmpty() : "流式输出无内容";
        System.out.println("流式对话测试通过 ✓");
    }
}