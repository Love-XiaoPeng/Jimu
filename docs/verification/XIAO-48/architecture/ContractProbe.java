import io.github.lovexiaopeng.jimu.adapters.runtime.contract.SchemaContract;
import java.nio.file.*;
import java.util.*;

/** Runs the unmodified Jimu SchemaContract against the handoff artifacts. */
class ContractProbe {
  public static void main(String[] args) throws Exception {
    Path root=Path.of(args[0]);
    int schemas=0, positive=0, negative=0;
    try(var files=Files.walk(root)) {
      for(Path p:files.filter(p->p.toString().endsWith(".schema.json")).sorted().toList()) {
        new SchemaContract(Files.readAllBytes(p)); schemas++;
      }
    }
    for(String[] c:List.of(
      new String[]{"list_mcp_resources/input.schema.json","list-input.json"},
      new String[]{"list_mcp_resource_templates/input.schema.json","list-input.json"},
      new String[]{"read_mcp_resource/input.schema.json","read-input.json"},
      new String[]{"list_mcp_resources/output.schema.json","resources-output.json"},
      new String[]{"list_mcp_resource_templates/output.schema.json","templates-output.json"},
      new String[]{"read_mcp_resource/output.schema.json","read-output.json"},
      new String[]{"server-profile-v1.schema.json","server-profile.json"})) {
      var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
      new SchemaContract(Files.readAllBytes(root.resolve(c[0]))).validate(mapper.readTree(Files.readString(root.resolve("examples").resolve(c[1]))));
      positive++;
    }
    for(String[] c:List.of(
      new String[]{"list_mcp_resources/input.schema.json","{\"server\":\"docs\",\"endpoint\":\"http://127.0.0.1:1/mcp\"}"},
      new String[]{"list_mcp_resources/input.schema.json","{\"server\":\"docs\",\"cursor\":\"raw-upstream-cursor\"}"},
      new String[]{"list_mcp_resources/input.schema.json","{\"server\":\"docs\",\"cursor\":null}"},
      new String[]{"read_mcp_resource/input.schema.json","{\"server\":\"docs\",\"uri\":\"not a URI\"}"},
      new String[]{"read_mcp_resource/input.schema.json","{\"server\":\"docs\",\"uri\":\"jimu-fixture://docs/a\",\"tenant\":\"tenant-b\"}"})) {
      var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
      boolean rejected=false;
      try{new SchemaContract(Files.readAllBytes(root.resolve(c[0]))).validate(mapper.readTree(c[1]));}
      catch(IllegalArgumentException expected){rejected=true;}
      if(!rejected)throw new AssertionError(c[1]);
      negative++;
    }
    System.out.println("PASS unmodified Jimu SchemaContract: "+schemas+" schemas initialized, "+positive+" positive examples, "+negative+" negative inputs");
  }
}
