package eu.nebulous.resource.discovery.monitor.controller;

import eu.nebulous.resource.discovery.monitor.DeviceProcessor;
import eu.nebulous.resource.discovery.monitor.model.ArchivedDevice;
import eu.nebulous.resource.discovery.monitor.model.Device;
import eu.nebulous.resource.discovery.monitor.model.DeviceException;
import eu.nebulous.resource.discovery.monitor.service.DeviceLifeCycleRequestService;
import eu.nebulous.resource.discovery.monitor.service.DeviceManagementService;
import eu.nebulous.resource.discovery.registration.model.RegistrationRequestException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/monitor")
@PreAuthorize("hasAuthority('ROLE_ADMIN')")
public class DeviceManagementController {
	private final DeviceProcessor deviceProcessor;
	private final DeviceManagementService deviceService;
	private final DeviceLifeCycleRequestService deviceLifeCycleRequestService;

	private boolean isAuthenticated(Authentication authentication) {
		return authentication!=null && StringUtils.isNotBlank(authentication.getName());
	}

	private boolean isAdmin(Authentication authentication) {
		if (isAuthenticated(authentication)) {
			return authentication.getAuthorities().stream()
					.map(GrantedAuthority::getAuthority)
					.anyMatch("ROLE_ADMIN"::equals);
		}
		return false;
	}

	@PreAuthorize("hasAuthority('ROLE_ADMIN') || hasAuthority('ROLE_USER')")
	@GetMapping(value = "/device", produces = MediaType.APPLICATION_JSON_VALUE)
	public List<Device> listDevicesUser(Authentication authentication) {
		return isAuthenticated(authentication)
				? deviceService.getByOwner(authentication.getName().trim())
				: listDevicesAll();
	}

	@GetMapping(value = "/device/all", produces = MediaType.APPLICATION_JSON_VALUE)
	public List<Device> listDevicesAll() {
		return deviceService.getAll();
	}

	@GetMapping(value = "/device/owner/{owner}", produces = MediaType.APPLICATION_JSON_VALUE)
	public List<Device> listDevicesForOwner(@PathVariable String owner) {
		return deviceService.getByOwner(owner);
	}

	@PreAuthorize("hasAuthority('ROLE_ADMIN') || hasAuthority('ROLE_USER')")
	@GetMapping(value = "/device/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
	public Device getDevice(@PathVariable String id, Authentication authentication) {
		Device device = deviceService.getById(id)
				.orElseThrow(() -> new DeviceException("Not found device with id: " + id));
		if (isAuthenticated(authentication)
				&& ! authentication.getName().trim().equals(device.getOwner())
				&& ! isAdmin(authentication))
			throw new DeviceException("Cannot retrieve device with id: " + id);
		return device;
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

	// ------------------------------------------------------------------------

	@GetMapping(value = "/device/{id}/onboard")
	public void onboardDevice(@PathVariable String id) {
		deviceLifeCycleRequestService.reinstallRequest(id);
	}

	@GetMapping(value = "/device/{id}/offboard")
	public void offboardDevice(@PathVariable String id) {
		deviceLifeCycleRequestService.uninstallRequest(id);
	}

	@GetMapping(value = "/request-update")
	public String requestUpdate() {
		deviceLifeCycleRequestService.requestInfoUpdate();
		return "REQUESTED-UPDATE";
	}

	@PreAuthorize("hasAuthority('ROLE_ADMIN')")
	@GetMapping(value = "/device/process")
	public Map<String, String> processDevices() throws ExecutionException, InterruptedException {
		Future<String> future = deviceProcessor.processRequests();
		return Map.of("result", future.isDone() ? future.get() : "STARTED");
	}

	@PreAuthorize("hasAuthority('ROLE_ADMIN')")
	@GetMapping(value = "/device/{id}/archive", produces = MediaType.APPLICATION_JSON_VALUE)
	public String archiveDevice(@PathVariable String id) {
		deviceService.archiveDevice(id);
		return "ARCHIVED";
	}

	@PreAuthorize("hasAuthority('ROLE_ADMIN')")
	@GetMapping(value = "/device/{id}/unarchive", produces = MediaType.APPLICATION_JSON_VALUE)
	public String unarchiveDevice(@PathVariable String id) {
		deviceService.unarchiveDevice(id);
		return "UNARCHIVED";
	}

	// ------------------------------------------------------------------------

	@GetMapping(value = "/device/archived", produces = MediaType.APPLICATION_JSON_VALUE)
	public List<ArchivedDevice> listArchivedRequests(Authentication authentication) {
		return deviceService.getArchivedByOwner(authentication);
	}

	@PreAuthorize("hasAuthority('ROLE_ADMIN')")
	@GetMapping(value = "/device/archived/all", produces = MediaType.APPLICATION_JSON_VALUE)
	public List<ArchivedDevice> listArchivedRequestsAdmin() {
		return deviceService.getArchivedAll();
	}

	@GetMapping(value = "/device/archived/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
	public ArchivedDevice getArchivedRequest(@PathVariable String id, Authentication authentication) {
		return deviceService.getArchivedById(id, authentication)
				.orElseThrow(() -> new RegistrationRequestException("Not found archived registration request with id: "+id));
	}

	// ------------------------------------------------------------------------

	@ResponseStatus(value = HttpStatus.BAD_REQUEST)
	@ExceptionHandler(DeviceException.class)
	public Map<String,Object> handleRegistrationRequestException(DeviceException exception) {
		return Map.of(
				"status", HttpStatus.BAD_REQUEST.value(),
				"timestamp", System.currentTimeMillis(),
				"exception", exception.getClass().getName(),
				"message", exception.getMessage()
		);
	}
}
