package com.csd.salesforecast;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@EnableAsync
@SpringBootApplication
public class SalesForecastApplication {
    public static void main(String[] args) {
        SpringApplication.run(SalesForecastApplication.class, args);
    }
}
