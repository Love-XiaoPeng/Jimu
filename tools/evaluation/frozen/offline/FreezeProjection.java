package io.github.lovexiaopeng.jimu.adapters.runtime.web;
import java.nio.file.*;
import com.fasterxml.jackson.databind.ObjectMapper;
public class FreezeProjection {
  public static void main(String[] args) throws Exception {
    var output = PublicPageProjection.project(200, args[0], args[1], Files.readAllBytes(Path.of(args[2])));
    var json = io.github.lovexiaopeng.jimu.adapters.persistence.Payloads.json(output);
    Files.writeString(Path.of(args[3]), json);
    Files.writeString(Path.of(args[4]), output.content());
  }
}
