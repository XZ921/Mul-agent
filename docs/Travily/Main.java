package com.travel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Tavily Search API 最小化 PoC 测试程序。
 *
 * <p>目标：</p>
 * <ul>
 *     <li>直接调用 Tavily Search API。</li>
 *     <li>使用 advanced 搜索深度测试公开网络数据发现能力。</li>
 *     <li>开启 include_raw_content，测试网页正文抓取能力。</li>
 *     <li>成功时漂亮打印完整 JSON 响应。</li>
 *     <li>失败时完整打印 HTTP 状态码与错误响应体。</li>
 * </ul>
 *
 * <p>API Key 设置方式：</p>
 * <ul>
 *     <li>可以直接修改 DEFAULT_API_KEY。</li>
 *     <li>可以通过环境变量 TAVILY_API_KEY 传入真实 Key。</li>
 *     <li>可以通过第一个命令行参数传入真实 Key。</li>
 * </ul>
 */
public class Main {

    /**
     * Tavily Search API 地址。
     */
    private static final String TAVILY_SEARCH_ENDPOINT = "https://api.tavily.com/search";

    /**
     * 默认 API Key 占位符。
     * 实际测试时请替换为真实 Tavily API Key。
     */
    private static final String DEFAULT_API_KEY = "YOUR_TAVILY_API_KEY";

    /**
     * 默认测试搜索词。
     */
    private static final String DEFAULT_QUERY =
            "抖音 哔哩哔哩 竞品分析 核心功能 技术能力 推荐算法 用户画像 价格策略 商业化 2026 最新对比";

    /**
     * Jackson ObjectMapper 用于请求体 JSON 序列化，以及响应体 JSON 格式化输出。
     */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public static void main(String[] args) {
        String apiKey = resolveApiKey(args);
        String query = resolveQuery(args);

        try {
            executeTavilySearch(apiKey, query);
        } catch (InterruptedException e) {
            /*
             * 当前线程被中断时，需要恢复中断标记。
             * 这样上层调用方或运行环境仍然可以感知中断状态。
             */
            Thread.currentThread().interrupt();
            System.err.println("请求被中断，已恢复线程中断状态。");
            e.printStackTrace(System.err);
        } catch (IOException e) {
            /*
             * IOException 通常来自网络连接失败、DNS 解析失败、TLS 握手失败、
             * 请求发送失败或响应体读取失败。
             */
            System.err.println("调用 Tavily Search API 时发生 I/O 异常。");
            e.printStackTrace(System.err);
        } catch (Exception e) {
            /*
             * PoC 阶段保留兜底异常打印，便于快速观察未预期问题。
             */
            System.err.println("调用 Tavily Search API 时发生未预期异常。");
            e.printStackTrace(System.err);
        }
    }

    private static void executeTavilySearch(String apiKey, String query)
            throws IOException, InterruptedException {

        /*
         * 使用 LinkedHashMap 保持 JSON 字段顺序稳定，便于调试观察。
         *
         * Tavily Search API 请求体字段映射：
         * - api_key: Tavily API Key
         * - query: 测试搜索词
         * - search_depth: advanced，开启深度搜索模式
         * - include_raw_content: true，要求返回网页脱水后的纯净正文
         * - max_results: 5，最多返回 5 条结果
         */
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("api_key", apiKey);
        requestBody.put("query", query);
        requestBody.put("search_depth", "advanced");
        requestBody.put("include_raw_content", true);
        requestBody.put("max_results", 5);

        /*
         * 使用 Jackson 序列化 JSON，避免手写 JSON 时因为中文、引号、
         * 反斜杠等字符导致格式错误。
         */
        String requestJson = OBJECT_MAPPER.writeValueAsString(requestBody);

        /*
         * 使用 Java 11 标准库 HttpClient，无需引入第三方 HTTP 客户端。
         * connectTimeout 控制建立连接的最长等待时间。
         */
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();

        /*
         * 组装 HTTP POST 请求。
         *
         * 请求头：
         * - Content-Type: application/json
         *
         * 请求体：
         * - 使用 UTF-8 发送 JSON，确保中文 query 正确传输。
         *
         * 请求超时：
         * - include_raw_content=true 时，Tavily 可能需要抓取网页正文，
         *   因此这里给到 90 秒，方便观察正文抓取能力。
         */
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(TAVILY_SEARCH_ENDPOINT))
                .timeout(Duration.ofSeconds(90))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestJson, StandardCharsets.UTF_8))
                .build();

        printRequestSummary(query);

        /*
         * 同步发送 HTTP 请求，并按 UTF-8 读取响应体。
         * 对于最小化 PoC，同步调用最直接；如果后续要做并发压测，
         * 可以扩展为 sendAsync + CompletableFuture。
         */
        HttpResponse<String> response = httpClient.send(
                request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );

        int statusCode = response.statusCode();
        String responseBody = response.body();

        /*
         * 容错与观测：
         * HTTP 状态码不是 200 时，完整打印错误响应体。
         * 这有助于定位 API Key 错误、额度不足、请求参数错误或服务端异常。
         */
        if (statusCode != 200) {
            System.err.println("========== Tavily API Error ==========");
            System.err.println("HTTP Status Code: " + statusCode);
            System.err.println("Error Response Body:");
            System.err.println(responseBody);
            return;
        }

        /*
         * 成功响应：
         * 先解析完整 JSON，再通过 writerWithDefaultPrettyPrinter 格式化输出。
         * 这样可以完整观察 results、content、raw_content、response_time 等字段。
         */
        JsonNode responseJson = OBJECT_MAPPER.readTree(responseBody);
        String prettyResponse = OBJECT_MAPPER
                .writerWithDefaultPrettyPrinter()
                .writeValueAsString(responseJson);

        System.out.println("========== Tavily API Success ==========");
        System.out.println("HTTP Status Code: " + statusCode);
        System.out.println("Pretty JSON Response:");
        System.out.println(prettyResponse);
    }

    private static String resolveApiKey(String[] args) {
        if (args != null && args.length >= 1 && !isBlank(args[0])) {
            return args[0].trim();
        }

        String apiKeyFromEnv = System.getenv("TAVILY_API_KEY");
        if (!isBlank(apiKeyFromEnv)) {
            return apiKeyFromEnv.trim();
        }

        return DEFAULT_API_KEY;
    }

    private static String resolveQuery(String[] args) {
        if (args != null && args.length >= 2 && !isBlank(args[1])) {
            return args[1].trim();
        }

        return DEFAULT_QUERY;
    }

    private static void printRequestSummary(String query) {
        System.out.println("========== Tavily Search API PoC ==========");
        System.out.println("Endpoint: " + TAVILY_SEARCH_ENDPOINT);
        System.out.println("Query: " + query);
        System.out.println("Search Depth: advanced");
        System.out.println("Include Raw Content: true");
        System.out.println("Max Results: 5");
        System.out.println();
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
