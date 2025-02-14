
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
    private final Map<String, MessageProducer> producers = new HashMap<>();
    private final Map<String, MessageConsumer> consumers = new HashMap<>();
    private final Map<String, Set<Listener>> listeners = new HashMap<>();
    private ActiveMQConnection connection;
    private Session session;

    @Override
    public void afterPropertiesSet() throws Exception {
        log.trace("BrokerUtil: afterPropertiesSet: BEGIN");
        // Initialize broker connection
        taskScheduler.schedule(this::initializeBrokerConnection,
                Instant.now().plusSeconds(properties.getSubscriptionStartupDelay()));

        // Initialize connection health check
        int healthCheckPeriod = properties.getConnectionHealthCheckPeriod();
        if (healthCheckPeriod > 0) {
            taskScheduler.scheduleAtFixedRate(this::connectionHealthCheck,
                    Instant.now().plusSeconds(properties.getSubscriptionStartupDelay()),
                    Duration.ofSeconds(healthCheckPeriod));
            log.info("BrokerUtil: Enabled connection health check: period={}s", healthCheckPeriod);
        }

        // Debug print of BrokerUtil state
        /*taskScheduler.scheduleAtFixedRate(() ->
                        log.debug("BrokerUtil: current state:\n\tconsumers={}\n\tproducers={}\n\tlisteners={}\n",
                                consumers, producers, listeners),
                Instant.now().plusSeconds(5L),
                Duration.ofSeconds(60L));*/
    }

    private synchronized void initializeBrokerConnection() {
        try {
            // Open new connection to broker
            log.info("BrokerUtil: Initializing broker connection");
            log.trace("BrokerUtil: initializeBrokerConnection: BEGIN: Calling openBrokerConnection()");
            openBrokerConnection();

            // Re-subscribe consumers
            log.info("BrokerUtil: Re-subscribing consumers to topics: {}", consumers.keySet());
            Set<String> consumerTopics = new HashSet<>(consumers.keySet());
            consumers.clear();
            consumerTopics.forEach((topic) -> {
                try {
                    log.trace("BrokerUtil: initializeBrokerConnection: ....Re-subscribing consumer to topic: {}", topic);
                    getOrCreateConsumer(topic).setMessageListener(this);
                    log.trace("BrokerUtil: initializeBrokerConnection: ....SUCCESS Re-subscribing consumer to topic: {}", topic);
                } catch (JMSException e) {
                    log.error("BrokerUtil: initializeBrokerConnection: ....ERROR while Re-subscribing consumer to topic: {}", topic);
                }
            });

            // Re-create producers
            log.info("BrokerUtil: Re-creating producers for topics: {}", producers.keySet());
            Set<String> producerTopics = new HashSet<>(producers.keySet());
            producers.clear();
            producerTopics.forEach((topic) -> {
                try {
                    log.trace("BrokerUtil: initializeBrokerConnection: ....Re-creating producer for topic: {}", topic);
                    getOrCreateProducer(topic);
                    log.trace("BrokerUtil: initializeBrokerConnection: ....SUCCESS Re-creating producer for topic: {}", topic);
                } catch (JMSException e) {
                    log.warn("BrokerUtil: initializeBrokerConnection: ....ERROR while Re-creating producer for topic: {}", topic);
                }
            });

            log.trace("BrokerUtil: initializeBrokerConnection: END");
        } catch (Exception e) {
            log.error("BrokerUtil: ERROR while opening connection to Message broker: ", e);
            log.error("BrokerUtil: Will retry calling 'initializeBrokerConnection' in {} seconds: ", properties.getSubscriptionRetryDelay());
            taskScheduler.schedule(this::initializeBrokerConnection,
                    Instant.now().plusSeconds(properties.getSubscriptionRetryDelay()));
        }
    }

    private void openBrokerConnection() throws Exception {
        log.trace("BrokerUtil: openBrokerConnection: BEGIN");

        if (connection!=null && connection.isStarted()) {
            log.warn("BrokerUtil: Connection has already started!");
            log.trace("BrokerUtil: openBrokerConnection: END: Connection has already started!");
            return;
        }

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
        log.trace("BrokerUtil: openBrokerConnection: Starting connection: {}", conn);
        conn.start();
        this.connection = conn;
        this.session = ses;

        long startTm = System.currentTimeMillis();
        while (! conn.isStarted()) {
            if (System.currentTimeMillis() - startTm > 60*1000L)
                throw new RuntimeException("Waiting broker connection to start for too long");
            log.trace("BrokerUtil: openBrokerConnection: Waiting connection to start...");
            try { Thread.sleep(1000L); } catch (InterruptedException e) { log.warn("BrokerUtil: openBrokerConnection: Interrupted!"); }
        }
        log.trace("BrokerUtil: openBrokerConnection: connection.isStarted={}", conn.isStarted());

        log.info("BrokerUtil: Opened connection to Message broker: {}", properties.getBrokerURL());
    }

    private void closeBrokerConnection() throws JMSException {
        /*producers.clear();
        consumers.clear();
        listeners.clear();*/
        if (session != null) this.session.close();
        if (connection != null && !connection.isClosed() && !connection.isClosing())
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

    public void sendMessage(@NonNull String topic, @NonNull Map<String, ? extends Object> message) {
        sendMessage(topic, message, false);
    }

    public void sendMessage(@NonNull String topic, @NonNull Map<String, ? extends Object> message, boolean encrypt) {
        String jsonMessage = null;
        try {
            jsonMessage = objectMapper.writer().writeValueAsString(message);
        } catch (JsonProcessingException e) {
            log.error("Could not parse json message " + e);
        }
        sendMessage(topic, jsonMessage, encrypt);
    }

    public void sendMessage(@NonNull String topic, @NonNull String message) throws JMSException, JsonProcessingException {
        sendMessage(topic, message, false);
    }

    public void sendMessage(@NonNull String topic, @NonNull String message, boolean encrypt) {
        log.trace("BrokerUtil: sendMessage: BEGIN: topic={}, encrypt={}, message={}", topic, encrypt, message);
        ActiveMQTextMessage textMessage = new ActiveMQTextMessage();
        if (encrypt) {
            log.debug("BrokerUtil: sendMessage: ENCRYPT: topic={}, encrypt={}, message={}", topic, encrypt, message);
            sendMessage(topic, Map.of("encrypted-message", encryptionUtil.encryptText(message)), false);
            log.trace("BrokerUtil: sendMessage: ENCRYPT-END: topic={}, encrypt={}, message={}", topic, encrypt, message);
        } else {
            log.trace("BrokerUtil: sendMessage: PLAIN: topic={}, encrypt={}, message={}", topic, encrypt, message);

            boolean message_sent = false;
            while (!message_sent) {
                boolean error = false;
                try {
                    log.trace("BrokerUtil: sendMessage: PLAIN: Sending....  topic={}, encrypt={}, message={}", topic, encrypt, message);
                    textMessage.setText(message);
                    getOrCreateProducer(topic).send(textMessage);
                    message_sent = true;
                    log.trace("BrokerUtil: sendMessage: PLAIN: Sending....OK  topic={}, encrypt={}, message={}", topic, encrypt, message);
                } catch (Exception e) {
                    log.warn("BrokerUtil: EXCEPTION during sending message: ", e);
                    error = true;
                }

                if (error) {
                    log.warn("BrokerUtil: Error while sending message to topic: {}", topic);
                    log.trace("BrokerUtil: sendMessage: PLAIN: ERROR!!!!!  topic={}, encrypt={}, message={}", topic, encrypt, message);
                    // Close connection
                    try {
                        log.trace("BrokerUtil: sendMessage: PLAIN: Closing conn....  topic={}, encrypt={}, message={}", topic, encrypt, message);
                        closeBrokerConnection();
                        log.trace("BrokerUtil: sendMessage: PLAIN: Closed conn....  topic={}, encrypt={}, message={}", topic, encrypt, message);
                    } catch (Exception e) {
                        log.error("BrokerUtil: ERROR while closing connection to Message broker: ", e);
                        this.session = null;
                        this.connection = null;
                    }

                    // Try to re-connect
                    log.trace("BrokerUtil: sendMessage: PLAIN: RECONNECTING....  topic={}, encrypt={}, message={}", topic, encrypt, message);
                    initializeBrokerConnection();

                    // Wait until
                    log.trace("BrokerUtil: sendMessage: PLAIN: DELAYING 2 seconds....  topic={}, encrypt={}, message={}", topic, encrypt, message);
                    try {
                        Thread.sleep(2000L);
                    } catch (InterruptedException e) {
                        log.warn("BrokerUtil: sendMessage: PLAIN: Interrupted DELAYING 2 seconds....  topic={}, encrypt={}, message={}", topic, encrypt, message);
                        throw new RuntimeException(e);
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------------

    public MessageProducer getOrCreateProducer(@NonNull String topic) throws JMSException {
        MessageProducer producer = producers.get(topic);
        if (producer == null) {
            producer = createProducer(topic);
            producers.put(topic, producer);
        }
        return producer;
    }

    public MessageConsumer getOrCreateConsumer(@NonNull String topic) throws JMSException {
        log.trace("BrokerUtil: getOrCreateConsumer: BEGIN: topic={}", topic);
        MessageConsumer consumer = consumers.get(topic);
        log.debug("BrokerUtil: getOrCreateConsumer: MID: topic={}, consumer={}", topic, consumer);
        if (consumer == null) {
            consumer = createConsumer(topic);
            log.trace("BrokerUtil: getOrCreateConsumer: NEW CONSUMER: topic={}, consumer={}", topic, consumer);
            consumers.put(topic, consumer);
        }
        return consumer;
    }

    public MessageProducer createProducer(@NonNull String topic) throws JMSException {
        log.trace("BrokerUtil: createProducer: BEGIN: topic={}, session={}", topic, session);
        if (session == null) initializeBrokerConnection();
        log.debug("BrokerUtil: createProducer: New producer for: topic={}, session={}", topic, session);
        return session.createProducer(new ActiveMQTopic(topic));
    }

    public MessageConsumer createConsumer(@NonNull String topic) throws JMSException {
        log.trace("BrokerUtil: createConsumer: BEGIN: topic={}, session={}", topic, session);
        if (session == null) initializeBrokerConnection();
        log.debug("BrokerUtil: createConsumer: New consumer for: topic={}, session={}", topic, session);
        return session.createConsumer(new ActiveMQTopic(topic));
    }

    // ------------------------------------------------------------------------

    public void subscribe(@NonNull String topic, @NonNull Listener listener) throws JMSException {
        log.trace("BrokerUtil: subscribe: BEGIN: topic={}, listener={}", topic, listener);
        Set<Listener> set = listeners.computeIfAbsent(topic, t -> new HashSet<>());
        if (set.contains(listener)) return;
        log.trace("BrokerUtil: subscribe: ADDING LISTENER: topic={}, listener={}", topic, listener);
        set.add(listener);
        getOrCreateConsumer(topic).setMessageListener(this);
        log.trace("BrokerUtil: subscribe: END: topic={}, listener={}", topic, listener);
    }

    @Override
    public void onMessage(Message message) {
        try {
            log.debug("BrokerUtil: Received a message from broker: {}", message);
            if (message instanceof ActiveMQTextMessage textMessage) {
                String payload = textMessage.getText();
                log.trace("BrokerUtil: Message payload (TEXT): {}", payload);

                TypeReference<Map<String, Object>> typeRef = new TypeReference<>() {
                };
                Object obj = objectMapper.readerFor(typeRef).readValue(payload);
                log.trace("BrokerUtil: Message payload (OBJ):  {}", obj);

                if (obj instanceof Map<?, ?> dataMap) {
                    log.trace("BrokerUtil: Payload map: {}", dataMap);
                    String topic = ((ActiveMQTextMessage) message).getDestination().getPhysicalName();
                    log.trace("BrokerUtil: Topic={}", topic);
                    // Print response messages except the EMS node status reports (_ui_instance_info, _client_metrics)
                    if (StringUtils.isNotBlank(topic)
                            && !topic.equals(properties.getDeviceStatusMonitorTopic())
                            && !topic.equals(properties.getDeviceMetricsMonitorTopic()))
                    {
                        log.trace("BrokerUtil: Received a new message:   topic: {}", topic);
                        log.trace("BrokerUtil: Received a new message: payload: {}", dataMap);
                    }
                    handlePayload(topic, dataMap);
                } else {
                    log.warn("BrokerUtil: Message payload is not recognized. Expected Map but got: type={}, object={}",
                            obj!=null ? obj.getClass().getName() : null, obj);
                }
            } else {
                log.debug("BrokerUtil: Message type is not supported: type={}, message={}",
                        message!=null ? message.getClass().getName() : null, message);
            }
        } catch (Exception e) {
            log.warn("BrokerUtil: ERROR while processing message: {}\nException: ", message, e);
        }
    }

    private void handlePayload(@NonNull String topic, @NonNull Map<?, ?> dataMap) {
        log.trace("BrokerUtil: handlePayload: BEGIN: topic={}, map={}", topic, dataMap);
        // Decrypt message (if encrypted)
        Object encryptedMessage = dataMap.get("encrypted-message");
        log.trace("BrokerUtil: handlePayload: encrypted={}", encryptedMessage);
        if (encryptedMessage != null)
            dataMap = encryptionUtil.decryptMap(encryptedMessage.toString());
        log.trace("BrokerUtil: handlePayload: DATA-MAP={}", dataMap);

        // Dispatch message to listeners
        Set<Listener> set = listeners.get(topic);
        log.trace("BrokerUtil: handlePayload: listeners: {}", set);
        if (set == null) return;
        final Map<?, ?> immutableMap = Collections.unmodifiableMap(dataMap);
        set.forEach(l -> {
            log.trace("BrokerUtil: handlePayload: ....CALLING LISTENER: {} -- mao: {}", l, immutableMap);
            l.onMessage(immutableMap);
        });
        log.trace("BrokerUtil: handlePayload: END");
    }

    public interface Listener {
        void onMessage(Map map);
    }
}
