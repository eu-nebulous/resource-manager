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
@ConfigurationProperties(prefix = ResourceDiscoveryProperties.CONFIG_PREFIX)
public class ResourceDiscoveryProperties {
	public final static String CONFIG_PREFIX = "discovery";

	// Broker configuration
	private String brokerURL;
	private String brokerUsername;
	@ToString.Exclude
	private String brokerPassword;

	private String keyStoreFile;
	private String keyStorePassword;
	private String keyStoreType = "PKCS12";
	private String trustStoreFile;
	private String trustStorePassword;
	private String trustStoreType = "PKCS12";

	private int connectionHealthCheckPeriod = 60;			// in seconds
	private String healthCheckTopic = "_HEALTH_CHECK";

	// Subscription to Broker settings
	private long subscriptionStartupDelay = 10;
	private long subscriptionRetryDelay = 60;

	// Sample data creation settings
	private boolean createSampleDataAtStartup;
	private boolean createSampleDataPeriodically;
	private int createSampleDataStartupDelay = 30;
	private int createSampleDataPeriod = 60;
	private String createSampleDataOwner = "admin";

	// Device and Registration request processing settings (DeviceProcessor, RegistrationRequestProcessor)
	private boolean enablePeriodicProcessing = true;
	private long processingStartupDelay = 10;
	private long processingPeriod = 60;

	// Data collection settings
	private String dataCollectionRequestTopic = "ems.client.installation.requests";
	private String dataCollectionResponseTopic = "ems.client.installation.reports";
	private List<String> allowedDeviceInfoKeys = new ArrayList<>(List.of("*"));

	// Device authorization settings
	private AUTHORIZATION_TYPE authorizationType = AUTHORIZATION_TYPE.ALWAYS_AUTHORIZE;

	// Device monitoring settings
	private String deviceStatusMonitorTopic = "_ui_instance_info";	//XXX:TODO: Change Topic name. Also update EMS config.
	private String deviceMetricsMonitorTopic = "_client_metrics";	//XXX:TODO: Change Topic name. Also update EMS config.

	private String deviceLifeCycleRequestsTopic = "ems.client.installation.requests";
	private String deviceLifeCycleResponsesTopic = "ems.client.installation.reports";

	// SAL registration settings
	private boolean salRegistrationEnabled = true;
	private long salRegistrationTimeout = 60*1000;
	private String registration_topic_name = "eu.nebulouscloud.exn.sal.node.create";
	private String deregistration_topic_prefix = "eu.nebulouscloud.exn.sal.edge.node.delete";
	
	// Failed devices detection
	private boolean automaticFailedDetection = true;
	private long suspectDeviceThreshold = 5;				// in minutes
	private long failedDeviceThreshold = 10;				// in minutes

	// Device detailed data settings
	private String deviceInfoRequestsTopic = "ems.client.info.requests";
	private String deviceInfoResponsesTopic = "ems.client.info.reports";

	// Archiving settings
	private boolean automaticArchivingEnabled;
	private long archivingThreshold;						// in minutes
	private boolean immediatelyArchiveSuccessRequests = true;
	private boolean immediatelyArchiveOffboardedDevices = true;

	// Encryption settings
	private boolean enableEncryption;						// Set to 'true' to enable message encryption
	private boolean usePasswordGeneratedKey = true;
	private String generatedKeyFile;						// NOTE: If blank, the key will be logged
	private String keyPasswordFile;							// If provided, it will override the next settings
	private char[] symmetricKeyPassword;
	private byte[] salt;

	// Users
	private List<UserData> users;

	// API-Key authentication fields
	private boolean apiKeyAuthenticationEnabled;
	private String apiKeyValue;

	// Nebulous broker subscription details
	private String nebulous_broker_ip_address;
	private int nebulous_broker_port;
	private String nebulous_broker_username;
	private String nebulous_broker_password;
	private boolean deregistration_emulated;
	private String lost_device_topic;
	private String compromised_device_topic;
	
	// Nebulous server data
	private String nebulous_server_ip_address;

	public enum AUTHORIZATION_TYPE {
		NONE, MANUAL,
		ALWAYS_AUTHORIZE, ALWAYS_REJECT
		//, CALL_REST_API, ASK_ABAC_SERVER
	}

	@Data
	public static class UserData {
		private final String username;
		@ToString.Exclude
		private final String password;
		private final List<String> roles;
	}
}
