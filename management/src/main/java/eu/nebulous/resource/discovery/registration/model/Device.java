package eu.nebulous.resource.discovery.registration.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import eu.nebulous.resource.discovery.common.DeviceLocation;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.util.Map;

@Data
@SuperBuilder
@NoArgsConstructor
public class Device {
    private String id;
    private String os;
    private String name;
    private String owner;
    private String ipAddress;
    private DeviceLocation location;
    private String username;
    @ToString.Exclude
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private char[] password;
    @ToString.Exclude
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private char[] publicKey;
    private Map<String, String> deviceInfo;
}
