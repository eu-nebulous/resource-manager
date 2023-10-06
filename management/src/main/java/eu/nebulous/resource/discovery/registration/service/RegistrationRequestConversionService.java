package eu.nebulous.resource.discovery.registration.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.nebulous.resource.discovery.registration.model.ArchivedRegistrationRequest;
import eu.nebulous.resource.discovery.registration.model.RegistrationRequest;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class RegistrationRequestConversionService {
	private final ObjectMapper objectMapper;

	public ArchivedRegistrationRequest toArchivedRegistrationRequest(@NonNull RegistrationRequest registrationRequest) {
		return objectMapper
				.convertValue(registrationRequest, ArchivedRegistrationRequest.class);
	}

	public RegistrationRequest toRegistrationRequest(@NonNull ArchivedRegistrationRequest archivedRegistrationRequest) {
		return objectMapper
				.convertValue(archivedRegistrationRequest, ArchivedRegistrationRequest.class);
	}
}
