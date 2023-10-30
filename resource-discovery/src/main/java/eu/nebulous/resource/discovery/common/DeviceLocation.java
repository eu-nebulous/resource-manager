package eu.nebulous.resource.discovery.common;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@Data
@SuperBuilder
@NoArgsConstructor
public class DeviceLocation {
    private String id;
    private String name;
    private String continent;
    private String continentCode;
    private String country;
    private String countryCode;
    private String state;
    private String stateCode;
    private String city;
    private String zipcode;
    private String address;
    private String extra;
    private double latitude;
    private double longitude;
}
