package io.github.lovexiaopeng.jimu.bootstrap;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "io.github.lovexiaopeng.jimu")
public class JimuApplication {
  public static void main(String[] args) {
    // Each HTTP dispatch is budgeted by the host; transport retries must not create hidden
    // dispatches.
    System.setProperty("jdk.httpclient.disableRetryConnect", "true");
    System.setProperty("jdk.httpclient.redirects.retrylimit", "1");
    System.setProperty("jdk.httpclient.enableAllMethodRetry", "false");
    SpringApplication.run(JimuApplication.class, args);
  }
}
