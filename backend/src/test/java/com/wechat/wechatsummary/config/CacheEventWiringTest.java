package com.wechat.wechatsummary.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URL;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Guards application wiring that unit tests cannot see:
 * <ul>
 *   <li>the dedicated {@code cacheEventObjectMapper} coexists with the shared primary mapper
 *   ({@code AiConfig.objectMapper}) without creating injection ambiguity;</li>
 *   <li>the {@code cache.eviction.delayed-delay} property binds to the documented key;</li>
 *   <li>the eviction topology configuration instantiates.</li>
 * </ul>
 */
class CacheEventWiringTest {

    /** Mirrors the contract of {@code AiConfig.objectMapper()}: the shared default mapper. */
    @TestConfiguration(proxyBeanMethods = false)
    static class SharedMapperConfig {
        @Bean
        @Primary
        ObjectMapper sharedObjectMapper() {
            return new ObjectMapper();
        }

        @Bean
        ConnectionFactory connectionFactory() {
            return Mockito.mock(ConnectionFactory.class);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties({CacheEvictionProperties.class})
    static class PropsConfig {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(
                    SharedMapperConfig.class, PropsConfig.class, CacheEventRabbitConfig.class)
            .withPropertyValues(
                    "cache.eviction.delayed-delay=5s",
                    "cache.eviction.publish-max-attempts=5",
                    "cache.eviction.publish-confirm-timeout=5s",
                    "cache.eviction.publish-retry-backoff=200ms",
                    "cache.eviction.publish-retry-backoff-multiplier=2.0");

    @Test
    void qualifiedMapperCoexistsWithPrimaryWithoutAmbiguity() {
        runner.run(ctx -> {
            // Unqualified lookup must resolve via @Primary (throws
            // NoUniqueBeanDefinitionException if the primary marker is ever lost).
            ObjectMapper resolved = ctx.getBean(ObjectMapper.class);
            assertThat(resolved).isSameAs(ctx.getBean("sharedObjectMapper"));
            assertThat(ctx).hasBean("cacheEventObjectMapper");
            ObjectMapper qualified =
                    (ObjectMapper) ctx.getBean("cacheEventObjectMapper");
            assertThat(qualified).isNotSameAs(resolved);
        });
    }

    @Test
    void delayPropertyBindsToDocumentedKey() {
        runner.run(ctx -> {
            CacheEvictionProperties eviction = ctx.getBean(CacheEvictionProperties.class);
            assertThat(eviction.getDelayedDelay()).isEqualTo(Duration.ofSeconds(5));
            assertThat(eviction.getPublishMaxAttempts()).isEqualTo(5);
            assertThat(eviction.getPublishConfirmTimeout()).isEqualTo(Duration.ofSeconds(5));
            assertThat(eviction.getPublishRetryBackoff()).isEqualTo(Duration.ofMillis(200));
            assertThat(eviction.getPublishRetryBackoffMultiplier()).isEqualTo(2.0);
        });
    }

    @Test
    void rabbitmqPublisherConfirmsEnabledInApplicationYaml() throws Exception {
        // Without correlated confirms, broker-confirm futures never complete and every
        // publish burns its whole retry budget on confirm timeouts.
        URL yaml = getClass().getClassLoader().getResource("application.yaml");
        assertThat(yaml).isNotNull();
        Map<String, Object> root;
        try (var in = yaml.openStream()) {
            root = new org.yaml.snakeyaml.Yaml().load(in);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> rabbitmq =
                (Map<String, Object>) ((Map<String, Object>) root.get("spring")).get("rabbitmq");
        assertThat(rabbitmq.get("publisher-confirm-type")).isEqualTo("correlated");
    }

    @Test
    void topologyBeansInstantiated() {
        runner.run(ctx -> {
            assertThat(ctx).hasBean("cacheEvictionExchange");
            assertThat(ctx).hasBean("cacheEvictionHoldQueue");
            assertThat(ctx).hasBean("cacheEvictionQueue");
            assertThat(ctx).hasBean("cacheEvictionListenerContainerFactory");
        });
    }
}
