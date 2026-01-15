package eu.nebulous.resource.discovery.broker_communication;

import eu.nebulouscloud.exn.Connector;
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
    private HashMap<String, HashSet<String>> broker_and_topics_to_publish_to = new HashMap<>();
    private SyncedPublisher private_publisher_instance;
    private ArrayList<Publisher> publishers = new ArrayList<>();

    private Connector active_connector;
    private String topic;
    private String broker_ip;
    public SynchronousBrokerPublisher(String topic, String broker_ip, int broker_port, String brokerUsername, String brokerPassword, String amqLibraryConfigurationLocation) {

        boolean able_to_initialize_BrokerPublisher = topic!=null && broker_ip!=null && brokerUsername!=null && brokerPassword!=null && !topic.equals(EMPTY) && !broker_ip.equals(EMPTY) && !brokerUsername.equals(EMPTY) && !brokerPassword.equals(EMPTY);

        if (!able_to_initialize_BrokerPublisher){
            log.error("Unable to initialize SynchronousBrokerPublisher");
            return;
        }
        boolean publisher_configuration_changed;
        HashSet<String> topics_to_publish_to = new HashSet<>();
        topics_to_publish_to.add(topic);
        broker_and_topics_to_publish_to.put(broker_ip,topics_to_publish_to);
        //log.error("changed1");

        //log.error("preliminary_outside");
        //if (publisher_configuration_changed || hard_initialize_connector){
            //log.error("preliminary_inside1");
//            for (String current_broker_ip : broker_and_topics_to_publish_to.keySet()){
            log.info("Publisher configuration changed, creating new connector at  "+broker_ip+" for topic "+topic);
            if (active_connector!=null) {
                //active_connector.stop(new ArrayList<>(), publishers);
                active_connector.stop();
            }
            publishers.clear();
            //for (String broker_topic : broker_and_topics_to_publish_to.get(broker_ip)){
                //log.error("preliminary_inside2");
                //ArrayList<Publisher> publishers = new ArrayList<>();
                //SyncedPublisher publisher = new SyncedPublisher("resource_manager_"+broker_topic, broker_topic, true, true);
                SyncedPublisher publisher = new SyncedPublisher("resource_manager_"+topic, topic, true, true);
                publishers.add(publisher);
            //    if (broker_topic.equals(topic)){
                    log.debug("inside_assignment_to_private_publisher_instance");
                    this.private_publisher_instance = (SyncedPublisher) publishers.get(publishers.size()-1);
                    //this.topic = broker_topic;
                    this.topic = topic;
                    this.broker_ip = broker_ip;
            //    }
            //}
            //CustomConnectorHandler custom_handler = new CustomConnectorHandler();

            active_connector = new Connector("resource_manager_synchronous"
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

        //}
    }


    public Map publish_for_response (String json_string_content, Collection<String> application_names){
        Map reply = null;
        HashMap<String,Object> payload = new HashMap<>();
        if (application_names!=null && !application_names.isEmpty()) {
            for (String application_name : application_names) {

                boolean successful_json_parsing = false;
                JSONParser parser = new JSONParser();
                JSONObject json_object = new JSONObject();
                try {
                    json_object = (JSONObject) parser.parse(json_string_content);
                    successful_json_parsing = true;
                } catch (ParseException p) {
                    log.warn("publish_for_response-if: Could not parse the string content to be published to the broker as json, which is the following: " + json_string_content);
                }
                //metadata.put("jobId",application_name);
                if (private_publisher_instance != null) {
                    //reply = private_publisher_instance.sendSync(json_object, application_name, null, false);
                    if (successful_json_parsing) {
                        //json_object.put("jobId",application_name);
                        for (Object key : json_object.keySet()) {
                            payload.put((String)key, json_object.get(key));
                        }
                        reply = private_publisher_instance.sendSync(payload, application_name, null, false);
                    }else{
                        payload.put("body",json_string_content);
                        log.warn("Not Sending the original json string as its parsing was not successful");
                        //reply = private_publisher_instance.sendSync(payload, application_name, null, false);
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
                log.warn("publish_for_response-else: Could not parse the string content to be published to the broker as json, which is the following: " + json_string_content);
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
    public boolean is_publisher_null(){
        return (private_publisher_instance == null);
    }
}
