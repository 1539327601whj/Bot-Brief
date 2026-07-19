package com.ai.daily.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

public class ContentGrowthDTO {

    @Data
    public static class AccountRequest {
        private String platform;
        private String accountName;
        private String homepageUrl;
        private String avatarUrl;
        private Long followerCount;
        private String accountPositioning;
    }

    @Data
    public static class WorkRequest {
        private Long accountId;
        private String platform;
        private String title;
        private String coverUrl;
        private String workUrl;
        private LocalDateTime publishTime;
        private Long playCount;
        private Long likeCount;
        private Long commentCount;
        private Long collectCount;
        private Long shareCount;
        private Long followerGain;
        private String contentType;
    }

    @Data
    public static class CompetitorRequest {
        private String platform;
        private String accountName;
        private String homepageUrl;
        private String note;
    }

    @Data
    public static class WorkItem {
        private Long id;
        private Long accountId;
        private String platform;
        private String title;
        private String coverUrl;
        private String workUrl;
        private LocalDateTime publishTime;
        private Long playCount;
        private Long likeCount;
        private Long commentCount;
        private Long collectCount;
        private Long shareCount;
        private Long followerGain;
        private String contentType;
        private Double interactionRate;
        private String status;
    }

    @Data
    public static class Overview {
        private Integer accountCount;
        private Long totalFollowers;
        private Long totalPlayCount;
        private Long totalLikeCount;
        private Long totalCommentCount;
        private Long totalCollectCount;
        private Long totalShareCount;
        private Long totalFollowerGain;
        private Double averageInteractionRate;
        private WorkItem bestWork;
        private WorkItem decliningWork;
    }

    @Data
    public static class HotAnalysisRequest {
        private Long accountId;
        private Long workId;
    }

    @Data
    public static class TopicRequest {
        private Long accountId;
        private String goal;
        private Integer count;
    }

    @Data
    public static class RewriteRequest {
        private Long accountId;
        private String draftTitle;
        private String draftScript;
        private String targetPlatform;
        private String goal;
    }

    @Data
    public static class AiTextResponse {
        private String content;
        private List<String> highlights;
        private Boolean fallback;
    }
}
