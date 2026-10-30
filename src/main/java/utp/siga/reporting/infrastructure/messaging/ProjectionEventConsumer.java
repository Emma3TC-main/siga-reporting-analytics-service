package utp.siga.reporting.infrastructure.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import java.io.IOException;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;
import utp.siga.reporting.application.command.MovementConfirmedCommand;
import utp.siga.reporting.application.command.ProductUpdatedCommand;
import utp.siga.reporting.application.port.in.ProjectionEventUseCase;

@Component
public class ProjectionEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(ProjectionEventConsumer.class);

    private final ObjectMapper mapper;
    private final CanonicalEventValidator validator;
    private final ProjectionEventUseCase useCase;
    private final RetryTemplate retry;

    public ProjectionEventConsumer(
            ObjectMapper mapper,
            CanonicalEventValidator validator,
            ProjectionEventUseCase useCase,
            @Qualifier("reportingConsumerRetryTemplate") RetryTemplate retry) {
        this.mapper = Objects.requireNonNull(mapper);
        this.validator = Objects.requireNonNull(validator);
        this.useCase = Objects.requireNonNull(useCase);
        this.retry = Objects.requireNonNull(retry);
    }

    @RabbitListener(
            id = "reportingProjectionConsumer",
            queues = "${reporting.consumer.queue:reporting.projections.v1}",
            containerFactory = "reportingListenerContainerFactory",
            autoStartup = "${reporting.consumer.enabled:false}")
    public void onMessage(Message message, Channel channel) {
        long tag = message.getMessageProperties().getDeliveryTag();
        String routingKey = message.getMessageProperties().getReceivedRoutingKey();
        Object command;
        UUID eventId;
        UUID correlationId;
        try {
            JsonNode envelope = mapper.readTree(message.getBody());
            command = validator.validate(routingKey, envelope);
            eventId = UUID.fromString(envelope.path("eventId").asText());
            correlationId = UUID.fromString(envelope.path("correlationId").asText());
        } catch (Exception invalid) {
            log.warn("Rejecting invalid Reporting event on routingKey={}: {}", routingKey, invalid.getMessage());
            reject(channel, tag, false, invalid);
            return;
        }

        try {
            boolean changed = retry.execute(context -> process(command));
            channel.basicAck(tag, false);
            log.info("Reporting event committed eventId={} correlationId={} changed={}",
                    eventId, correlationId, changed);
        } catch (Exception failure) {
            log.error("Reporting event exhausted bounded retry eventId={} correlationId={}; dead-lettering",
                    eventId, correlationId, failure);
            reject(channel, tag, false, failure);
        }
    }

    private boolean process(Object command) {
        if (command instanceof MovementConfirmedCommand movement) {
            return useCase.process(movement);
        }
        if (command instanceof ProductUpdatedCommand product) {
            return useCase.process(product);
        }
        throw new IllegalArgumentException("Unsupported Reporting command");
    }

    private static void reject(Channel channel, long tag, boolean requeue, Exception cause) {
        try {
            channel.basicNack(tag, false, requeue);
        } catch (IOException nackFailure) {
            nackFailure.addSuppressed(cause);
            throw new IllegalStateException("Could not negatively acknowledge RabbitMQ delivery", nackFailure);
        }
    }
}
