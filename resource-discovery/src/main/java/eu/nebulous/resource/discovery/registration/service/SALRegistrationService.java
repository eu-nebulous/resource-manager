package eu.nebulous.resource.discovery.registration.service;

import eu.nebulous.resource.discovery.ResourceDiscoveryProperties;
import eu.nebulous.resource.discovery.broker_communication.SynchronousBrokerPublisher;
import eu.nebulous.resource.discovery.monitor.model.Device;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.json.simple.JSONObject;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;

import static eu.nebulous.resource.discovery.broker_communication.SALCommunicator.*;

@Slf4j
@Service
public class SALRegistrationService implements InitializingBean {
    @Autowired
    private final ResourceDiscoveryProperties processorProperties;

    public SALRegistrationService(ResourceDiscoveryProperties processorProperties) {
        this.processorProperties = processorProperties;
    }

    public void register(Device device) {

        String  application_name = "default-application"; //TODO decide on this
        Map<String,String> device_info = device.getDeviceInfo();
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
        String device_name = device.getName();

        Integer cores = Integer.parseInt(device_info.get("CPU_PROCESSORS"));
        Integer ram_gb = Integer.parseInt(device_info.get("RAM_TOTAL_KB"))/1000000;
        Integer disk_gb = Integer.parseInt(device_info.get("DISK_TOTAL_KB"))/1000000;
        String external_ip_address = device.getIpAddress();
        String device_username = device.getUsername();
        String device_password = new String(device.getPassword());
        String device_pub_key = new String(device.getPublicKey()); //TODO get here private key instead and pass this to device registration
        //TODO implement provider here: String provider = device.getProvider();
        //String network_rx =device_info.get("RX");
        //String network_tx = device_info.get("TX");

        Clock clock = Clock.systemUTC();

        JSONObject register_device_message = new JSONObject();
        register_device_message.put("device_name",device_name);
        register_device_message.put("timestamp",(int)(clock.millis()/1000));
        get_device_registration_json("10.100.100",external_ip_address,cores,ram_gb,disk_gb,device_name,"test_provider","Athens","Greece", device_username, device_password);
        log.error("topic is "+get_registration_topic_name(application_name));
        log.error("broker ip is "+processorProperties.getNebulous_broker_ip_address());
        log.error("broker port is "+processorProperties.getNebulous_broker_port());
        log.error("username is "+processorProperties.getNebulous_broker_username());
        log.error("password is "+processorProperties.getNebulous_broker_password());
        //String sal_running_applications_reply = request_running_applications_AMQP();
        //ArrayList<String> applications = get_running_applications(sal_running_applications_reply);
        //for (String application_name:applications) {
                SynchronousBrokerPublisher register_device_publisher = new SynchronousBrokerPublisher(get_registration_topic_name(application_name), processorProperties.getNebulous_broker_ip_address(),processorProperties.getNebulous_broker_port(), processorProperties.getNebulous_broker_username(), processorProperties.getNebulous_broker_password(), "");
                //TODO handle the response here
                Map response = register_device_publisher.publish_for_response(register_device_message.toJSONString(), Collections.singleton(application_name));
                log.info("The response received while trying to register device " + device_name);
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
        return "eu.nebulouscloud.exn.sal.node.create";
        //return ("eu.nebulouscloud.exn.sal.edge." + application_name);
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        if (    processorProperties.getNebulous_broker_password()!=null &&
                processorProperties.getNebulous_broker_username()!=null &&
                processorProperties.getNebulous_broker_ip_address()!=null
        ){
            log.info("Successful setting of properties for communication with SAL");
        }else{
            log.error("broker ip is "+processorProperties.getNebulous_broker_ip_address());
            log.error("username is "+processorProperties.getNebulous_broker_username());
            log.error("password is "+processorProperties.getNebulous_broker_password());
            throw new Exception("Required data is null - broker ip is "+processorProperties.getNebulous_broker_ip_address()+" username is "+processorProperties.getNebulous_broker_username()+" password is "+processorProperties.getNebulous_broker_password());
        }
    }
}
