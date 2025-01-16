
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
        log.error(">>>>>>>>  BrokerUtil: afterPropertiesSet: BEGIN");
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
        taskScheduler.scheduleAtFixedRate(() ->
                        log.error(">>>>>>>>  BrokerUtil: current state:\n\tconsumers={}\n\tproducers={}\n\tlisteners={}\n",
                                consumers, producers, listeners),
                Instant.now().plusSeconds(5L),
                Duration.ofSeconds(5L));
    }

    private synchronized void initializeBrokerConnection() {
        try {
            // Open new connection to broker
            log.error(">>>>>>>>  BrokerUtil: initializeBrokerConnection: BEGIN: Calling openBrokerConnection()");
            openBrokerConnection();

            // Re-subscribe consumers
            log.error(">>>>>>>>  BrokerUtil: initializeBrokerConnection: Re-subscribing consumers to topics: {}", consumers.keySet());
            Set<String> consumerTopics = new HashSet<>(consumers.keySet());
            consumers.clear();
            consumerTopics.forEach((topic) -> {
                try {
                    log.error(">>>>>>>>  BrokerUtil: initializeBrokerConnection: ....Re-subscribing consumer to topic: {}", topic);
                    getOrCreateConsumer(topic).setMessageListener(this);
                    log.error(">>>>>>>>  BrokerUtil: initializeBrokerConnection: ....OK Re-subscribing consumer to topic: {}", topic);
                } catch (JMSException e) {
                    log.error(">>>>>>>>  BrokerUtil: initializeBrokerConnection: ....ERROR while Re-subscribing consumer to topic: {}", topic);
                }
            });

            // Re-create producers
            log.error(">>>>>>>>  BrokerUtil: initializeBrokerConnection: Re-creating producers for topics: {}", producers.keySet());
            Set<String> producerTopics = new HashSet<>(producers.keySet());
            producers.clear();
            producerTopics.forEach((topic) -> {
                try {
                    log.error(">>>>>>>>  BrokerUtil: initializeBrokerConnection: ....Re-creating producer for topic: {}", topic);
                    getOrCreateProducer(topic);
                    log.error(">>>>>>>>  BrokerUtil: initializeBrokerConnection: ....OK Re-creating producer for topic: {}", topic);
                } catch (JMSException e) {
                    log.error(">>>>>>>>  BrokerUtil: initializeBrokerConnection: ....ERROR while Re-creating producer for topic: {}", topic);
                }
            });

            log.error(">>>>>>>>  BrokerUtil: initializeBrokerConnection: END");
        } catch (Exception e) {
            log.error("BrokerUtil: ERROR while opening connection to Message broker: ", e);
            log.error("BrokerUtil: Will retry calling 'initializeBrokerConnection' in {} seconds: ", properties.getSubscriptionRetryDelay());
            taskScheduler.schedule(this::initializeBrokerConnection,
                    Instant.now().plusSeconds(properties.getSubscriptionRetryDelay()));
        }
    }

    private void openBrokerConnection() throws Exception {
        log.error(">>>>>>>>  BrokerUtil: openBrokerConnection: BEGIN");

        if (connection!=null && connection.isStarted()) {
            log.error(">>>>>>>>  BrokerUtil: openBrokerConnection: END: Connection has already started!");
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
        log.error(">>>>>>>>  BrokerUtil: openBrokerConnection: Starting connection: {}", conn);
        conn.start();
        this.connection = conn;
        this.session = ses;

        while (! conn.isStarted()) {
            log.error(">>>>>>>>  BrokerUtil: openBrokerConnection: Waiting connection to start...");
            try { Thread.sleep(1000L); } catch (InterruptedException e) { log.warn(">>>>>>>>  BrokerUtil: openBrokerConnection: Interrupted!"); }
        }
        log.error(">>>>>>>>  BrokerUtil: openBrokerConnection: connection.isStarted={}", conn.isStarted());

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
        log.error(">>>>>>>>  BrokerUtil: sendMessage: BEGIN: topic={}, encrypt={}, message={}", topic, encrypt, message);
        ActiveMQTextMessage textMessage = new ActiveMQTextMessage();
        if (encrypt) {
            log.error(">>>>>>>>  BrokerUtil: sendMessage: ENCRYPT: topic={}, encrypt={}, message={}", topic, encrypt, message);
            sendMessage(topic, Map.of("encrypted-message", encryptionUtil.encryptText(message)), false);
            log.error(">>>>>>>>  BrokerUtil: sendMessage: ENCRYPT-END: topic={}, encrypt={}, message={}", topic, encrypt, message);
        } else {
            log.error(">>>>>>>>  BrokerUtil: sendMessage: PLAIN: topic={}, encrypt={}, message={}", topic, encrypt, message);

            boolean message_sent = false;
            while (!message_sent) {
                boolean error = false;
                try {
                    log.error(">>>>>>>>  BrokerUtil: sendMessage: PLAIN: Sending....  topic={}, encrypt={}, message={}", topic, encrypt, message);
                    textMessage.setText(message);
                    getOrCreateProducer(topic).send(textMessage);
                    message_sent = true;
                    log.error(">>>>>>>>  BrokerUtil: sendMessage: PLAIN: Sending....OK  topic={}, encrypt={}, message={}", topic, encrypt, message);
                } catch (Exception e) {
                    log.warn("BrokerUtil: EXCEPTION during sending message: ", e);
                    error = true;
                }

                if (error) {
                    log.error(">>>>>>>>  BrokerUtil: sendMessage: PLAIN: ERROR!!!!!  topic={}, encrypt={}, message={}", topic, encrypt, message);
                    // Close connection
                    try {
                        log.error(">>>>>>>>  BrokerUtil: sendMessage: PLAIN: Closing conn....  topic={}, encrypt={}, message={}", topic, encrypt, message);
                        closeBrokerConnection();
                        log.error(">>>>>>>>  BrokerUtil: sendMessage: PLAIN: Closed conn....  topic={}, encrypt={}, message={}", topic, encrypt, message);
                    } catch (Exception e) {
                        log.error("BrokerUtil: ERROR while closing connection to Message broker: ", e);
                        this.session = null;
                        this.connection = null;
                    }

                    // Try to re-connect
                    log.error(">>>>>>>>  BrokerUtil: sendMessage: PLAIN: RECONNECTING....  topic={}, encrypt={}, message={}", topic, encrypt, message);
                    initializeBrokerConnection();

                    // Wait until
                    log.error(">>>>>>>>  BrokerUtil: sendMessage: PLAIN: DELAYING 2 seconds....  topic={}, encrypt={}, message={}", topic, encrypt, message);
                    try {
                        Thread.sleep(2000L);
                    } catch (InterruptedException e) {
                        log.warn(">>>>>>>>  BrokerUtil: sendMessage: PLAIN: Interrupted DELAYING 2 seconds....  topic={}, encrypt={}, message={}", topic, encrypt, message);
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
        log.error(">>>>>>>>  BrokerUtil: getOrCreateConsumer: BEGIN: topic={}", topic);
        MessageConsumer consumer = consumers.get(topic);
        log.error(">>>>>>>>  BrokerUtil: getOrCreateConsumer: MID: topic={}, consumer={}", topic, consumer);
        if (consumer == null) {
            consumer = createConsumer(topic);
            log.error(">>>>>>>>  BrokerUtil: getOrCreateConsumer: NEW CONSUMER: topic={}, consumer={}", topic, consumer);
            consumers.put(topic, consumer);
        }
        return consumer;
    }

    public MessageProducer createProducer(@NonNull String topic) throws JMSException {
        log.error(">>>>>>>>  BrokerUtil: createProducer: BEGIN: topic={}, session={}", topic, session);
        if (session == null) initializeBrokerConnection();
        log.error(">>>>>>>>  BrokerUtil: createProducer: MID: topic={}, session={}", topic, session);
        return session.createProducer(new ActiveMQTopic(topic));
    }

    public MessageConsumer createConsumer(@NonNull String topic) throws JMSException {
        log.error(">>>>>>>>  BrokerUtil: createConsumer: BEGIN: topic={}, session={}", topic, session);
        if (session == null) initializeBrokerConnection();
        log.error(">>>>>>>>  BrokerUtil: createConsumer: MID: topic={}, session={}", topic, session);
        return session.createConsumer(new ActiveMQTopic(topic));
    }

    // ------------------------------------------------------------------------

    public void subscribe(@NonNull String topic, @NonNull Listener listener) throws JMSException {
        log.error(">>>>>>>>  BrokerUtil: subscribe: BEGIN: topic={}, listener={}", topic, listener);
        Set<Listener> set = listeners.computeIfAbsent(topic, t -> new HashSet<>());
        if (set.contains(listener)) return;
        log.error(">>>>>>>>  BrokerUtil: subscribe: ADDING LISTENER: topic={}, listener={}", topic, listener);
        set.add(listener);
        getOrCreateConsumer(topic).setMessageListener(this);
        log.error(">>>>>>>>  BrokerUtil: subscribe: END: topic={}, listener={}", topic, listener);
    }

    @Override
    public void onMessage(Message message) {
        try {
            log.error(">>>>>>>>  BrokerUtil: onMessage: BEGIN: message={}", message);
            log.debug("BrokerUtil: Received a message from broker: {}", message);
            if (message instanceof ActiveMQTextMessage textMessage) {
                String payload = textMessage.getText();
                log.error(">>>>>>>>  BrokerUtil: onMessage: AMQ-TEXT-MESG: payload={}", payload);
                log.trace("BrokerUtil: Message payload: {}", payload);

                TypeReference<Map<String, Object>> typeRef = new TypeReference<>() {
                };
                Object obj = objectMapper.readerFor(typeRef).readValue(payload);
                log.error(">>>>>>>>  BrokerUtil: onMessage: OBJ-from-TEXT: obj={}", obj);

                if (obj instanceof Map<?, ?> dataMap) {
                    log.error(">>>>>>>>  BrokerUtil: onMessage: OBJ-is-MAP: dataMap={}", dataMap);
                    String topic = ((ActiveMQTextMessage) message).getDestination().getPhysicalName();
                    log.error(">>>>>>>>  BrokerUtil: onMessage: DESTINATION: topic={}", topic);
                    // Print response messages except the EMS node status reports (_ui_instance_info, _client_metrics)
                    if (StringUtils.isNotBlank(topic)
                            && !topic.equals(properties.getDeviceStatusMonitorTopic())
                            && !topic.equals(properties.getDeviceMetricsMonitorTopic()))
                    {
                        log.warn("BrokerUtil: Received a new message:   topic: {}", topic);
                        log.warn("BrokerUtil: Received a new message: payload: {}", dataMap);
                    }
                    handlePayload(topic, dataMap);
                } else {
                    log.error(">>>>>>>>  BrokerUtil: onMessage: NOT-a-MAP: {} -- {}", obj!=null?obj.getClass().getName():null, obj);
                    log.warn("BrokerUtil: Message payload is not recognized. Expected Map but got: type={}, object={}", obj.getClass().getName(), obj);
                }
            } else {
                log.error(">>>>>>>>  BrokerUtil: onMessage: NOT-TEXT-MESG: {} -- {}", message.getClass().getName(), message);
                log.debug("BrokerUtil: Message type is not supported: {}", message);
            }
        } catch (Exception e) {
            log.error(">>>>>>>>  BrokerUtil: onMessage: EXCEPTION: ", e);
            log.warn("BrokerUtil: ERROR while processing message: {}\nException: ", message, e);
        }
    }

    private void handlePayload(@NonNull String topic, @NonNull Map<?, ?> dataMap) {
        log.error(">>>>>>>>  BrokerUtil: handlePayload: BEGIN: topic={}, map={}", topic, dataMap);
        // Decrypt message (if encrypted)
        Object encryptedMessage = dataMap.get("encrypted-message");
        log.error(">>>>>>>>  BrokerUtil: handlePayload: encrypted={}", encryptedMessage);
        if (encryptedMessage != null)
            dataMap = encryptionUtil.decryptMap(encryptedMessage.toString());
        log.error(">>>>>>>>  BrokerUtil: handlePayload: DATA-MAP={}", dataMap);

        // Dispatch message to listeners
        Set<Listener> set = listeners.get(topic);
        log.error(">>>>>>>>  BrokerUtil: handlePayload: listeners: {}", set);
        if (set == null) return;
        final Map<?, ?> immutableMap = Collections.unmodifiableMap(dataMap);
        set.forEach(l -> {
            log.error(">>>>>>>>  BrokerUtil: handlePayload: ....CALLING LISTENER: {} -- mao: {}", l, immutableMap);
            l.onMessage(immutableMap);
        });
        log.error(">>>>>>>>  BrokerUtil: handlePayload: END");
    }

    public interface Listener {
        void onMessage(Map map);
    }
}
