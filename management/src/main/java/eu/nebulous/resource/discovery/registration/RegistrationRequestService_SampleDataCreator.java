package eu.nebulous.resource.discovery.registration;

import eu.nebulous.resource.discovery.registration.model.Device;
import eu.nebulous.resource.discovery.registration.model.RegistrationRequest;
import eu.nebulous.resource.discovery.registration.model.RegistrationRequestStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class RegistrationRequestService_SampleDataCreator implements InitializingBean {
	private final RegistrationRequestService registrationRequestService;

	@Override
	public void afterPropertiesSet() throws Exception {
		for (int ii=0; ii<RegistrationRequestStatus.values().length; ii++) {
			registrationRequestService.addRequest(
					createRegistrationRequest(ii, "user_" + (ii % 5)));
		}
	}

	private RegistrationRequest createRegistrationRequest(int pos, String owner) {
		RegistrationRequestStatus[] statuses = RegistrationRequestStatus.values();
		return RegistrationRequest.builder()
				.id(UUID.randomUUID().toString())
				.device( createDevice(pos, owner) )
				.requester(owner)
				.requestDate(Instant.ofEpochMilli(
						Instant.now().minus(30, ChronoUnit.DAYS).toEpochMilli() + pos * 86400L ))
				.status( statuses[ pos%statuses.length ] )
				.build();
	}

	private Device createDevice(int pos, String owner) {
		return Device.builder()
				.deviceId( UUID.randomUUID().toString() )
				.deviceName( "Device name #"+pos )
				.owner(owner)
				.ipAddress("10.10.0."+(100+pos))
				.username("ubuntu_"+pos)
				.password("saggasas".toCharArray())
				.publicKey("===== PEM public key =====".toCharArray())
				.deviceInfo(new HashMap<>())
				.build();
	}
}
