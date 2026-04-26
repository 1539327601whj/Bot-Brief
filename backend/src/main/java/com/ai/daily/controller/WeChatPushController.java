package com.ai.daily.controller;

import com.ai.daily.dto.Result;
import com.ai.daily.service.WeChatPushService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * 企业微信推送控制器
 */
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
        if (webhookUrl == null || webhookUrl.isBlank()) {
            // 使用环境变量中的 webhook
            webhookUrl = System.getenv("WECHAT_WEBHOOK_URL");
        }
        
        if (webhookUrl == null || webhookUrl.isBlank()) {
            return Result.error(400, "请提供 webhookUrl 参数或设置 WECHAT_WEBHOOK_URL 环境变量");
        }
        
        boolean success = weChatPushService.pushToWeChat(webhookUrl);
        if (success) {
            return Result.ok("推送成功", null);
        } else {
            return Result.error(500, "推送失败，请检查 webhook 地址");
        }
    }
}
