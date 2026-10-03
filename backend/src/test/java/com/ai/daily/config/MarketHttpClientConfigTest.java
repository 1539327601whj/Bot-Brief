package com.ai.daily.config;

import com.ai.daily.screener.MarketCallMetrics;
import com.ai.daily.screener.MarketDataClient;
import com.ai.daily.screener.MarketRateLimitGate;
import com.ai.daily.screener.MarketRateLimitInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
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
        Method factory = beanFactory();
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
        Method factory = beanFactory();
        Method pushFactory = PushHttpClientConfig.class
                .getDeclaredMethod("pushRestTemplate", Duration.class, Duration.class);

        // 两条链路各有自己的超时与 host 白名单，共用一条 bean 会互相覆盖
        assertThat(factory.getAnnotation(Bean.class).value()[0])
                .isNotEqualTo(pushFactory.getAnnotation(Bean.class).value()[0]);
    }

    /**
     * 生产的 bean 必须**带着**限速拦截器，测试用的重载必须**不带**。
     *
     * <p>后者不是洁癖：{@code MockRestServiceServer} 只替换底层的 request factory，
     * 拦截器照跑——测试若拿到带闸门的实例，就会真的按生产间隔睡过去（默认每源 150ms，
     * 一次筛选二十多次外呼能睡好几秒），测试变慢且开始依赖限速参数。
     * 这里把「两条路各给什么」钉住，免得将来有人把 {@code @Bean} 挪回无闸门那个重载上。
     */
    @Test
    void theBeanCarriesTheRateLimitInterceptorAndTheTestFactoryDoesNot() {
        RestTemplate plain = new MarketHttpClientConfig()
                .marketRestTemplate(Duration.ofSeconds(5), Duration.ofSeconds(10));
        assertThat(plain).isNotNull();
        assertThat(plain.getRequestFactory()).isNotNull();
        assertThat(plain.getInterceptors()).isEmpty();

        // 间隔置 0 的闸门：这个用例只关心「挂上了没有」，不关心限速行为
        MarketRateLimitInterceptor interceptor = new MarketRateLimitInterceptor(
                new MarketRateLimitGate(0, 0), new MarketCallMetrics());
        RestTemplate bean = new MarketHttpClientConfig()
                .marketRestTemplate(Duration.ofSeconds(5), Duration.ofSeconds(10), interceptor);
        assertThat(bean.getInterceptors()).containsExactly(interceptor);
        assertThat(bean.getRequestFactory()).isNotNull();
    }

    /**
     * {@code @Bean} 的每一个非 {@code @Value} 参数，都必须自己是个 {@code @Component}——
     * 否则**只有启动时才会报错**（和 bean 名对不上是同一类失败：编译期、单测期全绿）。
     *
     * <p>这条是给「往这个工厂里加协作者」设的闸门：限速拦截器就是后加进去的第三个参数，
     * 而它要是哪天不再是个 bean，本地全量测试照样绿，炸的是服务器重启。
     */
    @Test
    void everyNonValueParameterOfTheBeanIsItselfAComponent() throws Exception {
        for (java.lang.reflect.Parameter parameter : beanFactory().getParameters()) {
            if (parameter.isAnnotationPresent(Value.class)) {
                continue;
            }
            assertThat(parameter.getType())
                    .as("参数 %s 既没有 @Value 又不是 @Component，装配不上时只有启动才报错",
                            parameter.getType().getSimpleName())
                    .hasAnnotation(org.springframework.stereotype.Component.class);
        }
    }

    /** {@code @Bean} 在 3 参那个重载上——另外两个用例都要靠它找到正确答案。 */
    private static Method beanFactory() throws NoSuchMethodException {
        return MarketHttpClientConfig.class
                .getDeclaredMethod("marketRestTemplate", Duration.class, Duration.class,
                        MarketRateLimitInterceptor.class);
    }
}