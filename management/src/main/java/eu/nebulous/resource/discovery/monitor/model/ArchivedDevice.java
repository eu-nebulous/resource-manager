package eu.nebulous.resource.discovery.monitor.model;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.mapping.Document;

@Slf4j
@Document(collection = "archived_device")
public class ArchivedDevice extends Device {
}
