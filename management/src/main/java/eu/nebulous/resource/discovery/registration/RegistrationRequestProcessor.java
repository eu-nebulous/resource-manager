package eu.nebulous.resource.discovery.registration;

import eu.nebulous.resource.discovery.registration.model.RegistrationRequest;
import eu.nebulous.resource.discovery.registration.model.RegistrationRequestStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.async.AsyncWebRequest;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
@EnableAsync
@RequiredArgsConstructor
public class RegistrationRequestProcessor {
	private final RegistrationRequestService registrationRequestService;
	private final AtomicBoolean isRunning = new AtomicBoolean(false);

	@Async
	public Future<String> processRequests() {
		// Check and set if already running
		if (! isRunning.compareAndSet(false, true)) {
			log.warn("RegistrationRequestProcessor: Already running");
			return CompletableFuture.completedFuture("ALREADY RUNNING");
		}

		// Process requests
		processNewRequests();

		// Clear running flag
		isRunning.set(false);

		return CompletableFuture.completedFuture("DONE");
	}

	private void processNewRequests() {
		List<RegistrationRequest> newRequests = registrationRequestService.getAll().stream()
				.filter(r -> r.getStatus() == RegistrationRequestStatus.NEW_REQUEST).toList();
		log.debug("processNewRequests: Found {} new requests: {}",
				newRequests.size(), newRequests.stream().map(RegistrationRequest::getId).toList());

		for (RegistrationRequest registrationRequest : newRequests) {
			registrationRequest.setStatus(RegistrationRequestStatus.DATA_COLLECTION);
			Map<String, String> emsNotification = Map.of(
					"registrationRequestId", registrationRequest.getId(),
					"deviceIpAddress", registrationRequest.getDevice().getIpAddress(),
					"deviceUsername", registrationRequest.getDevice().getUsername(),
					"devicePassword", new String(registrationRequest.getDevice().getPassword()),
					"devicePublicKey", new String(registrationRequest.getDevice().getPublicKey()),
					"timestamp", Long.toString(Instant.now().toEpochMilli()),
					"priority", Double.toString(1.0),
					"retry", Integer.toString(1)
			);
			log.debug("processNewRequests: Notifying EMS to collect device data for request with Id: {}", registrationRequest.getId());
		}
	}
}
