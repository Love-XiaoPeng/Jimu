package io.github.lovexiaopeng.jimu.api;

import io.github.lovexiaopeng.jimu.kernel.task.api.SessionTaskResponse;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpOutputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.converter.AbstractHttpMessageConverter;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** v5请求私有句柄的固定Tasks.View JSON；通用serializer不再先分配再补计量。 */
@Configuration(proxyBeanMethods = false)
public class SessionTaskResponseConfiguration implements WebMvcConfigurer {
  @Override
  public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
    converters.add(0, new Converter());
  }

  static final class Converter extends AbstractHttpMessageConverter<SessionTaskResponse> {
    Converter() {
      super(MediaType.APPLICATION_JSON);
    }

    @Override
    protected boolean supports(Class<?> type) {
      return type == SessionTaskResponse.class;
    }

    @Override
    protected SessionTaskResponse readInternal(
        Class<? extends SessionTaskResponse> type, HttpInputMessage input) {
      throw new UnsupportedOperationException("Response only");
    }

    @Override
    protected void writeInternal(SessionTaskResponse response, HttpOutputMessage output)
        throws IOException {
      try {
        response.write(
            view -> {
              response.retain(SessionTaskJsonWriter.WORKSPACE_BYTES);
              try (var writer = new SessionTaskJsonWriter(response, output.getBody())) {
                writer.write(view);
              } catch (IOException failure) {
                throw new UncheckedIOException(failure);
              }
            });
      } catch (UncheckedIOException failure) {
        throw failure.getCause();
      } finally {
        response.close();
      }
    }
  }
}
