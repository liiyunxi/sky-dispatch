package com.skydispatch.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;

/**
 * 调用 Python 算法服务的 HTTP 客户端配置。
 *
 * <p>用 Spring 6 的 {@code RestClient}（同步、流式 API），比 RestTemplate 更现代，
 * 又不像 WebClient 那样需要响应式依赖。
 *
 * <p>两个关键点：
 * <ol>
 *   <li><b>超时必须设</b>：算法服务卡住时不能把 Java 的调度线程一起拖死。
 *       连接 2 秒、读取 10 秒，超时会抛 {@code ResourceAccessException}，
 *       由全局异常处理器翻译成「算法服务不可用」。</li>
 *   <li><b>复用 Spring 的 ObjectMapper</b>：{@code RestClient.builder()} 默认用一套
 *       独立的 Jackson 配置，不认 {@code spring.jackson.*}。
 *       这里显式换成容器里的 ObjectMapper，保证时间字段按 ISO-8601 字符串传输，
 *       和 Python 的 pydantic datetime 对齐。</li>
 * </ol>
 */
@Configuration
public class RestClientConfig {

    @Bean
    public RestClient algorithmRestClient(SkyProperties properties, ObjectMapper objectMapper) {
        SkyProperties.Algorithm cfg = properties.algorithm();

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(cfg.connectTimeoutMs()));
        factory.setReadTimeout(Duration.ofMillis(cfg.readTimeoutMs()));

        return RestClient.builder()
                .baseUrl(cfg.baseUrl())
                .requestFactory(factory)
                .defaultHeader("Content-Type", "application/json")
                .messageConverters(converters -> useSharedObjectMapper(converters, objectMapper))
                .build();
    }

    private void useSharedObjectMapper(List<HttpMessageConverter<?>> converters, ObjectMapper objectMapper) {
        converters.removeIf(MappingJackson2HttpMessageConverter.class::isInstance);
        converters.add(0, new MappingJackson2HttpMessageConverter(objectMapper));
    }
}
