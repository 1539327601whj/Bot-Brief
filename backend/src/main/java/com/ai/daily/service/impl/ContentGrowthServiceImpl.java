package com.ai.daily.service.impl;

import com.ai.daily.dto.ContentGrowthDTO;
import com.ai.daily.entity.CompetitorAccount;
import com.ai.daily.entity.ContentAccount;
import com.ai.daily.entity.ContentGrowthAnalysis;
import com.ai.daily.entity.ContentWork;
import com.ai.daily.mapper.CompetitorAccountMapper;
import com.ai.daily.mapper.ContentAccountMapper;
import com.ai.daily.mapper.ContentGrowthAnalysisMapper;
import com.ai.daily.mapper.ContentWorkMapper;
import com.ai.daily.service.AiClientService;
import com.ai.daily.service.ContentGrowthService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class ContentGrowthServiceImpl implements ContentGrowthService {

    private final ContentAccountMapper contentAccountMapper;
    private final ContentWorkMapper contentWorkMapper;
    private final ContentGrowthAnalysisMapper contentGrowthAnalysisMapper;
    private final CompetitorAccountMapper competitorAccountMapper;
    private final AiClientService aiClientService;

    @Override
    public List<ContentAccount> listAccounts(Long userId) {
        return contentAccountMapper.selectList(new LambdaQueryWrapper<ContentAccount>()
                .eq(ContentAccount::getUserId, userId)
                .orderByDesc(ContentAccount::getCreatedAt));
    }

    @Override
    public ContentAccount createAccount(Long userId, ContentGrowthDTO.AccountRequest request) {
        ContentAccount account = new ContentAccount();
        account.setUserId(userId);
        fillAccount(account, request);
        account.setBindStatus("manual");
        contentAccountMapper.insert(account);
        return account;
    }

    @Override
    public ContentAccount updateAccount(Long userId, Long id, ContentGrowthDTO.AccountRequest request) {
        ContentAccount account = requireAccount(userId, id);
        String platform = account.getPlatform();
        if (!platform.equals(request.getPlatform())) {
            throw new IllegalArgumentException("已创建账号不能修改平台");
        }
        fillAccount(account, request);
        account.setPlatform(platform);
        contentAccountMapper.updateById(account);
        return account;
    }

    @Override
    @Transactional
    public boolean deleteAccount(Long userId, Long id) {
        requireAccount(userId, id);
        contentWorkMapper.delete(new LambdaQueryWrapper<ContentWork>()
                .eq(ContentWork::getUserId, userId)
                .eq(ContentWork::getAccountId, id));
        return contentAccountMapper.deleteById(id) > 0;
    }

    @Override
    public Page<ContentGrowthDTO.WorkItem> listWorks(Long userId, Long accountId, int page, int size) {
        LambdaQueryWrapper<ContentWork> wrapper = workWrapper(userId, accountId)
                .orderByDesc(ContentWork::getPublishTime)
                .orderByDesc(ContentWork::getCreatedAt);
        Page<ContentWork> workPage = contentWorkMapper.selectPage(new Page<>(page, size), wrapper);
        Page<ContentGrowthDTO.WorkItem> result = new Page<>(workPage.getCurrent(), workPage.getSize(), workPage.getTotal());
        result.setRecords(workPage.getRecords().stream().map(this::toWorkItem).toList());
        return result;
    }

    @Override
    public ContentWork createWork(Long userId, ContentGrowthDTO.WorkRequest request) {
        ContentAccount account = requireAccount(userId, request.getAccountId());
        requireMatchingPlatform(account, request.getPlatform());
        ContentWork work = new ContentWork();
        work.setUserId(userId);
        fillWork(work, request, account.getPlatform());
        contentWorkMapper.insert(work);
        return work;
    }

    @Override
    public ContentWork updateWork(Long userId, Long id, ContentGrowthDTO.WorkRequest request) {
        ContentWork work = requireWork(userId, id);
        ContentAccount account = requireAccount(userId, request.getAccountId());
        requireMatchingPlatform(account, request.getPlatform());
        fillWork(work, request, account.getPlatform());
        contentWorkMapper.updateById(work);
        return work;
    }

    @Override
    public boolean deleteWork(Long userId, Long id) {
        requireWork(userId, id);
        return contentWorkMapper.deleteById(id) > 0;
    }

    @Override
    public ContentGrowthDTO.WorkImportResult importWorks(Long userId, ContentGrowthDTO.WorkImportRequest request) {
        ContentAccount account = requireAccount(userId, request.getAccountId());
        String strategy = request.getConflictStrategy() == null || request.getConflictStrategy().isBlank()
                ? "UPDATE" : request.getConflictStrategy().toUpperCase(Locale.ROOT);
        List<ContentGrowthDTO.WorkImportRow> rows = request.getRows();

        List<ContentWork> existingWorks = contentWorkMapper.selectList(workWrapper(userId, account.getId()));
        Map<String, ContentWork> urlIndex = new HashMap<>();
        Map<String, ContentWork> titleTimeIndex = new HashMap<>();
        for (ContentWork work : existingWorks) {
            indexWork(work, urlIndex, titleTimeIndex);
        }

        Map<String, Integer> seenKeys = new HashMap<>();
        ContentGrowthDTO.WorkImportResult result = new ContentGrowthDTO.WorkImportResult();
        result.setTotal(rows.size());
        result.setCreated(0);
        result.setUpdated(0);
        result.setSkipped(0);
        result.setFailed(0);
        result.setErrors(new java.util.ArrayList<>());

        for (int i = 0; i < rows.size(); i++) {
            ContentGrowthDTO.WorkImportRow row = rows.get(i);
            int rowNumber = row.getRowNumber() == null ? i + 2 : row.getRowNumber();
            String error = validateImportRow(row, account.getPlatform());
            if (error != null) {
                failImportRow(result, rowNumber, null, error);
                continue;
            }

            String key = importKey(row);
            if (key != null && seenKeys.containsKey(key)) {
                result.setSkipped(result.getSkipped() + 1);
                result.getErrors().add(new ContentGrowthDTO.WorkImportError(
                        rowNumber, null, "与第 " + seenKeys.get(key) + " 行重复，已跳过"));
                continue;
            }
            if (key != null) seenKeys.put(key, rowNumber);

            ContentWork existing = findExisting(row, urlIndex, titleTimeIndex);
            if (existing != null && "SKIP".equals(strategy)) {
                result.setSkipped(result.getSkipped() + 1);
                result.getErrors().add(new ContentGrowthDTO.WorkImportError(rowNumber, null, "作品已存在，已按策略跳过"));
                continue;
            }

            try {
                ContentGrowthDTO.WorkRequest workRequest = toWorkRequest(account, row);
                if (existing == null) {
                    ContentWork work = new ContentWork();
                    work.setUserId(userId);
                    fillWork(work, workRequest, account.getPlatform());
                    contentWorkMapper.insert(work);
                    indexWork(work, urlIndex, titleTimeIndex);
                    result.setCreated(result.getCreated() + 1);
                } else {
                    ContentWork original = copyWork(existing);
                    removeFromIndexes(existing, urlIndex, titleTimeIndex);
                    try {
                        fillWork(existing, workRequest, account.getPlatform());
                        contentWorkMapper.updateById(existing);
                        indexWork(existing, urlIndex, titleTimeIndex);
                        result.setUpdated(result.getUpdated() + 1);
                    } catch (DataAccessException e) {
                        restoreWork(existing, original);
                        indexWork(existing, urlIndex, titleTimeIndex);
                        throw e;
                    }
                }
            } catch (DataAccessException e) {
                failImportRow(result, rowNumber, null, "写入数据库失败");
            }
        }
        return result;
    }

    @Override
    public ContentGrowthDTO.Overview getOverview(Long userId, Long accountId) {
        List<ContentAccount> accounts = accountId == null ? listAccounts(userId) : List.of(requireAccount(userId, accountId));
        List<ContentWork> works = contentWorkMapper.selectList(workWrapper(userId, accountId));

        long totalFollowers = accounts.stream().mapToLong(a -> safe(a.getFollowerCount())).sum();
        long totalPlay = works.stream().mapToLong(w -> safe(w.getPlayCount())).sum();
        long totalLike = works.stream().mapToLong(w -> safe(w.getLikeCount())).sum();
        long totalComment = works.stream().mapToLong(w -> safe(w.getCommentCount())).sum();
        long totalCollect = works.stream().mapToLong(w -> safe(w.getCollectCount())).sum();
        long totalShare = works.stream().mapToLong(w -> safe(w.getShareCount())).sum();
        long totalFollowerGain = works.stream().mapToLong(w -> safe(w.getFollowerGain())).sum();
        long totalInteraction = totalLike + totalComment + totalCollect + totalShare;

        ContentGrowthDTO.Overview overview = new ContentGrowthDTO.Overview();
        overview.setAccountCount(accounts.size());
        overview.setTotalFollowers(totalFollowers);
        overview.setTotalPlayCount(totalPlay);
        overview.setTotalLikeCount(totalLike);
        overview.setTotalCommentCount(totalComment);
        overview.setTotalCollectCount(totalCollect);
        overview.setTotalShareCount(totalShare);
        overview.setTotalFollowerGain(totalFollowerGain);
        overview.setAverageInteractionRate(totalPlay == 0 ? 0 : roundRate((double) totalInteraction / totalPlay));
        overview.setBestWork(findBestWork(works));
        overview.setDecliningWork(findDecliningWork(works));
        return overview;
    }

    @Override
    public ContentGrowthDTO.AiTextResponse analyzeHotWork(Long userId, ContentGrowthDTO.HotAnalysisRequest request) {
        Long accountId = request == null ? null : request.getAccountId();
        Long workId = request == null ? null : request.getWorkId();
        ContentWork work = workId == null ? bestRawWork(userId, accountId) : requireWork(userId, workId);
        if (work == null) {
            return aiResponse("请先录入至少 1 条作品数据，再进行爆款分析。", true);
        }

        ContentAccount account = requireAccount(userId, work.getAccountId());
        List<ContentWork> works = recentWorks(userId, work.getAccountId(), 10);
        String prompt = "你是内容增长顾问，请分析这条作品为什么表现好，并给出可复用的方法。\n"
                + accountContext(account)
                + "\n重点作品：\n" + workContext(work)
                + "\n账号近期作品：\n" + worksContext(works)
                + "\n请用中文输出：1. 爆款原因；2. 可复用结构；3. 下一条内容建议；4. 风险提醒。";
        String content = aiClientService.chat(prompt);
        saveAnalysis(userId, work.getAccountId(), "hot_analysis", prompt, content);
        return aiResponse(content, isFallback(content));
    }

    @Override
    public ContentGrowthDTO.AiTextResponse recommendTopics(Long userId, ContentGrowthDTO.TopicRequest request) {
        Long accountId = request == null ? null : request.getAccountId();
        ContentAccount account = accountId == null ? firstAccount(userId) : requireAccount(userId, accountId);
        if (account == null) {
            return aiResponse("请先添加一个内容账号，再生成选题推荐。", true);
        }

        int count = request != null && request.getCount() != null ? Math.max(1, Math.min(request.getCount(), 10)) : 5;
        String goal = request == null || request.getGoal() == null || request.getGoal().isBlank() ? "涨粉" : request.getGoal();
        List<ContentWork> works = recentWorks(userId, account.getId(), 10);
        String prompt = "你是短视频/图文内容选题顾问，请根据账号定位和历史作品，推荐明天可以拍的选题。\n"
                + accountContext(account)
                + "\n增长目标：" + goal
                + "\n历史作品：\n" + worksContext(works)
                + "\n请推荐 " + count + " 个选题。每个选题包含：标题、推荐理由、适合平台、内容角度、脚本方向。";
        String content = aiClientService.chat(prompt);
        saveAnalysis(userId, account.getId(), "topic_recommendation", prompt, content);
        return aiResponse(content, isFallback(content));
    }

    @Override
    public ContentGrowthDTO.AiTextResponse rewriteAdvice(Long userId, ContentGrowthDTO.RewriteRequest request) {
        Long accountId = request == null ? null : request.getAccountId();
        ContentAccount account = accountId == null ? firstAccount(userId) : requireAccount(userId, accountId);
        String targetPlatform = request == null ? null : request.getTargetPlatform();
        String goal = request == null || request.getGoal() == null || request.getGoal().isBlank() ? "提高完播和收藏" : request.getGoal();
        String draftTitle = request == null ? "" : Objects.toString(request.getDraftTitle(), "");
        String draftScript = request == null ? "" : Objects.toString(request.getDraftScript(), "");
        if (draftTitle.isBlank() && draftScript.isBlank()) {
            return aiResponse("请先输入标题或脚本草稿，再生成改稿建议。", true);
        }

        String prompt = "你是内容改稿顾问，请优化用户的标题和脚本，让内容更适合平台传播。\n"
                + (account == null ? "账号定位：未提供\n" : accountContext(account))
                + "\n目标平台：" + Objects.toString(targetPlatform, "未指定")
                + "\n增长目标：" + goal
                + "\n原标题：" + draftTitle
                + "\n脚本草稿：" + draftScript
                + "\n请输出：1. 标题优化方案；2. 封面文案建议；3. 开头 3 秒钩子；4. 脚本结构；5. 评论区互动引导。";
        String content = aiClientService.chat(prompt);
        saveAnalysis(userId, account == null ? null : account.getId(), "rewrite_advice", prompt, content);
        return aiResponse(content, isFallback(content));
    }

    @Override
    public List<CompetitorAccount> listCompetitors(Long userId) {
        return competitorAccountMapper.selectList(new LambdaQueryWrapper<CompetitorAccount>()
                .eq(CompetitorAccount::getUserId, userId)
                .orderByDesc(CompetitorAccount::getCreatedAt));
    }

    @Override
    public CompetitorAccount createCompetitor(Long userId, ContentGrowthDTO.CompetitorRequest request) {
        CompetitorAccount competitor = new CompetitorAccount();
        competitor.setUserId(userId);
        competitor.setPlatform(request.getPlatform());
        competitor.setAccountName(request.getAccountName());
        competitor.setHomepageUrl(request.getHomepageUrl());
        competitor.setNote(request.getNote());
        competitorAccountMapper.insert(competitor);
        return competitor;
    }

    @Override
    public boolean deleteCompetitor(Long userId, Long id) {
        CompetitorAccount competitor = competitorAccountMapper.selectById(id);
        if (competitor == null || !userId.equals(competitor.getUserId())) {
            throw new IllegalArgumentException("竞品账号不存在");
        }
        return competitorAccountMapper.deleteById(id) > 0;
    }

    private void fillAccount(ContentAccount account, ContentGrowthDTO.AccountRequest request) {
        account.setPlatform(trimToNull(request.getPlatform()));
        account.setAccountName(trimToNull(request.getAccountName()));
        account.setHomepageUrl(trimToNull(request.getHomepageUrl()));
        account.setAvatarUrl(trimToNull(request.getAvatarUrl()));
        account.setFollowerCount(safe(request.getFollowerCount()));
        account.setAccountPositioning(trimToNull(request.getAccountPositioning()));
    }

    private void fillWork(ContentWork work, ContentGrowthDTO.WorkRequest request, String accountPlatform) {
        work.setAccountId(request.getAccountId());
        work.setPlatform(accountPlatform);
        work.setTitle(trimToNull(request.getTitle()));
        work.setCoverUrl(trimToNull(request.getCoverUrl()));
        work.setWorkUrl(trimToNull(request.getWorkUrl()));
        work.setPublishTime(request.getPublishTime());
        work.setPlayCount(safe(request.getPlayCount()));
        work.setLikeCount(safe(request.getLikeCount()));
        work.setCommentCount(safe(request.getCommentCount()));
        work.setCollectCount(safe(request.getCollectCount()));
        work.setShareCount(safe(request.getShareCount()));
        work.setFollowerGain(safe(request.getFollowerGain()));
        work.setContentType(request.getContentType() == null || request.getContentType().isBlank() ? "video" : request.getContentType().trim());
    }

    private ContentGrowthDTO.WorkRequest toWorkRequest(ContentAccount account, ContentGrowthDTO.WorkImportRow row) {
        ContentGrowthDTO.WorkRequest request = new ContentGrowthDTO.WorkRequest();
        request.setAccountId(account.getId());
        request.setPlatform(account.getPlatform());
        request.setTitle(row.getTitle());
        request.setCoverUrl(row.getCoverUrl());
        request.setWorkUrl(row.getWorkUrl());
        request.setPublishTime(row.getPublishTime());
        request.setPlayCount(row.getPlayCount());
        request.setLikeCount(row.getLikeCount());
        request.setCommentCount(row.getCommentCount());
        request.setCollectCount(row.getCollectCount());
        request.setShareCount(row.getShareCount());
        request.setFollowerGain(row.getFollowerGain());
        request.setContentType(row.getContentType());
        return request;
    }

    private String validateImportRow(ContentGrowthDTO.WorkImportRow row, String accountPlatform) {
        if (row == null) return "数据行不能为空";
        if (trimToNull(row.getTitle()) == null) return "作品标题不能为空";
        if (row.getTitle().trim().length() > 500) return "作品标题不能超过 500 个字符";
        if (row.getPlatform() != null && !row.getPlatform().isBlank() && !accountPlatform.equals(row.getPlatform().trim())) {
            return "作品平台必须与目标账号平台一致";
        }
        if (tooLong(row.getWorkUrl(), 1000) || tooLong(row.getCoverUrl(), 1000)) return "作品或封面链接不能超过 1000 个字符";
        if (tooLong(row.getContentType(), 40)) return "内容类型不能超过 40 个字符";
        if (hasNegative(row.getPlayCount(), row.getLikeCount(), row.getCommentCount(), row.getCollectCount(),
                row.getShareCount(), row.getFollowerGain())) return "数据指标不能为负数";
        return null;
    }

    private ContentWork findExisting(ContentGrowthDTO.WorkImportRow row,
                                     Map<String, ContentWork> urlIndex,
                                     Map<String, ContentWork> titleTimeIndex) {
        String urlKey = urlKey(row.getWorkUrl());
        if (urlKey != null) return urlIndex.get(urlKey);
        String titleKey = titleTimeKey(row.getTitle(), row.getPublishTime());
        return titleKey == null ? null : titleTimeIndex.get(titleKey);
    }

    private String importKey(ContentGrowthDTO.WorkImportRow row) {
        String urlKey = urlKey(row.getWorkUrl());
        if (urlKey != null) return "url:" + urlKey;
        String titleKey = titleTimeKey(row.getTitle(), row.getPublishTime());
        return titleKey == null ? null : "title:" + titleKey;
    }

    private void indexWork(ContentWork work, Map<String, ContentWork> urlIndex, Map<String, ContentWork> titleTimeIndex) {
        String urlKey = urlKey(work.getWorkUrl());
        if (urlKey != null) urlIndex.put(urlKey, work);
        String titleKey = titleTimeKey(work.getTitle(), work.getPublishTime());
        if (titleKey != null) titleTimeIndex.put(titleKey, work);
    }

    private void removeFromIndexes(ContentWork work,
                                   Map<String, ContentWork> urlIndex,
                                   Map<String, ContentWork> titleTimeIndex) {
        String urlKey = urlKey(work.getWorkUrl());
        if (urlKey != null) urlIndex.remove(urlKey);
        String titleKey = titleTimeKey(work.getTitle(), work.getPublishTime());
        if (titleKey != null) titleTimeIndex.remove(titleKey);
    }

    private ContentWork copyWork(ContentWork work) {
        ContentWork copy = new ContentWork();
        copy.setAccountId(work.getAccountId());
        copy.setPlatform(work.getPlatform());
        copy.setTitle(work.getTitle());
        copy.setCoverUrl(work.getCoverUrl());
        copy.setWorkUrl(work.getWorkUrl());
        copy.setPublishTime(work.getPublishTime());
        copy.setPlayCount(work.getPlayCount());
        copy.setLikeCount(work.getLikeCount());
        copy.setCommentCount(work.getCommentCount());
        copy.setCollectCount(work.getCollectCount());
        copy.setShareCount(work.getShareCount());
        copy.setFollowerGain(work.getFollowerGain());
        copy.setContentType(work.getContentType());
        return copy;
    }

    private void restoreWork(ContentWork target, ContentWork source) {
        target.setAccountId(source.getAccountId());
        target.setPlatform(source.getPlatform());
        target.setTitle(source.getTitle());
        target.setCoverUrl(source.getCoverUrl());
        target.setWorkUrl(source.getWorkUrl());
        target.setPublishTime(source.getPublishTime());
        target.setPlayCount(source.getPlayCount());
        target.setLikeCount(source.getLikeCount());
        target.setCommentCount(source.getCommentCount());
        target.setCollectCount(source.getCollectCount());
        target.setShareCount(source.getShareCount());
        target.setFollowerGain(source.getFollowerGain());
        target.setContentType(source.getContentType());
    }

    private String urlKey(String url) {
        String value = trimToNull(url);
        if (value == null) return null;
        int fragment = value.indexOf('#');
        if (fragment >= 0) value = value.substring(0, fragment);
        return value.trim();
    }

    private String titleTimeKey(String title, LocalDateTime publishTime) {
        if (publishTime == null || trimToNull(title) == null) return null;
        String normalized = Normalizer.normalize(title.trim(), Normalizer.Form.NFKC)
                .replaceAll("\\s+", " ");
        return normalized + "|" + publishTime.truncatedTo(ChronoUnit.SECONDS);
    }

    private void failImportRow(ContentGrowthDTO.WorkImportResult result, int rowNumber, String field, String message) {
        result.setFailed(result.getFailed() + 1);
        result.getErrors().add(new ContentGrowthDTO.WorkImportError(rowNumber, field, message));
    }

    private String trimToNull(String value) {
        if (value == null || value.trim().isEmpty()) return null;
        return value.trim();
    }

    private boolean tooLong(String value, int max) {
        return value != null && value.length() > max;
    }

    private boolean hasNegative(Long... values) {
        for (Long value : values) {
            if (value != null && value < 0) return true;
        }
        return false;
    }

    private void requireMatchingPlatform(ContentAccount account, String platform) {
        if (!account.getPlatform().equals(platform)) {
            throw new IllegalArgumentException("作品平台必须与所属账号平台一致");
        }
    }

    private ContentAccount requireAccount(Long userId, Long id) {
        if (id == null) throw new IllegalArgumentException("账号不存在");
        ContentAccount account = contentAccountMapper.selectById(id);
        if (account == null || !userId.equals(account.getUserId())) {
            throw new IllegalArgumentException("账号不存在");
        }
        return account;
    }

    private ContentWork requireWork(Long userId, Long id) {
        if (id == null) throw new IllegalArgumentException("作品不存在");
        ContentWork work = contentWorkMapper.selectById(id);
        if (work == null || !userId.equals(work.getUserId())) {
            throw new IllegalArgumentException("作品不存在");
        }
        return work;
    }

    private LambdaQueryWrapper<ContentWork> workWrapper(Long userId, Long accountId) {
        LambdaQueryWrapper<ContentWork> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ContentWork::getUserId, userId);
        if (accountId != null) wrapper.eq(ContentWork::getAccountId, accountId);
        return wrapper;
    }

    private ContentGrowthDTO.WorkItem findBestWork(List<ContentWork> works) {
        return works.stream()
                .max(Comparator.comparingLong((ContentWork w) -> safe(w.getPlayCount()))
                        .thenComparingDouble(this::interactionRate))
                .map(this::toWorkItem)
                .orElse(null);
    }

    private ContentWork bestRawWork(Long userId, Long accountId) {
        return contentWorkMapper.selectList(workWrapper(userId, accountId)).stream()
                .max(Comparator.comparingLong((ContentWork w) -> safe(w.getPlayCount()))
                        .thenComparingDouble(this::interactionRate))
                .orElse(null);
    }

    private ContentGrowthDTO.WorkItem findDecliningWork(List<ContentWork> works) {
        if (works.size() < 3) return null;
        double average = works.stream().mapToLong(w -> safe(w.getPlayCount())).average().orElse(0);
        return works.stream()
                .filter(w -> w.getPublishTime() != null)
                .max(Comparator.comparing(ContentWork::getPublishTime))
                .filter(w -> average > 0 && safe(w.getPlayCount()) < average * 0.5)
                .map(this::toWorkItem)
                .orElse(null);
    }

    private ContentGrowthDTO.WorkItem toWorkItem(ContentWork work) {
        ContentGrowthDTO.WorkItem item = new ContentGrowthDTO.WorkItem();
        item.setId(work.getId());
        item.setAccountId(work.getAccountId());
        item.setPlatform(work.getPlatform());
        item.setTitle(work.getTitle());
        item.setCoverUrl(work.getCoverUrl());
        item.setWorkUrl(work.getWorkUrl());
        item.setPublishTime(work.getPublishTime());
        item.setPlayCount(safe(work.getPlayCount()));
        item.setLikeCount(safe(work.getLikeCount()));
        item.setCommentCount(safe(work.getCommentCount()));
        item.setCollectCount(safe(work.getCollectCount()));
        item.setShareCount(safe(work.getShareCount()));
        item.setFollowerGain(safe(work.getFollowerGain()));
        item.setContentType(work.getContentType());
        item.setInteractionRate(roundRate(interactionRate(work)));
        item.setStatus(workStatus(work));
        return item;
    }

    private String workStatus(ContentWork work) {
        double rate = interactionRate(work);
        long play = safe(work.getPlayCount());
        if (play >= 10000 || rate >= 0.1) return "hot";
        if (play < 500 && rate < 0.02) return "declining";
        return "normal";
    }

    private ContentAccount firstAccount(Long userId) {
        return contentAccountMapper.selectOne(new LambdaQueryWrapper<ContentAccount>()
                .eq(ContentAccount::getUserId, userId)
                .orderByDesc(ContentAccount::getCreatedAt)
                .last("LIMIT 1"));
    }

    private List<ContentWork> recentWorks(Long userId, Long accountId, int limit) {
        return contentWorkMapper.selectList(workWrapper(userId, accountId)
                .orderByDesc(ContentWork::getPublishTime)
                .orderByDesc(ContentWork::getCreatedAt)
                .last("LIMIT " + limit));
    }

    private String accountContext(ContentAccount account) {
        return "账号平台：" + account.getPlatform()
                + "\n账号名称：" + account.getAccountName()
                + "\n粉丝数：" + safe(account.getFollowerCount())
                + "\n账号定位：" + Objects.toString(account.getAccountPositioning(), "未填写") + "\n";
    }

    private String worksContext(List<ContentWork> works) {
        if (works.isEmpty()) return "暂无历史作品数据。";
        StringBuilder sb = new StringBuilder();
        for (ContentWork work : works) {
            sb.append(workContext(work)).append("\n");
        }
        return sb.toString();
    }

    private String workContext(ContentWork work) {
        return "《" + work.getTitle() + "》"
                + " 播放=" + safe(work.getPlayCount())
                + " 点赞=" + safe(work.getLikeCount())
                + " 评论=" + safe(work.getCommentCount())
                + " 收藏=" + safe(work.getCollectCount())
                + " 分享=" + safe(work.getShareCount())
                + " 涨粉=" + safe(work.getFollowerGain())
                + " 互动率=" + roundRate(interactionRate(work));
    }

    private void saveAnalysis(Long userId, Long accountId, String type, String input, String result) {
        ContentGrowthAnalysis analysis = new ContentGrowthAnalysis();
        analysis.setUserId(userId);
        analysis.setAccountId(accountId);
        analysis.setAnalysisType(type);
        analysis.setInputText(input);
        analysis.setResultText(result);
        analysis.setCreatedAt(LocalDateTime.now());
        contentGrowthAnalysisMapper.insert(analysis);
    }

    private ContentGrowthDTO.AiTextResponse aiResponse(String content, boolean fallback) {
        ContentGrowthDTO.AiTextResponse response = new ContentGrowthDTO.AiTextResponse();
        response.setContent(content);
        response.setFallback(fallback);
        response.setHighlights(List.of());
        return response;
    }

    private boolean isFallback(String content) {
        return content == null || content.startsWith("AI 服务暂未配置") || content.startsWith("AI 调用失败");
    }

    private double interactionRate(ContentWork work) {
        long play = safe(work.getPlayCount());
        if (play == 0) return 0;
        long interaction = safe(work.getLikeCount()) + safe(work.getCommentCount()) + safe(work.getCollectCount()) + safe(work.getShareCount());
        return (double) interaction / play;
    }

    private Double roundRate(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }

    private long safe(Long value) {
        return value == null ? 0 : value;
    }

    private Long nonNegative(Long value) {
        return Math.max(0, safe(value));
    }
}
