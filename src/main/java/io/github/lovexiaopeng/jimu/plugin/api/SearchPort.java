package io.github.lovexiaopeng.jimu.plugin.api;

import java.util.List;

/** Host-bound immutable native search. Cursors never confer authority across task scopes. */
public interface SearchPort {
  record GlobPage(
      String root,
      List<String> paths,
      int seen,
      int returned,
      boolean truncated,
      String nextCursor) {
    public GlobPage {
      paths = List.copyOf(paths);
    }
  }

  record Match(String path, long lineNumber, String line, boolean lineTruncated) {}

  record GrepPage(
      List<Match> matches, int seen, int returned, boolean truncated, String nextCursor) {
    public GrepPage {
      matches = List.copyOf(matches);
    }
  }

  GlobPage glob(String pattern, String path, String cursor);

  GrepPage grep(String pattern, String path, String include, String cursor);
}
