package eu.nebulous.resource.discovery.monitor.service;

import eu.nebulous.resource.discovery.ResourceDiscoveryProperties;
import eu.nebulous.resource.discovery.common.BrokerUtil;
import eu.nebulous.resource.discovery.common.REQUEST_TYPE;
import eu.nebulous.resource.discovery.monitor.model.Device;
import eu.nebulous.resource.discovery.monitor.model.DeviceException;
import eu.nebulous.resource.discovery.monitor.model.DeviceStatus;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceLifeCycleRequestService {
	private final ResourceDiscoveryProperties properties;
	private final DeviceManagementService deviceManagementService;
	private final BrokerUtil brokerUtil;

	// ------------------------------------------------------------------------

	public void reinstallRequest(String id) {
		log.trace("reinstallRequest: BEGIN: device-id {}", id);
		Optional<Device> result = deviceManagementService.getById(id);
		if (result.isEmpty())
			throw new DeviceException(
					"Device with the Id does not exists in repository: " + id);
		Device device = result.get();

		try {
			// Prepare request
			log.debug("reinstallRequest: Requesting device re-onboarding with Id: {}", device.getId());
			Map<String, String> onboardingRequest = prepareRequestPayload(REQUEST_TYPE.REINSTALL, device);

			// Send request
			brokerUtil.sendMessage(properties.getDeviceLifeCycleRequestsTopic(), onboardingRequest);
			device.setStatus(DeviceStatus.ONBOARDING);

			log.debug("reinstallRequest: Save updated device: id={}, device={}", device.getId(), device);
			deviceManagementService.update(device);
			log.debug("reinstallRequest: Onboarding request sent for device with Id: {}", device.getId());
		} catch (Exception e) {
			log.warn("reinstallRequest: EXCEPTION while sending onboarding request for device with Id: {}\n", device.getId(), e);
			device.setStatus(DeviceStatus.ONBOARD_ERROR);
			device.getMessages().add("EXCEPTION "+e.getMessage());
			deviceManagementService.update(device);
		}

		log.trace("reinstallRequest: END");
	}

	public void uninstallRequest(String id) {
		log.trace("uninstallRequest: BEGIN: device-id {}", id);
		Optional<Device> result = deviceManagementService.getById(id);
		if (result.isEmpty())
			throw new DeviceException(
					"Device with the Id does not exists in repository: " + id);
		Device device = result.get();

		try {
			// Prepare request
			log.debug("uninstallRequest: Requesting device off-onboarding with Id: {}", device.getId());
			Map<String, String> offboardingRequest = prepareRequestPayload(REQUEST_TYPE.UNINSTALL, device);

			// Send request
			brokerUtil.sendMessage(properties.getDeviceLifeCycleRequestsTopic(), offboardingRequest);
			device.setStatus(DeviceStatus.OFFBOARDING);

			log.debug("uninstallRequest: Save updated device: id={}, device={}", device.getId(), device);
			deviceManagementService.update(device);
			log.debug("uninstallRequest: Off-boarding request sent for device with Id: {}", device.getId());
		} catch (Exception e) {
			log.warn("uninstallRequest: EXCEPTION while sending off-boarding request for device with Id: {}\n", device.getId(), e);
			device.setStatus(DeviceStatus.OFFBOARD_ERROR);
			device.getMessages().add("EXCEPTION "+e.getMessage());
			deviceManagementService.update(device);
		}

		log.trace("uninstallRequest: END");
	}

	public void requestInfoUpdate() {
		try {
			// Prepare request
			log.debug("requestInfoUpdate: Requesting device info and metrics update");
			Map<String, String> updateRequest = prepareRequestPayload(REQUEST_TYPE.INFO, null);

			// Send request
			brokerUtil.sendMessage(properties.getDeviceLifeCycleRequestsTopic(), updateRequest);

			log.debug("requestInfoUpdate: Update request sent");
		} catch (Exception e) {
			log.warn("requestInfoUpdate: EXCEPTION while sending update request:\n", e);
		}
		log.trace("requestInfoUpdate: END");
	}

	// ------------------------------------------------------------------------

	private static Map<String, String> prepareRequestPayload(@NonNull REQUEST_TYPE requestType, Device device) {
		try {
			Map<String, String> payload;
			if (device==null) {
				payload = new LinkedHashMap<>(Map.of(
						"requestType", requestType.name()
				));
			} else {
				payload = new LinkedHashMap<>(Map.of(
						"requestId", device.getRequestId(),
						"requestType", requestType.name(),
						"deviceId", device.getId(),
						"deviceOs", device.getOs(),
						"deviceName", device.getName(),
						"deviceIpAddress", device.getIpAddress(),
						"deviceUsername", device.getUsername(),
						"devicePassword", new String(device.getPassword()),
						"devicePublicKey", new String(device.getPublicKey())
				));
			}
			payload.put("timestamp", Long.toString(Instant.now().toEpochMilli()));
			payload.put("priority", Double.toString(1.0));
			payload.put("retry", Integer.toString(1));
			return payload;
		} catch (Exception e) {
			log.error("prepareRequestPayload: EXCEPTION: request-type={}, device={}\nException: ",
					requestType, device, e);
			throw e;
		}
	}
}
