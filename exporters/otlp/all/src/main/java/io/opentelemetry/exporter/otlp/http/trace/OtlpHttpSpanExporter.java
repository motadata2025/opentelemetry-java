/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.exporter.otlp.http.trace;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.io.SegmentedStringWriter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.exporter.internal.http.HttpExporter;
import io.opentelemetry.exporter.internal.http.HttpExporterBuilder;
import io.opentelemetry.exporter.internal.marshal.Marshaler;
import io.opentelemetry.exporter.internal.otlp.traces.SpanReusableDataMarshaler;
import io.opentelemetry.exporter.internal.otlp.traces.TraceRequestMarshaler;
import io.opentelemetry.exporter.logging.otlp.internal.writer.JsonUtil;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.common.export.MemoryMode;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import javax.annotation.concurrent.ThreadSafe;
import org.apache.commons.io.FileUtils;
import org.xerial.snappy.Snappy;

/**
 * Exports spans using OTLP via HTTP, using OpenTelemetry's protobuf model.
 *
 * @since 1.5.0
 */
@ThreadSafe
public final class OtlpHttpSpanExporter implements SpanExporter {

  private final HttpExporterBuilder<Marshaler> builder;
  private final HttpExporter<Marshaler> delegate;
  private final SpanReusableDataMarshaler marshaler;

  private static final Logger logger = Logger.getLogger(OtlpHttpSpanExporter.class.getName());

  private static final AtomicBoolean isShutdown = new AtomicBoolean(false);

  private static final AtomicBoolean isServiceNameSet = new AtomicBoolean(false);

  private static final String PATH_SEPARATOR = FileSystems.getDefault().getSeparator();

  private static final String CONFIG_DIR = "config";

  private static final String AGENT_CONFIG = "agent.json";

  private static final String AGENT_RUNNING_STATUS_PATH = "/agent/agent.service.status";

  private static final String AGENT_STATE_PATH = "/agent/agent.state";

  private static final String TRACE_AGENT_STATE_PATH = "/agent/trace.agent.status";

  private static final ObjectMapper mapper = new ObjectMapper();

  public static final String AGENT_INSTALL_DIR = Optional.ofNullable(System.getProperty("otel.javaagent.configuration-file")).map(Paths::get).map(
      Path::getParent).map(Path::getParent).orElseThrow(() -> new IllegalStateException("Invalid configuration file path")).toString() + PATH_SEPARATOR;

  public static final String DATA_DIR = AGENT_INSTALL_DIR + "cache" + PATH_SEPARATOR;

  public static final String TRACE_FILE_FORMAT = "trace-%s-%s.cache"; // trace_servicename-653545242231.cache

  private static final String DEFAULT_SERVICE_NAME = "unknown_service";

  private String serviceName = DEFAULT_SERVICE_NAME;

  private static final String MOTADATA_TRACE_SERVICE_CHECK_TIME = "motadata.trace.service.check.time.sec";

  public static final int SERVICE_CHECK_TIME = getServiceTime();

  public Timer timer = new Timer("Config Check", true);

  private void updateExportStatus()
  {
    File configs = new File(AGENT_INSTALL_DIR  + CONFIG_DIR + PATH_SEPARATOR + AGENT_CONFIG);

    try
    {
      JsonNode rootNode = mapper.readTree(configs);

      boolean isAgentRunning = rootNode.at(AGENT_RUNNING_STATUS_PATH).asText().equalsIgnoreCase("running") &&
          rootNode.at(AGENT_STATE_PATH).asText().equalsIgnoreCase("enable") &&
          rootNode.at(TRACE_AGENT_STATE_PATH).asText().equalsIgnoreCase("yes") &&
          rootNode.at(String.format("/trace.agent/%s/service.trace.state", serviceName)).asText().equalsIgnoreCase("yes");

      logger.info(AGENT_RUNNING_STATUS_PATH + " : " + rootNode.at(AGENT_RUNNING_STATUS_PATH).asText());

      logger.info(AGENT_STATE_PATH + " : " + rootNode.at(AGENT_STATE_PATH).asText());

      logger.info(TRACE_AGENT_STATE_PATH + " : " + rootNode.at(TRACE_AGENT_STATE_PATH).asText());

      logger.info(String.format("/trace.agent/%s/service.trace.state", serviceName) + " : " + rootNode.at(String.format("/trace.agent/%s/service.trace.state", serviceName)).asText());

      logger.info("Agent running status : " + isAgentRunning);

      logger.info("Agent dir " + AGENT_INSTALL_DIR);

      isShutdown.set(!isAgentRunning);
    }
    catch (Exception exception)
    {
      logger.warning(exception.getMessage());
    }
  }

  OtlpHttpSpanExporter(
      HttpExporterBuilder<Marshaler> builder,
      HttpExporter<Marshaler> delegate,
      MemoryMode memoryMode) {
    this.builder = builder;
    this.delegate = delegate;
    this.marshaler = new SpanReusableDataMarshaler(memoryMode, delegate::export);
  }

  /**
   * Returns a new {@link OtlpHttpSpanExporter} using the default values.
   *
   * <p>To load configuration values from environment variables and system properties, use <a
   * href="https://github.com/open-telemetry/opentelemetry-java/tree/main/sdk-extensions/autoconfigure">opentelemetry-sdk-extension-autoconfigure</a>.
   *
   * @return a new {@link OtlpHttpSpanExporter} instance.
   */
  public static OtlpHttpSpanExporter getDefault() {
    return builder().build();
  }

  /**
   * Returns a new builder instance for this exporter.
   *
   * @return a new builder instance for this exporter.
   */
  public static OtlpHttpSpanExporterBuilder builder() {
    return new OtlpHttpSpanExporterBuilder();
  }

  /**
   * Returns a builder with configuration values equal to those for this exporter.
   *
   * <p>IMPORTANT: Be sure to {@link #shutdown()} this instance if it will no longer be used.
   *
   * @since 1.29.0
   */
  public OtlpHttpSpanExporterBuilder toBuilder() {
    return new OtlpHttpSpanExporterBuilder(builder.copy(), marshaler.getMemoryMode());
  }

  private void setServiceName(Collection<SpanData> spans) {
    SpanData spanData = !spans.isEmpty() ? spans.stream().findFirst().get() : null;
    Resource resource = spanData != null ? spanData.getResource() : null;
    Attributes attributes = resource != null ? resource.getAttributes() : null;
    String name = attributes != null ? attributes.get(AttributeKey.stringKey("service.name")) : null;

    if (name != null) {
      isServiceNameSet.set(true);
      serviceName = name;
      logger.info("Open-telemetry agent service name : " + serviceName);

      timer.scheduleAtFixedRate(new TimerTask() {
        @Override
        public void run() {
          logger.info("Checking agent status");
          updateExportStatus();
        }
      }, 0L, SERVICE_CHECK_TIME * 1000L);
    } else {
      serviceName = DEFAULT_SERVICE_NAME;
    }
  }

  /**
   * Submits all the given spans in a single batch to the OpenTelemetry collector.
   *
   * @param spans the list of sampled Spans to be exported.
   * @return the result of the operation
   */
  @Override
  public CompletableResultCode export(Collection<SpanData> spans) {

    if (!isServiceNameSet.get()) {
      setServiceName(spans);
    }

    if (!isShutdown.get() && isServiceNameSet.get()) {
      TraceRequestMarshaler traceRequestMarshaler = TraceRequestMarshaler.create(spans);

      SegmentedStringWriter segmentedStringWriter =
          new SegmentedStringWriter(JsonUtil.JSON_FACTORY._getBufferRecycler());

      try (JsonGenerator gen = JsonUtil.create(segmentedStringWriter)) {
        traceRequestMarshaler.writeJsonToGenerator(gen);
      } catch (IOException ignore) {
        logger.warning("Failed to write trace request marshaller. " + ignore.getMessage());
      }

      String filePath = DATA_DIR + String.format(TRACE_FILE_FORMAT, serviceName, System.currentTimeMillis());

      try {

        String content = segmentedStringWriter.getAndClear();

        JsonNode node = mapper.readTree(content);

        JsonNode spansNode = node.get("resourceSpans");

        logger.info("Writing trace file: " + content);

        for (int resourceIndex = 0; resourceIndex < spansNode.size(); resourceIndex++)
        {
            JsonNode resourceSpan = spansNode.get(resourceIndex);

            JsonNode resource = resourceSpan.get("resource");

            String resourceAttribute = resource.asText("attributes");

            logger.info("Harsh parseAttribute(resourceAttribute) "+ parseAttribute(resourceAttribute).toString());
        }

        FileUtils.writeByteArrayToFile(new File(filePath), Snappy.compress(content));

      } catch (IOException ignore) {
        logger.warning("Failed to write into file: " + Arrays.toString(ignore.getStackTrace()));
      }

      marshaler.export(spans);

      return CompletableResultCode.ofSuccess();
    }
    else
    {
      logger.info("Agent is not running, hence skipping the export");
    }

    return CompletableResultCode.ofSuccess();
  }


  private static Map<String, Object> parseAttribute(String attributes)
      throws JsonProcessingException {
    ObjectMapper objectMapper = new ObjectMapper();
    Map<String, Object> attributeMap = new HashMap<>();

      if (!attributes.isEmpty()) {
        // Convert Vert.x JsonArray to Jackson JsonNode
        JsonNode arrayNode = objectMapper.readTree(attributes);

        for (JsonNode node : arrayNode) {
          String metric = node.path("key").asText();
          JsonNode valueNode = node.path("value");

          if (!valueNode.isMissingNode() && !valueNode.isEmpty()) {
            // Get the first field from the value object
            JsonNode firstValue = valueNode.fields().next().getValue();

            attributeMap.put(metric, firstValue);
            }
          }
        }

    return attributeMap;
  }

  /**
   * The OTLP exporter does not batch spans, so this method will immediately return with success.
   *
   * @return always Success
   */
  @Override
  public CompletableResultCode flush() {
    return CompletableResultCode.ofSuccess();
  }

  /** Shutdown the exporter, releasing any resources and preventing subsequent exports. */
  @Override
  public CompletableResultCode shutdown() {

    if (timer != null)
    {
      timer.cancel();
    }

    return delegate.shutdown();
  }

  @Override
  public String toString() {
    StringJoiner joiner = new StringJoiner(", ", "OtlpHttpSpanExporter{", "}");
    joiner.add(builder.toString(false));
    joiner.add("memoryMode=" + marshaler.getMemoryMode());
    return joiner.toString();
  }

  private static int getServiceTime()
  {
    String time = System.getProperty(MOTADATA_TRACE_SERVICE_CHECK_TIME);

    if (time == null)
    {
      time = System.getenv(MOTADATA_TRACE_SERVICE_CHECK_TIME.toLowerCase(Locale.ROOT).replaceAll(
          "\\.", "_"));
    }

    return time == null ? 30 : Integer.min(Integer.max(Integer.parseInt(time), 30), 120);
  }
}
