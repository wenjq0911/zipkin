/*
 * Copyright 2015-2020 The OpenZipkin Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package zipkin2.elasticsearch.integration;

import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import java.io.IOException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.TestInstance;
import zipkin2.CheckResult;
import zipkin2.Span;
import zipkin2.elasticsearch.ElasticsearchStorage;
import zipkin2.elasticsearch.internal.Internal;
import zipkin2.storage.QueryRequest;

import static java.util.Arrays.asList;
import static org.assertj.core.api.Assertions.assertThat;
import static zipkin2.TestObjects.CLIENT_SPAN;
import static zipkin2.TestObjects.DAY;
import static zipkin2.TestObjects.TODAY;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class ITEnsureIndexTemplate {

  ElasticsearchStorage storage;

  /**
   * Returns a new {@link ElasticsearchStorage.Builder} for connecting to the backend for the test.
   */
  abstract ElasticsearchStorage.Builder newStorageBuilder(TestInfo testInfo);

  abstract void clear() throws Exception;

  @AfterAll void closeStorage() {
    storage.close();
  }

  @AfterEach void clearStorage() throws Exception {
    clear();
  }

  @Test void createZipkinIndexTemplate_getTraces_returnsSuccess(TestInfo testInfo)
    throws IOException {
    ElasticsearchStorage.Builder builder = newStorageBuilder(testInfo);
    storage = builder
      .templatePriority(10)
      .build();

    try {
      // Delete all index templates in order to create the "catch-all" index template, because
      // ES does not allow multiple index templates of the same index_patterns and priority
      deleteIndexTemplate("*");
      setUpCatchAllTemplate();

      // Implicitly creates an index template
      CheckResult check = storage.check();

      assertThat(check.ok()).isTrue();

      Span span = Span.newBuilder().traceId(CLIENT_SPAN.traceId())
        .id("1")
        .timestamp(TODAY * 1000L)
        .putTag("queryTest", "ok")
        .build();

      storage.spanConsumer().accept(asList(span)).execute();

      // Assert that Zipkin's templates work and source is returned
      assertThat(storage.spanStore().getTraces(QueryRequest.newBuilder()
        .endTs(TODAY + DAY)
        .lookback(DAY * 2)
        .limit(10)
        .parseAnnotationQuery("queryTest=" + span.tags().get("queryTest"))
        .build()).execute())
        .flatExtracting(t -> t).containsExactly(span);
    } finally {
      // Delete "catch-all" index template so it does not interfere with any other test
      deleteIndexTemplate("catch-all");
    }
  }

  /**
   * Create a "catch-all" index template with the lowest priority prior to running tests to ensure
   * that the index templates created during tests with higher priority function as designed. Only
   * applicable for ES >= 7.8
   */
  void setUpCatchAllTemplate() throws IOException {
    AggregatedHttpRequest updateTemplate = AggregatedHttpRequest.of(
      RequestHeaders.of(
        HttpMethod.PUT, catchAllIndexPath(), HttpHeaderNames.CONTENT_TYPE, MediaType.JSON_UTF_8),
      HttpData.ofUtf8(catchAllTemplate()));
    Internal.instance.http(storage).newCall(updateTemplate, (parser, contentString) -> null,
      "update-template").execute();
  }

  String catchAllIndexPath() {
    return "/_index_template/catch-all";
  }

  /**
   * Catch-all template doesn't store source
   */
  String catchAllTemplate() {
    return "{\n"
      + "  \"index_patterns\" : [\"*\"],\n"
      + "  \"priority\" : 0,\n"
      + "  \"template\": {\n"
      + "    \"settings\" : {\n"
      + "      \"number_of_shards\" : 1\n"
      + "    },\n"
      + "    \"mappings\" : {\n"
      + "      \"_source\": {\"enabled\": false }\n"
      + "    }\n"
      + "  }\n"
      + "}";
  }

  void deleteIndexTemplate(String pattern) throws IOException {
    String url = "/_index_template/" + pattern;
    AggregatedHttpRequest delete = AggregatedHttpRequest.of(HttpMethod.DELETE, url);
    Internal.instance.http(storage)
      .newCall(delete, (parser, contentString) -> null, "delete-index").execute();
  }
}
