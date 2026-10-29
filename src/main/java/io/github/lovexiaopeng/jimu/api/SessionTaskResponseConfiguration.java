package io.github.lovexiaopeng.jimu.api;

import io.github.lovexiaopeng.jimu.kernel.task.api.SessionTaskResponse;
import io.github.lovexiaopeng.jimu.kernel.task.api.Tasks;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpOutputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.converter.AbstractHttpMessageConverter;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 内部响应句柄沿原MVC JSON converter消费，wire仍为原Tasks.View。 */
@Configuration(proxyBeanMethods = false)
public class SessionTaskResponseConfiguration implements WebMvcConfigurer {
  @Override
  public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
    var delegate =
        converters.stream()
            .filter(c -> c.canWrite(Tasks.View.class, MediaType.APPLICATION_JSON))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("Task JSON converter required"));
    converters.add(0, new Converter(delegate));
  }

  static final class Converter extends AbstractHttpMessageConverter<SessionTaskResponse> {
    private final HttpMessageConverter<Object> delegate;

    @SuppressWarnings("unchecked")
    Converter(HttpMessageConverter<?> delegate) {
      super(MediaType.APPLICATION_JSON);
      this.delegate = (HttpMessageConverter<Object>) delegate;
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
              try {
                delegate.write(
                    view,
                    MediaType.APPLICATION_JSON,
                    new HttpOutputMessage() {
                      public HttpHeaders getHeaders() {
                        return output.getHeaders();
                      }

                      public OutputStream getBody() throws IOException {
                        return new FilterOutputStream(output.getBody()) {
                          @Override
                          public void write(int value) throws IOException {
                            response.wire(1);
                            out.write(value);
                          }

                          @Override
                          public void write(byte[] bytes, int offset, int length)
                              throws IOException {
                            response.wire(length);
                            out.write(bytes, offset, length);
                          }
                        };
                      }
                    });
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
