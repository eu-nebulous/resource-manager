
package eu.nebulous.resource.discovery.monitor.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.nebulous.resource.discovery.ResourceDiscoveryProperties;
import eu.nebulous.resource.discovery.common.BrokerUtil;
import eu.nebulous.resource.discovery.monitor.model.Device;
import eu.nebulous.resource.discovery.monitor.model.DeviceStatus;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@EnableAsync
@EnableScheduling
@RequiredArgsConstructor
public abstract class AbstractMonitorService implements InitializingBean, BrokerUtil.Listener {
    @NonNull protected final String name;
    protected final ResourceDiscoveryProperties monitorProperties;
    protected final TaskScheduler taskScheduler;
    protected final ObjectMapper objectMapper;
    protected final BrokerUtil brokerUtil;

    @Override
    public void afterPropertiesSet() throws Exception {
        // Initialize device status listener
        log.warn("!!!!!!!!!!!   AbstractMonitorService.afterPropertiesSet: {}", this.getClass());
        taskScheduler.schedule(this::initializeDeviceStatusListener,
                Instant.now().plusSeconds(monitorProperties.getSubscriptionStartupDelay()));
    }

    private void initializeDeviceStatusListener() {
        getTopicsToMonitor().forEach(topic -> {
            try {
                brokerUtil.subscribe(topic, this);
                log.debug("{}: Subscribed to topic: {}", name, topic);
                log.warn("!!!!!!!!!!!   AbstractMonitorService.initializeDeviceStatusListener: {}: Subscribed to topic: {}", this.getClass(), topic);
            } catch (Exception e) {
                log.error("{}: ERROR while subscribing to topic: {}\n", name, topic, e);
                taskScheduler.schedule(this::initializeDeviceStatusListener, Instant.now().plusSeconds(monitorProperties.getSubscriptionRetryDelay()));
            }
        });
    }

    protected abstract @NonNull List<String> getTopicsToMonitor();

    @Override
    public void onMessage(Map message) {
        try {
            log.debug("{}: Received a message: {}", name, message);
            processPayload(message);
        } catch (Exception e) {
            log.warn("{}: ERROR while processing message: {}\nException: ", name, message, e);
        }
    }

    public void setHealthyStatus(Device device) {
        device.setStatus(DeviceStatus.HEALTHY);
        device.setSuspectTimestamp(null);
        device.setRetries(0);
    }

    protected abstract void processPayload(Map<?, ?> dataMap);
}
