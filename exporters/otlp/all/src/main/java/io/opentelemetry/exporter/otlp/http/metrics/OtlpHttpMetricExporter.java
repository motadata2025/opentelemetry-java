/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.exporter.otlp.http.metrics;

import io.opentelemetry.exporter.internal.http.HttpExporter;
import io.opentelemetry.exporter.internal.http.HttpExporterBuilder;
import io.opentelemetry.exporter.internal.marshal.Marshaler;
import io.opentelemetry.exporter.internal.otlp.metrics.MetricReusableDataMarshaler;
import io.opentelemetry.exporter.internal.otlp.metrics.MetricsRequestMarshaler;
import io.opentelemetry.exporter.otlp.internal.agent.AgentConfiguration;
import io.opentelemetry.exporter.otlp.internal.agent.AgentStatusMonitor;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.common.export.MemoryMode;
import io.opentelemetry.sdk.metrics.Aggregation;
import io.opentelemetry.sdk.metrics.InstrumentType;
import io.opentelemetry.sdk.metrics.data.AggregationTemporality;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.metrics.export.AggregationTemporalitySelector;
import io.opentelemetry.sdk.metrics.export.DefaultAggregationSelector;
import io.opentelemetry.sdk.metrics.export.MetricExporter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.StringJoiner;
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

  private static final AgentConfiguration.SignalConfig signalConfig = new AgentConfiguration.SignalConfig("trace");

  private static final Logger logger = Logger.getLogger(OtlpHttpMetricExporter.class.getName());
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

    logger.warning("metric received in OtlpHttpMetricExporter. ");
    logger.warning(String.format("Service name is set or not : %s", AgentStatusMonitor.isServiceNameSet()));
    logger.warning(String.format("Service name : %s", AgentStatusMonitor.getServiceName()));

    AgentStatusMonitor.setSignalConfig(signalConfig);

    if (!AgentStatusMonitor.isServiceNameSet()) {
      String name = AgentStatusMonitor.extractServiceNameFromMetric(metrics);

      if (name != null) {
        AgentStatusMonitor.initialize(name);
      }
    }

    if (true || AgentStatusMonitor.shouldExport()) { // TODO -- temporary condition for testing purpose...
      MetricsRequestMarshaler metricsRequestMarshaler = MetricsRequestMarshaler.create(metrics);

      try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
        metricsRequestMarshaler.writeBinaryTo(output);

        FileUtils.writeByteArrayToFile(new File(AgentConfiguration.DATA_DIR +
            String.format(AgentStatusMonitor.getSignalFileFormat(), AgentStatusMonitor.getServiceName(), System.currentTimeMillis())), Snappy.compress(output.toByteArray()));

      } catch (IOException exception) {
        logger.warning("Failed to write metric request marshaller. " + exception.getMessage());
      }
    }
    else
    {
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
    AgentStatusMonitor.shutdown();
    return delegate.shutdown();
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
}
