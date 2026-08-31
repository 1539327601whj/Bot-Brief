package com.ai.daily.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ContentGrowthSchemaRepairTest {

    @Test
    void missingTablesWhenLegacyInitSqlHasNone() {
        assertThat(ContentGrowthSchemaRepair.missingTables(List.of("subscription", "reports")))
                .containsExactly(
                        "content_account",
                        "content_work",
                        "content_growth_analysis",
                        "competitor_account");
    }

    @Test
    void missingTablesIgnoresNameCase() {
        assertThat(ContentGrowthSchemaRepair.missingTables(List.of(
                "CONTENT_ACCOUNT",
                "content_work",
                "Content_Growth_Analysis",
                "competitor_account"))).isEmpty();
    }

    @Test
    void createSqlUsesIfNotExists() {
        for (String table : ContentGrowthSchemaRepair.requiredTables()) {
            assertThat(ContentGrowthSchemaRepair.createSql(table))
                    .contains("CREATE TABLE IF NOT EXISTS " + table);
        }
    }
}
