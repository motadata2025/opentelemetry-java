/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.exporter.otlp.http.metrics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.exporter.internal.http.HttpExporter;
import io.opentelemetry.exporter.internal.http.HttpExporterBuilder;
import io.opentelemetry.exporter.internal.marshal.Marshaler;
import io.opentelemetry.exporter.internal.otlp.metrics.MetricReusableDataMarshaler;
import io.opentelemetry.exporter.internal.otlp.metrics.MetricsRequestMarshaler;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.common.export.MemoryMode;
import io.opentelemetry.sdk.metrics.Aggregation;
import io.opentelemetry.sdk.metrics.InstrumentType;
import io.opentelemetry.sdk.metrics.data.AggregationTemporality;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.metrics.export.AggregationTemporalitySelector;
import io.opentelemetry.sdk.metrics.export.DefaultAggregationSelector;
import io.opentelemetry.sdk.metrics.export.MetricExporter;
import io.opentelemetry.sdk.resources.Resource;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.Locale;
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
 * Exports metrics using OTLP via HTTP, using OpenTelemetry's protobuf model.
 *
 * @since 1.14.0
 */
@ThreadSafe
public final class OtlpHttpMetricExporter implements MetricExporter {

  private final HttpExporterBuilder<Marshaler> builder;
  private final HttpExporter<Marshaler> delegate;
  // Visible for testing
  final AggregationTemporalitySelector aggregationTemporalitySelector;
  // Visible for testing
  final DefaultAggregationSelector defaultAggregationSelector;
  private final MetricReusableDataMarshaler marshaler;

  private static final Logger logger = Logger.getLogger(OtlpHttpMetricExporter.class.getName());

  private static final AtomicBoolean isShutdown = new AtomicBoolean(false);

  private static final AtomicBoolean isServiceNameSet = new AtomicBoolean(false);

  private static final String PATH_SEPARATOR = FileSystems.getDefault().getSeparator();

  private static final String CONFIG_DIR = "config";

  private static final String AGENT_CONFIG = "agent.json";

  private static final String AGENT_RUNNING_STATUS_PATH = "/agent/agent.service.status";

  private static final String AGENT_STATE_PATH = "/agent/agent.state";

  private static final String TRACE_AGENT_STATE_PATH = "/agent/trace.agent.status";

  private static final ObjectMapper mapper = new ObjectMapper();

  public static final String AGENT_INSTALL_DIR =
      Optional.ofNullable(System.getProperty("otel.javaagent.configuration-file"))
              .map(Paths::get)
              .map(Path::getParent)
              .map(Path::getParent)
              .orElseThrow(() -> new IllegalStateException("Invalid configuration file path"))
              .toString()
          + PATH_SEPARATOR;

  public static final String DATA_DIR = AGENT_INSTALL_DIR + "cache" + PATH_SEPARATOR;

  public static final String TRACE_FILE_FORMAT =
      "trace-%s-%s.cache"; // trace_servicename-653545242231.cache

  private static final String DEFAULT_SERVICE_NAME = "unknown_service";

  private String serviceName = DEFAULT_SERVICE_NAME;

  private static final String MOTADATA_TRACE_SERVICE_CHECK_TIME =
      "motadata.trace.service.check.time.sec";

  public static final int SERVICE_CHECK_TIME = getServiceTime();

  public Timer timer = new Timer("Config Check", true);

  private void updateExportStatus() {
    File configs = new File(AGENT_INSTALL_DIR + CONFIG_DIR + PATH_SEPARATOR + AGENT_CONFIG);

    try {
      JsonNode rootNode = mapper.readTree(configs);

      boolean isAgentRunning =
          rootNode.at(AGENT_RUNNING_STATUS_PATH).asText().equalsIgnoreCase("running")
              && rootNode.at(AGENT_STATE_PATH).asText().equalsIgnoreCase("enable")
              && rootNode.at(TRACE_AGENT_STATE_PATH).asText().equalsIgnoreCase("yes")
              && rootNode
                  .at(String.format("/trace.agent/%s/service.trace.state", serviceName))
                  .asText()
                  .equalsIgnoreCase("yes");

      logger.info(
          AGENT_RUNNING_STATUS_PATH + " : " + rootNode.at(AGENT_RUNNING_STATUS_PATH).asText());

      logger.info(AGENT_STATE_PATH + " : " + rootNode.at(AGENT_STATE_PATH).asText());

      logger.info(TRACE_AGENT_STATE_PATH + " : " + rootNode.at(TRACE_AGENT_STATE_PATH).asText());

      logger.info(
          String.format("/trace.agent/%s/service.trace.state", serviceName)
              + " : "
              + rootNode
                  .at(String.format("/trace.agent/%s/service.trace.state", serviceName))
                  .asText());

      logger.info("Agent running status : " + isAgentRunning);

      isShutdown.set(!isAgentRunning);
    } catch (Exception exception) {
      logger.warning(exception.getMessage());
    }
  }

  OtlpHttpMetricExporter(
      HttpExporterBuilder<Marshaler> builder,
      HttpExporter<Marshaler> delegate,
      AggregationTemporalitySelector aggregationTemporalitySelector,
      DefaultAggregationSelector defaultAggregationSelector,
      MemoryMode memoryMode) {
    this.builder = builder;
    this.delegate = delegate;
    this.aggregationTemporalitySelector = aggregationTemporalitySelector;
    this.defaultAggregationSelector = defaultAggregationSelector;
    this.marshaler = new MetricReusableDataMarshaler(memoryMode, delegate::export);
  }

  /**
   * Returns a new {@link OtlpHttpMetricExporter} using the default values.
   *
   * <p>To load configuration values from environment variables and system properties, use <a
   * href="https://github.com/open-telemetry/opentelemetry-java/tree/main/sdk-extensions/autoconfigure">opentelemetry-sdk-extension-autoconfigure</a>.
   *
   * @return a new {@link OtlpHttpMetricExporter} instance.
   */
  public static OtlpHttpMetricExporter getDefault() {
    return builder().build();
  }

  /**
   * Returns a new builder instance for this exporter.
   *
   * @return a new builder instance for this exporter.
   */
  public static OtlpHttpMetricExporterBuilder builder() {
    return new OtlpHttpMetricExporterBuilder();
  }

  /**
   * Returns a builder with configuration values equal to those for this exporter.
   *
   * <p>IMPORTANT: Be sure to {@link #shutdown()} this instance if it will no longer be used.
   *
   * @since 1.29.0
   */
  public OtlpHttpMetricExporterBuilder toBuilder() {
    return new OtlpHttpMetricExporterBuilder(
        builder.copy(),
        aggregationTemporalitySelector,
        defaultAggregationSelector,
        marshaler.getMemoryMode());
  }

  @Override
  public AggregationTemporality getAggregationTemporality(InstrumentType instrumentType) {
    return aggregationTemporalitySelector.getAggregationTemporality(instrumentType);
  }

  @Override
  public Aggregation getDefaultAggregation(InstrumentType instrumentType) {
    return defaultAggregationSelector.getDefaultAggregation(instrumentType);
  }

  @Override
  public MemoryMode getMemoryMode() {
    return marshaler.getMemoryMode();
  }

  /**
   * Submits all the given metrics in a single batch to the OpenTelemetry collector.
   *
   * @param metrics the list of sampled Metrics to be exported.
   * @return the result of the operation
   */
  @Override
  public CompletableResultCode export(Collection<MetricData> metrics) {

    logger.info("Exporting metrics " + metrics);

    if (!isServiceNameSet.get()) {
      setServiceName(metrics);
    }

    if (!isShutdown.get() && isServiceNameSet.get()) {
      MetricsRequestMarshaler traceRequestMarshaler = MetricsRequestMarshaler.create(metrics);

      try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
        traceRequestMarshaler.writeBinaryTo(output);

        FileUtils.writeByteArrayToFile(
            new File(
                DATA_DIR
                    + String.format(TRACE_FILE_FORMAT, serviceName, System.currentTimeMillis())),
            Snappy.compress(output.toByteArray()));

      } catch (IOException exception) {
        logger.warning("Failed to write trace request marshaller. " + exception.getMessage());
      }
    } else {
      logger.info("Agent is not running, hence skipping the export");
    }

    return CompletableResultCode.ofSuccess();
  }

  /**
   * The OTLP exporter does not batch metrics, so this method will immediately return with success.
   *
   * @return always Success
   */
  @Override
  public CompletableResultCode flush() {
    return CompletableResultCode.ofSuccess();
  }

  /** Shutdown the exporter. */
  @Override
  public CompletableResultCode shutdown() {

    if (timer != null) {
      timer.cancel();
    }

    return delegate.shutdown();
  }

  private void setServiceName(Collection<MetricData> metrics) {
    MetricData metricData = !metrics.isEmpty() ? metrics.stream().findFirst().get() : null;
    Resource resource = metricData != null ? metricData.getResource() : null;
    Attributes attributes = resource != null ? resource.getAttributes() : null;
    String name =
        attributes != null ? attributes.get(AttributeKey.stringKey("service.name")) : null;

    if (name != null) {
      isServiceNameSet.set(true);
      serviceName = name;
      logger.info("Open-telemetry agent service name : " + serviceName);

      timer.scheduleAtFixedRate(
          new TimerTask() {
            @Override
            public void run() {
              logger.info("Checking agent status");
              updateExportStatus();
            }
          },
          0L,
          SERVICE_CHECK_TIME * 1000L);
    }
  }

  @Override
  public String toString() {
    StringJoiner joiner = new StringJoiner(", ", "OtlpHttpMetricExporter{", "}");
    joiner.add(builder.toString(false));
    joiner.add(
        "aggregationTemporalitySelector="
            + AggregationTemporalitySelector.asString(aggregationTemporalitySelector));
    joiner.add(
        "defaultAggregationSelector="
            + DefaultAggregationSelector.asString(defaultAggregationSelector));
    joiner.add("memoryMode=" + marshaler.getMemoryMode());
    return joiner.toString();
  }

  private static int getServiceTime() {
    String time = System.getProperty(MOTADATA_TRACE_SERVICE_CHECK_TIME);

    if (time == null) {
      time =
          System.getenv(
              MOTADATA_TRACE_SERVICE_CHECK_TIME.toLowerCase(Locale.ROOT).replaceAll("\\.", "_"));
    }

    return time == null ? 30 : Integer.min(Integer.max(Integer.parseInt(time), 30), 120);
  }
}
