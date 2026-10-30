package utp.siga.reporting.infrastructure.messaging;

import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.support.RetryTemplate;

@Configuration
public class RabbitMQConfiguration {

    @Bean
    TopicExchange reportingEventsExchange(@Value("${reporting.consumer.exchange:siga.events}") String exchange) {
        return new TopicExchange(exchange, true, false);
    }

    @Bean
    Queue reportingProjectionQueue(@Value("${reporting.consumer.queue:reporting.projections.v1}") String queue) {
        return QueueBuilder.durable(queue)
                .withArgument("x-single-active-consumer", true)
                .deadLetterExchange(queue + ".dlx")
                .deadLetterRoutingKey(queue + ".dead")
                .build();
    }

    @Bean
    DirectExchange reportingDeadLetterExchange(
            @Value("${reporting.consumer.queue:reporting.projections.v1}") String queue) {
        return new DirectExchange(queue + ".dlx", true, false);
    }

    @Bean
    Queue reportingDeadLetterQueue(@Value("${reporting.consumer.queue:reporting.projections.v1}") String queue) {
        return QueueBuilder.durable(queue + ".dlq").build();
    }

    @Bean
    Binding reportingDeadLetterBinding(
            @Qualifier("reportingDeadLetterQueue") Queue deadLetterQueue,
            DirectExchange reportingDeadLetterExchange,
            @Value("${reporting.consumer.queue:reporting.projections.v1}") String queue) {
        return BindingBuilder.bind(deadLetterQueue).to(reportingDeadLetterExchange).with(queue + ".dead");
    }

    @Bean
    Binding reportingMovementBinding(
            @Qualifier("reportingProjectionQueue") Queue queue,
            TopicExchange reportingEventsExchange,
            @Value("${reporting.consumer.movement-routing-key:inventory.movement.confirmed.v1}") String key) {
        return BindingBuilder.bind(queue).to(reportingEventsExchange).with(key);
    }

    @Bean
    Binding reportingProductBinding(
            @Qualifier("reportingProjectionQueue") Queue queue,
            TopicExchange reportingEventsExchange,
            @Value("${reporting.consumer.product-routing-key:catalog.product.updated.v1}") String key) {
        return BindingBuilder.bind(queue).to(reportingEventsExchange).with(key);
    }

    @Bean
    SimpleRabbitListenerContainerFactory reportingListenerContainerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer,
            ConnectionFactory connectionFactory) {
        var factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        factory.setConcurrentConsumers(1);
        factory.setMaxConcurrentConsumers(1);
        factory.setPrefetchCount(1);
        factory.setDefaultRequeueRejected(false);
        return factory;
    }

    @Bean("reportingConsumerRetryTemplate")
    RetryTemplate reportingConsumerRetryTemplate(
            @Value("${reporting.consumer.retry.max-attempts:3}") int maxAttempts,
            @Value("${reporting.consumer.retry.initial-interval-ms:250}") long initial,
            @Value("${reporting.consumer.retry.multiplier:2.0}") double multiplier,
            @Value("${reporting.consumer.retry.max-interval-ms:2000}") long maximum) {
        return RetryTemplate.builder()
                .maxAttempts(maxAttempts)
                .exponentialBackoff(initial, multiplier, maximum)
                .build();
    }
}
