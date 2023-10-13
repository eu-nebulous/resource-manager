
package eu.nebulous.resource.discovery.monitor.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.nebulous.resource.discovery.ResourceDiscoveryProperties;
import eu.nebulous.resource.discovery.monitor.model.Device;
import eu.nebulous.resource.discovery.monitor.model.DeviceStatus;
import jakarta.jms.Message;
import jakarta.jms.MessageConsumer;
import jakarta.jms.MessageListener;
import jakarta.jms.Session;
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
    protected final ResourceDiscoveryProperties monitorProperties;
    protected final TaskScheduler taskScheduler;
    protected final ObjectMapper objectMapper;

    @Override
    public void afterPropertiesSet() throws Exception {
        // Initialize device status listener
        taskScheduler.schedule(this::initializeDeviceStatusListener,
                Instant.now().plusSeconds(monitorProperties.getSubscriptionStartupDelay()));
    }

    private void initializeDeviceStatusListener() {
        getTopicsToMonitor().forEach(topic -> {
            try {
                ActiveMQConnectionFactory connectionFactory = new ActiveMQConnectionFactory(
                        monitorProperties.getBrokerUsername(), monitorProperties.getBrokerPassword(), monitorProperties.getBrokerURL());
                ActiveMQConnection conn = (ActiveMQConnection) connectionFactory.createConnection();
                Session session = conn.createSession();
                MessageConsumer consumer = session.createConsumer(
                        new ActiveMQTopic(topic));
                consumer.setMessageListener(this);
                conn.start();
            } catch (Exception e) {
                log.error("AbstractMonitorService: ERROR while subscribing to Message broker for Device status updates: ", e);
                taskScheduler.schedule(this::initializeDeviceStatusListener, Instant.now().plusSeconds(monitorProperties.getSubscriptionRetryDelay()));
            }
        });
    }

    protected abstract @NonNull List<String> getTopicsToMonitor();

    @Override
    public void onMessage(Message message) {
        try {
            log.debug("AbstractMonitorService: Received a JMS message: {}", message);
            if (message instanceof ActiveMQTextMessage textMessage) {
                String payload = textMessage.getText();
                log.trace("AbstractMonitorService: Message payload: {}", payload);
                TypeReference<Map<String,Object>> typeRef = new TypeReference<>() { };
                Object obj = objectMapper.readerFor(typeRef).readValue(payload);
                if (obj instanceof Map<?,?> dataMap) {
                    processPayload(dataMap);
                } else {
                    log.debug("AbstractMonitorService: Message payload is not recognized. Expected Map but got: type={}, object={}", obj.getClass().getName(), obj);
                }
            } else {
                log.debug("AbstractMonitorService: Message type is not supported: {}", message);
            }
        } catch (Exception e) {
            log.warn("AbstractMonitorService: ERROR while processing message: {}\nException: ", message, e);
        }
    }

    public void setHealthyStatus(Device device) {
        device.setStatus(DeviceStatus.HEALTHY);
        device.setSuspectTimestamp(null);
        device.setRetries(0);
    }

    protected abstract void processPayload(Map<?, ?> dataMap);
}
