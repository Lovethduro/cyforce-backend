package com.cyforce;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class CyforceBackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(CyforceBackendApplication.class, args);
	}

}
