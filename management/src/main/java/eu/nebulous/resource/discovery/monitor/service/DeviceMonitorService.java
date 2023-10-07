
package eu.nebulous.resource.discovery.monitor.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.nebulous.resource.discovery.ResourceDiscoveryProperties;
import eu.nebulous.resource.discovery.monitor.model.Device;
import eu.nebulous.resource.discovery.monitor.model.DeviceStatusUpdate;
import jakarta.jms.Message;
import jakarta.jms.MessageConsumer;
import jakarta.jms.MessageListener;
import jakarta.jms.Session;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.activemq.ActiveMQConnection;
import org.apache.activemq.ActiveMQConnectionFactory;
import org.apache.activemq.command.ActiveMQTextMessage;
import org.apache.activemq.command.ActiveMQTopic;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@EnableAsync
@EnableScheduling
@RequiredArgsConstructor
public class DeviceMonitorService implements InitializingBean, MessageListener {
    private final ResourceDiscoveryProperties monitorProperties;
    private final DeviceManagementService deviceManagementService;
    private final TaskScheduler taskScheduler;
    private final ObjectMapper objectMapper;

    @Override
    public void afterPropertiesSet() throws Exception {
        // Initialize device status listener
        taskScheduler.schedule(this::initializeDeviceStatusListener,
                Instant.now().plusSeconds(monitorProperties.getSubscriptionStartupDelay()));
    }

    private void initializeDeviceStatusListener() {
        try {
            ActiveMQConnectionFactory connectionFactory = new ActiveMQConnectionFactory(
                    monitorProperties.getBrokerUsername(), monitorProperties.getBrokerPassword(), monitorProperties.getBrokerURL());
            ActiveMQConnection conn = (ActiveMQConnection) connectionFactory.createConnection();
            Session session = conn.createSession();
            MessageConsumer consumer = session.createConsumer(
                    new ActiveMQTopic(monitorProperties.getDeviceStatusMonitorTopic()));
            consumer.setMessageListener(this);
            conn.start();
        } catch (Exception e) {
            log.error("DeviceMonitorService: ERROR while subscribing to Message broker for Device status updates: ", e);
            taskScheduler.schedule(this::initializeDeviceStatusListener, Instant.now().plusSeconds(monitorProperties.getSubscriptionRetryDelay()));
        }
    }

    @Override
    public void onMessage(Message message) {
        try {
            log.debug("DeviceMonitorService: Received a JMS message: {}", message);
            if (message instanceof ActiveMQTextMessage textMessage) {
                String payload = textMessage.getText();
                log.trace("DeviceMonitorService: Message payload: {}", payload);
                TypeReference<Map<String,Object>> typeRef = new TypeReference<>() { };
                Object obj = objectMapper.readerFor(typeRef).readValue(payload);
                if (obj instanceof Map<?,?> dataMap) {
                    processPayload(dataMap);
                } else {
                    log.debug("DeviceMonitorService: Message payload is not recognized. Expected Map but got: type={}, object={}", obj.getClass().getName(), obj);
                }
            } else {
                log.debug("DeviceMonitorService: Message type is not supported: {}", message);
            }
        } catch (Exception e) {
            log.warn("DeviceMonitorService: ERROR while processing message: {}\nException: ", message, e);
        }
    }

    private void processPayload(@NonNull Map<?,?> dataMap) {
        Object obj = dataMap.get("message");
        if (obj==null) {
            log.debug("DeviceMonitorService: Message does not contain device status info (message field is null): {}", dataMap);
            return;
        }
        if (obj instanceof Map<?,?> infoMap) {
            if (infoMap.isEmpty())
                log.debug("DeviceMonitorService: Message device status map (message field) is empty: {}", dataMap);
            else
                updateDeviceInfo(infoMap);
        } else {
            log.debug("DeviceMonitorService: Message is not a device status update (message field is not a map): {}", dataMap);
        }
    }

    private void updateDeviceInfo(@NonNull Map<?, ?> infoMap) {
        try {
            @NonNull DeviceStatusUpdate deviceStatusUpdate = objectMapper.convertValue(infoMap, DeviceStatusUpdate.class);

            // Get registered device using IP address
            String ipAddress = deviceStatusUpdate.getIpAddress();
            Optional<Device> result = deviceManagementService.getByIpAddress(ipAddress);
            if (result.isEmpty()) {
                log.warn("DeviceMonitorService: Device status update IP address does not match any registered device: {}", infoMap);
                return;
            }
            Device device = result.get();

            // Further check device reference
            if (! device.getNodeReference().equals(deviceStatusUpdate.getReference())) {
                log.debug("DeviceMonitorService: Device status update node reference does NOT match to the registered device's one: update={}, registered-device={}", deviceStatusUpdate, device);
                log.warn("DeviceMonitorService: Device status update node reference does NOT match to the registered device's one: id={}, update-ref={}, registered-device-ref={}",
                        device.getId(), deviceStatusUpdate.getReference(), device.getNodeReference());
                return;
            }

            // Check if the received device status update has no state
            if (StringUtils.isBlank(deviceStatusUpdate.getState())) {
                log.warn("DeviceMonitorService: Device status update has empty state field. Ignoring it: update={}", deviceStatusUpdate);
                return;
            }

            // Check if the received device status update is older than the cached one
            if (device.getStatusUpdate()!=null && device.getStatusUpdate().getStateLastUpdate()!=null) {
                if (device.getStatusUpdate().getStateLastUpdate().isAfter(deviceStatusUpdate.getStateLastUpdate())) {
                    log.warn("DeviceMonitorService: Device status update received is older than the cached one. Ignoring it: id={}, update-timestamp={}, registered-timestamp={}",
                            device.getId(), deviceStatusUpdate.getStateLastUpdate(), device.getStatusUpdate().getStateLastUpdate());
                    return;
                }
            }

            // Update device data
            device.setStatusUpdate(deviceStatusUpdate);
            deviceManagementService.update(device);
            log.info("DeviceMonitorService: Device status updated for device: id={}, ip-address={}, update={}",
                    device.getId(), device.getIpAddress(), deviceStatusUpdate);
        } catch (Exception e) {
            log.warn("DeviceMonitorService: EXCEPTION while converting device status update info map to DeviceStatus object: {}\n", infoMap, e);
        }
    }
}
