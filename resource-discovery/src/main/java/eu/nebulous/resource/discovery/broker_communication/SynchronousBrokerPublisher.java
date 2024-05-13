package eu.nebulous.resource.discovery.broker_communication;

import eu.nebulouscloud.exn.core.Publisher;
import eu.nebulouscloud.exn.core.SyncedPublisher;
import eu.nebulouscloud.exn.settings.StaticExnConfig;
import lombok.extern.slf4j.Slf4j;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.slf4j.Marker;

import java.util.*;


@Slf4j
public class SynchronousBrokerPublisher {
    public static String EMPTY="";
    private static HashMap<String, HashSet<String>> broker_and_topics_to_publish_to = new HashMap<>();
    private SyncedPublisher private_publisher_instance;
    private ArrayList<Publisher> publishers = new ArrayList<>();

    private ExtendedConnector active_connector;
    private String topic;
    private String broker_ip;

    public SynchronousBrokerPublisher(String topic, String broker_ip, int broker_port, String brokerUsername, String brokerPassword, String amqLibraryConfigurationLocation) {

        boolean able_to_initialize_BrokerPublisher = topic!=null && broker_ip!=null && brokerUsername!=null && brokerPassword!=null && !topic.equals(EMPTY) && !broker_ip.equals(EMPTY) && !brokerUsername.equals(EMPTY) && !brokerPassword.equals(EMPTY);

        if (!able_to_initialize_BrokerPublisher){
            return;
        }
        boolean publisher_configuration_changed;
        if (!broker_and_topics_to_publish_to.containsKey(broker_ip)){
            HashSet<String> topics_to_publish_to = new HashSet<>();
            topics_to_publish_to.add(topic);
            broker_and_topics_to_publish_to.put(broker_ip,topics_to_publish_to);
            //log.error("changed1");
            publisher_configuration_changed = true;
        }else{
            if (!broker_and_topics_to_publish_to.get(broker_ip).contains(topic)){
                broker_and_topics_to_publish_to.get(broker_ip).add(topic);
                //log.error("changed2");
                publisher_configuration_changed = true;
            }
            else{
                publisher_configuration_changed = false;
            }
        }

        //log.error("preliminary_outside");
        if (publisher_configuration_changed){
            //log.error("preliminary_inside1");
//            for (String current_broker_ip : broker_and_topics_to_publish_to.keySet()){
            log.info("Publisher configuration changed, creating new connector at  "+broker_ip+" for topic "+topic);
            if (active_connector!=null) {
                active_connector.stop(new ArrayList<>(), publishers);
            }
            publishers.clear();
            for (String broker_topic : broker_and_topics_to_publish_to.get(broker_ip)){
                //log.error("preliminary_inside2");
                //ArrayList<Publisher> publishers = new ArrayList<>();
                SyncedPublisher publisher = new SyncedPublisher("resource_manager_"+broker_topic, broker_topic, true, true);
                publishers.add(publisher);
                if (broker_topic.equals(topic)){
                    log.error("inside_assignment_to_private_publisher_instance");
                    this.private_publisher_instance = (SyncedPublisher) publishers.get(publishers.size()-1);
                    this.topic = broker_topic;
                    this.broker_ip = broker_ip;
                }
            }
            //CustomConnectorHandler custom_handler = new CustomConnectorHandler();

            active_connector = new ExtendedConnector("resource_manager"
                    , new CustomConnectorHandler() {}
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

        }
    }


    public Map publish_for_response (String json_string_content, Collection<String> application_names){
        Map reply = null;
        HashMap<String,Object> payload = new HashMap<>();
        HashMap<String,String> metadata = new HashMap<>();
        metadata.put("user","admin");
        metadata.put("type","edge");
        if (application_names!=null && !application_names.isEmpty()) {
            for (String application_name : application_names) {

                boolean successful_json_parsing = false;
                JSONParser parser = new JSONParser();
                JSONObject json_object = new JSONObject();
                try {
                    json_object = (JSONObject) parser.parse(json_string_content);
                    successful_json_parsing = true;
                } catch (ParseException p) {
                    log.warn("Could not parse the string content to be published to the broker as json, which is the following: " + json_string_content);
                }
                metadata.put("jobId",application_name);
                payload.put("metaData",metadata);
                if (private_publisher_instance != null) {
                    //reply = private_publisher_instance.sendSync(json_object, application_name, null, false);
                    if (successful_json_parsing) {
                        json_object.put("jobId",application_name);
                        payload.put("body",json_object.toJSONString());
                        reply = private_publisher_instance.sendSync(payload, application_name, null, false);
                    }else{
                        payload.put("body",json_string_content);
                        log.warn(Marker.ANY_MARKER,"Sending the original json string without any modification as its parsing was not successful");
                        reply = private_publisher_instance.sendSync(payload, application_name, null, false);
                    }
                } else {
                    log.error("Could not send message to AMQP broker, as the private publisher instance is null (is broker ip specified?)");
                }
            }
        }else{ //Send an empty string for application
            JSONParser parser = new JSONParser();
            JSONObject json_object = new JSONObject();
            try {
                json_object = (JSONObject) parser.parse(json_string_content);

            } catch (ParseException p) {
                log.warn("Could not parse the string content to be published to the broker as json, which is the following: " + json_string_content);
            }
            if (private_publisher_instance != null) {
                log.info("Sending new synchronous message\n"+json_object.toJSONString());
                reply = private_publisher_instance.sendSync(json_object,EMPTY, null, false);
                log.info("Sent new synchronous message\n"+json_object.toJSONString());
            } else {
                log.error("Could not send message to AMQP broker, as the private publisher instance is null (is broker ip specified?)");
            }
        }
        return reply;
    }
}
