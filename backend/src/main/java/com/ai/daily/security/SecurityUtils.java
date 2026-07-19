package com.ai.daily.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

public final class SecurityUtils {

    private SecurityUtils() {}

    public static UserPrincipal currentUserOrNull() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) return null;
        Object principal = auth.getPrincipal();
        if (principal instanceof UserPrincipal up) return up;
        return null;
    }

    public static Long currentUserId() {
        UserPrincipal up = currentUserOrNull();
        return up == null ? null : up.getUserId();
    }

    public static Long requireUserId() {
        Long id = currentUserId();
        if (id == null) throw new IllegalStateException("未登录");
        return id;
    }

    public static boolean isAdmin() {
        UserPrincipal up = currentUserOrNull();
        return up != null && "ADMIN".equals(up.getRole());
    }
}
