package com.ashish.distributed_order_platform.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ClockConfig {

	/** Injected instead of calling Instant.now() so time is controllable in tests. */
	@Bean
	Clock clock() {
		return Clock.systemUTC();
	}
}
