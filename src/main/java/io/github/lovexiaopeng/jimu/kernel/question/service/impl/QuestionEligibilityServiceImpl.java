package io.github.lovexiaopeng.jimu.kernel.question.service.impl;

import io.github.lovexiaopeng.jimu.kernel.question.port.QuestionEligibility;
import io.github.lovexiaopeng.jimu.kernel.question.service.QuestionEligibilityService;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;

/** Complete original pin, installation, retention and raw binding qualification. */
public final class QuestionEligibilityServiceImpl implements QuestionEligibilityService {
  public <P, C> void access(QuestionEligibility<P, C> port) {
    var pin = port.pin();
    var contract = port.contract(port.version(pin));
    var installed = port.installation();
    if (!port.version(pin).equals(installed.version())
        || !port.digest(pin).equals(installed.digest())
        || !port.digest(pin).equals(port.contractOutputDigest(contract))
        || port.epoch(pin) != installed.epoch()) throw new TaskFailure(409, "PLUGIN_PIN_MISMATCH");
    if ("PURGED".equals(port.cleanupState()))
      throw new TaskFailure(410, "QUESTION_CONTENT_EXPIRED");
    String binding = port.bindingSnapshot();
    if (binding == null
        || !port.hash(binding).equals(port.bindingDigest())
        || !port.expectedBinding(port.version(pin)).equals(binding))
      throw new TaskFailure(409, "SNAPSHOT_CORRUPT");
  }
}
