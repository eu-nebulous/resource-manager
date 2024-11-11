package eu.nebulous.resource.discovery.broker_communication;

import eu.nebulouscloud.exn.Connector;
import eu.nebulouscloud.exn.core.Consumer;
import eu.nebulouscloud.exn.core.Context;
import eu.nebulouscloud.exn.core.Handler;
import eu.nebulouscloud.exn.settings.StaticExnConfig;
import lombok.extern.slf4j.Slf4j;
import org.apache.qpid.protonj2.client.Message;
import org.json.simple.JSONValue;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;

import static eu.nebulous.resource.discovery.broker_communication.BrokerPublisher.EMPTY;

@Slf4j
public class BrokerSubscriber {

    private AtomicBoolean stop_signal = new AtomicBoolean();
    
    private class MessageProcessingHandler extends Handler {
        private BrokerSubscriptionDetails broker_details;
        private static final BiFunction temporary_function = (Object o, Object o2) -> {
            //System.out.println("");
            log.info("REPLACE_TEMPORARY_HANDLING_FUNCTIONALITY");
            return "IN_PROCESSING";
        };
        private BiFunction<BrokerSubscriptionDetails, String, String> processing_function;

        @Override
        public void onMessage(String key, String address, Map body, Message message, Context context) {
            log.info("Handling message for address " + address);
            processing_function.apply(broker_details, JSONValue.toJSONString(body));
        }

        public MessageProcessingHandler(BrokerSubscriptionDetails broker_details) {
            this.broker_details = broker_details;
            this.processing_function = temporary_function;
        }

        public MessageProcessingHandler(BiFunction<BrokerSubscriptionDetails, String, String> biFunction, BrokerSubscriptionDetails broker_details) {
            this.broker_details = broker_details;
            this.processing_function = biFunction;
        }

        public BiFunction getProcessing_function() {
            return processing_function;
        }

        public void setProcessing_function(BiFunction processing_function) {
            this.processing_function = processing_function;
        }
    }

    private static HashMap<String, HashSet<String>> broker_and_topics_to_subscribe_to = new HashMap<>();
    private static HashMap<String, HashMap<String, Consumer>> active_consumers_per_topic_per_broker_ip = new HashMap<>();
    private static HashMap<String, Connector> current_connectors = new HashMap<>();
    ArrayList<Consumer> consumers = new ArrayList<>();
    private String topic;
    private String broker_ip;
    private int broker_port;
    private String brokerUsername;
    private String brokerPassword;
    BrokerSubscriptionDetails broker_details;

    public BrokerSubscriber(String topic, String broker_ip, int broker_port, String brokerUsername, String brokerPassword, String amqLibraryConfigurationLocation, String application_name) {
        stop_signal.set(false);
        boolean able_to_initialize_BrokerSubscriber = topic != null && broker_ip != null && brokerUsername != null && brokerPassword != null && !topic.equals(EMPTY) && !broker_ip.equals(EMPTY) && !brokerUsername.equals(EMPTY) && !brokerPassword.equals(EMPTY);

        if (!able_to_initialize_BrokerSubscriber) {
            try {
                throw new Exception("Unable to initialize Subscriber");
            } catch (Exception e) {
                String message = "Topic is " + topic + " broker ip is " + broker_ip + " broker username/pass are " + brokerUsername + "," + brokerPassword;

                log.info(message);
                throw new RuntimeException(e);
            }
        }
        broker_details = new BrokerSubscriptionDetails(broker_ip, broker_port, brokerUsername, brokerPassword, application_name, topic);
        boolean subscriber_configuration_changed;
        if (!broker_and_topics_to_subscribe_to.containsKey(broker_ip)) {
            HashSet<String> topics_to_subscribe_to = new HashSet<>();
            //topics_to_subscribe_to.add(realtime_metric_topic_name);
            //topics_to_subscribe_to.add(forecasted_metric_topic_name);
            //topics_to_subscribe_to.add();
            topics_to_subscribe_to.add(topic);
            broker_and_topics_to_subscribe_to.put(broker_ip, new HashSet<>());
            active_consumers_per_topic_per_broker_ip.put(broker_ip, new HashMap<>());
            broker_and_topics_to_subscribe_to.get(broker_ip).add(topic);

            subscriber_configuration_changed = true;
        } else {
            if (!broker_and_topics_to_subscribe_to.get(broker_ip).contains(topic)) {
                broker_and_topics_to_subscribe_to.get(broker_ip).add(topic);
                subscriber_configuration_changed = true;
            } else {
                subscriber_configuration_changed = false;
            }
        }
        if (subscriber_configuration_changed) {
            Consumer current_consumer;
            if (application_name != null && !application_name.equals(EMPTY)) { //Create a consumer for one application
                log.info("APP level subscriber " + topic);
                current_consumer = new Consumer(topic, topic, new MessageProcessingHandler(broker_details), application_name, true, true);
            } else { //Allow the consumer to get information from any publisher
                current_consumer = new Consumer(topic, topic, new MessageProcessingHandler(broker_details), true, true);
                log.info("HIGH level subscriber " + topic);
            }
            active_consumers_per_topic_per_broker_ip.get(broker_ip).put(topic, current_consumer);

            this.topic = topic;
            this.broker_ip = broker_ip;
            this.broker_port = broker_port;
            this.brokerUsername = brokerUsername;
            this.brokerPassword = brokerPassword;
            add_topic_consumer_to_broker_connector(current_consumer);
        }
    }

    /**
     * This method updates the global connector of Resource manager to the AMQP server, by adding support for one more component
     */
    private void add_topic_consumer_to_broker_connector(Consumer new_consumer) {
        if (current_connectors.get(broker_ip) != null) {
            //current_connectors.get(broker_ip).stop(consumers,new ArrayList<>());
            current_connectors.get(broker_ip).stop();
        } 
        if (consumers.isEmpty()){
            consumers = new ArrayList<>();
        }
        boolean do_not_add_new_consumer = false;
        for (Consumer consumer : consumers) {
            if (Objects.equals(consumer.linkAddress, new_consumer.linkAddress)) {
                do_not_add_new_consumer = true;
            }
        }
        if(!do_not_add_new_consumer) {
            consumers.add(new_consumer);
        }
            Connector connector = new Connector("resource_manager",
                    new CustomConnectorHandler() {
                    },
                    List.of(),
                    consumers,
                    false,
                    false,
                    new StaticExnConfig(
                            broker_ip,
                            broker_port,
                            brokerUsername,
                            brokerPassword,
                            60,
                            EMPTY
                    )
            );
            connector.start();
            current_connectors.put(broker_ip, connector);
    }

    private void remove_topic_from_broker_connector(String topic_key) {
        if (current_connectors.get(broker_ip) != null) {
            //current_connectors.get(broker_ip).remove_consumer_with_key(topic_key);
        }
    }
    
    public int subscribe (BiFunction function, String application_name) {
        return subscribe(function,application_name,stop_signal);
    }
    
    public int subscribe(BiFunction function, String application_name, AtomicBoolean stop_signal) {
        int exit_status = -1;
        log.info("ESTABLISHING SUBSCRIPTION for " + topic);
        /*
        //First remove any leftover consumer
        if (active_consumers_per_topic_per_broker_ip.containsKey(broker_ip)) {
            active_consumers_per_topic_per_broker_ip.get(broker_ip).remove(topic);
            remove_topic_from_broker_connector(topic);
        } else {
            active_consumers_per_topic_per_broker_ip.put(broker_ip, new HashMap<>());
        }
        */
        //Then add the new consumer
        Consumer new_consumer;
        if (application_name != null && !application_name.equals(EMPTY)) {
            new_consumer = new Consumer(topic, topic, new MessageProcessingHandler(function, broker_details), application_name,
                    true, true);
        } else {
            new_consumer = new Consumer(topic, topic, new MessageProcessingHandler(function, broker_details), true, true);
        }
        new_consumer.setProperty("topic", topic);
        active_consumers_per_topic_per_broker_ip.get(broker_ip).put(topic, new_consumer);
        add_topic_consumer_to_broker_connector(new_consumer);

        log.info("ESTABLISHED SUBSCRIPTION to topic " + topic);
        synchronized (stop_signal) {
            while (!stop_signal.get()) {
                try {
                    stop_signal.wait();
                } catch (Exception e) {
                    log.warn( e.toString() + " in thread " + Thread.currentThread().getName());
                    break;
                }
            }
            log.info("Stopping subscription for broker " + broker_ip + " and topic " + topic + "at thread " + Thread.currentThread().getName());
            stop_signal.set(false);
        }
        active_consumers_per_topic_per_broker_ip.get(broker_ip).remove(topic);
        //remove_topic_from_broker_connector(topic);
        current_connectors.get(broker_ip).stop();
        exit_status = 0;
        return exit_status;
    }
}