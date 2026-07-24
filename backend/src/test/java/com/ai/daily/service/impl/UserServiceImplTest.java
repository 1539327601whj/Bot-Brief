package com.ai.daily.service.impl;

import com.ai.daily.entity.User;
import com.ai.daily.service.InviteCodeService;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

class UserServiceImplTest {

    @Test
    void rejectsDemoAccountBeforePasswordVerification() {
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        UserServiceImpl userService = spy(new UserServiceImpl(
                passwordEncoder,
                mock(InviteCodeService.class)
        ));
        User demo = new User();
        demo.setEmail("demo@example.com");
        demo.setPasswordHash("public-password-hash");
        demo.setAccountType(User.ACCOUNT_DEMO);
        demo.setEnabled(true);
        doReturn(demo).when(userService).findByEmail("demo@example.com");

        User authenticated = userService.authenticate(" Demo@Example.com ", "public-password");

        assertThat(authenticated).isNull();
        verify(passwordEncoder, never()).matches("public-password", "public-password-hash");
    }
}
