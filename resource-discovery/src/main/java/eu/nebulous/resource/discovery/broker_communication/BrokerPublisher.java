package eu.nebulous.resource.discovery.broker_communication;

import eu.nebulouscloud.exn.Connector;
import eu.nebulouscloud.exn.core.Publisher;
import eu.nebulouscloud.exn.handlers.ConnectorHandler;
import eu.nebulouscloud.exn.settings.StaticExnConfig;
import lombok.extern.slf4j.Slf4j;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import java.util.*;


@Slf4j
public class BrokerPublisher {
    public static String EMPTY="";
    //private HashMap<String, HashSet<String>> broker_and_topics_to_publish_to = new HashMap<>();
    private Publisher private_publisher_instance;
    private ArrayList<Publisher> publishers = new ArrayList<>();

    private Connector active_connector;
    private CustomConnectorHandler active_connector_handler;
    private String topic;
    private String broker_ip;
    private int broker_port;

    
    public BrokerPublisher(String topic, String broker_ip, int broker_port, String brokerUsername, String brokerPassword, String amqLibraryConfigurationLocation) {
        boolean able_to_initialize_BrokerPublisher = topic!=null && broker_ip!=null && brokerUsername!=null && brokerPassword!=null && !topic.equals(EMPTY) && !broker_ip.equals(EMPTY) && !brokerUsername.equals(EMPTY) && !brokerPassword.equals(EMPTY);

        if (!able_to_initialize_BrokerPublisher){
            log.error("Could not initialize BrokerPublisher");
            return;
        }
        boolean publisher_configuration_changed;
//        if (!broker_and_topics_to_publish_to.containsKey(broker_ip)){
//            HashSet<String> topics_to_publish_to = new HashSet<>();
//            topics_to_publish_to.add(topic);
//            broker_and_topics_to_publish_to.put(broker_ip,topics_to_publish_to);
//            publisher_configuration_changed = true;
//        }else{
//            if (!broker_and_topics_to_publish_to.get(broker_ip).contains(topic)){
//                broker_and_topics_to_publish_to.get(broker_ip).add(topic);
//                publisher_configuration_changed = true;
//            }
//            else{
//                publisher_configuration_changed = false;
//            }
//        }


        //if (publisher_configuration_changed || hard_initialize_connector){
//            for (String current_broker_ip : broker_and_topics_to_publish_to.keySet()){
            log.info("Publisher configuration changed, creating new connector at "+broker_ip+" for topic "+topic);
            if (active_connector!=null) {
                //active_connector.stop(new ArrayList<>(), publishers);
                synchronized (active_connector_handler.getReady()){
                    while (!active_connector_handler.getReady().get()) {
                        try {
                            active_connector_handler.wait();
                        } catch (InterruptedException e) {
                            throw new RuntimeException(e);
                        }
                    }
                    try {
                        active_connector.stop(); //TODO reassure expected stop() functionality is working here when this is necessary
                    }catch (Exception e){
                        e.printStackTrace();
                    }
                }
                
            }
            publishers.clear();
            //for (String broker_topic : broker_and_topics_to_publish_to.get(broker_ip)){
                //ArrayList<Publisher> publishers = new ArrayList<>();
                Publisher publisher = new Publisher("resource_manager_"+topic, topic, true, true);
                publishers.add(publisher);
                //if (broker_topic.equals(topic)){
                    this.private_publisher_instance = publisher;
                    //this.topic = broker_topic;
                    this.topic = topic;
                    this.broker_ip = broker_ip;
                    this.broker_port = broker_port;
                //}
            //}
            //CustomConnectorHandler custom_handler = new CustomConnectorHandler();
            active_connector_handler = new CustomConnectorHandler() {};
            active_connector = new Connector("resource_manager"
                    , active_connector_handler
                    , publishers
                    , List.of(),
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
            active_connector.start();

        //}
    }

    //TODO The methods below assume that the only content to be sent is json-like
    public void publish (String json_string_content, Collection<String> application_names, boolean stop_connector){
        for (String application_name : application_names) {
            publish(json_string_content,stop_connector);
        }
    }
    
    public void publish (String json_string_content,boolean stop_connector){
        JSONParser parser = new JSONParser();
        JSONObject json_object = new JSONObject();
        try {
            json_object = (JSONObject) parser.parse(json_string_content);
        } catch (ParseException p) {
            log.warn( "publish: Could not parse the string content to be published to the broker as json, which is the following: "+json_string_content);
        }
        if (!is_publisher_null()) {
            private_publisher_instance.send(json_object);
            log.info("Sent new message\n"+json_object.toJSONString());
        } else {
            log.error( "Could not send message to AMQP broker, as the publisher instance is null");
        }
        if (stop_connector){
            synchronized (active_connector_handler.getReady()){
                while (!active_connector_handler.getReady().get()) {
                    try {
                        log.error( "Waiting for the connector to be ready to stop");
                        active_connector_handler.wait();
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                }
                try {
                    log.error( "Now able to stop the connector");
                    active_connector.stop(); //Reassure expected stop() functionality is working here when this is necessary
                }catch (Exception e){
                    e.printStackTrace();
                }
            }
        }
    }
    
    public boolean is_publisher_null(){
        return (private_publisher_instance == null);
    }
    
    public void stop(){
        if (active_connector!=null) {
            //active_connector.stop(new ArrayList<>(), publishers);
            synchronized (active_connector_handler.getReady()){
                while (!active_connector_handler.getReady().get()) {
                    try {
                        active_connector_handler.getReady().wait();
                     } catch (InterruptedException e) {
                        
                    }
                }
            }
            
            active_connector.stop();
        }
    }
}
