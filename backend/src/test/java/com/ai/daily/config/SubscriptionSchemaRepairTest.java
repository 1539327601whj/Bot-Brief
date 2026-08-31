package com.ai.daily.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SubscriptionSchemaRepairTest {

    @Test
    void missingAltersCoversLegacyInitSql() {
        List<String> alters = SubscriptionSchemaRepair.missingAlters(
                List.of("id", "receive_time", "preference_fields", "enabled", "created_at", "updated_at"));

        assertThat(alters).containsExactly(
                "ALTER TABLE subscription ADD COLUMN user_id BIGINT NOT NULL DEFAULT 1",
                "ALTER TABLE subscription ADD COLUMN morning_enabled TINYINT(1) NOT NULL DEFAULT 1",
                "ALTER TABLE subscription ADD COLUMN morning_time TIME NOT NULL DEFAULT '08:00:00'",
                "ALTER TABLE subscription ADD COLUMN evening_enabled TINYINT(1) NOT NULL DEFAULT 1",
                "ALTER TABLE subscription ADD COLUMN evening_time TIME NOT NULL DEFAULT '20:00:00'",
                "ALTER TABLE subscription ADD COLUMN topic_schedules JSON DEFAULT NULL"
        );
    }

    @Test
    void missingAltersIgnoresColumnNameCase() {
        List<String> alters = SubscriptionSchemaRepair.missingAlters(List.of(
                "USER_ID",
                "Morning_Enabled",
                "morning_time",
                "evening_enabled",
                "evening_time",
                "TOPIC_SCHEDULES"));

        assertThat(alters).isEmpty();
    }

    @Test
    void uniqueIndexNeededUntilPresent() {
        assertThat(SubscriptionSchemaRepair.needsUniqueIndex(List.of("PRIMARY"))).isTrue();
        assertThat(SubscriptionSchemaRepair.needsUniqueIndex(List.of("PRIMARY", "UK_SUBSCRIPTION_USER_ID")))
                .isFalse();
        assertThat(SubscriptionSchemaRepair.createUniqueIndexSql())
                .isEqualTo("CREATE UNIQUE INDEX uk_subscription_user_id ON subscription(user_id)");
    }
}
