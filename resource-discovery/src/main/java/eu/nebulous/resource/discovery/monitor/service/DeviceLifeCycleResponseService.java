
package eu.nebulous.resource.discovery.monitor.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.nebulous.resource.discovery.common.BrokerUtil;
import eu.nebulous.resource.discovery.common.REQUEST_TYPE;
import eu.nebulous.resource.discovery.ResourceDiscoveryProperties;
import eu.nebulous.resource.discovery.monitor.model.Device;
import eu.nebulous.resource.discovery.monitor.model.DeviceStatus;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@Service
public class DeviceLifeCycleResponseService extends AbstractMonitorService {
    private final DeviceManagementService deviceManagementService;

    public DeviceLifeCycleResponseService(ResourceDiscoveryProperties monitorProperties, TaskScheduler taskScheduler,
                                          ObjectMapper objectMapper, DeviceManagementService deviceManagementService,
                                          BrokerUtil brokerUtil)
    {
        super("DeviceLifeCycleResponseService", monitorProperties, taskScheduler, objectMapper, brokerUtil);
        this.deviceManagementService = deviceManagementService;
    }

    @Override
    protected @NonNull List<String> getTopicsToMonitor() {
        return List.of(monitorProperties.getDeviceLifeCycleResponsesTopic());
    }

    @Override
    protected void processPayload(Map<?, ?> dataMap) {
        log.trace("DeviceLifeCycleResponseService: BEGIN: {}", dataMap);
        if (dataMap==null || dataMap.isEmpty()) {
            log.debug("DeviceLifeCycleResponseService: Device Life-Cycle map is empty: {}", dataMap);
            return;
        }

        // Extract needed message data
        Map<String, Object> responseMap = dataMap.entrySet().stream()
                .filter(e -> e.getKey() != null)
                .collect(Collectors.toMap(e -> e.getKey().toString(), Map.Entry::getValue));

        String requestTypeStr = responseMap.getOrDefault("requestType", "").toString();
        String requestId = responseMap.getOrDefault("requestId", "").toString();
        String deviceId = responseMap.getOrDefault("deviceId", "").toString();
        String ipAddress = responseMap.getOrDefault("deviceIpAddress", "").toString();
        String reference = responseMap.getOrDefault("reference", "").toString();
        String status = responseMap.getOrDefault("status", "").toString();
        log.debug("DeviceLifeCycleResponseService: Device Life-Cycle map data:: requestType={}, status={}, requestId={}, deviceId={}, ipAddress={}, reference={}",
                requestTypeStr, status, requestId, deviceId, ipAddress, reference);

        // Check if we process the indicated requestType
        REQUEST_TYPE requestType = REQUEST_TYPE.valueOf(requestTypeStr);
        if (requestType!=REQUEST_TYPE.REINSTALL && requestType!=REQUEST_TYPE.UNINSTALL) {
            log.debug("DeviceLifeCycleResponseService: Ignoring message due to its requestType: requestType={}, status={}, requestId={}, deviceId={}, ipAddress={}, reference={}",
                    requestType, status, requestId, deviceId, ipAddress, reference);
            return;
        }

        // Check if we have all needed fields
        if (StringUtils.isBlank(ipAddress) || StringUtils.isBlank(reference) || StringUtils.isBlank(status)) {
            log.warn("DeviceLifeCycleResponseService: Ignoring message because ipAddress, reference, or status field is missing: requestType={}, status={}, requestId={}, deviceId={}, ipAddress={}, reference={}",
                    requestType, status, requestId, deviceId, ipAddress, reference);
            return;
        }

        // Find device record
        Optional<Device> opt = deviceManagementService.getByIpAddress(ipAddress);
        if (opt.isEmpty()) {
            log.warn("DeviceLifeCycleResponseService: Not found device with given ipAddress: requestType={}, status={}, requestId={}, deviceId={}, ipAddress={}, reference={}",
                    requestType, status, requestId, deviceId, ipAddress, reference);
            return;
        }
        Device device = opt.get();

        // Check if reference matches
        if (StringUtils.isBlank(device.getNodeReference()) || ! device.getNodeReference().equals(reference)) {
            log.warn("DeviceLifeCycleResponseService: Reference mismatch: requestType={}, status={}, requestId={}, deviceId={}, ipAddress={}, reference={}, device-reference={}",
                    requestType, status, requestId, deviceId, ipAddress, reference, device.getNodeReference());
            return;
        }
        // Device identified

        // Process by requestType
        if (requestType==REQUEST_TYPE.REINSTALL)
            processReinstallMessage(responseMap, device, requestType, requestId, deviceId, ipAddress, reference, status);
        else
            processUninstallMessage(responseMap, device, requestType, requestId, deviceId, ipAddress, reference, status);

        log.trace("DeviceLifeCycleResponseService: END: requestType={}, status={}, requestId={}, deviceId={}, ipAddress={}, reference={}, device-reference={}",
                requestType, status, requestId, deviceId, ipAddress, reference, device.getNodeReference());
    }

    private void processReinstallMessage(Map<String, Object> responseMap, Device device, REQUEST_TYPE requestType, String requestId, String deviceId, String ipAddress, String reference, String status) {
        // Update device state
        DeviceStatus newStatus;
        if ("SUCCESS".equalsIgnoreCase(status)) {
            device.setStatus(newStatus = DeviceStatus.ONBOARDED);
        } else {
            device.setStatus(newStatus = DeviceStatus.ONBOARD_ERROR);
        }
        deviceManagementService.update(device);

        log.debug("DeviceLifeCycleResponseService: processReinstallMessage: Device status updated: newStatus={} --requestType={}, status={}, requestId={}, deviceId={}, ipAddress={}, reference={}, device-reference={}",
                newStatus, requestType, status, requestId, deviceId, ipAddress, reference, device.getNodeReference());
    }

    private void processUninstallMessage(Map<String, Object> responseMap, Device device, REQUEST_TYPE requestType, String requestId, String deviceId, String ipAddress, String reference, String status) {
        // Update device state
        DeviceStatus newStatus;
        if ("SUCCESS".equalsIgnoreCase(status)) {
            device.setStatus(newStatus = DeviceStatus.OFFBOARDED);
        } else {
            device.setStatus(newStatus = DeviceStatus.OFFBOARD_ERROR);
        }
        deviceManagementService.update(device);

        log.debug("DeviceLifeCycleResponseService: processUninstallMessage: Device status updated: newStatus={} --requestType={}, status={}, requestId={}, deviceId={}, ipAddress={}, reference={}, device-reference={}",
                newStatus, requestType, status, requestId, deviceId, ipAddress, reference, device.getNodeReference());

        // Archive device, if successfully off-boarded
        if (newStatus==DeviceStatus.OFFBOARDED && monitorProperties.isImmediatelyArchiveOffboardedDevices()) {
            deviceManagementService.archiveDevice(device.getId());
            log.debug("DeviceLifeCycleResponseService: processUninstallMessage: Device ARCHIVED: id={}, ip-address={}, reference={}",
                    device.getId(), device.getIpAddress(), device.getNodeReference());
        }
    }
}
