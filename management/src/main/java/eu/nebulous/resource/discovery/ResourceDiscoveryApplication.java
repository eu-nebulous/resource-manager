package eu.nebulous.resource.discovery;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Data
@SpringBootApplication
@Configuration(proxyBeanMethods = false)
public class ResourceDiscoveryApplication implements InitializingBean {

	public static void main(String[] args) {
		SpringApplication.run(ResourceDiscoveryApplication.class, args);
	}

	private final ResourceDiscoveryProperties properties;
	@Override
	public void afterPropertiesSet() throws Exception {
		log.info("ResourceDiscoveryApplication: {}", properties);
	}
}
