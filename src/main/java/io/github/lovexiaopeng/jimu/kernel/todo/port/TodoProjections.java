package io.github.lovexiaopeng.jimu.kernel.todo.port;

/** 原行与codec的惰性视图；不预读字段或重写投影。 */
public interface TodoProjections<R extends TodoProjections.Projection> {
  R projection();

  String hash(String snapshot);

  Binding decode(String snapshot);

  String expectedBinding();

  interface Projection {
    long recordVersion();

    long todoVersion();

    String snapshot();

    String digest();
  }

  interface Binding {
    int version();

    int todoVersion();
  }
}
