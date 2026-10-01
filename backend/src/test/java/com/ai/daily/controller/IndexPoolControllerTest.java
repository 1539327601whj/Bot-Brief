package com.ai.daily.controller;

import com.ai.daily.screener.IndexFundPool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class IndexPoolControllerTest {

    private IndexPoolController controller;

    @BeforeEach
    void setUp() {
        controller = new IndexPoolController();
        ReflectionTestUtils.setField(controller, "indexPool",
                new IndexFundPool(new ClassPathResource("screener/index-pool.json")));
        ReflectionTestUtils.setField(controller, "ingestToken", "secret");
    }

    @Test
    void rejectsMissingAndWrongTokens() {
        assertThat(controller.list(null).getCode()).isEqualTo(401);
        assertThat(controller.list("wrong").getCode()).isEqualTo(401);
        assertThat(controller.list(null).getData()).isNull();
    }

    @Test
    void handsOutTheWholePoolSoThePythonSyncDoesNotKeepASecondCopy() {
        var body = controller.list("secret");

        assertThat(body.getCode()).isEqualTo(200);
        assertThat(body.getData()).isNotEmpty();
        // 同步脚本要拿 csindexCode / danjuanCode 去逐个源取数，一个都不能少
        assertThat(body.getData()).filteredOn(IndexFundPool.Fund::hasValuation)
                .isNotEmpty()
                .allSatisfy(f -> {
                    assertThat(f.valuationSource()).isIn("csindex", "danjuan");
                    assertThat(f.percentileMethod()).isNotBlank();
                });
    }
}