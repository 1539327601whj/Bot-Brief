package com.ai.daily.dto;

import lombok.Data;

public class AuthDTO {

    @Data
    public static class LoginRequest {
        private String email;
        private String password;
    }

    @Data
    public static class RegisterRequest {
        private String email;
        private String password;
        private String displayName;
        private String inviteCode;
    }

    @Data
    public static class UserInfo {
        private Long id;
        private String email;
        private String displayName;
        private String role;
        private String accountType;
    }

    @Data
    public static class LoginResponse {
        private String token;
        private UserInfo user;
    }
}
