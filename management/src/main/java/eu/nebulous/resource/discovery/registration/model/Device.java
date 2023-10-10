package eu.nebulous.resource.discovery.registration.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;
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
    private String username;
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private char[] password;
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private char[] publicKey;
    private Map<String, String> deviceInfo;
}
