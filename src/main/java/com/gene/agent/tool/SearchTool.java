package com.gene.agent.tool;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gene.agent.config.AgentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.HtmlUtils;

/**
 * 联网搜索：直接抓 Bing / DuckDuckGo 的搜索结果页，不需要申请搜索 API Key。
 * 网页 HTML 会被剥成 "标题 + 摘要 + 链接" 的纯文本再交给模型 —— 塞标签只会浪费 token 还容易带偏。
 * 如果以后换成付费搜索 API，只需要改 execute()，对 agent 的契约不变。
 */
@Component
public class SearchTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(SearchTool.class);

    private static final Pattern TAGS = Pattern.compile("<[^>]+>");
    private static final Pattern REDIRECT_PARAM = Pattern.compile("[?&](?:uddg|u)=([^&]+)");
    private static final int MAX_RESULTS = 5;

    private static final Pattern BING_TITLE = Pattern.compile("<h2[^>]*>\\s*<a[^>]*href=\"([^\"]*)\"[^>]*>(.*?)</h2>", Pattern.DOTALL);
    private static final Pattern BING_SNIPPET = Pattern.compile("<p class=\"b_lineclamp[^\"]*\"[^>]*>(.*?)</p>", Pattern.DOTALL);
    private static final Pattern DDG_TITLE = Pattern.compile("class=\"result__a\"[^>]*>(.*?)</a>", Pattern.DOTALL);
    private static final Pattern DDG_LINK = Pattern.compile("<a[^>]*class=\"result__a\"[^>]*href=\"([^\"]*)\"", Pattern.DOTALL);
    private static final Pattern DDG_SNIPPET = Pattern.compile("class=\"result__snippet\"[^>]*>(.*?)</a>", Pattern.DOTALL);

    private final RestClient restClient;

    public SearchTool(AgentProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                // www.bing.com 会 302 到 cn.bing.com，不跟跳转就什么都抓不到
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(properties.readTimeout());
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }
    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = JsonNodeFactory.instance.objectNode();

        schema.put("type", "object");

        ObjectNode properties = schema.putObject("properties");

        ObjectNode city = properties.putObject("city");
        city.put("type", "string");
        city.put("description", "城市名称");

        schema.putArray("required").add("city");
        schema.put("additionalProperties", false);

        return schema;
//        return objectMapper.readTree("""
//        {
//          "type": "object",
//          "properties": {
//            "city": {
//              "type": "string",
//              "description": "城市名称"
//            }
//          },
//          "required": ["city"],
//          "additionalProperties": false
//        }
//        """);
    }
    @Override
    public String name() {
        return "search";
    }

    @Override
    public String description() {
        return "在网上搜索事实性信息。输入 JSON 对象，字段 query 为简短的搜索关键词，不要写完整问句。"
                + "返回若干条 标题 / 摘要 / 链接。例子: {\"query\": \"杭州地铁运营时间\"}";
    }

    @Override
    public String execute(JsonNode input) {
        String query = extractQuery(input);
        if (query.isBlank()) {
            return "错误: search 需要搜索关键词";
        }
        List<SearchAttempt> attempts = List.of(
                () -> searchBing(query),
                () -> searchDuckDuckGo(query));
        List<String> failures = new ArrayList<>();
        for (int i = 0; i < attempts.size(); i++) {
            String engine = i == 0 ? "bing" : "duckduckgo";
            try {
                List<Hit> hits = attempts.get(i).search();
                if (!hits.isEmpty()) {
                    return render(hits);
                }
                failures.add(engine + " 页面上没有解析到结果");
            } catch (Exception ex) {
                log.debug("{} 搜索失败: {}", engine, ex.toString());
                failures.add(engine + " 请求失败: " + rootMessage(ex));
            }
        }
        return "搜索没有可用结果 -> " + String.join("; ", failures);
    }

    /** input 约定为 {"query": "关键词"}；模型偶尔直接给文本节点，做一层兜底。 */
    private String extractQuery(JsonNode input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        String raw = input.isTextual() ? input.asText() : input.path("query").asText("");
        return raw.trim().replaceAll("^\"+|\"+$", "");
    }

    private List<Hit> searchBing(String query) {
        String html = get("https://www.bing.com/search?setlang=zh-hans&q=" + encode(query));
        List<Hit> hits = new ArrayList<>();
        for (String chunk : splitOn(html, "<li class=\"b_algo\"")) {
            Matcher titleMatcher = BING_TITLE.matcher(chunk);
            if (titleMatcher.find()) {
                hits.add(new Hit(clean(titleMatcher.group(2)),
                        firstMatch(BING_SNIPPET, chunk),
                        resolveUrl(titleMatcher.group(1))));
            }
            if (hits.size() >= MAX_RESULTS) {
                break;
            }
        }
        return dropEmpty(hits);
    }

    private List<Hit> searchDuckDuckGo(String query) {
        String html = get("https://html.duckduckgo.com/html/?kl=cn-zh&q=" + encode(query));
        List<Hit> hits = new ArrayList<>();
        for (String chunk : splitOn(html, "class=\"result results_links")) {
            Matcher linkMatcher = DDG_LINK.matcher(chunk);
            Matcher titleMatcher = DDG_TITLE.matcher(chunk);
            if (titleMatcher.find()) {
                String url = linkMatcher.find() ? resolveUrl(linkMatcher.group(1)) : "";
                hits.add(new Hit(clean(titleMatcher.group(1)),
                        firstMatch(DDG_SNIPPET, chunk),
                        url));
            }
            if (hits.size() >= MAX_RESULTS) {
                break;
            }
        }
        return dropEmpty(hits);
    }

    private List<Hit> dropEmpty(List<Hit> hits) {
        return hits.stream().filter(hit -> !hit.title.isBlank()).toList();
    }

    private String render(List<Hit> hits) {
        List<String> lines = new ArrayList<>();
        int index = 1;
        for (Hit hit : hits) {
            lines.add(index++ + ". " + hit.title);
            if (!hit.snippet.isBlank()) {
                lines.add("   " + truncate(hit.snippet, 240));
            }
            if (!hit.url.isBlank()) {
                lines.add("   " + hit.url);
            }
        }
        return String.join("\n", lines);
    }

    private String get(String url) {
        String body = restClient.get()
                .uri(URI.create(url))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.6")
                .retrieve()
                .body(String.class);
        return body == null ? "" : body;
    }

    /** Bing 给的是原始链接，DuckDuckGo 把真实地址藏在 uddg 参数里。 */
    private String resolveUrl(String rawHref) {
        String href = HtmlUtils.htmlUnescape(rawHref == null ? "" : rawHref);
        Matcher matcher = REDIRECT_PARAM.matcher(href);
        if (matcher.find()) {
            return URLDecoder.decode(matcher.group(1), StandardCharsets.UTF_8);
        }
        return href.startsWith("http") ? href : "";
    }

    private String firstMatch(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? clean(matcher.group(1)) : "";
    }

    private String clean(String html) {
        return HtmlUtils.htmlUnescape(TAGS.matcher(html == null ? "" : html).replaceAll(" "))
                .replaceAll("\\s+", " ")
                .trim();
    }

    private List<String> splitOn(String html, String marker) {
        List<String> chunks = new ArrayList<>();
        int cursor = 0;
        while (true) {
            int start = html.indexOf(marker, cursor);
            if (start < 0) {
                return chunks;
            }
            int next = html.indexOf(marker, start + marker.length());
            chunks.add(html.substring(start, next < 0 ? html.length() : next));
            cursor = start + marker.length();
        }
    }

    private String truncate(String text, int maxChars) {
        return text.length() <= maxChars ? text : text.substring(0, maxChars) + "...";
    }

    private String encode(String query) {
        return URLEncoder.encode(query, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private String rootMessage(Throwable error) {
        Throwable cursor = error;
        while (cursor.getCause() != null && cursor.getCause() != cursor) {
            cursor = cursor.getCause();
        }
        return cursor.getMessage() == null ? cursor.toString() : cursor.getMessage();
    }

    private record Hit(String title, String snippet, String url) {
    }

    private interface SearchAttempt {
        List<Hit> search() throws Exception;
    }
}
