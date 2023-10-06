package eu.nebulous.resource.discovery.registration;

import eu.nebulous.resource.discovery.ResourceDiscoveryProperties;
import eu.nebulous.resource.discovery.registration.model.Device;
import eu.nebulous.resource.discovery.registration.model.RegistrationRequest;
import eu.nebulous.resource.discovery.registration.model.RegistrationRequestStatus;
import eu.nebulous.resource.discovery.registration.service.RegistrationRequestService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class RegistrationRequestService_SampleDataCreator implements InitializingBean {
	private final ResourceDiscoveryProperties properties;
	private final RegistrationRequestService registrationRequestService;
	private final TaskScheduler taskScheduler;
	private int cnt;

	@Override
	public void afterPropertiesSet() throws Exception {
		// Create initial sample data
		if (properties.isCreateSampleDataAtStartup()) {
			for (int ii = 0; ii < RegistrationRequestStatus.values().length; ii++) {
				registrationRequestService.addRequest(
						createRegistrationRequest(ii, "user_" + (ii % 5)));
			}
		}

		// Schedule periodic sample data creation
		if (properties.isCreateSampleDataPeriodically()) {
			cnt = RegistrationRequestStatus.values().length;
			taskScheduler.scheduleAtFixedRate(
					() -> registrationRequestService.addRequest(
							createRegistrationRequest((cnt++) % 100, properties.getCreateSampleDataOwner())),
					Instant.now().plusSeconds(properties.getCreateSampleDataStartupDelay()),
					Duration.ofSeconds(properties.getCreateSampleDataPeriod()));
		}
	}

	private RegistrationRequest createRegistrationRequest(int pos, String owner) {
		RegistrationRequestStatus[] statuses = RegistrationRequestStatus.values();
		return RegistrationRequest.builder()
				.id(UUID.randomUUID().toString())
				.device( createDevice(pos, owner) )
				.requester(owner)
				.requestDate(Instant.now())
				.status(RegistrationRequestStatus.NEW_REQUEST)
				.build();
	}

	private Device createDevice(int pos, String owner) {
		return Device.builder()
				.id( UUID.randomUUID().toString() )
				.name( "Device name #"+pos )
				.os("LINUX")
				.owner(owner)
				.ipAddress("10.10.0."+(100+pos))
				.username("ubuntu_"+pos)
				.password("password".toCharArray())
				.publicKey("===== PEM public key =====".toCharArray())
				.deviceInfo(new HashMap<>())
				.build();
	}
}
