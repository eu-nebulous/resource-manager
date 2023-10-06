package eu.nebulous.resource.discovery.monitor.controller;

import eu.nebulous.resource.discovery.monitor.model.Device;
import eu.nebulous.resource.discovery.monitor.model.DeviceException;
import eu.nebulous.resource.discovery.monitor.service.DeviceConversionService;
import eu.nebulous.resource.discovery.monitor.service.DeviceManagementService;
import eu.nebulous.resource.discovery.registration.IRegistrationRequestProcessor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/monitor")
@PreAuthorize("hasAuthority('ROLE_ADMIN')")
public class DeviceManagementController {
	private final DeviceManagementService deviceService;
	private final DeviceConversionService deviceConversionService;
	private final IRegistrationRequestProcessor deviceRequestProcessor;

	@GetMapping(value = { "/device", "/device/all" }, produces = MediaType.APPLICATION_JSON_VALUE)
	public List<Device> listDevicesAll() {
		return deviceService.getAll();
	}

	@GetMapping(value = "/device/owner/{owner}", produces = MediaType.APPLICATION_JSON_VALUE)
	public List<Device> listDevicesForOwner(@PathVariable String owner) {
		return deviceService.getByOwner(owner);
	}

	@GetMapping(value = "/device/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
	public Device getDevice(@PathVariable String id) {
		return deviceService.getById(id)
				.orElseThrow(() -> new DeviceException("Not found device with id: "+id));
	}

	@GetMapping(value = "/device/ipaddress/{ipAddress}", produces = MediaType.APPLICATION_JSON_VALUE)
	public Device getDeviceByIpAddress(@PathVariable String ipAddress) {
		return deviceService.getByIpAddress(ipAddress)
				.orElseThrow(() -> new DeviceException("Not found device with IP address: "+ipAddress));
	}

	@PutMapping(value = "/device", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
	public Device createDevice(@RequestBody Device device) {
		return deviceService.save(device);
	}

	@PostMapping(value = "/device/{id}", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
	public Device updateDevice(@PathVariable String id, @RequestBody Device device) {
		if (! StringUtils.equals(id, device.getId()))
			throw new DeviceException(
					"Id does not match the id in device: "+id+" <> "+device.getId());
		return deviceService.update(device);
	}

	@DeleteMapping(value = "/device/{id}")
	public void deleteDevice(@PathVariable String id) {
		deviceService.deleteById(id);
	}

	@GetMapping(value = "/device/{id}/archive", produces = MediaType.APPLICATION_JSON_VALUE)
	public String archiveDevice(@PathVariable String id) {
		deviceService.archiveDevice(id);
		return "ARCHIVED";
	}
}
