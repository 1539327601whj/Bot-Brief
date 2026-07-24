package com.ai.daily.config;

import com.ai.daily.entity.User;
import com.ai.daily.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.DefaultApplicationArguments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DemoAccountBootstrapRunnerTest {

    private UserService userService;
    private DemoAccountProperties properties;
    private DemoAccountBootstrapRunner runner;

    @BeforeEach
    void setUp() {
        userService = mock(UserService.class);
        properties = new DemoAccountProperties();
        properties.setEnabled(true);
        properties.setEmail(" Demo@Example.com ");
        properties.setDisplayName("公开演示账号");
        properties.setTokenExpirationMinutes(30);
        runner = new DemoAccountBootstrapRunner(userService, properties);
    }

    @Test
    void createsDemoAccountWithoutBusinessData() throws Exception {
        when(userService.findByEmail("demo@example.com")).thenReturn(null);

        runner.run(new DefaultApplicationArguments());

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userService).save(captor.capture());
        User demo = captor.getValue();
        assertThat(demo.getEmail()).isEqualTo("demo@example.com");
        assertThat(demo.getPasswordHash()).isEqualTo(DemoAccountBootstrapRunner.NON_LOGIN_PASSWORD_SENTINEL);
        assertThat(demo.getDisplayName()).isEqualTo("公开演示账号");
        assertThat(demo.getRole()).isEqualTo("USER");
        assertThat(demo.getAccountType()).isEqualTo(User.ACCOUNT_DEMO);
        assertThat(demo.getEnabled()).isTrue();
        assertThat(demo.getInviteCodeUsed()).isNull();
        verify(userService, never()).updateById(demo);
    }

    @Test
    void leavesMatchingDemoAccountUnchanged() throws Exception {
        User demo = new User();
        demo.setId(9L);
        demo.setEmail("demo@example.com");
        demo.setPasswordHash(DemoAccountBootstrapRunner.NON_LOGIN_PASSWORD_SENTINEL);
        demo.setDisplayName("公开演示账号");
        demo.setRole("USER");
        demo.setAccountType(User.ACCOUNT_DEMO);
        demo.setEnabled(true);
        when(userService.findByEmail("demo@example.com")).thenReturn(demo);

        runner.run(new DefaultApplicationArguments());

        verify(userService, never()).save(demo);
        verify(userService, never()).updateById(demo);
    }

    @Test
    void rejectsEmailOwnedByNormalAccount() {
        User normal = new User();
        normal.setAccountType(User.ACCOUNT_NORMAL);
        when(userService.findByEmail("demo@example.com")).thenReturn(normal);

        assertThatThrownBy(() -> runner.run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Demo 邮箱已被普通账号占用");

        verify(userService, never()).save(normal);
        verify(userService, never()).updateById(normal);
    }
}
