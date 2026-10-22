package io.github.lovexiaopeng.jimu.plugin.api;

/**
 * Host-bound atomic literal edit; scope, observations and operation identity are not model input.
 */
@FunctionalInterface
public interface EditPort {
  FilePort.Written edit(String filePath, String oldString, String newString, boolean replaceAll);
}
