package io.github.lovexiaopeng.jimu.plugins.builtin.websearch;

import io.github.lovexiaopeng.jimu.plugin.api.ExecutionContext;
import java.util.List;

/** Fixed Harness queries input. Search snippets do not constitute fetched webpage evidence. */
public interface SearchProvider {
  record Input(List<String> queries) {
    public Input {
      queries = List.copyOf(queries);
      if (queries.isEmpty()
          || queries.size() > 4
          || queries.stream().anyMatch(String::isBlank)
          || queries.stream().distinct().count() != queries.size())
        throw new IllegalArgumentException("Invalid search queries");
    }
  }

  record Source(String url, String title, String snippet, String publishedAt) {}

  record Output(List<Source> sources, boolean truncated, String answer) {
    public Output {
      sources = List.copyOf(sources);
    }
  }

  Output search(Input input, ExecutionContext context);
}
