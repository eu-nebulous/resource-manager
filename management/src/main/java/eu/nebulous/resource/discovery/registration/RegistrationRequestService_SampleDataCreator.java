package eu.nebulous.resource.discovery.registration;

import eu.nebulous.resource.discovery.registration.model.Device;
import eu.nebulous.resource.discovery.registration.model.RegistrationRequest;
import eu.nebulous.resource.discovery.registration.model.RegistrationRequestStatus;
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
	private final RegistrationRequestService registrationRequestService;
	private final TaskScheduler taskScheduler;
	private int cnt;

	@Override
	public void afterPropertiesSet() throws Exception {
		// Create initial sample data
		for (int ii=0; ii<RegistrationRequestStatus.values().length; ii++) {
			registrationRequestService.addRequest(
					createRegistrationRequest(ii, "user_" + (ii % 5)));
		}

		// Schedule periodic sample data creation
		cnt = 20;
		taskScheduler.scheduleAtFixedRate(
				() -> registrationRequestService.addRequest(createRegistrationRequest(cnt++, "admin")),
				Instant.now().plusSeconds(80), Duration.ofSeconds(30));
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
				.deviceId( UUID.randomUUID().toString() )
				.deviceName( "Device name #"+pos )
				.owner(owner)
				.ipAddress("10.10.0."+(100+pos))
				.username("ubuntu_"+pos)
				.password("password".toCharArray())
				.publicKey("===== PEM public key =====".toCharArray())
				.deviceInfo(new HashMap<>())
				.build();
	}
}
