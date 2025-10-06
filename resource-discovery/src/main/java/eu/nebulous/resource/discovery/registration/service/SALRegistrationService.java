package eu.nebulous.resource.discovery.registration.service;

import eu.nebulous.resource.discovery.ResourceDiscoveryProperties;
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

import java.time.Clock;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.LinkedBlockingDeque;

import static eu.nebulous.resource.discovery.broker_communication.SALCommunicator.get_device_registration_json;

@Slf4j
@Service
@ConditionalOnProperty(name = ResourceDiscoveryProperties.CONFIG_PREFIX + ".sal-registration.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class SALRegistrationService implements InitializingBean {
    private final DeviceManagementService deviceManagementService;
    private final ResourceDiscoveryProperties processorProperties;
    private final TaskExecutor taskExecutor;
    private final LinkedBlockingDeque<Device> queue = new LinkedBlockingDeque<>();
    private Thread processQueueThread;
    private long lastRegistrationStartTimestamp = -1L;

    public void queueForRegistration(@NonNull Device device) {
        if (processorProperties.isSalRegistrationEnabled())
            queue.add(device);
    }

    public String register(Device device) {
        log.warn("SALRegistrationService: register: BEGIN: device: {}", device);
        
        String  application_name = device.getRef().split("\\|")[1];
        if (application_name.equals("all_applications")){
            application_name="";
        }
        /*String public_ip = System.getenv("NEBULOUS_IP");
        if (public_ip==null || public_ip.isEmpty()){
            public_ip = processorProperties.getNebulous_server_ip_address();
            log.warn("Using default IP address ({}) to fetch Proactive client jar files from, as the environmental variable was not set or found", public_ip);}*/
        String public_ip = processorProperties.getNebulous_server_ip_address();

        Map<String,String> device_info = device.getDeviceInfo();
        log.warn("SALRegistrationService: register: DEVICE-INFO: {}", device_info);
        /* Information available from the EMS, based on https://gitlab.com/nebulous-project/ems-main/-/blob/master/ems-core/bin/detect.sh?ref_type=heads
        echo CPU_SOCKETS=$TMP_NUM_CPUS
        echo CPU_CORES=$TMP_NUM_CORES
        echo CPU_PROCESSORS=$TMP_NUM_PROCESSORS
        echo RAM_TOTAL_KB=$TMP_RAM_TOTAL_KB
        echo RAM_AVAILABLE_KB=$TMP_RAM_AVAILABLE_KB
        echo RAM_FREE_KB=$TMP_RAM_FREE_KB
        echo RAM_USED_KB=$TMP_RAM_USED_KB
        echo RAM_UTILIZATION=$TMP_RAM_UTILIZATION
        echo DISK_TOTAL_KB=$TMP_DISK_TOTAL_KB
        echo DISK_FREE_KB=$TMP_DISK_FREE_KB
        echo DISK_USED_KB=$TMP_DISK_USED_KB
        echo DISK_UTILIZATION=$TMP_DISK_UTILIZATION
        echo OS_ARCHITECTURE=$TMP_ARCHITECTURE
        echo OS_KERNEL=$TMP_KERNEL
        echo OS_KERNEL_RELEASE=$TMP_KERNEL_RELEASE
         */
        String device_name = device.getRef();
        Double price = device.getPrice();

        int number_of_gpus = 0;
        int number_of_fpgas = 0;
        int cpu_cores = Integer.parseInt(device_info.get("CPU_PROCESSORS"));
        int gpus = device.getGpus();
        long ram_mb = Math.round(Integer.parseInt(device_info.get("RAM_TOTAL_KB"))*1.0/1000);
        long disk_mb = Math.round(Integer.parseInt(device_info.get("DISK_TOTAL_KB"))*1.0/1000);
        String external_ip_address = device.getIpAddress();
        String device_username = device.getUsername();
        String device_password = new String
                (device.getPassword());
        String device_pub_key = new String(device.getPublicKey()); //TODO get here private key instead and pass this to device registration
        //TODO implement provider here: String provider = device.getProvider();
        //String network_rx =device_info.get("RX");
        //String network_tx = device_info.get("TX");
        String provider_id = device.getProvider();
        String city_name = ""; //TODO improve this
        String country_name = ""; //TODO improve this
        String internal_ip = ""; //TODO improve this
        String os_family = "UBUNTU"; //TODO improve this
        //String os_architecture = "ARMv8"; //TODO improve this
        String os_architecture = device_info.get("OS_ARCHITECTURE").equals("aarch64") ? "ARMv8" : device_info.get("OS_ARCHITECTURE").equals("armv8") ?"ARMv8": device_info.get("OS_ARCHITECTURE").equals("armv7l") ? "ARMv7": "AMD";
        String jar_url = os_architecture.equals("ARMv8") ? "http://"+public_ip+":8880/rest/node-arm-v8.jar" : os_architecture.equals("ARMv7") ? "http://"+public_ip+":8880/rest/node-arm-v7.jar" :  "http://"+public_ip+":8880/rest/node-amd-64.jar";
        
        int os_version = 2204; //TODO improve this
        String private_key = ""; //TODO improve this
        int external_access_port = device.getPort();
        double device_longitude = 0,device_latitude =0;
        if (device.getLocation()!=null){
            String location_name = "";
            if (device.getLocation().getName()!=null) {
                location_name = device.getLocation().getName();
            }
            if (device.getLocation().getCity()!=null) {
                city_name = device.getLocation().getCity();
            }
            if (device.getLocation().getCountry()!=null){
                country_name= device.getLocation().getCountry();
            }
            device_longitude = device.getLocation().getLongitude();
            device_latitude = device.getLocation().getLatitude();
        }

        Clock clock = Clock.systemUTC();

        //JSONObject register_device_message = new JSONObject();
        //register_device_message.put("device_name",device_name);
        //register_device_message.put("timestamp",(int)(clock.millis()/1000));


        String register_device_message_string = get_device_registration_json(internal_ip,external_ip_address,external_access_port,os_family,os_architecture,jar_url,os_version,cpu_cores,gpus,ram_mb,disk_mb,number_of_fpgas,device_name,price,provider_id,city_name,country_name, device_username, device_password,private_key,device_longitude, device_latitude);
        log.info("topic is {}", get_registration_topic_name(application_name));
        log.info("broker ip is {}", processorProperties.getNebulous_broker_ip_address());
        log.info("broker port is {}", processorProperties.getNebulous_broker_port());
        log.info("username is {}", processorProperties.getNebulous_broker_username());
        log.info("password is {}", StringUtils.isNotBlank(processorProperties.getNebulous_broker_password()) ? "<provided>" : "<not provided>");
        //String sal_running_applications_reply = request_running_applications_AMQP();
        //ArrayList<String> applications = get_running_applications(sal_running_applications_reply);
        //for (String application_name:applications) {
        SynchronousBrokerPublisher register_device_publisher = new SynchronousBrokerPublisher(get_registration_topic_name(application_name), processorProperties.getNebulous_broker_ip_address(),processorProperties.getNebulous_broker_port(), processorProperties.getNebulous_broker_username(), processorProperties.getNebulous_broker_password(), "");
        int sending_attempt = 1;
        while (register_device_publisher.is_publisher_null()){
            if (sending_attempt<=2) {
                register_device_publisher = new SynchronousBrokerPublisher(get_registration_topic_name(application_name), processorProperties.getNebulous_broker_ip_address(), processorProperties.getNebulous_broker_port(), processorProperties.getNebulous_broker_username(), processorProperties.getNebulous_broker_password(), "");
            }else{
                log.warn("Will now attempt to reset the Synchronous publisher connector to register");
                register_device_publisher = new SynchronousBrokerPublisher(get_registration_topic_name(application_name), processorProperties.getNebulous_broker_ip_address(), processorProperties.getNebulous_broker_port(), processorProperties.getNebulous_broker_username(), processorProperties.getNebulous_broker_password(), "");
            }
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            sending_attempt++;
        }
        //TODO handle the response here
        Map response = register_device_publisher.publish_for_response(register_device_message_string, Collections.singleton(application_name));
        log.warn("The response received while trying to register device " + device_name + " is "+response.toString());
        JSONObject response_json = new JSONObject(response);
        JSONParser json_parser = new JSONParser();
        JSONObject response_json_body = null;
        try {
            response_json_body = (JSONObject) json_parser.parse(String.valueOf(response_json.get("body")));
        } catch (ParseException e) {
            throw new RuntimeException(e);
        }
        String device_id = (String) response_json_body.get("id");
        return device_id;
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

    private String get_registration_topic_name(String application_name) {
        return processorProperties.getRegistration_topic_name();
        //return ("eu.nebulouscloud.exn.sal.edge." + application_name);
    }

    @Override
    public void afterPropertiesSet() {
        if (! processorProperties.isSalRegistrationEnabled()) {
            log.info("SAL registration is disabled due to configuration");
            return;
        }

        if (    StringUtils.isNotBlank(processorProperties.getNebulous_broker_ip_address()) &&
                StringUtils.isNotBlank(processorProperties.getNebulous_broker_username()) &&
                StringUtils.isNotBlank(processorProperties.getNebulous_broker_password()) )
        {
            log.info("Successful setting of properties for communication with SAL");
            taskExecutor.execute(this::processQueue);
            taskExecutor.execute(this::checkProcessQueue);
        } else {
            String message = String.format("Nebulous broker configuration is missing:  ip-address=%s, username=%s, password=%s",
                    processorProperties.getNebulous_broker_ip_address(),
                    processorProperties.getNebulous_broker_username(),
                    StringUtils.isNotBlank(processorProperties.getNebulous_broker_password()) ? "<provided>" : "<not provided>");
            log.error(message);
            throw new RuntimeException(message);
        }
    }

    public void processQueue() {
        processQueueThread = Thread.currentThread();
        while (true) {
            Device device = null;
            try {
                device = queue.take();
                log.warn("SALRegistrationService: processQueue(): Will register device: {}", device);
                lastRegistrationStartTimestamp = System.currentTimeMillis();
                String device_sal_id = register(device);
                device.setSal_id(device_sal_id);
                lastRegistrationStartTimestamp = -1L;
                device.setRegisteredToSAL(true);
                deviceManagementService.update(device);
                log.warn("SALRegistrationService: processQueue(): Device registered to SAL: {}", device);
            } catch (InterruptedException e) {
                log.warn("SALRegistrationService: processQueue(): Interrupted. Will not register device to SAL: {}", device);
                lastRegistrationStartTimestamp = -1L;
//                break;
            } catch (Exception e) {
                log.warn("SALRegistrationService: processQueue(): EXCEPTION caught. Will not register device to SAL: {}", device, e);
                lastRegistrationStartTimestamp = -1L;
            }
        }
    }

    public void checkProcessQueue() {
        while (true) {
            try {
                Thread.sleep(1000);
                if (processQueueThread!=null && lastRegistrationStartTimestamp > 0) {
                    long runningTime = System.currentTimeMillis() - lastRegistrationStartTimestamp;
                    if (runningTime > processorProperties.getSalRegistrationTimeout()) {
                        log.warn("SALRegistrationService: checkProcessQueue(): Method 'processQueue' is running for too log. Will attempt to interrupt it");
                        processQueueThread.interrupt();
                        lastRegistrationStartTimestamp = -1L;
                    }
                }
            } catch (Exception e) {
                log.warn("SALRegistrationService: checkProcessQueue(): EXCEPTION caught: ", e);
            }
        }
    }
}

