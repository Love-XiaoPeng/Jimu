package io.github.lovexiaopeng.jimu.adapters.persistence.search.mapper;

import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface NativeSearchRunMapper {
  List<Map<String, Object>> selectNonStoppedSearch(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("task") String task,
      @Param("invocation") String invocation);

  List<Map<String, Object>> selectSearchAggregate(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("task") String task);

  int insertSearchRun(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("invocation") String invocation,
      @Param("task") String task,
      @Param("effect") String effect,
      @Param("attempt") String attempt,
      @Param("worker") String worker,
      @Param("host") String host,
      @Param("directory") String directory,
      @Param("snapshot") String snapshot,
      @Param("digest") String digest,
      @Param("pin") String pin);

  int startSearchRun(
      @Param("query") SessionQueryContext query,
      @Param("identity") String identity,
      @Param("tenant") String tenant,
      @Param("task") String task,
      @Param("invocation") String invocation,
      @Param("attempt") String attempt);

  List<Map<String, Object>> selectSearchStopRun(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("invocation") String invocation,
      @Param("task") String task,
      @Param("effect") String effect,
      @Param("worker") String worker);

  int stopSearchRun(
      @Param("query") SessionQueryContext query,
      @Param("identity") String identity,
      @Param("tenant") String tenant,
      @Param("invocation") String invocation);

  List<Map<String, Object>> selectSearchCompleteRun(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("task") String task,
      @Param("invocation") String invocation,
      @Param("attempt") String attempt);

  int completeSearchRun(
      @Param("query") SessionQueryContext query,
      @Param("hash") String hash,
      @Param("size") long size,
      @Param("cursors") String cursors,
      @Param("tenant") String tenant,
      @Param("invocation") String invocation);

  List<Map<String, Object>> selectSearchPage(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("task") String task);

  List<Map<String, Object>> selectNativeSearches(
      @Param("query") SessionQueryContext query, @Param("host") String host);

  List<Map<String, Object>> selectExpiredSearches(
      @Param("query") SessionQueryContext query, @Param("host") String host);

  List<Map<String, Object>> selectSearchRemovalRun(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("invocation") String invocation,
      @Param("task") String task,
      @Param("effect") String effect);

  int removeSearchStorage(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("invocation") String invocation);
}
