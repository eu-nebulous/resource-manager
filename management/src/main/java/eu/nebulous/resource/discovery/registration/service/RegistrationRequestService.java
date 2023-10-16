package eu.nebulous.resource.discovery.registration.service;

import eu.nebulous.resource.discovery.monitor.service.DeviceManagementService;
import eu.nebulous.resource.discovery.registration.model.*;
import eu.nebulous.resource.discovery.registration.repository.ArchivedRegistrationRequestRepository;
import eu.nebulous.resource.discovery.registration.repository.RegistrationRequestRepository;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class RegistrationRequestService {
	private final RegistrationRequestRepository registrationRequestRepository;
	private final ArchivedRegistrationRequestRepository archivedRegistrationRequestRepository;
	private final RegistrationRequestConversionService registrationRequestConversionService;
	private final DeviceManagementService deviceManagementService;

	/*private final InMemoryRegistrationRequestRepository<RegistrationRequest>
			registrationRequestRepository = new InMemoryRegistrationRequestRepository<>();
	private final InMemoryRegistrationRequestRepository<ArchivedRegistrationRequest>
			archivedRegistrationRequestRepository = new InMemoryRegistrationRequestRepository<>();*/

	// ------------------------------------------------------------------------

	// Used in RegistrationRequestService_SampleDataCreator to create sample requests
	public void addRequest(@NonNull RegistrationRequest registrationRequest) {
		registrationRequestRepository.save(registrationRequest);
	}

	public Optional<RegistrationRequest> getById(@NonNull String id) {
		return registrationRequestRepository.findById(id);
	}

	public List<RegistrationRequest> getAll() {
		return Collections.unmodifiableList(registrationRequestRepository.findAll());
	}

	public boolean isIpAddressInUse(@NonNull String ipAddress, String excludeId) {
		List<RegistrationRequest> result = registrationRequestRepository.findByDeviceIpAddress(ipAddress);
		return result.stream().anyMatch(r -> !r.getId().equals(excludeId));
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
		} else {
			throw new RegistrationRequestException(
					"New registration request already has an Id: " + registrationRequest.getId());
		}
		if (getById(registrationRequest.getId()).isPresent())
			throw new RegistrationRequestException(
					"A registration request with the same Id already exists in repository: "+registrationRequest.getId());
		registrationRequest.setRequestDate(Instant.now());
		checkRegistrationRequest(registrationRequest);

		// check IP address uniqueness
		checkIpAddressUniqueness(registrationRequest);

		registrationRequestRepository.save(registrationRequest);
		return registrationRequest;
	}

	public RegistrationRequest update(@NonNull RegistrationRequest registrationRequest) {
		return update(registrationRequest, true);
	}

	public RegistrationRequest update(@NonNull RegistrationRequest registrationRequest, boolean checkEditDel) {
		return update(registrationRequest, checkEditDel, false);
	}

	public RegistrationRequest update(@NonNull RegistrationRequest registrationRequest, boolean checkEditDel, boolean skipUniqueIpAddressCheck) {
		Optional<RegistrationRequest> result = getById(registrationRequest.getId());
		if (result.isEmpty())
			throw new RegistrationRequestException(
					"Registration request with the Id does not exists in repository: "+registrationRequest.getId());
		checkRegistrationRequest(registrationRequest);
		if (checkEditDel)
			canEditOrDelete(result.get());

		// check IP address uniqueness
		if (!skipUniqueIpAddressCheck)
			checkIpAddressUniqueness(registrationRequest);

		// Copy submitted registration request data onto the retrieved request
		BeanUtils.copyProperties(registrationRequest, result.get(),
				"id", "device", "requester", "requestDate");
		result.get().setLastUpdateDate(Instant.now());

		// Check if device password/public key need update...
		List<String> ignoreList = new ArrayList<>();
		if (isCharArrayIsBlank(registrationRequest.getDevice().getPassword())
				&& isCharArrayIsBlank(registrationRequest.getDevice().getPublicKey()))
		{
			ignoreList.add("password");
			ignoreList.add("publicKey");
		}
		// ...then copy submitted request's device data onto the retrieved request's device
		BeanUtils.copyProperties(
				registrationRequest.getDevice(),
				result.get().getDevice(),
				ignoreList.toArray(new String[0]));

		registrationRequestRepository.save(result.get());

		return getById(registrationRequest.getId()).orElseThrow(() ->
				new RegistrationRequestException("Request update failed for Id: "+registrationRequest.getId()));
	}

	private boolean isCharArrayIsBlank(char[] arr) {
		if (arr==null) return true;
		for (char c : arr)
			if (!isWhiteSpaceChar(c)) return false;
		return true;
	}
	private boolean isWhiteSpaceChar(char c) {
		return c==' ' || c=='\t' || c=='\r' || c=='\n';
	}

	private void checkRegistrationRequest(@NonNull RegistrationRequest registrationRequest) {
		List<String> errors = new ArrayList<>();
		if (StringUtils.isBlank(registrationRequest.getId())) errors.add("Null or blank Id");
		if (registrationRequest.getDevice()==null) errors.add("No Device specified");
		if (StringUtils.isBlank(registrationRequest.getRequester())) errors.add("Null or blank Requester");
		if (registrationRequest.getRequestDate()==null) errors.add("Null Request date");
		if (registrationRequest.getStatus()==null) errors.add("Null Status");
		if (!errors.isEmpty()) {
			throw new RegistrationRequestException(
					String.format("Registration request has errors: %s\n%s",
							String.join(", ", errors), registrationRequest));
		}
		checkDevice(registrationRequest.getDevice());
	}

	private void checkDevice(@NonNull Device device) {
		//XXX:TODO
	}

	private void checkIpAddressUniqueness(RegistrationRequest registrationRequest) {
		boolean exists1 = this.isIpAddressInUse(
				registrationRequest.getDevice().getIpAddress(), registrationRequest.getId());
		boolean exists2 = deviceManagementService.isIpAddressInUse(
				registrationRequest.getDevice().getIpAddress());
		if (exists1 || exists2) {
			throw new RegistrationRequestException(
					"The IP address is already in use by: another-registration-request="+exists1+", registered-device="+exists2);
		}
	}

	private void canEditOrDelete(RegistrationRequest registrationRequest) {
		RegistrationRequestStatus status = registrationRequest.getStatus();
		if (status==RegistrationRequestStatus.ONBOARDING_REQUESTED || status== RegistrationRequestStatus.SUCCESS)
			throw new RegistrationRequestException(
					"Registration request with the Id cannot be edited or deleted due to its status: "
							+ registrationRequest.getId() + ", status=" + status);
	}

	public void deleteById(@NonNull String id) {
		Optional<RegistrationRequest> result = getById(id);
		if (result.isEmpty())
			throw new RegistrationRequestException(
					"Registration request with the Id does not exists in repository: "+id);
		canEditOrDelete(result.get());
		registrationRequestRepository.delete(result.get());
		result.get().setLastUpdateDate(Instant.now());
	}

	public void delete(@NonNull RegistrationRequest registrationRequest) {
		registrationRequestRepository.deleteById(registrationRequest.getId());
		registrationRequest.setLastUpdateDate(Instant.now());
	}

	// ------------------------------------------------------------------------

	public Optional<RegistrationRequest> getByIdAsUser(@NonNull String id, Authentication authentication) {
		Optional<RegistrationRequest> result = getById(id);
        result.ifPresent(registrationRequest -> checkRequester(registrationRequest, authentication));
		return result;
	}

	private void checkAdmin(@NonNull String requestId, Authentication authentication) {
		boolean isAdmin = authentication.getAuthorities().stream()
				.map(GrantedAuthority::getAuthority).toList().contains("ROLE_ADMIN");
		if (! isAdmin)
			throw new RegistrationRequestException(
					new IllegalAccessException("Operation requires ADMIN role. Cannot access request with Id: "+requestId));
	}

	private void checkRequester(@NonNull RegistrationRequest registrationRequest, Authentication authentication) {
		if (! canAccess(registrationRequest, authentication))
			throw new RegistrationRequestException(
					new IllegalAccessException("User "+authentication.getName()+" cannot access request with Id: "+registrationRequest.getId()));
	}

	private boolean canAccess(@NonNull RegistrationRequest registrationRequest, Authentication authentication) {
		return canAccess(registrationRequest, authentication, false);
	}

	private boolean canAccess(@NonNull RegistrationRequest registrationRequest, Authentication authentication, boolean sameUserOnly) {
		String requester = registrationRequest.getRequester();
		if (requester == null && authentication.getName() == null) return true;
        return requester != null && (
                requester.equals(authentication.getName()) ||
						!sameUserOnly && authentication.getAuthorities().stream()
								.map(GrantedAuthority::getAuthority).toList().contains("ROLE_ADMIN")
        );
    }

	public List<RegistrationRequest> getAllAsUser(Authentication authentication) {
		return getAll().stream().filter(req -> canAccess(req, authentication, true)).toList();
	}

	public @NonNull RegistrationRequest saveAsUser(@NonNull RegistrationRequest registrationRequest, Authentication authentication) {
		registrationRequest.setRequester(authentication.getName());
		checkRequester(registrationRequest, authentication);
		return save(registrationRequest);
	}

	public RegistrationRequest updateAsUser(@NonNull RegistrationRequest registrationRequest, Authentication authentication) {
		Optional<RegistrationRequest> result = getByIdAsUser(registrationRequest.getId(), authentication);
		if (result.isEmpty())
			throw new RegistrationRequestException(
					"Registration request with the Id does not exists in repository: "+registrationRequest.getId());
		checkRequester(result.get(), authentication);
		return update(registrationRequest);
	}

	public void deleteByIdAsUser(@NonNull String id, Authentication authentication) {
		Optional<RegistrationRequest> result = getByIdAsUser(id, authentication);
		if (result.isEmpty())
			throw new RegistrationRequestException(
					"Registration request with the Id does not exists in repository: "+id);
		checkRequester(result.get(), authentication);
		deleteById(id);
	}

	public void deleteAsUser(@NonNull RegistrationRequest registrationRequest, Authentication authentication) {
		checkRequester(registrationRequest, authentication);
		deleteByIdAsUser(registrationRequest.getId(), authentication);
	}

	// ------------------------------------------------------------------------

	public RegistrationRequest authorizeRequest(String id, boolean authorize, Authentication authentication) {
		Optional<RegistrationRequest> result = getById(id);
		if (result.isEmpty())
			throw new RegistrationRequestException(
				"Registration request with the Id does not exists in repository: "+id);
		checkAdmin(result.get().getId(), authentication);

		result.get().setStatus( authorize
				? RegistrationRequestStatus.PENDING_ONBOARDING
				: RegistrationRequestStatus.AUTHORIZATION_REJECT );
		return update(result.get());
	}

	// ------------------------------------------------------------------------

	public Optional<ArchivedRegistrationRequest> getArchivedById(@NonNull String id) {
		return archivedRegistrationRequestRepository.findById(id);
	}

	public Optional<ArchivedRegistrationRequest> getArchivedByIdAsUser(@NonNull String id, Authentication authentication) {
		Optional<ArchivedRegistrationRequest> result = getArchivedById(id);
		result.ifPresent(registrationRequest -> checkRequester(registrationRequest, authentication));
		return result;
	}

	public List<ArchivedRegistrationRequest> getArchivedAll() {
		return Collections.unmodifiableList(archivedRegistrationRequestRepository.findAll());
	}

	public List<ArchivedRegistrationRequest> getArchivedAllAsUser(Authentication authentication) {
		return getArchivedAll().stream().filter(req -> canAccess(req, authentication, true)).toList();
	}

	public void archiveRequest(String id, Authentication authentication) {
		checkAdmin(id, authentication);
		archiveRequestBySystem(id);
	}

	public void archiveRequestBySystem(String id) {
		Optional<RegistrationRequest> result = getById(id);
		if (result.isEmpty())
			throw new RegistrationRequestException(
					"Registration request with the Id does not exists in repository: " + id);
		result.get().setArchiveDate(Instant.now());
		archivedRegistrationRequestRepository.save(
				registrationRequestConversionService.toArchivedRegistrationRequest(result.get()));
		registrationRequestRepository.delete(result.get());
	}

	public void unarchiveRequest(String id, Map<String,String> credentials, Authentication authentication) {
		Optional<ArchivedRegistrationRequest> result = getArchivedById(id);
		if (result.isEmpty())
			throw new RegistrationRequestException(
					"Archived registration request with Id does not exists in repository: "+id);
		checkAdmin(result.get().getId(), authentication);
		checkCredentials(result.get().getId(), credentials);

		result.get().setArchiveDate(null);
		RegistrationRequest restoredRequest = registrationRequestConversionService.toRegistrationRequest(result.get());
		Device device = restoredRequest.getDevice();
		device.setUsername(credentials.get("username"));
		device.setPassword(credentials.get("password").toCharArray());
		device.setPublicKey(credentials.get("publicKey").toCharArray());
		registrationRequestRepository.save(restoredRequest);
		archivedRegistrationRequestRepository.deleteById(result.get().getId());
	}

	private void checkCredentials(String id, Map<String, String> credentials) {
		if (credentials==null || credentials.isEmpty())
			throw new RegistrationRequestException(
					"No credentials provided for un-archiving request with Id: "+id);
		if (StringUtils.isBlank(credentials.getOrDefault("username", "")))
			throw new RegistrationRequestException(
					"No username provided for un-archiving request with Id: "+id);
		if (StringUtils.isBlank(credentials.getOrDefault("password", "")) &&
				StringUtils.isBlank(credentials.getOrDefault("publicKey", "")))
			throw new RegistrationRequestException(
					"No password or SSH key provided for un-archiving request with Id: "+id);
	}
}
