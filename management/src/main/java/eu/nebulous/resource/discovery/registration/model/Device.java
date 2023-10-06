package eu.nebulous.resource.discovery.registration.model;

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
    private char[] password;
    private char[] publicKey;
    private Map<String, String> deviceInfo;
}
