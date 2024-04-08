package eu.nebulous.resource.discovery.registration.service;

import eu.nebulous.resource.discovery.ResourceDiscoveryProperties;
import eu.nebulous.resource.discovery.broker_communication.SynchronousBrokerPublisher;
import eu.nebulous.resource.discovery.monitor.model.Device;
import eu.nebulous.resource.discovery.monitor.service.DeviceManagementService;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.LinkedBlockingDeque;

import static eu.nebulous.resource.discovery.broker_communication.SALCommunicator.get_device_registration_json;

@Slf4j
@Service
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

        int cores = Integer.parseInt(device_info.get("CPU_PROCESSORS"));
        int ram_gb = Integer.parseInt(device_info.get("RAM_TOTAL_KB"))/1000000;
        int disk_gb = Integer.parseInt(device_info.get("DISK_TOTAL_KB"))/1000000;
        String external_ip_address = device.getIpAddress();
        String device_username = device.getUsername();
        String device_password = new String(device.getPassword());
        String device_pub_key = new String(device.getPublicKey()); //TODO get here private key instead and pass this to device registration
        //TODO implement provider here: String provider = device.getProvider();
        //String network_rx =device_info.get("RX");
        //String network_tx = device_info.get("TX");

        Clock clock = Clock.systemUTC();

        //JSONObject register_device_message = new JSONObject();
        //register_device_message.put("device_name",device_name);
        //register_device_message.put("timestamp",(int)(clock.millis()/1000));

        String register_device_message_string = get_device_registration_json("10.100.100",external_ip_address,cores,ram_gb,disk_gb,device_name,"test_provider","Athens","Greece", device_username, device_password);
        log.info("topic is {}", get_registration_topic_name(application_name));
        log.info("broker ip is {}", processorProperties.getNebulous_broker_ip_address());
        log.info("broker port is {}", processorProperties.getNebulous_broker_port());
        log.info("username is {}", processorProperties.getNebulous_broker_username());
        log.info("password is {}", StringUtils.isNotBlank(processorProperties.getNebulous_broker_password()) ? "<provided>" : "<not provided>");
        //String sal_running_applications_reply = request_running_applications_AMQP();
        //ArrayList<String> applications = get_running_applications(sal_running_applications_reply);
        //for (String application_name:applications) {
                SynchronousBrokerPublisher register_device_publisher = new SynchronousBrokerPublisher(get_registration_topic_name(application_name), processorProperties.getNebulous_broker_ip_address(),processorProperties.getNebulous_broker_port(), processorProperties.getNebulous_broker_username(), processorProperties.getNebulous_broker_password(), "");
                //TODO handle the response here
                Map response = register_device_publisher.publish_for_response(register_device_message_string, Collections.singleton(application_name));
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
                register(device);
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
