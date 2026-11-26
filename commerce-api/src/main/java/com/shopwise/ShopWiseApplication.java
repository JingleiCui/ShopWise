package com.shopwise;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class ShopWiseApplication {
  public static void main(String[] args) {
    var app = SpringApplication.run(ShopWiseApplication.class, args);
    if (app.getEnvironment().getProperty("shopwise.seed-only", Boolean.class, false)) app.close();
  }
}
