package io.github.lovexiaopeng.jimu.kernel.task.port;

import io.github.lovexiaopeng.jimu.kernel.task.api.Tasks.Identity;

/** 原锁结果的惰性首行视图；enabled 保留原类型，epoch 仅在资格通过后转换。 */
public interface TaskIdentityRecords {
  Rows<Actor> lockActor(Identity identity);

  Rows<Installation> lockInstallation(Identity identity, String capability);

  interface Rows<R> {
    boolean isEmpty();

    R first();
  }

  interface Actor {
    Object enabled();
  }

  interface Installation extends Actor {
    Number epoch();
  }
}
