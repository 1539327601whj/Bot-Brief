package com.ai.daily.service;

import com.ai.daily.dto.ContentGrowthDTO;
import com.ai.daily.entity.CompetitorAccount;
import com.ai.daily.entity.ContentAccount;
import com.ai.daily.entity.ContentWork;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import java.util.List;

public interface ContentGrowthService {

    List<ContentAccount> listAccounts(Long userId);

    ContentAccount createAccount(Long userId, ContentGrowthDTO.AccountRequest request);

    ContentAccount updateAccount(Long userId, Long id, ContentGrowthDTO.AccountRequest request);

    boolean deleteAccount(Long userId, Long id);

    Page<ContentGrowthDTO.WorkItem> listWorks(Long userId, Long accountId, int page, int size);

    ContentWork createWork(Long userId, ContentGrowthDTO.WorkRequest request);

    ContentWork updateWork(Long userId, Long id, ContentGrowthDTO.WorkRequest request);

    boolean deleteWork(Long userId, Long id);

    ContentGrowthDTO.Overview getOverview(Long userId, Long accountId);

    ContentGrowthDTO.AiTextResponse analyzeHotWork(Long userId, ContentGrowthDTO.HotAnalysisRequest request);

    ContentGrowthDTO.AiTextResponse recommendTopics(Long userId, ContentGrowthDTO.TopicRequest request);

    ContentGrowthDTO.AiTextResponse rewriteAdvice(Long userId, ContentGrowthDTO.RewriteRequest request);

    List<CompetitorAccount> listCompetitors(Long userId);

    CompetitorAccount createCompetitor(Long userId, ContentGrowthDTO.CompetitorRequest request);

    boolean deleteCompetitor(Long userId, Long id);
}
