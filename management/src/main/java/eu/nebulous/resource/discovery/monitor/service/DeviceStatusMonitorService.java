
package eu.nebulous.resource.discovery.monitor.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.nebulous.resource.discovery.ResourceDiscoveryProperties;
import eu.nebulous.resource.discovery.monitor.model.Device;
import eu.nebulous.resource.discovery.monitor.model.DeviceStatusUpdate;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
public class DeviceStatusMonitorService extends AbstractMonitorService {
    private final DeviceManagementService deviceManagementService;

    public DeviceStatusMonitorService(ResourceDiscoveryProperties monitorProperties, TaskScheduler taskScheduler, ObjectMapper objectMapper, DeviceManagementService deviceManagementService) {
        super(monitorProperties, taskScheduler, objectMapper);
        this.deviceManagementService = deviceManagementService;
    }

    @Override
    protected @NonNull List<String> getTopicsToMonitor() {
        return List.of(monitorProperties.getDeviceStatusMonitorTopic());
    }

    protected void processPayload(@NonNull Map<?,?> dataMap) {
        Object obj = dataMap.get("message");
        if (obj==null) {
            log.debug("DeviceStatusMonitorService: Message does not contain device status info (message field is null): {}", dataMap);
            return;
        }
        if (obj instanceof Map<?,?> infoMap) {
            if (infoMap.isEmpty())
                log.debug("DeviceStatusMonitorService: Device status map (message field) is empty: {}", dataMap);
            else
                updateDeviceInfo(infoMap);
        } else {
            log.debug("DeviceStatusMonitorService: Message is not a device status update (message field is not a map): {}", dataMap);
        }
    }

    private void updateDeviceInfo(@NonNull Map<?, ?> infoMap) {
        try {
            @NonNull DeviceStatusUpdate deviceStatusUpdate = objectMapper.convertValue(infoMap, DeviceStatusUpdate.class);

            // Get registered device using IP address
            String ipAddress = deviceStatusUpdate.getIpAddress();
            Optional<Device> result = deviceManagementService.getByIpAddress(ipAddress);
            if (result.isEmpty()) {
                log.warn("DeviceStatusMonitorService: Device status update IP address does not match any registered device: {}", infoMap);
                return;
            }
            Device device = result.get();

            // Further check device reference
            if (! device.getNodeReference().equals(deviceStatusUpdate.getReference())) {
                log.debug("DeviceStatusMonitorService: Device status update node reference does NOT match to the registered device's one: update={}, registered-device={}", deviceStatusUpdate, device);
                log.warn("DeviceStatusMonitorService: Device status update node reference does NOT match to the registered device's one: id={}, update-ref={}, registered-device-ref={}",
                        device.getId(), deviceStatusUpdate.getReference(), device.getNodeReference());
                return;
            }

            // Check if the received device status update has no state
            if (StringUtils.isBlank(deviceStatusUpdate.getState())) {
                log.warn("DeviceStatusMonitorService: Device status update has empty state field. Ignoring it: update={}", deviceStatusUpdate);
                return;
            }

            // Check if the received device status update is older than the cached one
            if (device.getStatusUpdate()!=null && device.getStatusUpdate().getStateLastUpdate()!=null) {
                if (device.getStatusUpdate().getStateLastUpdate().isAfter(deviceStatusUpdate.getStateLastUpdate())) {
                    log.warn("DeviceStatusMonitorService: Device status update received is older than the cached one. Ignoring it: id={}, update-timestamp={}, registered-timestamp={}",
                            device.getId(), deviceStatusUpdate.getStateLastUpdate(), device.getStatusUpdate().getStateLastUpdate());
                    return;
                }
            }

            // Update device data
            device.setStatusUpdate(deviceStatusUpdate);
            deviceManagementService.update(device);
            log.debug("DeviceStatusMonitorService: Device status updated for device: id={}, ip-address={}, update={}",
                    device.getId(), device.getIpAddress(), deviceStatusUpdate);
        } catch (Exception e) {
            log.warn("DeviceStatusMonitorService: EXCEPTION while converting device status update info map to DeviceStatus object: {}\n", infoMap, e);
        }
    }
}
