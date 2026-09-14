package com.dlab;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@org.springframework.boot.context.properties.ConfigurationPropertiesScan
@SpringBootApplication
public class DlabApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(DlabApiApplication.class, args);
    }

}
