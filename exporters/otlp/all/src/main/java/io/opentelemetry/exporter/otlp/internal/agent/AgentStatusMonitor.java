/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.exporter.otlp.internal.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.io.File;
import java.util.Collection;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * This class is internal and is hence not for public use. Its APIs are unstable and can change at
 * any time.
 */
public final class AgentStatusMonitor {

  private static final Logger logger = Logger.getLogger(AgentStatusMonitor.class.getName());
  private static final ObjectMapper mapper = new ObjectMapper();

  private static AgentConfiguration.SignalConfig signalConfig;
  private static AtomicBoolean isShutdown = new AtomicBoolean(false);
  private static AtomicBoolean isServiceNameSet = new AtomicBoolean(false);
  private static Timer timer;
  private static volatile String serviceName;

  public AgentStatusMonitor(AgentConfiguration.SignalConfig config) {
    signalConfig = config;
    isShutdown = new AtomicBoolean(false);
    isServiceNameSet = new AtomicBoolean(false);
    timer = new Timer("Agent Config Check", true);
    serviceName = AgentConfiguration.DEFAULT_SERVICE_NAME_VALUE;
  }

  /**
   * Initializes monitoring with the given service name and starts periodic status checks.
   *
   * @param name the service name to monitor
   */
  public static void initialize(String name) {
    if (name != null && !isServiceNameSet.get()) {
      isServiceNameSet.set(true);
      serviceName = name;
      logger.info("Open-telemetry agent service name : " + name);

      int checkInterval =
          AgentConfiguration.resolveServiceCheckTime(signalConfig.getCheckTimeProperty());

      if (timer == null) {
        timer = new Timer("Agent Config Check", true);

        timer.scheduleAtFixedRate(
            new TimerTask() {
              @Override
              public void run() {
                logger.info("Checking agent status");
                updateExportStatus();
              }
            },
            0L,
            checkInterval * 1000L);
      }
    }
  }

  public static String extractServiceName(Collection<SpanData> spans) {
    SpanData spanData = !spans.isEmpty() ? spans.stream().findFirst().get() : null;
    Resource resource = spanData != null ? spanData.getResource() : null;
    Attributes attributes = resource != null ? resource.getAttributes() : null;

    return attributes != null ? attributes.get(AttributeKey.stringKey("service.name")) : null;
  }

  // TODO -- we need to think of generic way to use only extractServiceName method...
  public static String extractServiceNameFromMetric(Collection<MetricData> metrics) {
    MetricData spanData = !metrics.isEmpty() ? metrics.stream().findFirst().get() : null;
    Resource resource = spanData != null ? spanData.getResource() : null;
    Attributes attributes = resource != null ? resource.getAttributes() : null;

    return attributes != null ? attributes.get(AttributeKey.stringKey("service.name")) : null;
  }

  public static void setSignalConfig(AgentConfiguration.SignalConfig signalConfig) {
    AgentStatusMonitor.signalConfig = signalConfig;
  }

  public static String getSignalFileFormat() {
    return String.format("%s%s", signalConfig.getFilePrefix(), signalConfig.getFileFormat());
  }

  /**
   * Checks if the agent is currently running and export is enabled.
   *
   * @return true if export should proceed, false otherwise
   */
  public static boolean shouldExport() {
    return !isShutdown.get() && isServiceNameSet.get();
  }

  /**
   * Gets the current service name.
   *
   * @return the service name
   */
  public static String getServiceName() {
    return serviceName;
  }

  /**
   * Checks if the service name has been set.
   *
   * @return true if service name is set, false otherwise
   */
  public static boolean isServiceNameSet() {
    return isServiceNameSet.get();
  }

  /** Shuts down the monitor and cancels all scheduled tasks. */
  public static void shutdown() {
    if (timer != null) {
      timer.cancel();
    }
  }

  private static void updateExportStatus() {
    File configFile = new File(AgentConfiguration.CONFIG_FILE_PATH);

    try {
      JsonNode rootNode = mapper.readTree(configFile);

      boolean isAgentRunning =
          rootNode
                  .at(AgentConfiguration.AGENT_RUNNING_STATUS_PATH)
                  .asText()
                  .equalsIgnoreCase("running")
              && rootNode
                  .at(AgentConfiguration.AGENT_STATE_PATH)
                  .asText()
                  .equalsIgnoreCase("enable")
              && rootNode.at(signalConfig.getAgentStatusPath()).asText().equalsIgnoreCase("yes")
              && rootNode
                  .at(signalConfig.getServiceStatePath(serviceName))
                  .asText()
                  .equalsIgnoreCase("yes");

      logger.info(
          AgentConfiguration.AGENT_RUNNING_STATUS_PATH
              + " : "
              + rootNode.at(AgentConfiguration.AGENT_RUNNING_STATUS_PATH).asText());
      logger.info(
          AgentConfiguration.AGENT_STATE_PATH
              + " : "
              + rootNode.at(AgentConfiguration.AGENT_STATE_PATH).asText());
      logger.info(
          signalConfig.getAgentStatusPath()
              + " : "
              + rootNode.at(signalConfig.getAgentStatusPath()).asText());
      logger.info(
          signalConfig.getServiceStatePath(serviceName)
              + " : "
              + rootNode.at(signalConfig.getServiceStatePath(serviceName)).asText());
      logger.info("Agent running status : " + isAgentRunning);

      isShutdown.set(!isAgentRunning);
    } catch (Exception exception) {
      logger.warning("Failed to update export status: " + exception.getMessage());
    }
  }
}
