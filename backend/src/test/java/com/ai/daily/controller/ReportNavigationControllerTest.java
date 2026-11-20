package com.ai.daily.controller;

import com.ai.daily.dto.Result;
import com.ai.daily.entity.Report;
import com.ai.daily.security.UserPrincipal;
import com.ai.daily.service.ReportQueryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReportNavigationControllerTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void returnsIdsAndOneBasedIndexForSameEdition() {
        Report report = new Report();
        report.setId(10L);
        report.setUserId(Report.PUBLIC_OWNER_ID);
        report.setEdition("market_watch_evening");
        ReportQueryService query = mock(ReportQueryService.class);
        when(query.getById(7L, false, false, 10L)).thenReturn(report);
        when(query.visibleIdsOfEdition(7L, false, false, "market_watch_evening"))
                .thenReturn(List.of(12L, 11L, 10L, 9L));
        loginAs(7L, "USER", "NORMAL");

        Result<Map<String, Object>> result = newController(query).getNavigation(10L);

        assertThat(result.getCode()).isEqualTo(200);
        assertThat(result.getData()).containsEntry("total", 4);
        assertThat(result.getData()).containsEntry("index", 3);
        assertThat(result.getData()).containsEntry("edition", "market_watch_evening");
        assertThat(result.getData().get("ids")).isEqualTo(List.of(12L, 11L, 10L, 9L));
    }

    @Test
    void returnsNotFoundWhenReportIsNotVisible() {
        ReportQueryService query = mock(ReportQueryService.class);
        when(query.getById(7L, false, false, 99L)).thenReturn(null);
        loginAs(7L, "USER", "NORMAL");

        Result<Map<String, Object>> result = newController(query).getNavigation(99L);

        assertThat(result.getCode()).isEqualTo(404);
    }

    private static ReportController newController(ReportQueryService query) {
        ReportController controller = new ReportController();
        ReflectionTestUtils.setField(controller, "reportQueryService", query);
        return controller;
    }

    private static void loginAs(Long userId, String role, String accountType) {
        UserPrincipal principal = new UserPrincipal(userId, "u@example.com", role, accountType, "x", true);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }
}
