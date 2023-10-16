package eu.nebulous.resource.discovery.monitor.controller;

import eu.nebulous.resource.discovery.monitor.model.ArchivedDevice;
import eu.nebulous.resource.discovery.monitor.model.Device;
import eu.nebulous.resource.discovery.monitor.model.DeviceException;
import eu.nebulous.resource.discovery.monitor.service.DeviceManagementService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/monitor/archived")
@PreAuthorize("hasAuthority('ROLE_ADMIN')")
public class ArchivedDeviceManagementController {
	private final DeviceManagementService deviceService;

	@GetMapping(value = "/device/all", produces = MediaType.APPLICATION_JSON_VALUE)
	public List<ArchivedDevice> listDevicesAll() {
		return deviceService.getArchivedAll();
	}

	@GetMapping(value = "/device/owner/{owner}", produces = MediaType.APPLICATION_JSON_VALUE)
	public List<ArchivedDevice> listDevicesForOwner(@PathVariable String owner) {
		return deviceService.getArchivedByOwner(owner);
	}

	@GetMapping(value = "/device/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
	public Device getDevice(@PathVariable String id) {
		return deviceService.getArchivedById(id)
				.orElseThrow(() -> new DeviceException("Not found archived device with id: "+id));
	}

	@GetMapping(value = "/device/ipaddress/{ipAddress}", produces = MediaType.APPLICATION_JSON_VALUE)
	public List<ArchivedDevice> getDeviceByIpAddress(@PathVariable String ipAddress) {
		return deviceService.getArchivedByIpAddress(ipAddress);
	}

	@GetMapping(value = "/device/{id}/unarchive", produces = MediaType.APPLICATION_JSON_VALUE)
	public String unarchiveDevice(@PathVariable String id) {
		deviceService.unarchiveDevice(id);
		return "UNARCHIVED";
	}
}
