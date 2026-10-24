package io.github.lovexiaopeng.jimu.adapters.runtime.search;

import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.kernel.task.api.NativeSearchLedger.ProcessIdentity;
import java.io.IOException;
import org.junit.jupiter.api.Test;

class NativeProcessReceiptTest {
  @Test
  void legacyJsonDoesNotAcquireCurrentObserverIdentity() throws Exception {
    var self = NativeProcesses.identity(ProcessHandle.current().pid());
    var legacy =
        Payloads.read(
            "{\"pid\":"
                + self.pid()
                + ",\"startTicks\":0,\"bootId\":\""
                + self.bootId()
                + "\",\"pidNamespace\":\""
                + self.pidNamespace()
                + "\"}",
            ProcessIdentity.class);
    assertThrows(IOException.class, () -> NativeProcesses.stopped(legacy));
  }

  @Test
  void foreignObserverRejectsChangedTicksBeforeDeathProof() throws Exception {
    var self = NativeProcesses.identity(ProcessHandle.current().pid());
    var foreign = new ProcessIdentity(self.pid(), 0, self.bootId(), self.pidNamespace(), "0");
    assertThrows(IOException.class, () -> NativeProcesses.stopped(foreign));
  }

  @Test
  void foreignBootIsUnproven() throws Exception {
    var self = NativeProcesses.identity(ProcessHandle.current().pid());
    var foreign =
        new ProcessIdentity(
            self.pid(), 0, "other-boot", self.pidNamespace(), self.observerPidNamespace());
    assertThrows(IOException.class, () -> NativeProcesses.stopped(foreign));
  }
}
