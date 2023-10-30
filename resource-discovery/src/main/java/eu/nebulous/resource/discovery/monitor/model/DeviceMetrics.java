package eu.nebulous.resource.discovery.monitor.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class DeviceMetrics {
    private String ipAddress;
    private String clientId;
    private Instant timestamp;
    private Map<String, Object> metrics;
    private List<Object> latestEvents;
}
