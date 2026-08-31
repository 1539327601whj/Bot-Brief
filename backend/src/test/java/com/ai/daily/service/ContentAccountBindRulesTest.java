package com.ai.daily.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ContentAccountBindRulesTest {

    @Test
    void blankHomepageIsAllowed() {
        assertThat(ContentAccountBindRules.validateHomepage("douyin", null)).isNull();
        assertThat(ContentAccountBindRules.validateHomepage("douyin", "  ")).isNull();
    }

    @Test
    void acceptsMatchingHttpsHomepage() {
        assertThat(ContentAccountBindRules.validateHomepage("douyin", "https://www.douyin.com/user/abc")).isNull();
        assertThat(ContentAccountBindRules.validateHomepage("xiaohongshu", "https://www.xiaohongshu.com/user/profile/1")).isNull();
        assertThat(ContentAccountBindRules.validateHomepage("bilibili", "https://space.bilibili.com/1")).isNull();
    }

    @Test
    void rejectsMissingSchemeAndWrongHost() {
        assertThat(ContentAccountBindRules.validateHomepage("douyin", "www.douyin.com/user/1"))
                .contains("http://");
        assertThat(ContentAccountBindRules.validateHomepage("kuaishou", "https://www.xiaohongshu.com/user/1"))
                .contains("对应所选平台");
    }
}
