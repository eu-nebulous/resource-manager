
package eu.nebulous.resource.discovery.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.nebulous.resource.discovery.ResourceDiscoveryProperties;
import jakarta.jms.*;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.activemq.ActiveMQConnection;
import org.apache.activemq.ActiveMQSslConnectionFactory;
import org.apache.activemq.command.ActiveMQTextMessage;
import org.apache.activemq.command.ActiveMQTopic;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Slf4j
@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
@RequiredArgsConstructor
public class BrokerUtil implements InitializingBean, MessageListener {
    private final ResourceDiscoveryProperties properties;
    private final EncryptionUtil encryptionUtil;
    private final TaskScheduler taskScheduler;
    private final ObjectMapper objectMapper;
    private final Map<String,MessageProducer> producers = new HashMap<>();
    private final Map<String,MessageConsumer> consumers = new HashMap<>();
    private final Map<String, Set<Listener>> listeners = new HashMap<>();
    private ActiveMQConnection connection;
    private Session session;

    @Override
    public void afterPropertiesSet() throws Exception {
        // Initialize broker connection
        taskScheduler.schedule(this::initializeBrokerConnection,
                Instant.now().plusSeconds(properties.getSubscriptionStartupDelay()));

        // Initialize connection health check
        int healthCheckPeriod = properties.getConnectionHealthCheckPeriod();
        if (healthCheckPeriod>0) {
            taskScheduler.scheduleAtFixedRate(this::connectionHealthCheck,
                    Instant.now().plusSeconds(properties.getSubscriptionStartupDelay()),
                    Duration.ofSeconds(healthCheckPeriod));
            log.info("BrokerUtil: Enabled connection health check: period={}s", healthCheckPeriod);
        }
    }

    private void initializeBrokerConnection() {
            try {
                openBrokerConnection();
            } catch (Exception e) {
                log.error("BrokerUtil: ERROR while opening connection to Message broker: ", e);
                taskScheduler.schedule(this::initializeBrokerConnection,
                    Instant.now().plusSeconds(properties.getSubscriptionRetryDelay()));
        }
    }

    private void openBrokerConnection() throws Exception {
        ActiveMQSslConnectionFactory cf = new ActiveMQSslConnectionFactory(properties.getBrokerURL());
        cf.setUserName(properties.getBrokerUsername());
        cf.setPassword(properties.getBrokerPassword());

        log.debug("BrokerUtil: Keystore and Truststore settings: keystore-file={}, keystore-type={}, truststore-file={}, truststore-type={}",
                properties.getKeyStoreFile(), properties.getKeyStoreType(), properties.getTrustStoreFile(), properties.getTrustStoreType());
        if (StringUtils.isNotBlank(properties.getKeyStoreFile())) {
            cf.setKeyStore(properties.getKeyStoreFile());
            cf.setKeyStorePassword(properties.getKeyStorePassword());
            cf.setKeyStoreType(properties.getKeyStoreType());
        }
        if (StringUtils.isNotBlank(properties.getTrustStoreFile())) {
            cf.setTrustStore(properties.getTrustStoreFile());
            cf.setTrustStorePassword(properties.getTrustStorePassword());
            cf.setTrustStoreType(properties.getKeyStoreType());
        }
        cf.setWatchTopicAdvisories(true);

        ActiveMQConnection conn = (ActiveMQConnection) cf.createConnection();
        Session ses = conn.createSession();
        conn.start();
        this.connection = conn;
        this.session = ses;
        log.info("BrokerUtil: Opened connection to Message broker: {}", properties.getBrokerURL());
    }

    private void closeBrokerConnection() throws JMSException {
        producers.clear();
        consumers.clear();
        listeners.clear();
        if (session!=null) this.session.close();
        if (connection!=null && ! connection.isClosed() && ! connection.isClosing())
            this.connection.close();
        this.session = null;
        this.connection = null;
        log.info("BrokerUtil: Closed connection to Message broker: {}", properties.getBrokerURL());
    }

    public void connectionHealthCheck() {
        log.debug("BrokerUtil: Checking connection health: {}", properties.getBrokerURL());
        boolean error = false;
        try {
            sendMessage(properties.getHealthCheckTopic(), Map.of("ping", "pong"));
        } catch (Exception e) {
            log.warn("BrokerUtil: EXCEPTION during connection health: ", e);
            error = true;
        }

        if (error) {
            // Close connection
            try {
                closeBrokerConnection();
            } catch (JMSException e) {
                log.error("BrokerUtil: ERROR while closing connection to Message broker: ", e);
                this.session = null;
                this.connection = null;
            }

            // Try to re-connect
            taskScheduler.schedule(this::initializeBrokerConnection,
                    Instant.now().plusSeconds(1));
        }
    }

    // ------------------------------------------------------------------------

    public void sendMessage(@NonNull String topic, @NonNull Map<String,? extends Object> message) {
         sendMessage(topic, message, false);
    }

    public void sendMessage(@NonNull String topic, @NonNull Map<String,? extends Object> message, boolean encrypt) {
        String jsonMessage = null;
        try {
            jsonMessage = objectMapper.writer().writeValueAsString(message);
        } catch (JsonProcessingException e) {
            log.error("Could not parse json message "+e);
        }
        sendMessage(topic, jsonMessage, encrypt);
    }

    public void sendMessage(@NonNull String topic, @NonNull String message) throws JMSException, JsonProcessingException {
        sendMessage(topic, message, false);
    }

    public void sendMessage(@NonNull String topic, @NonNull String message, boolean encrypt) {
        ActiveMQTextMessage textMessage = new ActiveMQTextMessage();
        if (encrypt) {
            sendMessage(topic, Map.of("encrypted-message", encryptionUtil.encryptText(message)), false);
        } else {
            
            boolean message_sent = false;
            while (!message_sent) {
                boolean error = false;
                try {
                    textMessage.setText(message);
                    getOrCreateProducer(topic).send(textMessage);
                    message_sent=true;
                } catch (Exception e) {
                    log.warn("BrokerUtil: EXCEPTION during sending message: ", e);
                    error = true;
                }

                if (error) {
                    // Close connection
                    try {
                        closeBrokerConnection();
                    } catch (Exception e) {
                        log.error("BrokerUtil: ERROR while closing connection to Message broker: ", e);
                        this.session = null;
                        this.connection = null;
                    }

                    // Try to re-connect
                    taskScheduler.schedule(this::initializeBrokerConnection,
                            Instant.now().plusSeconds(1));
                    try {
                        Thread.sleep(1000L);
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------------

    public MessageProducer getOrCreateProducer(@NonNull String topic) throws JMSException {
        MessageProducer producer = producers.get(topic);
        if (producer==null) { producer = createProducer(topic); producers.put(topic, producer); }
        return producer;
    }

    public MessageConsumer getOrCreateConsumer(@NonNull String topic) throws JMSException {
        MessageConsumer consumer = consumers.get(topic);
        if (consumer==null) { consumer = createConsumer(topic); consumers.put(topic, consumer); }
        return consumer;
    }

    public MessageProducer createProducer(@NonNull String topic) throws JMSException {
        if (session==null) initializeBrokerConnection();
        return session.createProducer(new ActiveMQTopic(topic));
    }

    public MessageConsumer createConsumer(@NonNull String topic) throws JMSException {
        if (session==null) initializeBrokerConnection();
        return session.createConsumer(new ActiveMQTopic(topic));
    }

    // ------------------------------------------------------------------------

    public void subscribe(@NonNull String topic, @NonNull Listener listener) throws JMSException {
        Set<Listener> set = listeners.computeIfAbsent(topic, t -> new HashSet<>());
        if (set.contains(listener)) return;
        set.add(listener);
        getOrCreateConsumer(topic).setMessageListener(this);
    }

    @Override
    public void onMessage(Message message) {
        try {
            log.debug("BrokerUtil: Received a message from broker: {}", message);
            if (message instanceof ActiveMQTextMessage textMessage) {
                String payload = textMessage.getText();
                log.trace("BrokerUtil: Message payload: {}", payload);

                TypeReference<Map<String,Object>> typeRef = new TypeReference<>() { };
                Object obj = objectMapper.readerFor(typeRef).readValue(payload);

                if (obj instanceof Map<?,?> dataMap) {
                    String topic = ((ActiveMQTextMessage) message).getDestination().getPhysicalName();
                    // Print response messages except the EMS node status reports (_ui_instance_info, _client_metrics)
                    if (StringUtils.isNotBlank(topic)
                            && ! topic.equals(properties.getDeviceStatusMonitorTopic())
                            && ! topic.equals(properties.getDeviceMetricsMonitorTopic()))
                    {
                        log.warn("BrokerUtil: Received a new message:   topic: {}", topic);
                        log.warn("BrokerUtil: Received a new message: payload: {}", dataMap);
                    }
                    handlePayload(topic, dataMap);
                } else {
                    log.warn("BrokerUtil: Message payload is not recognized. Expected Map but got: type={}, object={}", obj.getClass().getName(), obj);
                }
            } else {
                log.debug("BrokerUtil: Message type is not supported: {}", message);
            }
        } catch (Exception e) {
            log.warn("BrokerUtil: ERROR while processing message: {}\nException: ", message, e);
        }
    }

    private void handlePayload(@NonNull String topic, @NonNull Map<?, ?> dataMap) {
        // Decrypt message (if encrypted)
        Object encryptedMessage = dataMap.get("encrypted-message");
        if (encryptedMessage!=null)
            dataMap = encryptionUtil.decryptMap(encryptedMessage.toString());

        // Dispatch message to listeners
        Set<Listener> set = listeners.get(topic);
        if (set==null) return;
        final Map<?, ?> immutableMap = Collections.unmodifiableMap(dataMap);
        set.forEach(l -> l.onMessage(immutableMap));
    }

    public interface Listener {
        void onMessage(Map map);
    }
}
