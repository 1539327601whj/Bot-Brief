package com.ai.daily.controller;

import com.ai.daily.config.DemoAccountProperties;
import com.ai.daily.dto.AuthDTO;
import com.ai.daily.dto.Result;
import com.ai.daily.entity.User;
import com.ai.daily.security.JwtService;
import com.ai.daily.security.SecurityUtils;
import com.ai.daily.security.UserPrincipal;
import com.ai.daily.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserService userService;
    private final JwtService jwtService;
    private final DemoAccountProperties demoProperties;

    @PostMapping("/register")
    public Result<AuthDTO.LoginResponse> register(@RequestBody AuthDTO.RegisterRequest req) {
        try {
            User u = userService.register(req.getEmail(), req.getPassword(), req.getDisplayName(), req.getInviteCode());
            return Result.ok("注册成功", buildLoginResponse(u));
        } catch (IllegalArgumentException e) {
            return Result.error(400, e.getMessage());
        } catch (Exception e) {
            return Result.error(500, "注册失败：" + e.getMessage());
        }
    }

    @PostMapping("/login")
    public Result<AuthDTO.LoginResponse> login(@RequestBody AuthDTO.LoginRequest req) {
        User u = userService.authenticate(req.getEmail(), req.getPassword());
        if (u == null) return Result.error(401, "邮箱或密码错误");
        return Result.ok(buildLoginResponse(u));
    }

    @PostMapping("/demo")
    public Result<AuthDTO.LoginResponse> demo() {
        if (!demoProperties.isEnabled()) {
            return Result.error(404, "Demo 登录未开启");
        }
        int expirationMinutes = demoProperties.getTokenExpirationMinutes();
        if (expirationMinutes < 5 || expirationMinutes > 60) {
            return Result.error(503, "Demo token 有效期配置无效");
        }
        User u = userService.findByEmail(demoProperties.getEmail().trim().toLowerCase());
        if (u == null || !Boolean.TRUE.equals(u.getEnabled()) || !User.ACCOUNT_DEMO.equals(u.getAccountType())) {
            return Result.error(503, "Demo 账号不可用");
        }
        return Result.ok(buildLoginResponse(u, Duration.ofMinutes(expirationMinutes)));
    }

    @GetMapping("/me")
    public Result<AuthDTO.UserInfo> me() {
        UserPrincipal up = SecurityUtils.currentUserOrNull();
        if (up == null) return Result.error(401, "未登录");
        User u = userService.getById(up.getUserId());
        if (u == null) return Result.error(401, "用户不存在");
        return Result.ok(toUserInfo(u));
    }

    private AuthDTO.LoginResponse buildLoginResponse(User u) {
        return buildLoginResponse(u, null);
    }

    private AuthDTO.LoginResponse buildLoginResponse(User u, Duration validity) {
        AuthDTO.LoginResponse resp = new AuthDTO.LoginResponse();
        resp.setToken(validity == null
                ? jwtService.generate(u.getId(), u.getEmail(), u.getRole())
                : jwtService.generate(u.getId(), u.getEmail(), u.getRole(), validity));
        resp.setUser(toUserInfo(u));
        return resp;
    }

    private AuthDTO.UserInfo toUserInfo(User u) {
        AuthDTO.UserInfo info = new AuthDTO.UserInfo();
        info.setId(u.getId());
        info.setEmail(u.getEmail());
        info.setDisplayName(u.getDisplayName());
        info.setRole(u.getRole());
        info.setAccountType(u.getAccountType());
        return info;
    }
}
