package eu.nebulous.resource.discovery.registration;

import eu.nebulous.resource.discovery.registration.model.Device;
import eu.nebulous.resource.discovery.registration.model.RegistrationRequest;
import eu.nebulous.resource.discovery.registration.model.RegistrationRequestException;
import eu.nebulous.resource.discovery.registration.model.RegistrationRequestStatus;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Slf4j
@Service
public class RegistrationRequestService {
	private final LinkedList<RegistrationRequest> requests;

	public RegistrationRequestService() {
		int ii = 0;
		requests = new LinkedList<>( Arrays.asList(
				createRegistrationRequest(ii++, "user_"+(ii%5)),
				createRegistrationRequest(ii++, "user_"+(ii%5)),
				createRegistrationRequest(ii++, "user_"+(ii%5)),
				createRegistrationRequest(ii++, "user_"+(ii%5)),
				createRegistrationRequest(ii++, "user_"+(ii%5)),
				createRegistrationRequest(ii++, "user_"+(ii%5)),
				createRegistrationRequest(ii++, "user_"+(ii%5)),
				createRegistrationRequest(ii++, "user_"+(ii%5)),
				createRegistrationRequest(ii++, "user_"+(ii%5)),
				createRegistrationRequest(ii++, "user_"+(ii%5)),
				createRegistrationRequest(ii++, "user_"+(ii%5)),
				createRegistrationRequest(ii++, "user_"+(ii%5)),
				createRegistrationRequest(ii++, "user_"+(ii%5))
		) );
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

	// ------------------------------------------------------------------------

	public Optional<RegistrationRequest> getById(@NonNull String id) {
		return requests.stream().filter(rr -> id.equals(rr.getId())).findAny();
	}

	public List<RegistrationRequest> getAll() {
		return Collections.unmodifiableList(requests);
	}

	public @NonNull RegistrationRequest save(@NonNull RegistrationRequest registrationRequest) {
		RegistrationRequestStatus status = registrationRequest.getStatus();
		if (status == null) {
			registrationRequest.setStatus(RegistrationRequestStatus.NEW_REQUEST);
		}
		if (status != null && status != RegistrationRequestStatus.NEW_REQUEST) {
			throw new RegistrationRequestException("Cannot save a new registration request with status "+status);
		}
		if (StringUtils.isBlank(registrationRequest.getId())) {
			registrationRequest.setId( UUID.randomUUID().toString() );
		}
		if (getById(registrationRequest.getId()).isPresent())
			throw new RegistrationRequestException(
					"A registration request with the same Id already exists in repository: "+registrationRequest.getId());
		checkRegistrationRequest(registrationRequest);
		requests.add(registrationRequest);
		return registrationRequest;
	}

	public RegistrationRequest update(@NonNull RegistrationRequest registrationRequest) {
		Optional<RegistrationRequest> result = getById(registrationRequest.getId());
		if (result.isEmpty())
			throw new RegistrationRequestException(
					"Registration request with the Id does not exists in repository: "+registrationRequest.getId());
		checkRegistrationRequest(registrationRequest);
		int index = requests.indexOf(result.get());
		requests.set(index, registrationRequest);
		return getById(registrationRequest.getId()).orElseThrow(() ->
				new RegistrationRequestException("Request update failed for Id: "+registrationRequest.getId()));
	}

	private void checkRegistrationRequest(@NonNull RegistrationRequest registrationRequest) {
		List<String> errors = new ArrayList<>();
		if (StringUtils.isBlank(registrationRequest.getId())) errors.add("Null or blank Id");
		if (registrationRequest.getDevice()==null) errors.add("No Device specified");
		if (StringUtils.isBlank(registrationRequest.getId())) errors.add("Null or blank Requestor");
		if (registrationRequest.getRequestDate()==null) errors.add("Null Request date");
		if (registrationRequest.getStatus()==null) errors.add("Null Status");
		if (!errors.isEmpty()) {
			throw new RegistrationRequestException(
					String.format("Registration request has errors: %s\n%s",
							String.join(", ", errors), registrationRequest));
		}
	}

	public void deleteById(@NonNull String id) {
		Optional<RegistrationRequest> result = getById(id);
		if (result.isEmpty())
			throw new RegistrationRequestException(
					"Registration request with the Id does not exists in repository: "+id);
		requests.remove(result.get());
	}

	public void delete(@NonNull RegistrationRequest registrationRequest) {
		deleteById(registrationRequest.getId());
	}

	// ------------------------------------------------------------------------

	public Optional<RegistrationRequest> getByIdAsUser(@NonNull String id, @NonNull String user) {
		Optional<RegistrationRequest> result = getById(id);
        result.ifPresent(registrationRequest -> checkRequester(registrationRequest, user));
		return result;
	}

	private void checkRequester(@NonNull RegistrationRequest registrationRequest, String user) {
		if (!canAccess(registrationRequest, user))
			throw new RegistrationRequestException(
					new IllegalAccessException("User "+user+" cannot access request with Id: "+registrationRequest.getId()));
	}

	private boolean canAccess(@NonNull RegistrationRequest registrationRequest, String user) {
		String requester = registrationRequest.getRequester();
		if (requester==null && user==null) return true;
        return requester != null && requester.equals(user);
    }

	public List<RegistrationRequest> getAllAsUser(@NonNull String user) {
		return getAll().stream().filter(req -> canAccess(req, user)).toList();
	}

	public @NonNull RegistrationRequest saveAsUser(@NonNull RegistrationRequest registrationRequest, @NonNull String user) {
		checkRequester(registrationRequest, user);
		return save(registrationRequest);
	}

	public RegistrationRequest updateAsUser(@NonNull RegistrationRequest registrationRequest, @NonNull String user) {
		checkRequester(registrationRequest, user);
		Optional<RegistrationRequest> result = getByIdAsUser(registrationRequest.getId(), user);
		if (result.isEmpty())
			throw new RegistrationRequestException(
					"Registration request with the Id does not exists in repository: "+registrationRequest.getId());
		checkRequester(result.get(), user);
		return update(registrationRequest);
	}

	public void deleteByIdAsUser(@NonNull String id, @NonNull String user) {
		Optional<RegistrationRequest> result = getByIdAsUser(id, user);
		if (result.isEmpty())
			throw new RegistrationRequestException(
					"Registration request with the Id does not exists in repository: "+id);
		checkRequester(result.get(), user);
		deleteById(id);
	}

	public void deleteAsUser(@NonNull RegistrationRequest registrationRequest, @NonNull String user) {
		checkRequester(registrationRequest, user);
		deleteByIdAsUser(registrationRequest.getId(), user);
	}
}
