package com.ai.daily.config;

import com.ai.daily.screener.MarketDataClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code MarketDataClient} 用 {@code @Qualifier("marketRestTemplate")} 注入，bean 名来自
 * {@code @Bean("marketRestTemplate")}。这两个字符串对不上时**只有启动才报错**，
 * 所以这里用反射把它们钉在一起。
 *
 * <p>不用 ApplicationContextRunner：它没有 Boot 的 PropertySourcesPlaceholderConfigurer 与
 * {@code ApplicationConversionService}，解析不了 {@code ${...:5s}} → Duration。
 * 同一写法在 {@link PushHttpClientConfig} 里已经在生产跑通，是夹具限制而不是配置问题。
 */
class MarketHttpClientConfigTest {

    @Test
    void beanNameMatchesTheQualifierTheScreenerClientInjects() throws Exception {
        Method factory = MarketHttpClientConfig.class
                .getDeclaredMethod("marketRestTemplate", Duration.class, Duration.class);
        Bean bean = factory.getAnnotation(Bean.class);
        assertThat(bean).as("marketRestTemplate 必须带 @Bean").isNotNull();
        assertThat(bean.value()).hasSize(1);
        String declaredBeanName = bean.value()[0];

        Constructor<?> ctor = MarketDataClient.class.getConstructors()[0];
        Qualifier qualifier = ctor.getParameters()[0].getAnnotation(Qualifier.class);
        assertThat(qualifier).as("MarketDataClient 的第一个构造参数必须带 @Qualifier").isNotNull();

        assertThat(qualifier.value()).isEqualTo(declaredBeanName);
        assertThat(declaredBeanName).isEqualTo("marketRestTemplate");
    }

    @Test
    void screenerBeanDoesNotShadowThePushChannelBean() throws Exception {
        Method factory = MarketHttpClientConfig.class
                .getDeclaredMethod("marketRestTemplate", Duration.class, Duration.class);
        Method pushFactory = PushHttpClientConfig.class
                .getDeclaredMethod("pushRestTemplate", Duration.class, Duration.class);

        // 两条链路各有自己的超时与 host 白名单，共用一条 bean 会互相覆盖
        assertThat(factory.getAnnotation(Bean.class).value()[0])
                .isNotEqualTo(pushFactory.getAnnotation(Bean.class).value()[0]);
    }

    @Test
    void factoryBuildsARestTemplateWithTheConfiguredTimeouts() {
        RestTemplate rt = new MarketHttpClientConfig()
                .marketRestTemplate(Duration.ofSeconds(5), Duration.ofSeconds(10));
        assertThat(rt).isNotNull();
        assertThat(rt.getRequestFactory()).isNotNull();
    }
}