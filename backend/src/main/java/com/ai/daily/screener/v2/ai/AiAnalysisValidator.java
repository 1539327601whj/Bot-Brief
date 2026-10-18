package com.ai.daily.screener.v2.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 大模型输出的闸门。
 *
 * <p>四道检查，任何一道不过就回退规则文案：
 * <ol>
 *   <li>必须是合法 JSON，且结构是 {@code sections[]}；</li>
 *   <li>引用的 evidence ID 必须全部在证据包里；</li>
 *   <li>正文里的数字必须全部来自证据包（label / display / value / basis / asOf）；</li>
 *   <li>不得出现章程禁止的表达：结论承诺、概率、目标价。</li>
 * </ol>
 *
 * <p><b>数字校验按数值比较，不按字符串比较。</b>「14.50」与「14.5」是同一个事实，
 * 按字符串比会误杀；反过来，「37%」与「14.5%」数值不同，必须杀掉。
 */
public final class AiAnalysisValidator {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final List<String> BANNED = List.of(
            "必涨", "稳赚", "保证收益", "目标价", "建议满仓", "立即买入",
            "推荐股票", "满仓", "抄底");

    private static final Pattern PROBABILITY = Pattern.compile(
            "(上涨|下跌|盈利|成功)?概率\\s*[:：]?\\s*\\d|胜率\\s*[:：]?\\s*\\d");
    private static final Pattern TARGET_PRICE = Pattern.compile(
            "目标位|看至|涨到\\s*\\d|跌到\\s*\\d|预计涨幅|上涨空间|下行空间");
    private static final Pattern NUMBER = Pattern.compile("\\d+(?:\\.\\d+)?");

    private AiAnalysisValidator() {
    }

    public static Validation validate(String raw, EvidencePack pack) {
        if (raw == null || raw.isBlank()) {
            return Validation.fail("模型没有返回内容");
        }
        JsonNode root = parse(raw);
        if (root == null) {
            return Validation.fail("模型返回的不是合法 JSON");
        }
        JsonNode sectionsNode = root.path("sections");
        if (!sectionsNode.isArray() || sectionsNode.isEmpty()) {
            return Validation.fail("缺少 sections 数组");
        }
        if (sectionsNode.size() > 8) {
            return Validation.fail("小节过多（" + sectionsNode.size() + " 节）");
        }

        Set<String> allowedIds = pack.ids();
        Set<String> allowedNumbers = allowedNumbers(pack);
        List<Section> sections = new ArrayList<>();

        for (JsonNode node : sectionsNode) {
            String heading = text(node, "heading");
            String text = text(node, "text");
            if (heading == null) {
                return Validation.fail("有小节缺少标题");
            }
            if (text == null) {
                return Validation.fail("小节「" + heading + "」缺少正文");
            }
            JsonNode idsNode = node.path("evidenceIds");
            if (!idsNode.isArray() || idsNode.isEmpty()) {
                return Validation.fail("小节「" + heading + "」没有引用证据");
            }
            List<String> ids = new ArrayList<>();
            for (JsonNode idNode : idsNode) {
                String id = idNode.asText();
                if (id == null || id.isBlank() || !allowedIds.contains(id)) {
                    return Validation.fail("引用了证据包之外的编号：" + id);
                }
                ids.add(id);
            }

            String all = heading + text;
            for (String word : BANNED) {
                if (all.contains(word)) {
                    return Validation.fail("出现禁用表达「" + word + "」");
                }
            }
            if (PROBABILITY.matcher(all).find()) {
                return Validation.fail("出现概率/胜率表达");
            }
            if (TARGET_PRICE.matcher(all).find()) {
                return Validation.fail("出现目标价/涨跌幅预测表达");
            }

            for (String token : numbers(text)) {
                if (!allowedNumbers.contains(normalize(token))) {
                    return Validation.fail("正文出现证据包之外的数字：" + token);
                }
            }
            sections.add(new Section(heading, text, List.copyOf(ids)));
        }
        return Validation.ok(List.copyOf(sections));
    }

    /**
     * JSON 提取允许模型偶尔加一句开场白，但**不允许它把正文写成 markdown 表格**。
     * 截取第一个 {@code {} 到最后一个 {@code }} 之间，是这两者之间最小的宽容。
     */
    private static JsonNode parse(String raw) {
        String text = raw.strip();
        if (text.startsWith("```")) {
            int firstBreak = text.indexOf('\n');
            if (firstBreak > 0) {
                text = text.substring(firstBreak + 1);
            }
            int lastFence = text.lastIndexOf("```");
            if (lastFence >= 0) {
                text = text.substring(0, lastFence);
            }
            text = text.strip();
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            return MAPPER.readTree(text.substring(start, end + 1));
        } catch (Exception e) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isTextual()) {
            return null;
        }
        String text = value.asText();
        return text == null || text.isBlank() ? null : text.strip();
    }

    private static List<String> numbers(String text) {
        List<String> out = new ArrayList<>();
        Matcher matcher = NUMBER.matcher(text);
        while (matcher.find()) {
            out.add(matcher.group());
        }
        return out;
    }

    /**
     * 证据包里所有可以作为事实引用的数字。
     *
     * <p>把 label 与 basis 也算进来是刻意的：模型复述「MA250」「近 30 天」时，
     * 这些数字本来就在材料里；不算进来会逼着模型把口径名改写成别的说法，
     * 反而更容易说不清。
     */
    private static Set<String> allowedNumbers(EvidencePack pack) {
        Set<String> allowed = new HashSet<>();
        for (Evidence item : pack.items()) {
            collect(allowed, item.label());
            collect(allowed, item.display());
            collect(allowed, item.basis());
            collect(allowed, item.asOf());
            if (item.value() != null) {
                allowed.add(normalize(item.value().stripTrailingZeros().toPlainString()));
            }
        }
        return allowed;
    }

    private static void collect(Set<String> out, String text) {
        if (text == null) {
            return;
        }
        Matcher matcher = NUMBER.matcher(text);
        while (matcher.find()) {
            out.add(normalize(matcher.group()));
        }
    }

    private static String normalize(String token) {
        try {
            return new BigDecimal(token).stripTrailingZeros().toPlainString();
        } catch (NumberFormatException e) {
            return token;
        }
    }

    /** 一个大模型小节。 */
    public record Section(String heading, String text, List<String> evidenceIds) {
    }

    /** 校验结果。失败时 {@code reason} 是给页面看的原因，不包含模型原文。 */
    public record Validation(boolean ok, List<Section> sections, String reason) {

        static Validation ok(List<Section> sections) {
            return new Validation(true, sections, null);
        }

        static Validation fail(String reason) {
            return new Validation(false, List.of(), reason);
        }
    }
}
