package eu.nebulous.resource.discovery.monitor.controller;

import eu.nebulous.resource.discovery.SecurityConfig;
import eu.nebulous.resource.discovery.monitor.DeviceProcessor;
import eu.nebulous.resource.discovery.monitor.model.ArchivedDevice;
import eu.nebulous.resource.discovery.monitor.model.Device;
import eu.nebulous.resource.discovery.monitor.model.DeviceException;
import eu.nebulous.resource.discovery.monitor.service.DeviceLifeCycleRequestService;
import eu.nebulous.resource.discovery.monitor.service.DeviceManagementService;
import eu.nebulous.resource.discovery.registration.model.RegistrationRequestException;
import eu.nebulous.resource.discovery.registration.service.SALDeregistrationService;
import eu.nebulous.resource.discovery.registration.service.SALRegistrationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/monitor")
@PreAuthorize(DeviceManagementController.REQUIRES_ADMIN_ROLE)
public class DeviceManagementController {
	public final static String REQUIRES_ADMIN_ROLE = "hasAuthority('ROLE_ADMIN')";
	public final static String REQUIRES_ADMIN_OR_USER_ROLE =
			"hasAuthority('ROLE_ADMIN') || hasAuthority('ROLE_USER') || hasAuthority('"+ SecurityConfig.SSO_USER_ROLE +"')";

	private final DeviceProcessor deviceProcessor;
	private final DeviceManagementService deviceService;
	private final DeviceLifeCycleRequestService deviceLifeCycleRequestService;
	private final Optional<SALRegistrationService> salRegistrationService;
	private final Optional<SALDeregistrationService> salDeregistrationService;

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

	@PreAuthorize(REQUIRES_ADMIN_OR_USER_ROLE)
	@GetMapping(value = "/device", produces = MediaType.APPLICATION_JSON_VALUE)
	public List<Device> listDevicesUser(Authentication authentication) {
		return isAuthenticated(authentication)
				? deviceService.getByOwner(authentication.getName().trim())
				: listDevicesAll();
	}

	@GetMapping(value = "/testdereg/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
	public String testderegistration(@PathVariable String id) {
		Device device = new Device();
		device.setRef("application_id|222|"+ RandomStringUtils.randomAlphanumeric(10));
		device.setSal_id(id);
		salDeregistrationService.ifPresent(sdr -> sdr.deregister(device));
		return "Triggered deregistration for device";
	}
	
	
	@GetMapping(value = "/device/all", produces = MediaType.APPLICATION_JSON_VALUE)
	public List<Device> listDevicesAll() {
		return deviceService.getAll();
	}

	@GetMapping(value = "/device/owner/{owner}", produces = MediaType.APPLICATION_JSON_VALUE)
	public List<Device> listDevicesForOwner(@PathVariable String owner) {
		return deviceService.getByOwner(owner);
	}

	@PreAuthorize(REQUIRES_ADMIN_OR_USER_ROLE)
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
		log.warn("DeviceManagementController: createDevice: device: {}", device);

        salRegistrationService.ifPresent(salRegistrationService -> salRegistrationService.register(device));
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

	@PreAuthorize(REQUIRES_ADMIN_OR_USER_ROLE)
	@GetMapping(value = "/device/{id}/onboard")
	public void onboardDevice(@PathVariable String id) {
		deviceLifeCycleRequestService.reinstallRequest(id);
	}

	@PreAuthorize(REQUIRES_ADMIN_OR_USER_ROLE)
	@GetMapping(value = "/device/{id}/offboard")
	public void offboardDevice(@PathVariable String id) {
		deviceLifeCycleRequestService.uninstallRequest(id);
	}

	@PreAuthorize(REQUIRES_ADMIN_OR_USER_ROLE)
	@GetMapping(value = "/request-update")
	public String requestUpdate() {
		deviceLifeCycleRequestService.requestInfoUpdate();
		return "REQUESTED-UPDATE";
	}

	@GetMapping(value = "/device/process")
	public Map<String, String> processDevices() throws ExecutionException, InterruptedException {
		Future<String> future = deviceProcessor.processDevices();
		return Map.of("result", future.isDone() ? future.get() : "STARTED");
	}

	@GetMapping(value = "/device/{id}/archive", produces = MediaType.APPLICATION_JSON_VALUE)
	public String archiveDevice(@PathVariable String id) {
		deviceService.archiveDevice(id);
		return "ARCHIVED";
	}

	@PostMapping(value = "/device/{id}/unarchive",
			consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
	public String unarchiveDevice(@PathVariable String id, @RequestBody Map<String,String> credentials) {
		deviceService.unarchiveDevice(id, credentials);
		return "UNARCHIVED";
	}

	// ------------------------------------------------------------------------

	@PreAuthorize(REQUIRES_ADMIN_OR_USER_ROLE)
	@GetMapping(value = "/device/archived", produces = MediaType.APPLICATION_JSON_VALUE)
	public List<ArchivedDevice> listArchivedRequests(Authentication authentication) {
		return deviceService.getArchivedByOwner(authentication);
	}

	@GetMapping(value = "/device/archived/all", produces = MediaType.APPLICATION_JSON_VALUE)
	public List<ArchivedDevice> listArchivedRequestsAdmin() {
		return deviceService.getArchivedAll();
	}

	@PreAuthorize(REQUIRES_ADMIN_OR_USER_ROLE)
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
