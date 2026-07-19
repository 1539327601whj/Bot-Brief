package com.ai.daily.service;

import com.ai.daily.entity.User;
import com.baomidou.mybatisplus.extension.service.IService;

public interface UserService extends IService<User> {

    User findByEmail(String email);

    /**
     * 用邀请码注册新用户。返回创建的用户。
     * 校验：邀请码存在、未使用、未过期；邮箱未占用。
     */
    User register(String email, String rawPassword, String displayName, String inviteCode);

    /**
     * 校验邮箱 + 密码。返回用户，失败返回 null。
     */
    User authenticate(String email, String rawPassword);
}
