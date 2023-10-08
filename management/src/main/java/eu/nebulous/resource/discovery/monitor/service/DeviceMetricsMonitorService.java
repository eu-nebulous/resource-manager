
package eu.nebulous.resource.discovery.monitor.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.nebulous.resource.discovery.ResourceDiscoveryProperties;
import eu.nebulous.resource.discovery.monitor.model.Device;
import eu.nebulous.resource.discovery.monitor.model.DeviceMetrics;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;

import java.io.Serializable;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@Service
public class DeviceMetricsMonitorService extends AbstractMonitorService {
    private final DeviceManagementService deviceManagementService;

    public DeviceMetricsMonitorService(ResourceDiscoveryProperties monitorProperties, TaskScheduler taskScheduler, ObjectMapper objectMapper, DeviceManagementService deviceManagementService) {
        super(monitorProperties, taskScheduler, objectMapper);
        this.deviceManagementService = deviceManagementService;
    }

    @Override
    protected @NonNull List<String> getTopicsToMonitor() {
        return List.of(monitorProperties.getDeviceMetricsMonitorTopic());
    }

    protected void processPayload(@NonNull Map<?,?> dataMap) {
        Object obj = dataMap.get("message");
        if (obj==null) {
            log.debug("DeviceMetricsMonitorService: Message does not contain device metrics (message field is null): {}", dataMap);
            return;
        }
        if (obj instanceof Map<?,?> infoMap) {
            if (infoMap.isEmpty())
                log.debug("DeviceMetricsMonitorService: Device metrics map (message field) is empty: {}", dataMap);
            else
                updateDeviceMetrics(infoMap);
        } else {
            log.debug("DeviceMetricsMonitorService: Message is not a device metrics (message field is not a map): {}", dataMap);
        }
    }

    private void updateDeviceMetrics(@NonNull Map<?, ?> infoMap) {
        try {
            @NonNull Map<?,?> metricsMap = objectMapper.convertValue(infoMap, Map.class);

            // Extract required data from metrics map
            String clientId = stringValue(metricsMap.get("clientId"));
            String ipAddress = stringValue(metricsMap.get("ipAddress"));
            String timestampStr = stringValue(metricsMap.get("receivedAtServer"));
            if (clientId.isEmpty() || ipAddress.isEmpty() || timestampStr.isEmpty()) {
                log.warn("DeviceMetricsMonitorService: Device metrics received do not contain clientId or ipAddress or receivedAtServer. Ignoring them: {}", metricsMap);
                return;
            }
            Instant timestamp = StringUtils.isNotBlank(timestampStr)
                    ? Instant.parse(timestampStr) : null;

            // Get registered device using IP address
            Optional<Device> result = deviceManagementService.getByIpAddress(ipAddress);
            if (result.isEmpty()) {
                log.warn("DeviceMetricsMonitorService: Device metrics IP address does not match any registered device: {}", infoMap);
                return;
            }
            Device device = result.get();

            // Check if the received device metrics are older than the cached
            if (device.getMetrics()!=null && device.getMetrics().getTimestamp()!=null && timestamp!=null) {
                if (device.getMetrics().getTimestamp().isAfter(timestamp)) {
                    log.warn("DeviceMetricsMonitorService: Device metrics received are older than the cached. Ignoring them: id={}, update-timestamp={}, registered-timestamp={}",
                            device.getId(), timestamp, device.getMetrics().getTimestamp());
                    return;
                }
            }

            // Prepare DeviceMetrics object
            metricsMap.remove("clientId");
            metricsMap.remove("ipAddress");
            metricsMap.remove("receivedAtServer");
            metricsMap.remove("_received_at_server_timestamp");
            Object latestEventsObj = metricsMap.remove("latest-events");
            List<Object> latestEvents = (latestEventsObj instanceof List list) ? list : Collections.emptyList();
            Map<String, Object> metricsMapClean = metricsMap.entrySet().stream()
                    .filter(e -> e.getKey()!=null)
                    .filter(e -> e.getValue()!=null)
                    .collect(Collectors.toMap(
                            e -> e.getKey().toString(),
                            e -> (Serializable) e.getValue()
                    ));

            DeviceMetrics metrics = new DeviceMetrics();
            metrics.setClientId(clientId);
            metrics.setIpAddress(ipAddress);
            metrics.setTimestamp(timestamp);
            metrics.setMetrics(metricsMapClean);
            metrics.setLatestEvents(latestEvents);

            // Update device data
            device.setMetrics(metrics);
            deviceManagementService.update(device);
            log.debug("DeviceMetricsMonitorService: Device metrics updated for device: id={}, ip-address={}, update={}",
                    device.getId(), device.getIpAddress(), metrics);
        } catch (Exception e) {
            log.warn("DeviceMetricsMonitorService: EXCEPTION while processing device metrics map: {}\n", infoMap, e);
        }
    }

    protected String stringValue(Object o) {
        if (o==null) return null;
        return o.toString().trim();
    }
}
