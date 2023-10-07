package eu.nebulous.resource.discovery;

import lombok.Data;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Data
@Configuration
@ConfigurationProperties(prefix = "discovery")
public class ResourceDiscoveryProperties {
	// Broker configuration
	private String brokerUsername;
	@ToString.Exclude
	private String brokerPassword;
	private String brokerURL;

	// Subscription to Broker settings
	private long subscriptionStartupDelay = 10;
	private long subscriptionRetryDelay = 60;

	// Sample data creation settings
	private boolean createSampleDataAtStartup;
	private boolean createSampleDataPeriodically;
	private int createSampleDataStartupDelay = 30;
	private int createSampleDataPeriod = 60;
	private String createSampleDataOwner = "admin";

	// Registration request processing settings
	private boolean enablePeriodicProcessing = true;
	private long processingStartupDelay = 10;
	private long processingPeriod = 60;

	// Data collection settings
	private String dataCollectionRequestTopic = "ems.client.installation.requests";
	private String dataCollectionResponseTopic = "ems.client.installation.reports";
	private List<String> allowedDeviceInfoKeys = new ArrayList<>(List.of("*"));

	// Device monitoring settings
	private String deviceStatusMonitorTopic = "_ui_instance_info";	//XXX:TODO: Change Topic name. Also update EMS config.

	// Archiving settings
	private boolean automaticArchivingEnabled;
	private long archivingThreshold;				// in minutes

	// Users
	private List<UserData> users;

	@Data
	public static class UserData {
		private final String username;
		@ToString.Exclude
		private final String password;
		private final List<String> roles;
	}
}
