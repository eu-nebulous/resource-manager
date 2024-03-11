
package eu.nebulous.resource.discovery.monitor;

import eu.nebulous.resource.discovery.ResourceDiscoveryProperties;
import eu.nebulous.resource.discovery.broker_communication.BrokerPublisher;
import java.time.Clock;
import eu.nebulous.resource.discovery.monitor.model.Device;
import eu.nebulous.resource.discovery.monitor.model.DeviceStatus;
import eu.nebulous.resource.discovery.monitor.service.DeviceManagementService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.json.simple.JSONObject;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
@EnableAsync
@EnableScheduling
@RequiredArgsConstructor
public class DeviceProcessor  implements InitializingBean {
    private final static List<DeviceStatus> STATUSES_TO_EXCLUDE_FROM_SUSPECT_CHECK = List.of(
            DeviceStatus.ON_HOLD, DeviceStatus.ONBOARDING, DeviceStatus.FAILED,
            DeviceStatus.OFFBOARDING, DeviceStatus.OFFBOARDED, DeviceStatus.OFFBOARD_ERROR
    );
    private final static List<DeviceStatus> STATUSES_TO_ARCHIVE = List.of(
            DeviceStatus.FAILED,
            DeviceStatus.OFFBOARDED,
            DeviceStatus.OFFBOARD_ERROR
    );

    private final ResourceDiscoveryProperties processorProperties;
    private final DeviceManagementService deviceManagementService;
    private final TaskScheduler taskScheduler;
    private final AtomicBoolean isRunning = new AtomicBoolean(false);

    @Override
    public void afterPropertiesSet() throws Exception {
        // Check configuration
        Instant suspectDeviceThreshold = Instant.now().minus(processorProperties.getSuspectDeviceThreshold(), ChronoUnit.MINUTES);
        Instant failedDeviceThreshold = Instant.now().minus(processorProperties.getFailedDeviceThreshold(), ChronoUnit.MINUTES);
        if (suspectDeviceThreshold.isBefore(failedDeviceThreshold))
            throw new IllegalArgumentException("DeviceProcessor: Configuration error: suspectDeviceThreshold is before failedDeviceThreshold: "
                    + processorProperties.getSuspectDeviceThreshold() + " < " + processorProperties.getFailedDeviceThreshold());

        // Initialize periodic device processing
        if (processorProperties.isEnablePeriodicProcessing()) {
            Instant firstRun;
            taskScheduler.scheduleAtFixedRate(this::processDevices,
                    firstRun = Instant.now().plusSeconds(processorProperties.getProcessingStartupDelay()),
                    Duration.ofSeconds(processorProperties.getProcessingPeriod()));
            log.info("DeviceProcessor: Started periodic device processing: period={}s, first-run-at={}",
                    processorProperties.getProcessingPeriod(), firstRun.atZone(ZoneId.systemDefault()));
        } else {
            log.info("DeviceProcessor: Periodic device processing is disabled. You can still invoke it through GUI");
        }
    }

    public Future<String> processDevices() {
        try {
            // Check and set if already running
            if (!isRunning.compareAndSet(false, true)) {
                log.warn("processDevices: Already running");
                return CompletableFuture.completedFuture("ALREADY RUNNING");
            }
            log.debug("processDevices: Processing devices");

            // Process requests
            try {
                if (processorProperties.isAutomaticFailedDetection())
                    processFailedDevices();
                if (processorProperties.isAutomaticArchivingEnabled())
                    archiveDevices();
            } catch (Throwable t) {
                log.error("processDevices: ERROR while processing devices: ", t);
            }

            log.debug("processDevices: Processing completed");

            return CompletableFuture.completedFuture("DONE");
        } catch (Throwable e) {
            log.error("processDevices: EXCEPTION: ", e);
            return CompletableFuture.completedFuture("ERROR: "+e.getMessage());
        } finally {
            // Clear running flag
            isRunning.set(false);
        }
    }

    private void processFailedDevices() {
        Instant suspectDeviceThreshold = Instant.now().minus(processorProperties.getSuspectDeviceThreshold(), ChronoUnit.MINUTES);
        Instant failedDeviceThreshold = Instant.now().minus(processorProperties.getFailedDeviceThreshold(), ChronoUnit.MINUTES);
        log.trace("processFailedDevices: BEGIN: suspect-threshold={}, failed-threshold={}",
                suspectDeviceThreshold, failedDeviceThreshold);
        List<Device> suspectDevices = deviceManagementService.getAll().stream()
                .filter(r -> ! STATUSES_TO_EXCLUDE_FROM_SUSPECT_CHECK.contains(r.getStatus()))
                .filter(r -> r.getStatusUpdate()==null || r.getStatusUpdate().getStateLastUpdate().isBefore(suspectDeviceThreshold))
                .filter(r -> r.getMetrics()==null || r.getMetrics().getTimestamp().isBefore(suspectDeviceThreshold))
                .filter(r -> r.getCreationDate().isBefore(suspectDeviceThreshold))
                .toList();

        if (log.isDebugEnabled())
            log.debug("processFailedDevices: Found {} suspect devices: {}",
                    suspectDevices.size(), suspectDevices.stream().map(Device::getId).toList());

        for (Device device : suspectDevices) {
            // Mark device as suspect
            log.debug("processFailedDevices: Marking as suspect device with Id: {}", device.getId());
            device.setStatus(DeviceStatus.SUSPECT);
            if (device.getSuspectTimestamp()==null) {
                device.setSuspectTimestamp(Instant.now());
                device.setRetries(0);
                log.info("processFailedDevices: Marked as suspect device with Id: {}", device.getId());
            } else {
                device.incrementRetries();
            }

            // If fail threshold exceeded the mark device as PROBLEMATIC
            if ( (device.getStatusUpdate()==null || device.getStatusUpdate().getStateLastUpdate().isBefore(failedDeviceThreshold))
                    && (device.getMetrics()==null || device.getMetrics().getTimestamp().isBefore(failedDeviceThreshold))
                    && device.getCreationDate().isBefore(failedDeviceThreshold) )
            {
                device.setStatus(DeviceStatus.FAILED);
                JSONObject lost_device_message = new JSONObject();
                lost_device_message.put("device_name",device.getName());
                Clock clock = Clock.systemUTC();
                lost_device_message.put("timestamp",(int)(clock.millis()/1000));
                BrokerPublisher device_lost_publisher = new BrokerPublisher(processorProperties.getLost_device_topic(), processorProperties.getNebulous_broker_ip_address(), processorProperties.getNebulous_broker_username(), processorProperties.getNebulous_broker_password(), "");
                device_lost_publisher.publish(lost_device_message.toJSONString(), Collections.singleton(""));
                log.warn("processFailedDevices: Marked as FAILED device with Id: {}", device.getId());
            }

            deviceManagementService.update(device);
        }

        log.trace("processProblematicDevices: END");
    }

    private void archiveDevices() {
        Instant archiveThreshold = Instant.now().minus(processorProperties.getArchivingThreshold(), ChronoUnit.MINUTES);
        log.trace("archiveDevices: BEGIN: archive-threshold: {}", archiveThreshold);
        List<Device> devicesForArchiving = deviceManagementService.getAll().stream()
                .filter(r -> STATUSES_TO_ARCHIVE.contains(r.getStatus()))
                .filter(r -> r.getLastUpdateDate().isBefore(archiveThreshold))
                .toList();

        log.debug("archiveDevices: Found {} devices for archiving: {}",
                devicesForArchiving.size(), devicesForArchiving.stream().map(Device::getId).toList());

        for (Device device : devicesForArchiving) {
            log.debug("archiveDevices: Archiving device with Id: {}", device.getId());
            deviceManagementService.archiveDeviceBySystem(device.getId());
            log.info("archiveDevices: Archived device with Id: {}", device.getId());
        }

        log.trace("archiveDevices: END");
    }
}
