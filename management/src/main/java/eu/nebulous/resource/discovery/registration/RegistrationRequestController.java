package eu.nebulous.resource.discovery.registration;

import eu.nebulous.resource.discovery.registration.model.RegistrationRequest;
import eu.nebulous.resource.discovery.registration.model.RegistrationRequestException;
import eu.nebulous.resource.discovery.registration.model.RegistrationRequestStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/discovery")
public class RegistrationRequestController {
	private final RegistrationRequestService registrationRequestService;
	private final IRegistrationRequestProcessor registrationRequestProcessor;

	@GetMapping(value = "/whoami", produces = MediaType.APPLICATION_JSON_VALUE)
	public Map<String, Object> whoami(Authentication authentication) {
		List<String> roles = authentication != null
				? authentication.getAuthorities().stream()
						.map(Object::toString)
						.map(s -> StringUtils.removeStartIgnoreCase(s, "ROLE_"))
						.toList()
				: Collections.emptyList();
		return Map.of(
				"user", authentication!=null ? authentication.getName() : "",
				"roles", roles,
				"admin", roles.contains("ADMIN")
		);
	}

	@PreAuthorize("hasAuthority('ROLE_ADMIN')")
	@GetMapping(value = "/request/process", produces = MediaType.APPLICATION_JSON_VALUE)
	public Map<String, String> processRequests() throws ExecutionException, InterruptedException {
		Future<String> future = registrationRequestProcessor.processRequests();
		return Map.of("result", future.isDone() ? future.get() : "STARTED");
	}

	@PreAuthorize("hasAuthority('ROLE_ADMIN')")
	@GetMapping(value = "/request/all", produces = MediaType.APPLICATION_JSON_VALUE)
	public List<RegistrationRequest> listRequestsAdmin(Authentication authentication) {
		return registrationRequestService.getAll();
	}

	@GetMapping(value = "/request", produces = MediaType.APPLICATION_JSON_VALUE)
	public List<RegistrationRequest> listRequests(Authentication authentication) {
		return registrationRequestService.getAllAsUser(authentication);
	}

	@GetMapping(value = "/request/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
	public RegistrationRequest getRequest(@PathVariable String id, Authentication authentication) {
		return registrationRequestService.getByIdAsUser(id, authentication)
				.orElseThrow(() -> new RegistrationRequestException("Not found registration request with id: "+id));
	}

	@PutMapping(value = "/request", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
	public RegistrationRequest createRequest(@RequestBody RegistrationRequest registrationRequest, Authentication authentication) {
		return registrationRequestService.saveAsUser(registrationRequest, authentication);
	}

	@PostMapping(value = "/request/{id}", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
	public RegistrationRequest updateRequest(@PathVariable String id,
											 @RequestBody RegistrationRequest registrationRequest,
											 Authentication authentication)
	{
		if (! StringUtils.equals(id, registrationRequest.getId()))
			throw new RegistrationRequestException(
					"Id does not match the id in registration request: "+id+" <> "+registrationRequest.getId());
		return registrationRequestService.updateAsUser(registrationRequest, authentication);
	}

	@DeleteMapping(value = "/request/{id}")
	public void deleteRequest(@PathVariable String id, Authentication authentication) {
		registrationRequestService.deleteByIdAsUser(id, authentication);
	}

	@PreAuthorize("hasAuthority('ROLE_ADMIN')")
	@GetMapping(value = "/request/{id}/authorize", produces = MediaType.APPLICATION_JSON_VALUE)
	public RegistrationRequest authorizeRequest(@PathVariable String id, Authentication authentication) {
		return registrationRequestService.authorizeRequest(id, true, authentication);
	}

	@PreAuthorize("hasAuthority('ROLE_ADMIN')")
	@GetMapping(value = "/request/{id}/reject", produces = MediaType.APPLICATION_JSON_VALUE)
	public RegistrationRequest rejectRequest(@PathVariable String id, Authentication authentication) {
		return registrationRequestService.authorizeRequest(id, false, authentication);
	}

	@PreAuthorize("hasAuthority('ROLE_ADMIN')")
	@GetMapping(value = "/request/{id}/status/{newStatus}", produces = MediaType.APPLICATION_JSON_VALUE)
	public RegistrationRequest setRequestStatus(@PathVariable String id, @PathVariable String newStatus) {
		RegistrationRequestStatus _newStatus = RegistrationRequestStatus.valueOf(newStatus);
		RegistrationRequest request = registrationRequestService.getById(id)
				.orElseThrow(() -> new RegistrationRequestException("Not found registration request with id: " + id));
		request.setStatus(_newStatus);
		return registrationRequestService.update(request);
	}

	@PreAuthorize("hasAuthority('ROLE_ADMIN')")
	@GetMapping(value = "/request/{id}/archive", produces = MediaType.APPLICATION_JSON_VALUE)
	public String archiveRequest(@PathVariable String id, Authentication authentication) {
		registrationRequestService.archiveRequest(id, authentication);
		return "ARCHIVED";
	}

	@PreAuthorize("hasAuthority('ROLE_ADMIN')")
	@GetMapping(value = "/request/{id}/unarchive", produces = MediaType.APPLICATION_JSON_VALUE)
	public RegistrationRequest unarchiveRequest(@PathVariable String id, Authentication authentication) {
		registrationRequestService.unarchiveRequest(id, authentication);
		return registrationRequestService.getById(id)
				.orElseThrow(() -> new RegistrationRequestException("Failed to unarchive registration request with id: " + id));
	}

	@GetMapping(value = "/request/archived", produces = MediaType.APPLICATION_JSON_VALUE)
	public List<RegistrationRequest> listArchivedRequests(Authentication authentication) {
		return registrationRequestService.getArchivedAllAsUser(authentication);
	}

	@PreAuthorize("hasAuthority('ROLE_ADMIN')")
	@GetMapping(value = "/request/archived/all", produces = MediaType.APPLICATION_JSON_VALUE)
	public List<RegistrationRequest> listArchivedRequestsAdmin() {
		return registrationRequestService.getArchivedAll();
	}

	@GetMapping(value = "/request/archived/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
	public RegistrationRequest getArchivedRequest(@PathVariable String id, Authentication authentication) {
		return registrationRequestService.getArchivedByIdAsUser(id, authentication)
				.orElseThrow(() -> new RegistrationRequestException("Not found archived registration request with id: "+id));
	}
}
