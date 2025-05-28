/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.exporter.otlp.http.trace;

import io.opentelemetry.exporter.internal.http.HttpExporter;
import io.opentelemetry.exporter.internal.http.HttpExporterBuilder;
import io.opentelemetry.exporter.internal.marshal.Marshaler;
import io.opentelemetry.exporter.internal.otlp.traces.SpanReusableDataMarshaler;
import io.opentelemetry.exporter.internal.otlp.traces.TraceRequestMarshaler;
import io.opentelemetry.exporter.otlp.internal.agent.AgentConfiguration;
import io.opentelemetry.exporter.otlp.internal.agent.AgentStatusMonitor;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.common.export.MemoryMode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.StringJoiner;
import java.util.logging.Logger;
import javax.annotation.concurrent.ThreadSafe;
import org.apache.commons.io.FileUtils;
import org.xerial.snappy.Snappy;
import java.io.ByteArrayOutputStream;

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

  private static final AgentConfiguration.SignalConfig signalConfig = new AgentConfiguration.SignalConfig("trace");

  private static final Logger logger = Logger.getLogger(OtlpHttpSpanExporter.class.getName());

  /*private void updateExportStatus()
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

      isShutdown.set(!isAgentRunning);
    }
    catch (Exception exception)
    {
      logger.warning(exception.getMessage());
    }
  }*/

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

  /*private void setServiceName(Collection<SpanData> spans) {
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
    }
  }*/

  /**
   * Submits all the given spans in a single batch to the OpenTelemetry collector.
   *
   * @param spans the list of sampled Spans to be exported.
   * @return the result of the operation
   */
  @Override
  public CompletableResultCode export(Collection<SpanData> spans) {

    AgentStatusMonitor.setSignalConfig(signalConfig);

    if (!AgentStatusMonitor.isServiceNameSet()) {
      String name = AgentStatusMonitor.extractServiceName(spans);

      if (name != null) {
        AgentStatusMonitor.initialize(name);
      }
    }

    if (AgentStatusMonitor.shouldExport()) {
      TraceRequestMarshaler traceRequestMarshaler = TraceRequestMarshaler.create(spans);

      try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
        traceRequestMarshaler.writeBinaryTo(output);

        FileUtils.writeByteArrayToFile(new File(AgentConfiguration.DATA_DIR +
            String.format(AgentStatusMonitor.getSignalFileFormat(), AgentStatusMonitor.getServiceName(), System.currentTimeMillis())), Snappy.compress(output.toByteArray()));

      } catch (IOException exception) {
        logger.warning("Failed to write trace request marshaller. " + exception.getMessage());
      }
    }
    else
    {
      logger.info("Agent is not running, hence skipping the export");
    }

    return CompletableResultCode.ofSuccess();
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
    AgentStatusMonitor.shutdown();
    return delegate.shutdown();
  }

  @Override
  public String toString() {
    StringJoiner joiner = new StringJoiner(", ", "OtlpHttpSpanExporter{", "}");
    joiner.add(builder.toString(false));
    joiner.add("memoryMode=" + marshaler.getMemoryMode());
    return joiner.toString();
  }
}
