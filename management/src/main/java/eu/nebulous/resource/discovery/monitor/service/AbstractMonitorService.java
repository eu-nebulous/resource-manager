
package eu.nebulous.resource.discovery.monitor.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.nebulous.resource.discovery.ResourceDiscoveryProperties;
import eu.nebulous.resource.discovery.monitor.model.Device;
import eu.nebulous.resource.discovery.monitor.model.DeviceStatus;
import jakarta.jms.*;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.activemq.ActiveMQConnection;
import org.apache.activemq.ActiveMQConnectionFactory;
import org.apache.activemq.command.ActiveMQTextMessage;
import org.apache.activemq.command.ActiveMQTopic;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@EnableAsync
@EnableScheduling
@RequiredArgsConstructor
public abstract class AbstractMonitorService implements InitializingBean, MessageListener {
    @NonNull protected final String name;
    protected final ResourceDiscoveryProperties monitorProperties;
    protected final TaskScheduler taskScheduler;
    protected final ObjectMapper objectMapper;
    protected ActiveMQConnection connection;
    protected Session session;

    @Override
    public void afterPropertiesSet() throws Exception {
        // Initialize device status listener
        taskScheduler.schedule(this::initializeDeviceStatusListener,
                Instant.now().plusSeconds(monitorProperties.getSubscriptionStartupDelay()));
    }

    private void initializeDeviceStatusListener() {
        try {
            openBrokerConnection();
            getTopicsToMonitor().forEach(topic -> {
                try {
                    MessageConsumer consumer = session.createConsumer(new ActiveMQTopic(topic));
                    consumer.setMessageListener(this);
                    log.debug("{}: Subscribed to topic: {}", name, topic);
                } catch (Exception e) {
                    log.error("{}: ERROR while subscribing to topic: {}\n", name, topic, e);
                    taskScheduler.schedule(this::initializeDeviceStatusListener, Instant.now().plusSeconds(monitorProperties.getSubscriptionRetryDelay()));
                }
            });
        } catch (JMSException e) {
            log.error("{}: ERROR while opening connection to Message broker: ", name, e);
            taskScheduler.schedule(this::initializeDeviceStatusListener, Instant.now().plusSeconds(monitorProperties.getSubscriptionRetryDelay()));
        }
    }

    private void openBrokerConnection() throws JMSException {
        ActiveMQConnectionFactory connectionFactory = new ActiveMQConnectionFactory(
                monitorProperties.getBrokerUsername(), monitorProperties.getBrokerPassword(), monitorProperties.getBrokerURL());
        ActiveMQConnection conn = (ActiveMQConnection) connectionFactory.createConnection();
        Session ses = conn.createSession();
        this.connection = conn;
        this.session = ses;
        connection.start();
    }

    protected abstract @NonNull List<String> getTopicsToMonitor();

    @Override
    public void onMessage(Message message) {
        try {
            log.debug("{}: Received a JMS message: {}", name, message);
            if (message instanceof ActiveMQTextMessage textMessage) {
                String payload = textMessage.getText();
                log.trace("{}: Message payload: {}", name, payload);
                TypeReference<Map<String,Object>> typeRef = new TypeReference<>() { };
                Object obj = objectMapper.readerFor(typeRef).readValue(payload);
                if (obj instanceof Map<?,?> dataMap) {
                    processPayload(dataMap);
                } else {
                    log.debug("{}: Message payload is not recognized. Expected Map but got: type={}, object={}", name, obj.getClass().getName(), obj);
                }
            } else {
                log.debug("{}: Message type is not supported: {}", name, message);
            }
        } catch (Exception e) {
            log.warn("{}: ERROR while processing message: {}\nException: ", name, message, e);
        }
    }

    public void setHealthyStatus(Device device) {
        device.setStatus(DeviceStatus.HEALTHY);
        device.setSuspectTimestamp(null);
        device.setRetries(0);
    }

    protected abstract void processPayload(Map<?, ?> dataMap);
}
