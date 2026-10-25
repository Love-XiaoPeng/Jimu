package io.github.lovexiaopeng.jimu.kernel.question.port;

/** 原问题谓词和数据库字节汇总，按资格判断位置惰性求值。 */
import java.util.List;

public interface QuestionStorage {
  List<String> capabilities();

  Long bytes();
}
