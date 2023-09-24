package eu.nebulous.resource.discovery.registration;

import eu.nebulous.resource.discovery.registration.model.RegistrationRequest;
import eu.nebulous.resource.discovery.registration.model.RegistrationRequestException;
import eu.nebulous.resource.discovery.registration.model.RegistrationRequestStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/discovery")
public class RegistrationRequestController {
	private final RegistrationRequestService registrationRequestService;

	@GetMapping(value = "/request", produces = MediaType.APPLICATION_JSON_VALUE)
	public List<RegistrationRequest> listRequests() {
		return registrationRequestService.getAll();
	}

	@GetMapping(value = "/request/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
	public RegistrationRequest getRequest(@PathVariable String id) {
		return registrationRequestService.getById(id)
				.orElseThrow(() -> new RegistrationRequestException("Not found registration request with id: "+id));
	}

	@PutMapping(value = "/request", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
	public RegistrationRequest addRequest(@RequestBody RegistrationRequest registrationRequest) {
		registrationRequest.setRequester("Moi");
		registrationRequest.setRequestDate(Instant.now());
		registrationRequest.setStatus(RegistrationRequestStatus.NEW_REQUEST);
		return registrationRequestService.save(registrationRequest);
	}

	@PostMapping(value = "/request/{id}", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
	public RegistrationRequest updateRequest(@PathVariable String id, @RequestBody RegistrationRequest registrationRequest) {
		if (! StringUtils.equals(id, registrationRequest.getId()))
			throw new RegistrationRequestException(
					"Id does not match the id in registration request: "+id+" <> "+registrationRequest.getId());
		return registrationRequestService.update(registrationRequest);
	}

	@DeleteMapping(value = "/request/{id}")
	public void deleteRequest(@PathVariable String id) {
		registrationRequestService.deleteById(id);
	}
}
