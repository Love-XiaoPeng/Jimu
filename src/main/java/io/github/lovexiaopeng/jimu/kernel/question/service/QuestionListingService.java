package io.github.lovexiaopeng.jimu.kernel.question.service;

import io.github.lovexiaopeng.jimu.kernel.question.port.QuestionListing;
import java.util.Map;

public interface QuestionListingService {
  <Q, A> Map<String, Object> list(QuestionListing<Q, A> port);
}
