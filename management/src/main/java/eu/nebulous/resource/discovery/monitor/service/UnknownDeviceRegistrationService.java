
package eu.nebulous.resource.discovery.monitor.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.nebulous.resource.discovery.common.REQUEST_TYPE;
import eu.nebulous.resource.discovery.ResourceDiscoveryProperties;
import eu.nebulous.resource.discovery.monitor.model.Device;
import eu.nebulous.resource.discovery.monitor.model.DeviceStatus;
import jakarta.jms.JMSException;
import jakarta.jms.MessageProducer;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.activemq.command.ActiveMQTextMessage;
import org.apache.activemq.command.ActiveMQTopic;
import org.apache.commons.lang3.StringUtils;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Slf4j
@Service
public class UnknownDeviceRegistrationService extends AbstractMonitorService {
    private final static List<String> MONITORED_REQUEST_TYPES = List.of(
            REQUEST_TYPE.INFO.name(),
            REQUEST_TYPE.INSTALL.name(),
            REQUEST_TYPE.REINSTALL.name()
    );
    private final DeviceManagementService deviceManagementService;
    private final Map<String, String> detectedDevices = Collections.synchronizedMap(new LinkedHashMap<>());
    private final List<Map> deviceDetailsQueue = Collections.synchronizedList(new LinkedList<>());

    public UnknownDeviceRegistrationService(ResourceDiscoveryProperties monitorProperties, TaskScheduler taskScheduler, ObjectMapper objectMapper, DeviceManagementService deviceManagementService) {
        super("UnknownDeviceRegistrationService", monitorProperties, taskScheduler, objectMapper);
        this.deviceManagementService = deviceManagementService;
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        super.afterPropertiesSet();

        // Initialize unknown device processor
        taskScheduler.scheduleWithFixedDelay(this::processCachedData,
                Instant.now().plusSeconds(10), Duration.ofSeconds(10));
    }

    @Override
    protected @NonNull List<String> getTopicsToMonitor() {
        return List.of(
                monitorProperties.getDeviceInfoResponsesTopic(),
                monitorProperties.getDeviceLifeCycleResponsesTopic(),
                monitorProperties.getDeviceStatusMonitorTopic(),
                monitorProperties.getDeviceMetricsMonitorTopic());
    }

    protected void processPayload(@NonNull Map<?, ?> dataMap) {
        log.trace("UnknownDeviceRegistrationService: BEGIN: {}", dataMap);

        // Extract 'message' field if present
        boolean isMetricEvent = false;
        if (dataMap.get("message") instanceof Map map) {
            log.trace("UnknownDeviceRegistrationService: Extracted message field: {}", map);
            dataMap = map;
            isMetricEvent = true;
        }

        // Get 'ipAddress' and 'reference' fields
        Object requestTypeObj = dataMap.get("requestType");
        Object ipAddressObj = dataMap.get("deviceIpAddress");
        if (ipAddressObj == null)
            ipAddressObj = dataMap.get("ipAddress");
        Object referenceObj = dataMap.get("reference");
        String requestType = requestTypeObj == null ? null : requestTypeObj.toString();
        String ipAddress = ipAddressObj == null ? null : ipAddressObj.toString();
        String reference = referenceObj == null ? null : referenceObj.toString();
        log.trace("UnknownDeviceRegistrationService: requestType={}, ipAddress={}, reference={}", requestType, ipAddress, reference);

        if (StringUtils.isNotBlank(ipAddress) && StringUtils.isNotBlank(reference)) {
            // Process message based on its requestType
            if (REQUEST_TYPE.NODE_DETAILS.name().equalsIgnoreCase(requestType)) {
                // It is a Node-details response message
                deviceDetailsQueue.add(dataMap);
                log.trace("UnknownDeviceRegistrationService: END: Cached device details response for processing: ipAddress={}", ipAddress);
            } else if (isMetricEvent || MONITORED_REQUEST_TYPES.contains(requestType)) {
                // It is a Device status or Metrics message
                // cache ipAddress and message
                detectedDevices.put(ipAddress, reference);
                log.trace("UnknownDeviceRegistrationService: END: Cached device ipAddress and data for processing: ipAddress={}", ipAddress);
            } else
                log.trace("UnknownDeviceRegistrationService: END: Ignored message due to requestType: {}", dataMap);
        } else {
            // ipAddress or reference is missing. Ignoring message
            log.trace("UnknownDeviceRegistrationService: END: Missing ipAddress or reference field. Ignore message: {}", dataMap);
        }
    }

    // Invoked by taskScheduler
    public void processCachedData() {
        processDetectedDevices();
        processDeviceDetailsResponses();
    }

    private void processDetectedDevices() {
        // Copy and clear the unknown devices queue
        LinkedHashMap<String, String> map;
        synchronized (detectedDevices) {
            map = new LinkedHashMap<>(detectedDevices);
            detectedDevices.clear();
        }

        // Process detected devices
        LinkedHashMap<String, String> unknownDevices = new LinkedHashMap<>();
        map.forEach((ipAddress, reference) -> {
            log.trace("UnknownDeviceRegistrationService: Processing device data: ipAddress={}, reference={}", ipAddress, reference);

            // Check if device is registered
            Optional<Device> device = deviceManagementService.getByIpAddress(ipAddress.trim());
            if (device.isEmpty() || !reference.equalsIgnoreCase(device.get().getNodeReference())) {
                // Device is not registered
                log.trace("UnknownDeviceRegistrationService: Unknown device: ipAddress={}, reference={}, device={}", ipAddress, reference, device.orElse(null));
                unknownDevices.put(ipAddress, reference);
            } else {
                // Device is already registered
                log.trace("UnknownDeviceRegistrationService: Device is already registered: ipAddress={}, device={}",
                        ipAddress, device.get());
            }
        });

        log.trace("UnknownDeviceRegistrationService: Unknown devices: {}", unknownDevices);
        if (!unknownDevices.isEmpty())
            processUnknownDevices(unknownDevices);
        log.trace("UnknownDeviceRegistrationService: END: Unknown devices: {}", unknownDevices);
    }

    private void processUnknownDevices(LinkedHashMap<String, String> unknownDevices) {
        try {
            log.info("UnknownDeviceRegistrationService: Unknown devices: {}", unknownDevices);
            MessageProducer producer = session.createProducer(
                    new ActiveMQTopic(monitorProperties.getDeviceInfoRequestsTopic()));

            unknownDevices.forEach((ipAddress, reference) -> {
                try {
                    // Query EMS for device info
                    log.debug("UnknownDeviceRegistrationService: Sending Node-Details-Request Message: ipAddress={}, reference={}",
                            ipAddress, reference);
                    Map<String, String> request = new LinkedHashMap<>(Map.of(
                            "requestType", REQUEST_TYPE.NODE_DETAILS.name(),
                            "deviceIpAddress", ipAddress,
                            "reference", reference
                    ));
                    ActiveMQTextMessage message = new ActiveMQTextMessage();
                    message.setText(objectMapper.writeValueAsString(request));

                    log.debug("UnknownDeviceRegistrationService: Sending Node-Details-Request Message: request={}", message.getText());
                    producer.send(message);
                    log.debug("UnknownDeviceRegistrationService: Node-Details-Request Message sent: ipAddress={}", ipAddress);
                } catch (Exception e) {
                    log.error("UnknownDeviceRegistrationService: ERROR while creating Node-Details-Request Message: ", e);
                }
            });
        } catch (JMSException e) {
            log.error("UnknownDeviceRegistrationService: ERROR while creating Message producer: ", e);
        }
    }

    private void processDeviceDetailsResponses() {
        // Copy and clear the device-details responses queue
        LinkedList<Map> list;
        synchronized (deviceDetailsQueue) {
            list = new LinkedList<>(deviceDetailsQueue);
            deviceDetailsQueue.clear();
        }

        // Process device details responses
        list.forEach((map) -> {
            try {
                log.debug("UnknownDeviceRegistrationService: Processing device details response: {}", map);

                // Collect needed data from response
                String os = map.getOrDefault("os", null).toString();
                String name = map.getOrDefault("name", null).toString();
                String owner = "-EMS-";
                String ipAddress = map.getOrDefault("deviceIpAddress", null).toString();
                String username = map.getOrDefault("username", null).toString();
                char[] password = map.getOrDefault("password", "").toString().toCharArray();
                char[] publicKey = map.getOrDefault("key", "").toString().toCharArray();

                String requestId = map.getOrDefault("requestId", "").toString();
                DeviceStatus status = DeviceStatus.NEW_DEVICE;
                String state = map.getOrDefault("state", "").toString();
                String nodeReference = map.getOrDefault("reference", "").toString();

                Map<String, String> deviceInfo = new LinkedHashMap<>();
                if (map.get("nodeInfo") instanceof Map<?, ?> nodeInfoMap) {
                    nodeInfoMap.forEach((k, v) -> {
                        if (k != null && v != null) {
                            String key = k.toString().trim();
                            String val = v.toString();
                            if (StringUtils.isNotBlank(key)) {
                                deviceInfo.put(key, val);
                            }
                        }
                    });
                }

                log.debug("""
                                UnknownDeviceRegistrationService: Device data collected from device details response:
                                  - os={}
                                  - name={}
                                  - owner={}
                                  - requestId={}
                                  - ipAddress={}
                                  - reference={}
                                  - username={}
                                  - password={}
                                  - key={}
                                  - status={}
                                  - state={}
                                  - deviceInfo={}
                                """,
                        os, name, owner, requestId, ipAddress, nodeReference,
                        username, password, publicKey, status, state, deviceInfo);

                Device newDevice = Device.builder()
                        .name(name)
                        .owner("--EMS--")
                        .requestId(requestId)
                        .ipAddress(ipAddress)
                        .nodeReference(nodeReference)
                        .os(os)
                        .username(username)
                        .password(password)
                        .publicKey(publicKey)
                        .status(status)
                        .deviceInfo(deviceInfo)
                        .build();
                newDevice = deviceManagementService.save(newDevice);
                log.info("UnknownDeviceRegistrationService: Registered device: {}", newDevice);
            } catch (Exception e) {
                log.warn("UnknownDeviceRegistrationService: EXCEPTION while processing device details response: {}\nException: ", map, e);
            }
        });

        log.trace("UnknownDeviceRegistrationService: END: Completed processing device-details responses");
    }
}
