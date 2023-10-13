
package eu.nebulous.resource.discovery.monitor;

import eu.nebulous.resource.discovery.ResourceDiscoveryProperties;
import eu.nebulous.resource.discovery.monitor.model.Device;
import eu.nebulous.resource.discovery.monitor.model.DeviceStatus;
import eu.nebulous.resource.discovery.monitor.service.DeviceManagementService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
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
    private final static List<DeviceStatus> STATUSES_TO_ARCHIVE = List.of(
            DeviceStatus.OFFBOARDED,
            DeviceStatus.OFFBOARD_ERROR
    );

    private final ResourceDiscoveryProperties processorProperties;
    private final DeviceManagementService deviceManagementService;
    private final TaskScheduler taskScheduler;
    private final AtomicBoolean isRunning = new AtomicBoolean(false);

    @Override
    public void afterPropertiesSet() throws Exception {
        // Initialize periodic device processing
        if (processorProperties.isEnablePeriodicProcessing()) {
            Instant firstRun;
            taskScheduler.scheduleAtFixedRate(this::processRequests,
                    firstRun = Instant.now().plusSeconds(processorProperties.getProcessingStartupDelay()),
                    Duration.ofSeconds(processorProperties.getProcessingPeriod()));
            log.info("DeviceProcessor: Started periodic device processing: period={}s, first-run-at={}",
                    processorProperties.getProcessingPeriod(), firstRun.atZone(ZoneId.systemDefault()));
        } else {
            log.info("DeviceProcessor: Periodic device processing is disabled. You can still invoke it through GUI");
        }
    }

    public Future<String> processRequests() {
        try {
            // Check and set if already running
            if (!isRunning.compareAndSet(false, true)) {
                log.warn("processRequests: Already running");
                return CompletableFuture.completedFuture("ALREADY RUNNING");
            }
            log.debug("processRequests: Processing devices");

            // Process requests
            try {
                if (processorProperties.isAutomaticArchivingEnabled())
                    archiveDevices();
            } catch (Throwable t) {
                log.error("processRequests: ERROR while processing devices: ", t);
            }

            log.debug("processRequests: Processing completed");

            return CompletableFuture.completedFuture("DONE");
        } catch (Throwable e) {
            log.error("processRequests: EXCEPTION: ", e);
            return CompletableFuture.completedFuture("ERROR: "+e.getMessage());
        } finally {
            // Clear running flag
            isRunning.set(false);
        }
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
            log.debug("archiveDevices: Archiving request with Id: {}", device.getId());
            deviceManagementService.archiveRequestBySystem(device.getId());
            log.info("archiveDevices: Archived request with Id: {}", device.getId());
        }

        log.trace("archiveDevices: END");
    }
}
