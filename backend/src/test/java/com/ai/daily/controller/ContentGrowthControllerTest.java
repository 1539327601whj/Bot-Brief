package com.ai.daily.controller;

import com.ai.daily.dto.ContentGrowthDTO;
import com.ai.daily.dto.Result;
import com.ai.daily.entity.ContentAccount;
import com.ai.daily.security.UserPrincipal;
import com.ai.daily.service.ContentGrowthService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContentGrowthControllerTest {

    private ContentGrowthService service;
    private ContentGrowthController controller;

    @BeforeEach
    void setUp() {
        service = mock(ContentGrowthService.class);
        controller = new ContentGrowthController(service);
        UserPrincipal principal = new UserPrincipal(7L, "user@example.com", "USER", "NORMAL", "hash", true);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void createAccountRejectsUnsupportedPlatform() {
        ContentGrowthDTO.AccountRequest request = new ContentGrowthDTO.AccountRequest();
        request.setPlatform("wechat_channels");
        request.setAccountName("视频号");

        Result<ContentAccount> result = controller.createAccount(request);

        assertThat(result.getCode()).isEqualTo(400);
        assertThat(result.getMessage()).contains("不支持的内容平台");
        verify(service, never()).createAccount(eq(7L), any());
    }

    @Test
    void createAccountRejectsBlankName() {
        ContentGrowthDTO.AccountRequest request = new ContentGrowthDTO.AccountRequest();
        request.setPlatform("xiaohongshu");
        request.setAccountName("  ");

        Result<ContentAccount> result = controller.createAccount(request);

        assertThat(result.getCode()).isEqualTo(400);
        assertThat(result.getMessage()).contains("账号名不能为空");
        verify(service, never()).createAccount(eq(7L), any());
    }

    @Test
    void createAccountPassesCurrentUser() {
        ContentGrowthDTO.AccountRequest request = new ContentGrowthDTO.AccountRequest();
        request.setPlatform("bilibili");
        request.setAccountName("UP主");
        ContentAccount saved = new ContentAccount();
        saved.setId(5L);
        saved.setUserId(7L);
        when(service.createAccount(7L, request)).thenReturn(saved);

        Result<ContentAccount> result = controller.createAccount(request);

        assertThat(result.getCode()).isEqualTo(200);
        assertThat(result.getData().getId()).isEqualTo(5L);
        verify(service).createAccount(7L, request);
    }

    @Test
    void updateAccountMapsMissingRowTo404() {
        ContentGrowthDTO.AccountRequest request = new ContentGrowthDTO.AccountRequest();
        request.setPlatform("douyin");
        request.setAccountName("还在");
        when(service.updateAccount(7L, 99L, request)).thenThrow(new IllegalArgumentException("账号不存在"));

        Result<ContentAccount> result = controller.updateAccount(99L, request);

        assertThat(result.getCode()).isEqualTo(404);
        assertThat(result.getMessage()).isEqualTo("账号不存在");
    }
}
