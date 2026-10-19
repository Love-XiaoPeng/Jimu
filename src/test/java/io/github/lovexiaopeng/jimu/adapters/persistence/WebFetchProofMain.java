package io.github.lovexiaopeng.jimu.adapters.persistence;

import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Test classpath only; loads private fixture properties, emits metadata only. */
public final class WebFetchProofMain {
  private WebFetchProofMain() {}

  public static void main(String[] args) throws Exception {
    var config = new Properties();
    try (var input = Files.newInputStream(Path.of(args[0]))) {
      config.load(input);
    }
    var source =
        new DriverManagerDataSource(
            config.getProperty("jimu.db.url"),
            config.getProperty("jimu.db.user"),
            config.getProperty("jimu.db.password"));
    try {
      System.out.println(
          Payloads.json(new WebFetchReconciliation(source).assess(args[1], args[2], args[3])));
    } catch (TaskFailure failure) {
      System.out.println(Payloads.json(java.util.Map.of("code", failure.code())));
      System.exit(2);
    }
  }
}
