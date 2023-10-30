package eu.nebulous.resource.discovery;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
public class StatusController {
	@GetMapping(value = "/status")
	public String status() {
		return "OK";
	}
}
