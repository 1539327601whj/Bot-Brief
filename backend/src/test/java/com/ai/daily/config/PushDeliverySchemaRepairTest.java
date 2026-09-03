package com.ai.daily.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PushDeliverySchemaRepairTest {

    @Test
    void missingTablesWhenLegacyInitSqlHasNone() {
        assertThat(PushDeliverySchemaRepair.missingTables(List.of("subscription", "reports")))
                .containsExactly("push_channel", "push_log");
    }

    @Test
    void missingTablesIgnoresNameCase() {
        assertThat(PushDeliverySchemaRepair.missingTables(List.of("PUSH_CHANNEL", "Push_Log"))).isEmpty();
    }

    @Test
    void createSqlUsesIfNotExists() {
        for (String table : PushDeliverySchemaRepair.requiredTables()) {
            assertThat(PushDeliverySchemaRepair.createSql(table))
                    .contains("CREATE TABLE IF NOT EXISTS " + table);
        }
    }

    @Test
    void dispatchKeyPatchIsIdempotent() {
        assertThat(PushDeliverySchemaRepair.needsDispatchKey(List.of("id", "user_id"))).isTrue();
        assertThat(PushDeliverySchemaRepair.needsDispatchKey(List.of("id", "DISPATCH_KEY"))).isFalse();
        assertThat(PushDeliverySchemaRepair.needsDispatchKeyIndex(List.of("PRIMARY"))).isTrue();
        assertThat(PushDeliverySchemaRepair.needsDispatchKeyIndex(List.of("PRIMARY", "uk_push_log_dispatch_key")))
                .isFalse();
    }
}
