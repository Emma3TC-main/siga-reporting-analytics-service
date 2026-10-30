package utp.siga.reporting.infrastructure.messaging;

import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.amqp.rabbit.listener.MessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.stereotype.Component;
import utp.siga.reporting.application.port.out.ConsumerControlPort;

@Component
public class RabbitConsumerControl implements ConsumerControlPort {

    private final RabbitListenerEndpointRegistry registry;
    private final AtomicBoolean restartRequired = new AtomicBoolean();

    public RabbitConsumerControl(RabbitListenerEndpointRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void pause() {
        MessageListenerContainer container = container();
        boolean running = container != null && container.isRunning();
        restartRequired.set(running);
        if (running) {
            container.stop();
        }
    }

    @Override
    public void resume() {
        MessageListenerContainer container = container();
        if (restartRequired.getAndSet(false) && container != null && !container.isRunning()) {
            container.start();
        }
    }

    private MessageListenerContainer container() {
        return registry.getListenerContainer("reportingProjectionConsumer");
    }
}
