package eu.nebulous.resource.discovery.registration.service;



import eu.nebulous.resource.discovery.ResourceDiscoveryProperties;
import eu.nebulous.resource.discovery.broker_communication.BrokerSubscriber;
import eu.nebulous.resource.discovery.broker_communication.BrokerSubscriptionDetails;
import eu.nebulous.resource.discovery.broker_communication.SynchronousBrokerPublisher;
import eu.nebulous.resource.discovery.monitor.model.Device;
import eu.nebulous.resource.discovery.monitor.service.DeviceManagementService;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.function.BiFunction;

import static eu.nebulous.resource.discovery.broker_communication.SALCommunicator.get_device_deregistration_json;

@Slf4j
@Service
@ConditionalOnProperty(name = ResourceDiscoveryProperties.CONFIG_PREFIX + ".sal-registration.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class SALDeregistrationService implements InitializingBean {
    
    private final DeviceManagementService deviceManagementService;
    private final ResourceDiscoveryProperties processorProperties;
    private final TaskExecutor taskExecutor;
    private final LinkedBlockingDeque<Device> queue = new LinkedBlockingDeque<>();
    private Thread processQueueThread;
    private long lastDeregistrationStartTimestamp = -1L;

    public void queueForDeregistration(@NonNull Device device) {
        if (processorProperties.isSalRegistrationEnabled()) //If registration is enabled, so should be deregistration as well
            queue.add(device);
    }

    public void deregister(Device device) {


        String application_name = device.getRef().split("\\|")[1];
        if (application_name.equals("all_applications")) {
            application_name = "";
        }
        


        String deregister_device_message_string = get_device_deregistration_json(device);
        if (processorProperties.isDeregistrationEmulated()){
            return;
        }
        SynchronousBrokerPublisher deregister_device_publisher = new SynchronousBrokerPublisher(get_deregistration_topic_name(), processorProperties.getNebulousBrokerIpAddress(), processorProperties.getNebulousBrokerPort(), processorProperties.getNebulousBrokerUsername(), processorProperties.getNebulousBrokerPassword(), "");
        int sending_attempt = 1;
        while (deregister_device_publisher.is_publisher_null()) {
            if (sending_attempt <= 2) {
                deregister_device_publisher = new SynchronousBrokerPublisher(get_deregistration_topic_name(), processorProperties.getNebulousBrokerIpAddress(), processorProperties.getNebulousBrokerPort(), processorProperties.getNebulousBrokerUsername(), processorProperties.getNebulousBrokerPassword(), "");
            } else {
                log.warn("Will now attempt to reset the Synchronous publisher connector to deregister");
                deregister_device_publisher = new SynchronousBrokerPublisher(get_deregistration_topic_name(), processorProperties.getNebulousBrokerIpAddress(), processorProperties.getNebulousBrokerPort(), processorProperties.getNebulousBrokerUsername(), processorProperties.getNebulousBrokerPassword(), "");
            }
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            sending_attempt++;
        }
        //TODO handle the response here
        if (deregister_device_message_string!=null && !deregister_device_message_string.isEmpty()) {
            deregister_device_publisher.publish_for_response(deregister_device_message_string, Collections.singleton(application_name));
            //log.warn("The response received while trying to deregister device " + device.getRef() + " is " + response.toString());
        }else{
            log.warn("Deregistration was to be initiated with an empty deregistration payload");
        }
        //}

        /* This is some realtime information, could be retrieved with a different call to the EMS.
        CurrDateTime: 1709207141
        UpDateTime: 1709186638
        Uptime: 20503
        CPU: 0
        RAM: 31.4725
        DISK: 10.3586
        RX: 0
        TX: 0
         */

    }
    
    private String get_deregistration_topic_name() {
        return processorProperties.getDeregistration_topic_prefix();
    }

    @Override
    public void afterPropertiesSet() {
        if (!processorProperties.isSalRegistrationEnabled()) {
            log.info("SAL (de)registration is disabled due to configuration");
            return;
        }

        if (StringUtils.isNotBlank(processorProperties.getNebulousBrokerIpAddress()) &&
                StringUtils.isNotBlank(processorProperties.getNebulousBrokerUsername()) &&
                StringUtils.isNotBlank(processorProperties.getNebulousBrokerPassword())) {
            log.info("Successful setting of properties for communication with SAL");
            taskExecutor.execute(this::processQueue);
            taskExecutor.execute(this::checkProcessQueue);
        } else {
            String message = String.format("Nebulous broker configuration is missing:  ip-address=%s, username=%s, password=%s",
                    processorProperties.getNebulousBrokerIpAddress(),
                    processorProperties.getNebulousBrokerUsername(),
                    StringUtils.isNotBlank(processorProperties.getNebulousBrokerPassword()) ? "<provided>" : "<not provided>");
            log.error(message);
            throw new RuntimeException(message);
        }
    }
    
    public void subscribeCompromisedDevices() {

        BiFunction<BrokerSubscriptionDetails,String,Device> compromised_device_handling = (broker_subscription_details, message) -> {

            JSONParser parser = new JSONParser();
            JSONObject jsonObject = null;
            try {
                jsonObject = (JSONObject) parser.parse(message);
            } catch (ParseException e) {
                throw new RuntimeException(e);
            }

            // Assuming the device name is inside a "device" object
            String device_id = (String) jsonObject.get("device_id");
            
            Optional<Device> compromisedDevice = deviceManagementService.getById(device_id);
            if (compromisedDevice.isPresent()){
                queue.add(compromisedDevice.get());
                return compromisedDevice.get();
            }else{
                return null;
            }
        };
        
        String topicName = get_device_compromised_topic_name();
        log.info("Subscribing to topic: {}", topicName);
        try {
            BrokerSubscriber subscriber = new BrokerSubscriber(topicName,processorProperties.getNebulousBrokerIpAddress(),processorProperties.getNebulousBrokerPort(),processorProperties.getBrokerUsername(),processorProperties.getBrokerPassword(),"","");
            subscriber.subscribe(compromised_device_handling,"");
        } catch (Exception e) {
            log.error("Error while subscribing to topic: " + topicName, e);
         }
    }
    

    public void processQueue() {
        processQueueThread = Thread.currentThread();
        while (true) {
            Device device = null;
            try {
                device = queue.take();
                log.warn("SALRegistrationService: processQueue(): Will deregister device: {}", device);
                lastDeregistrationStartTimestamp = System.currentTimeMillis();
                deregister(device);
                lastDeregistrationStartTimestamp = -1L;
                device.setRegisteredToSAL(false);
                deviceManagementService.update(device);
                log.warn("SALRegistrationService: processQueue(): Device deregistered from SAL: {}", device);
            } catch (InterruptedException e) {
                log.warn("SALRegistrationService: processQueue(): Interrupted. Will not deregister device from SAL: {}", device);
                lastDeregistrationStartTimestamp = -1L;
//                break;
            } catch (Exception e) {
                log.warn("SALRegistrationService: processQueue(): EXCEPTION caught. Will not deregister device from SAL: {}", device, e);
                lastDeregistrationStartTimestamp = -1L;
            }
        }
    }

    public void checkProcessQueue() {
        while (true) {
            try {
                Thread.sleep(1000);
                if (processQueueThread != null && lastDeregistrationStartTimestamp > 0) {
                    long runningTime = System.currentTimeMillis() - lastDeregistrationStartTimestamp;
                    if (runningTime > processorProperties.getSalRegistrationTimeout()) {
                        log.warn("SALDeregistrationService: checkProcessQueue(): Method 'processQueue' is running for too log. Will attempt to interrupt it");
                        processQueueThread.interrupt();
                        lastDeregistrationStartTimestamp = -1L;
                    }
                }
            } catch (Exception e) {
                log.warn("SALDeregistrationService: checkProcessQueue(): EXCEPTION caught: ", e);
            }
        }
    }
    
    private String get_device_compromised_topic_name(){
        return processorProperties.getCompromisedDeviceTopic();
    }
}