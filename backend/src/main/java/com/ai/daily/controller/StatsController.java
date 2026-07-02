package com.ai.daily.controller;

import com.ai.daily.dto.Result;
import com.ai.daily.entity.Report;
import com.ai.daily.service.ReportService;
import com.ai.daily.util.MarkdownUtils;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/stats")
public class StatsController {

    private static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");
    private static final LocalTime MORNING_PUSH = LocalTime.of(8, 0);
    private static final LocalTime EVENING_PUSH = LocalTime.of(20, 0);

    private static final Pattern ENGLISH_WORD = Pattern.compile("[A-Za-z][A-Za-z0-9+\\-]{2,}");
    private static final Pattern CHINESE_PHRASE = Pattern.compile("[\\u4e00-\\u9fa5]{2,4}");

    private static final Set<String> STOP_WORDS = new HashSet<>(Arrays.asList(
            "the","and","for","with","that","this","from","are","not","was","were","been",
            "have","has","had","will","would","could","should","can","may","one","two",
            "com","www","http","https","html","com.","cn.",
            "简报","晚间","早间","版本","今日","昨日","本周","更新","发布","推出","相关",
            "以及","可以","进行","使用","通过","目前","已经","方面","支持","模型","公司",
            "宣布","表示","据悉","据介绍","该公司","目前"
    ));

    @Autowired
    private ReportService reportService;

    @GetMapping("/dashboard")
    public Result<Map<String, Object>> dashboard() {
        LocalDateTime now = ZonedDateTime.now(BEIJING).toLocalDateTime();
        LocalDate today = now.toLocalDate();

        long totalCount = reportService.count();

        LambdaQueryWrapper<Report> todayWrapper = new LambdaQueryWrapper<Report>()
                .ge(Report::getCreatedAt, today.atStartOfDay())
                .lt(Report::getCreatedAt, today.plusDays(1).atStartOfDay());
        long todayCount = reportService.count(todayWrapper);

        LambdaQueryWrapper<Report> weekWrapper = new LambdaQueryWrapper<Report>()
                .ge(Report::getCreatedAt, today.minusDays(6).atStartOfDay())
                .select(Report::getTitle, Report::getSummary);
        List<Report> weekReports = reportService.list(weekWrapper);

        List<String> hotTags = extractHotTags(weekReports, 5);

        Map<String, Object> data = new HashMap<>();
        data.put("todayCount", todayCount);
        data.put("totalCount", totalCount);
        data.put("hotTags", hotTags);
        data.put("nextPushAt", nextPushAt(now).toString());
        return Result.ok(data);
    }

    private LocalDateTime nextPushAt(LocalDateTime now) {
        LocalDateTime morningToday = now.toLocalDate().atTime(MORNING_PUSH);
        LocalDateTime eveningToday = now.toLocalDate().atTime(EVENING_PUSH);
        if (now.isBefore(morningToday)) return morningToday;
        if (now.isBefore(eveningToday)) return eveningToday;
        return now.toLocalDate().plusDays(1).atTime(MORNING_PUSH);
    }

    private List<String> extractHotTags(List<Report> reports, int topN) {
        Map<String, Integer> counter = new HashMap<>();
        for (Report r : reports) {
            String text = MarkdownUtils.stripToPlainText(
                    (r.getTitle() == null ? "" : r.getTitle()) + " " +
                    (r.getSummary() == null ? "" : r.getSummary()), 0);

            addMatches(counter, ENGLISH_WORD.matcher(text), true);
            addMatches(counter, CHINESE_PHRASE.matcher(text), false);
        }

        return counter.entrySet().stream()
                .filter(e -> e.getValue() >= 2)
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(topN)
                .map(Map.Entry::getKey)
                .toList();
    }

    private void addMatches(Map<String, Integer> counter, Matcher m, boolean toLower) {
        while (m.find()) {
            String w = m.group();
            String key = toLower ? w.toLowerCase(Locale.ROOT) : w;
            if (STOP_WORDS.contains(key)) continue;
            counter.merge(key, 1, Integer::sum);
        }
    }
}
