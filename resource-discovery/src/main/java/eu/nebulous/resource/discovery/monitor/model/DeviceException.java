
package eu.nebulous.resource.discovery.monitor.model;

public class DeviceException extends RuntimeException {
    public DeviceException(String message) { super(message); }
    public DeviceException(Throwable t) { super(t); }
    public DeviceException(String message, Throwable t) { super(message, t); }
}
