package io.github.lovexiaopeng.jimu.adapters.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.persistence.artifact.mapper.ArtifactMapper;
import io.github.lovexiaopeng.jimu.kernel.artifact.port.ReportPublications;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.PluginPin;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.ReportFile;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** 同 DS/UoW 的单项操作；行/JSON 包装不预读成员，不复制原 task Map。 */
final class MapperReportPublications implements ReportPublications {
  private final JdbcLedger ledger;

  MapperReportPublications(JdbcLedger ledger) {
    this.ledger = ledger;
  }

  private ArtifactMapper mapper() {
    return ledger.mappers.artifacts();
  }

  public <T> T transaction(Lease lease, Supplier<T> work) {
    return ledger.leaseTx(lease, work);
  }

  public Task fenced(Lease lease, boolean admission) {
    var row = ledger.fenced(lease, admission);
    return new Task() {
      public boolean publicReport() {
        return ledger.publicReport(row);
      }

      public PluginPin compositionPin() {
        return ledger.tools(row).pins().get("compose_report");
      }

      public List<Composition> confirmed() {
        return mapper()
            .selectConfirmedCompositions(
                ledger.queryContext(), JdbcLedger.s(row, "tenant_id"), JdbcLedger.s(row, "id"))
            .stream()
            .<Composition>map(CompositionRow::new)
            .toList();
      }

      public boolean imageMatches(
          String input, String output, ReportFile file, Composition composition) {
        return new io.github.lovexiaopeng.jimu.adapters.runtime.contract.ImageReportComposer()
            .matches(
                input,
                output,
                file,
                ledger.imageReportContext(
                    row, JdbcLedger.s(((CompositionRow) composition).row(), "id")));
      }

      public boolean textMatches(String input, String output, ReportFile file) {
        return new io.github.lovexiaopeng.jimu.adapters.runtime.contract.ReportComposer()
            .matches(input, output, file, ledger.reportContext(row));
      }

      public void event(String fact, String type) {
        ledger.event(row, fact, type, null);
      }
    };
  }

  public Invocation invocation(Lease lease, String invocation) {
    var row =
        JdbcLedger.first(
            ledger
                .mappers
                .invocations()
                .selectTaskInvocation(
                    ledger.queryContext(), lease.tenant(), lease.taskId(), invocation));
    return new Invocation() {
      public String tool() {
        return JdbcLedger.s(row, "tool");
      }

      public String state() {
        return JdbcLedger.s(row, "state");
      }
    };
  }

  public String input(Lease lease, String invocation) {
    return JdbcLedger.s(
        JdbcLedger.first(
            mapper().selectReportInput(ledger.queryContext(), lease.tenant(), invocation)),
        "input_snapshot");
  }

  public Json read(String json) {
    return new Node(Payloads.read(json, JsonNode.class));
  }

  public ReportFile receipt(String json) {
    return Payloads.read(json, ReportFile.class);
  }

  public String json(ReportFile file) {
    return Payloads.json(file);
  }

  public String hash(String text) {
    return Payloads.hash(text);
  }

  public List<Artifact> matching(Lease lease, String invocation, String digest) {
    return mapper()
        .selectReportReceipt(
            ledger.queryContext(), lease.tenant(), lease.taskId(), invocation, digest)
        .stream()
        .<Artifact>map(ArtifactRow::new)
        .toList();
  }

  public List<Artifact> otherProducer(Lease lease, String invocation) {
    return mapper()
        .selectOtherReportProducer(
            ledger.queryContext(), lease.tenant(), lease.taskId(), invocation)
        .stream()
        .<Artifact>map(ArtifactRow::new)
        .toList();
  }

  public Budget bucket(Lease lease) {
    var row = ledger.bucket(lease.tenant());
    return new Budget() {
      public long held() {
        return ledger.storageHeld(row);
      }

      public long limit() {
        return JdbcLedger.n(row, "storage_limit");
      }
    };
  }

  public String newId() {
    return JdbcLedger.id();
  }

  public void insert(
      Lease lease,
      String artifact,
      String invocation,
      String upload,
      ReportFile file,
      String receipt,
      String digest) {
    mapper()
        .insertReport(
            ledger.queryContext(),
            lease.tenant(),
            artifact,
            lease.taskId(),
            invocation,
            upload,
            lease.fence(),
            file.sha256(),
            file.size(),
            file.mediaType(),
            receipt,
            digest);
  }

  public void hold(Lease lease, long size) {
    mapper().holdStorage(ledger.queryContext(), size, lease.tenant());
  }

  public void reserve(Lease lease, String artifact, long size) {
    ledger.reserve(
        lease.tenant(), lease.taskId(), artifact, null, "STORAGE", size, "RESERVED", "KNOWN");
  }

  public List<Artifact> stagedIds(Lease lease, String invocation) {
    return mapper()
        .selectReportIds(ledger.queryContext(), lease.tenant(), lease.taskId(), invocation)
        .stream()
        .<Artifact>map(ArtifactRow::new)
        .toList();
  }

  public List<Artifact> artifacts(Lease lease, String invocation) {
    return mapper()
        .selectReportArtifacts(ledger.queryContext(), lease.tenant(), lease.taskId(), invocation)
        .stream()
        .<Artifact>map(ArtifactRow::new)
        .toList();
  }

  public void publish(Lease lease, String key, String artifact) {
    mapper().publishReport(ledger.queryContext(), key, lease.fence(), lease.tenant(), artifact);
  }

  public void settleStorage(Lease lease, String artifact) {
    mapper().settleStorage(ledger.queryContext(), lease.tenant(), artifact);
  }

  public void succeed(Lease lease, String output, String digest, String invocation) {
    mapper().succeedPresent(ledger.queryContext(), output, digest, lease.tenant(), invocation);
  }

  public void settleTool(Lease lease, String invocation) {
    mapper().settlePresent(ledger.queryContext(), lease.tenant(), invocation);
  }

  private record CompositionRow(Map<String, Object> row) implements Composition {
    public String input() {
      return JdbcLedger.s(row, "input_snapshot");
    }

    public String output() {
      return JdbcLedger.s(row, "output_snapshot");
    }

    public String inputDigest() {
      return JdbcLedger.s(row, "input_digest");
    }

    public String outputDigest() {
      return JdbcLedger.s(row, "output_digest");
    }

    public String version() {
      return JdbcLedger.s(row, "plugin_version");
    }

    public String digest() {
      return JdbcLedger.s(row, "plugin_digest");
    }
  }

  private record ArtifactRow(Map<String, Object> row) implements Artifact {
    public String id() {
      return JdbcLedger.s(row, "id");
    }

    public String uploadId() {
      return JdbcLedger.s(row, "upload_id");
    }

    public String state() {
      return JdbcLedger.s(row, "state");
    }

    public String hash() {
      return JdbcLedger.s(row, "sha256");
    }

    public String snapshotKey() {
      return JdbcLedger.s(row, "snapshot_key");
    }

    public String receipt() {
      return JdbcLedger.s(row, "file_receipt");
    }

    public String receiptDigest() {
      return JdbcLedger.s(row, "receipt_digest");
    }
  }

  private record Node(JsonNode node) implements Json {
    public Json path(String name) {
      return new Node(node.path(name));
    }

    public Json get(int index) {
      return new Node(node.get(index));
    }

    public String text() {
      return node.asText();
    }

    public long number() {
      return node.asLong();
    }

    public boolean array() {
      return node.isArray();
    }

    public int size() {
      return node.size();
    }

    public Iterator<Json> iterator() {
      var original = node.iterator();
      return new Iterator<>() {
        public boolean hasNext() {
          return original.hasNext();
        }

        public Json next() {
          return new Node(original.next());
        }
      };
    }
  }
}
