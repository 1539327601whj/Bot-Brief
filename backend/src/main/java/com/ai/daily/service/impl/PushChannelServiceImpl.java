package com.ai.daily.service.impl;

import com.ai.daily.dto.PushChannelCreateRequest;
import com.ai.daily.dto.PushChannelResponse;
import com.ai.daily.dto.PushChannelUpdateRequest;
import com.ai.daily.entity.PushChannel;
import com.ai.daily.mapper.PushChannelMapper;
import com.ai.daily.service.PushChannelCrypto;
import com.ai.daily.service.PushChannelService;
import com.ai.daily.service.PushChannelValidator;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class PushChannelServiceImpl extends ServiceImpl<PushChannelMapper, PushChannel>
        implements PushChannelService, ApplicationRunner {

    private final PushChannelCrypto crypto;
    private final PushChannelValidator validator;

    @Override
    public List<PushChannelResponse> listResponsesByUser(Long userId) {
        return listStoredByUser(userId).stream().map(this::toResponse).toList();
    }

    @Override
    @Transactional
    public PushChannelResponse createForUser(Long userId, PushChannelCreateRequest request) {
        crypto.requireAvailable();
        if (request == null) throw new IllegalArgumentException("请求体为空");
        String type = validator.normalizeType(request.getChannelType());
        String target = trimRequired(request.getTarget());
        validator.validate(type, target);
        validator.validateSecret(request.getSecret());

        PushChannel channel = new PushChannel();
        channel.setUserId(userId);
        channel.setChannelType(type);
        channel.setDisplayName(normalizeDisplayName(request.getDisplayName()));
        channel.setTarget(crypto.encrypt(target));
        channel.setSecret(encryptedSecret(type, request.getSecret()));
        channel.setEnabled(request.getEnabled() == null || request.getEnabled());
        save(channel);
        return toResponse(channel);
    }

    @Override
    @Transactional
    public PushChannelResponse updateForUser(Long id, Long userId, PushChannelUpdateRequest request) {
        crypto.requireAvailable();
        if (request == null) throw new IllegalArgumentException("请求体为空");
        PushChannel stored = getStoredByIdForUser(id, userId);
        if (stored == null) return null;

        String existingType = stored.getChannelType();
        String type = request.getChannelType() == null
                ? existingType : validator.normalizeType(request.getChannelType());
        boolean typeChanged = !existingType.equals(type);
        String suppliedTarget = trimToNull(request.getTarget());
        if (typeChanged && suppliedTarget == null) {
            throw new IllegalArgumentException("更改渠道类型时必须提供新的推送目标");
        }
        String target = suppliedTarget == null ? crypto.decrypt(stored.getTarget()) : suppliedTarget;
        validator.validate(type, target);
        validator.validateSecret(request.getSecret());

        stored.setChannelType(type);
        if (request.getDisplayName() != null) stored.setDisplayName(normalizeDisplayName(request.getDisplayName()));
        if (suppliedTarget != null) stored.setTarget(crypto.encrypt(suppliedTarget));
        if (request.getEnabled() != null) stored.setEnabled(request.getEnabled());

        if ("email".equals(type) || "wechat".equals(type)) {
            stored.setSecret(null);
        } else if (Boolean.TRUE.equals(request.getClearSecret())) {
            stored.setSecret(null);
        } else if (request.getSecret() != null && !request.getSecret().isBlank()) {
            stored.setSecret(crypto.encrypt(request.getSecret().trim()));
        } else if (typeChanged) {
            stored.setSecret(null);
        }
        updateById(stored);
        return toResponse(stored);
    }

    @Override
    public boolean removeForUser(Long id, Long userId) {
        PushChannel stored = getStoredByIdForUser(id, userId);
        return stored != null && removeById(stored.getId());
    }

    @Override
    public List<PushChannel> listEnabledByUser(Long userId) {
        LambdaQueryWrapper<PushChannel> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PushChannel::getUserId, userId).eq(PushChannel::getEnabled, true);
        return list(wrapper).stream().map(this::decryptCopy).toList();
    }

    @Override
    public PushChannel getByIdForUser(Long id, Long userId) {
        PushChannel stored = getStoredByIdForUser(id, userId);
        return stored == null ? null : decryptCopy(stored);
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!crypto.isAvailable()) {
            log.warn("未配置 PUSH_CHANNEL_ENCRYPTION_KEY，渠道创建、读取和发送功能不可用");
            return;
        }
        int migrated = 0;
        long lastId = 0;
        while (true) {
            List<PushChannel> batch = lambdaQuery()
                    .gt(PushChannel::getId, lastId)
                    .orderByAsc(PushChannel::getId)
                    .last("LIMIT 200")
                    .list();
            if (batch.isEmpty()) break;

            for (PushChannel channel : batch) {
                lastId = channel.getId();
                boolean plaintextTarget = channel.getTarget() != null && !crypto.isEncrypted(channel.getTarget());
                boolean plaintextSecret = channel.getSecret() != null && !channel.getSecret().isBlank()
                        && !crypto.isEncrypted(channel.getSecret());
                if (!plaintextTarget && !plaintextSecret) continue;

                if (plaintextTarget) {
                    try {
                        validator.validate(channel.getChannelType(), channel.getTarget());
                    } catch (IllegalArgumentException e) {
                        log.warn("跳过无效历史推送渠道加密 channel_id={}", channel.getId());
                        continue;
                    }
                    channel.setTarget(crypto.encrypt(channel.getTarget()));
                }
                if (plaintextSecret) channel.setSecret(crypto.encrypt(channel.getSecret()));
                updateById(channel);
                migrated++;
            }
        }
        if (migrated > 0) log.info("已加密 {} 条历史推送渠道记录", migrated);
    }

    private List<PushChannel> listStoredByUser(Long userId) {
        LambdaQueryWrapper<PushChannel> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PushChannel::getUserId, userId).orderByDesc(PushChannel::getCreatedAt);
        return list(wrapper);
    }

    private PushChannel getStoredByIdForUser(Long id, Long userId) {
        LambdaQueryWrapper<PushChannel> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PushChannel::getId, id).eq(PushChannel::getUserId, userId).last("LIMIT 1");
        return getOne(wrapper);
    }

    private PushChannel decryptCopy(PushChannel stored) {
        crypto.requireAvailable();
        PushChannel channel = new PushChannel();
        channel.setId(stored.getId());
        channel.setUserId(stored.getUserId());
        channel.setChannelType(stored.getChannelType());
        channel.setDisplayName(stored.getDisplayName());
        channel.setTarget(crypto.decrypt(stored.getTarget()));
        channel.setSecret(crypto.decrypt(stored.getSecret()));
        channel.setEnabled(stored.getEnabled());
        channel.setCreatedAt(stored.getCreatedAt());
        channel.setUpdatedAt(stored.getUpdatedAt());
        return channel;
    }

    private PushChannelResponse toResponse(PushChannel stored) {
        String target = crypto.decrypt(stored.getTarget());
        String secret = crypto.decrypt(stored.getSecret());
        return PushChannelResponse.builder()
                .id(stored.getId())
                .channelType(stored.getChannelType())
                .displayName(stored.getDisplayName())
                .targetPreview(preview(stored.getChannelType(), target))
                .secretConfigured(secret != null && !secret.isBlank())
                .enabled(stored.getEnabled())
                .createdAt(stored.getCreatedAt())
                .updatedAt(stored.getUpdatedAt())
                .build();
    }

    private String preview(String type, String target) {
        if (target == null || target.isBlank()) return "";
        if ("email".equals(type)) {
            int at = target.indexOf('@');
            if (at <= 0) return "***";
            String local = target.substring(0, at);
            return local.charAt(0) + "***" + target.substring(at);
        }
        int keep = Math.min(8, target.length());
        return "***" + target.substring(target.length() - keep);
    }

    private String encryptedSecret(String type, String secret) {
        if ("email".equals(type) || "wechat".equals(type) || secret == null || secret.isBlank()) return null;
        return crypto.encrypt(secret.trim());
    }

    private String trimRequired(String value) {
        return value == null ? null : value.trim();
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim();
    }

    private String normalizeDisplayName(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        if (trimmed.length() > 100) throw new IllegalArgumentException("渠道昵称不能超过 100 个字符");
        return trimmed.isEmpty() ? null : trimmed;
    }
}
