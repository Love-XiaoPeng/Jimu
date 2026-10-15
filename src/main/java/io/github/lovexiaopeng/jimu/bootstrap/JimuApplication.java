package io.github.lovexiaopeng.jimu.bootstrap;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "io.github.lovexiaopeng.jimu")
public class JimuApplication {
  public static void main(String[] args) {
    SpringApplication.run(JimuApplication.class, args);
  }
}
