package io.github.lovexiaopeng.jimu.kernel.todo.port;

/** 原待办谓词和数据库字节汇总，按资格判断位置惰性求值。 */
import java.util.List;

public interface TodoStorage {
  List<String> capabilities();

  Long bytes();
}
