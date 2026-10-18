package com.ai.daily.screener.v2.ai;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 交给大模型的全部事实。**提示词之外没有第二条信息来源**。
 *
 * <p>编号是不透明的（{@code ev:val:pe_ttm} 之类），模型只被允许回引这里出现过的
 * 编号；任何未知编号、任何不在本包里出现过的数字，都会被
 * {@link AiAnalysisValidator} 拒绝并回退规则文案。
 */
public record EvidencePack(
        String subject,
        String snapshotId,
        String rulesVersion,
        List<Evidence> items
) {

    public EvidencePack {
        items = List.copyOf(items == null ? List.of() : items);
    }

    public boolean contains(String id) {
        return find(id).isPresent();
    }

    public Optional<Evidence> find(String id) {
        if (id == null) {
            return Optional.empty();
        }
        return items.stream().filter(item -> id.equals(item.id())).findFirst();
    }

    public Set<String> ids() {
        Set<String> ids = new LinkedHashSet<>();
        for (Evidence item : items) {
            ids.add(item.id());
        }
        return ids;
    }

    /** 提示词里的材料段。一行一条，避免模型把相邻两行拼成一个不存在的事实。 */
    public String material() {
        StringBuilder out = new StringBuilder();
        out.append("标的：").append(subject == null ? "未知" : subject).append('\n');
        out.append("快照：").append(snapshotId == null ? "未知" : snapshotId)
                .append("；规则版本：").append(rulesVersion == null ? "未知" : rulesVersion)
                .append('\n');
        out.append("只允许引用下列编号；正文里的每个数字都必须来自其中一行。\n");
        for (Evidence item : items) {
            out.append("- ").append(item.id())
                    .append(" | ").append(nullSafe(item.label()))
                    .append(" | ").append(nullSafe(item.display()))
                    .append(" | ").append(nullSafe(item.source()))
                    .append(" | ").append(nullSafe(item.asOf()))
                    .append(" | ").append(truncate(item.basis(), 120))
                    .append('\n');
        }
        return out.toString();
    }

    private static String nullSafe(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "—";
        }
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }
}
