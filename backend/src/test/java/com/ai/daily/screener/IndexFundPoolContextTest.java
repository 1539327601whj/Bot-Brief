package com.ai.daily.screener;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 指数池**作为 Bean** 能不能被 Spring 建出来。
 *
 * <p>为什么不并进 {@link IndexPoolLoaderTest}：那边全是 {@code new IndexFundPool(...)}，
 * 绕开了构造器选择这一步，所以哪怕容器里根本建不出这个 Bean，它们照样全绿。
 * 2026-10-01 那次部署就是栽在这上面——后端在服务器上启动失败回滚，而 CI 的 448 个测试
 * 一个都没红。
 *
 * <p>所以这里**只**验证「容器能拿到这个 Bean」这一件事，不碰池子内容（那是 Loader 的活）。
 * 用裸的 {@link AnnotationConfigApplicationContext} 而不是 {@code @SpringBootTest}：
 * 后者会把 MyBatis、数据源、整个 {@code com.ai.daily} 都拉起来，为了几百毫秒就能查出来的
 * 事实去要一个 MySQL，不划算。
 */
class IndexFundPoolContextTest {

    /**
     * 直接注册这一个类，不 {@code @ComponentScan}——同包下还有 {@code MarketDataClient}、
     * {@code StockScreenerService} 这些要依赖 Mapper 的 Bean，扫进来就变回整上下文了。
     */
    @Test
    void 容器能按构造器把指数池建出来() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(IndexFundPool.class);
            context.refresh();

            // 建不出来在 refresh() 就抛了；断到内容是为了确认注入的确实是生产那份池子，
            // 而不是某个空壳（构造器参数没注入时 @Value 会拿到 null，这条能挡住）。
            assertThat(context.getBean(IndexFundPool.class).funds()).isNotEmpty();
        }
    }
}