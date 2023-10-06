package eu.nebulous.resource.discovery.monitor.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.nebulous.resource.discovery.monitor.model.ArchivedDevice;
import eu.nebulous.resource.discovery.monitor.model.Device;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceConversionService {
	private final ObjectMapper objectMapper;

	public ArchivedDevice toArchivedDevice(@NonNull Device device) {
		return objectMapper
				.convertValue(device, ArchivedDevice.class);
	}

	public Device toDevice(@NonNull ArchivedDevice archivedDevice) {
		return objectMapper
				.convertValue(archivedDevice, Device.class);
	}
}
