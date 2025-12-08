package com.copyleft.GodsChoice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@ConfigurationPropertiesScan
@SpringBootApplication
public class GodsChoiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(GodsChoiceApplication.class, args);
	}

}
