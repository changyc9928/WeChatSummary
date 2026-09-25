package com.wechat.wechatsummary.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.wechat.wechatsummary.service.AiSettingsService.EffectivePreprocess;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.MessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;

@ExtendWith(MockitoExtension.class)
class PreprocessConcurrencyServiceTest {

    @Mock
    private RabbitListenerEndpointRegistry registry;

    @Mock
    private AiSettingsService settingsService;

    @InjectMocks
    private PreprocessConcurrencyService service;

    @Test
    void appliesOnlyToMediaQueues() {
        SimpleMessageListenerContainer image = mock(SimpleMessageListenerContainer.class);
        when(image.getQueueNames()).thenReturn(new String[]{"image.queue"});
        SimpleMessageListenerContainer cache = mock(SimpleMessageListenerContainer.class);
        when(cache.getQueueNames()).thenReturn(new String[]{"cache.eviction"});
        MessageListenerContainer generic = mock(MessageListenerContainer.class);

        when(registry.getListenerContainers()).thenReturn(List.of(image, cache, generic));
        when(settingsService.effectivePreprocess())
            .thenReturn(new EffectivePreprocess(4, 12, 7, 8, 50));
        when(settingsService.aiPermits()).thenReturn(6);

        assertEquals(1, service.apply());

        verify(image).setConcurrentConsumers(4);
        verify(image).setMaxConcurrentConsumers(12);
        verify(image).setPrefetchCount(7);
        verify(cache, never()).setConcurrentConsumers(4);
        verify(cache, never()).setMaxConcurrentConsumers(12);
        verify(cache, never()).setPrefetchCount(7);
    }
}
