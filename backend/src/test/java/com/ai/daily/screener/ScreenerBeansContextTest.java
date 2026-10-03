package com.ai.daily.screener;

import com.ai.daily.service.EtfPriceHistoryService;
import com.ai.daily.service.MarketValuationHistoryService;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 容器建得出这两个 Bean 吗。
 *
 * <p>为什么单独有这么一条：{@link StockScreenerService} 这次多了一个构造器参数
 * （{@code ScreenerPrefetchService} 加一个 {@code @Value}），{@link ScreenerPrefetchTask}
 * 是全新的 {@code @Component}。这两处的失败都不会让任何一个单测变红——
 * 它们全都在 {@code new} 对象，绕开了「容器能不能按构造器把它建出来」这一步。
 * 2026-10-01 那次部署正是栽在这上面：后端在服务器上启动失败回滚，而 CI 全绿
 * （{@link IndexFundPoolContextTest} 的注释里记着这件事）。
 *
 * <p>所以只验「建得出来」这一件事，用裸的 {@link AnnotationConfigApplicationContext}
 * 而不是 {@code @SpringBootTest}：后者要拉 MyBatis 与数据源，为一个几百毫秒就能查出来的
 * 事实去要一个 MySQL，不划算。依赖全部注册成 mock，也不 {@code @ComponentScan}——
 * 扫进来就变回整上下文了。
 */
class ScreenerBeansContextTest {

    @Test
    void 容器能按构造器把选股服务和预取任务建出来() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(MarketDataClient.class, () -> mock(MarketDataClient.class));
            context.registerBean(ScreenerCache.class, () -> new ScreenerCache(15, 20, 60, 10));
            context.registerBean(MarketValuationHistoryService.class,
                    () -> mock(MarketValuationHistoryService.class));
            context.registerBean(EtfPriceHistoryService.class, () -> mock(EtfPriceHistoryService.class));
            context.registerBean(AltQuoteSource.class, () -> mock(AltQuoteSource.class));
            context.registerBean(ScreenerPrefetchService.class, () -> mock(ScreenerPrefetchService.class));
            // 真实那份池子：它的构造器带 @Value(classpath:...)，顺带证明这一步在容器里也过
            context.registerBean(IndexFundPool.class);
            context.registerBean(StockScreenerService.class);
            context.registerBean(ScreenerPrefetchTask.class);

            context.refresh();

            // 建不出来在 refresh() 就抛了。断到对象是为了确认 @Value 都拿到了值——
            // 缺省值解析不了时容器给的是 null / 拆箱异常，而那时 Bean 可能已经被建出来了
            assertThat(context.getBean(StockScreenerService.class)).isNotNull();
            assertThat(context.getBean(ScreenerPrefetchTask.class)).isNotNull();
        }
    }

    /**
     * 限速三件套（计数 / 闸门 / 拦截器）也要能按构造器建出来——这一类失败**只有启动才报**，
     * 而且是本文件的由来（见上面的类注释）。{@link MarketRateLimitGate} 尤其危险：
     * 它有两个构造器（生产的 + 测试用的），少标 {@code @Autowired} 时 Spring 会退回去找
     * 无参构造器然后抛 {@code NoDefaultConstructorFoundException}，
     * 而所有单测都在 {@code new} 对象，一个都不会红。
     *
     * <p>{@code MarketRateLimitGateTest} 里那条反射用例钉的是规则本身，这里钉的是
     * **容器真的按那条规则建得出来**——两者不重复：万一将来有人把参数改成
     * {@code int}、或把 {@code @Value} 写到解析不了的表达式上，反射用例照样绿，这条会红。
     *
     * <p>要点：{@code @Value("${...:默认值}")} 需要占位符解析器，裸的
     * {@link AnnotationConfigApplicationContext} 没有 Boot 的
     * {@code PropertyPlaceholderAutoConfiguration}，所以这里显式注册一个——
     * 否则测的是「没有解析器时会怎样」，与生产的装配不是一回事。
     */
    @Test
    void 容器能按构造器把限速三件套建出来() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(PropertySourcesPlaceholderConfigurer.class);
            context.registerBean(MarketCallMetrics.class);
            context.registerBean(MarketRateLimitGate.class);
            context.registerBean(MarketRateLimitInterceptor.class);

            context.refresh();

            // 建不出来在 refresh() 就抛了。断到对象是为了确认 @Value 都拿到了值：
            // 占位符解析不了时给的是 null / 拆箱异常，那时 Bean 可能已经被建出来了。
            assertThat(context.getBean(MarketRateLimitGate.class)).isNotNull();
            assertThat(context.getBean(MarketRateLimitInterceptor.class)).isNotNull();
            assertThat(context.getBean(MarketCallMetrics.class).currentDay()).isNotNull();
        }
    }
}