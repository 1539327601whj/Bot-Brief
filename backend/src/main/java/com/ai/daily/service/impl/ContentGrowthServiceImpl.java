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
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
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
        fillAccount(account, request);
        contentAccountMapper.updateById(account);
        return account;
    }

    @Override
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
        requireAccount(userId, request.getAccountId());
        ContentWork work = new ContentWork();
        work.setUserId(userId);
        fillWork(work, request);
        contentWorkMapper.insert(work);
        return work;
    }

    @Override
    public ContentWork updateWork(Long userId, Long id, ContentGrowthDTO.WorkRequest request) {
        ContentWork work = requireWork(userId, id);
        requireAccount(userId, request.getAccountId());
        fillWork(work, request);
        contentWorkMapper.updateById(work);
        return work;
    }

    @Override
    public boolean deleteWork(Long userId, Long id) {
        requireWork(userId, id);
        return contentWorkMapper.deleteById(id) > 0;
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
        account.setPlatform(request.getPlatform());
        account.setAccountName(request.getAccountName());
        account.setHomepageUrl(request.getHomepageUrl());
        account.setAvatarUrl(request.getAvatarUrl());
        account.setFollowerCount(nonNegative(request.getFollowerCount()));
        account.setAccountPositioning(request.getAccountPositioning());
    }

    private void fillWork(ContentWork work, ContentGrowthDTO.WorkRequest request) {
        work.setAccountId(request.getAccountId());
        work.setPlatform(request.getPlatform());
        work.setTitle(request.getTitle());
        work.setCoverUrl(request.getCoverUrl());
        work.setWorkUrl(request.getWorkUrl());
        work.setPublishTime(request.getPublishTime());
        work.setPlayCount(nonNegative(request.getPlayCount()));
        work.setLikeCount(nonNegative(request.getLikeCount()));
        work.setCommentCount(nonNegative(request.getCommentCount()));
        work.setCollectCount(nonNegative(request.getCollectCount()));
        work.setShareCount(nonNegative(request.getShareCount()));
        work.setFollowerGain(nonNegative(request.getFollowerGain()));
        work.setContentType(request.getContentType() == null || request.getContentType().isBlank() ? "video" : request.getContentType());
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
