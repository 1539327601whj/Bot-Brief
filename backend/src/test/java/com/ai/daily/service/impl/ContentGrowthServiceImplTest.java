package com.ai.daily.service.impl;

import com.ai.daily.dto.ContentGrowthDTO;
import com.ai.daily.entity.ContentAccount;
import com.ai.daily.entity.ContentWork;
import com.ai.daily.mapper.CompetitorAccountMapper;
import com.ai.daily.mapper.ContentAccountMapper;
import com.ai.daily.mapper.ContentGrowthAnalysisMapper;
import com.ai.daily.mapper.ContentWorkMapper;
import com.ai.daily.service.AiClientService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContentGrowthServiceImplTest {

    private ContentAccountMapper accounts;
    private ContentWorkMapper works;
    private ContentGrowthServiceImpl service;

    @BeforeEach
    void setUp() {
        accounts = mock(ContentAccountMapper.class);
        works = mock(ContentWorkMapper.class);
        service = new ContentGrowthServiceImpl(
                accounts,
                works,
                mock(ContentGrowthAnalysisMapper.class),
                mock(CompetitorAccountMapper.class),
                mock(AiClientService.class));
    }

    @Test
    void createAccountBelongsToCallerAndStaysManual() {
        ContentGrowthDTO.AccountRequest request = request("douyin", "测试号");
        when(accounts.insert(any(ContentAccount.class))).thenAnswer(invocation -> {
            ContentAccount account = invocation.getArgument(0);
            account.setId(41L);
            return 1;
        });

        ContentAccount created = service.createAccount(7L, request);

        ArgumentCaptor<ContentAccount> captor = ArgumentCaptor.forClass(ContentAccount.class);
        verify(accounts).insert(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(7L);
        assertThat(captor.getValue().getBindStatus()).isEqualTo("manual");
        assertThat(captor.getValue().getPlatform()).isEqualTo("douyin");
        assertThat(created.getId()).isEqualTo(41L);
    }

    @Test
    void updateAccountRejectsOtherUsersRow() {
        when(accounts.selectById(9L)).thenReturn(account(9L, 3L, "douyin"));

        assertThatThrownBy(() -> service.updateAccount(7L, 9L, request("douyin", "别人的号")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("账号不存在");
        verify(accounts, never()).updateById(any());
    }

    @Test
    void deleteAccountRejectsOtherUsersRow() {
        when(accounts.selectById(9L)).thenReturn(account(9L, 3L, "bilibili"));

        assertThatThrownBy(() -> service.deleteAccount(7L, 9L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("账号不存在");
        verify(works, never()).delete(any());
        verify(accounts, never()).deleteById(9L);
    }

    @Test
    void deleteAccountRemovesOwnWorksThenAccount() {
        when(accounts.selectById(9L)).thenReturn(account(9L, 7L, "kuaishou"));
        when(accounts.deleteById(9L)).thenReturn(1);

        assertThat(service.deleteAccount(7L, 9L)).isTrue();
        verify(works).delete(any());
        verify(accounts).deleteById(9L);
    }

    @Test
    void updateWorkRejectsOtherUsersWork() {
        ContentWork foreign = new ContentWork();
        foreign.setId(12L);
        foreign.setUserId(3L);
        when(works.selectById(12L)).thenReturn(foreign);

        ContentGrowthDTO.WorkRequest request = new ContentGrowthDTO.WorkRequest();
        request.setAccountId(9L);
        request.setPlatform("douyin");
        request.setTitle("不能改别人的作品");

        assertThatThrownBy(() -> service.updateWork(7L, 12L, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("作品不存在");
        verify(works, never()).updateById(any());
    }

    private static ContentGrowthDTO.AccountRequest request(String platform, String name) {
        ContentGrowthDTO.AccountRequest request = new ContentGrowthDTO.AccountRequest();
        request.setPlatform(platform);
        request.setAccountName(name);
        request.setFollowerCount(10L);
        return request;
    }

    private static ContentAccount account(Long id, Long userId, String platform) {
        ContentAccount account = new ContentAccount();
        account.setId(id);
        account.setUserId(userId);
        account.setPlatform(platform);
        account.setAccountName("已有账号");
        account.setBindStatus("manual");
        return account;
    }
}
