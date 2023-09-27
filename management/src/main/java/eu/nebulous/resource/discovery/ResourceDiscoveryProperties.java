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
	private long subscriptionStartupDelay = 10;
	private long subscriptionRetry = 60;
	private boolean enablePeriodicProcessing = true;
	private long processingStartupDelay = 10;
	private long processingPeriod = 60;

	private boolean createSampleDataAtStartup;
	private boolean createSampleDataPeriodically;
	private int createSampleDataStartupDelay = 30;
	private int createSampleDataPeriod = 60;
	private String createSampleDataOwner = "admin";

	private String dataCollectionRequestTopic = "ems.client.installation.requests";
	private String dataCollectionResponseTopic = "ems.client.installation.reports";
	private List<String> allowedDeviceInfoKeys = new ArrayList<>(List.of("*"));

	private boolean automaticArchivingEnabled;
	private long archivingThreshold;				// in minutes

	private String brokerUsername;
	@ToString.Exclude
	private String brokerPassword;
	private String brokerURL;

	private List<UserData> users;

	@Data
	public static class UserData {
		private final String username;
		@ToString.Exclude
		private final String password;
		private final List<String> roles;
	}
}
