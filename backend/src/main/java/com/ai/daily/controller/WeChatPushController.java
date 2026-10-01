package com.ai.daily.controller;

import com.ai.daily.dto.Result;
import com.ai.daily.service.WeChatPushService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * 企业微信推送控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/push")
public class WeChatPushController {

    @Autowired
    private WeChatPushService weChatPushService;

    /**
     * 推送到企业微信
     * POST /api/push/wechat
     */
    @PostMapping("/wechat")
    public Result<String> pushToWeChat(@RequestParam(required = false) String webhookUrl) {
        boolean fromEnv = webhookUrl == null || webhookUrl.isBlank();
        if (fromEnv) {
            // 使用环境变量中的 webhook
            webhookUrl = System.getenv("WECHAT_WEBHOOK_URL");
        }
        
        if (webhookUrl == null || webhookUrl.isBlank()) {
            log.warn("企业微信推送请求被拒 未配置webhook");
            return Result.error(400, "请提供 webhookUrl 参数或设置 WECHAT_WEBHOOK_URL 环境变量");
        }

        // 只记「有请求」和来源，webhook 是带 key 的地址，不落日志
        log.info("收到企业微信推送请求 来源={}", fromEnv ? "env" : "param");
        boolean success = weChatPushService.pushToWeChat(webhookUrl);
        if (success) {
            return Result.ok("推送成功", null);
        } else {
            // 失败原因（HTTP 状态/errcode）由 WeChatPushService 记，这里不重复
            log.warn("企业微信推送返回失败");
            return Result.error(500, "推送失败，请检查 webhook 地址");
        }
    }
}
