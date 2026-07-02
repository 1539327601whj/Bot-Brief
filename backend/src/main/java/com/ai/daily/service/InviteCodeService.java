package com.ai.daily.service;

import com.ai.daily.entity.InviteCode;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

public interface InviteCodeService extends IService<InviteCode> {

    /** 管理员生成 n 个邀请码，返回生成的邀请码列表 */
    List<InviteCode> generate(Long adminUserId, int count);

    /** 按 code 查找 */
    InviteCode findByCode(String code);

    /** 标记邀请码已被 userId 使用 */
    void markUsed(String code, Long userId);
}
