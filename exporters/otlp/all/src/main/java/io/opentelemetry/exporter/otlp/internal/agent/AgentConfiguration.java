/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.exporter.otlp.internal.agent;

import java.util.Locale;
import java.util.Optional;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Configuration constants and utilities for agent-based export functionality.
 * This class provides centralized configuration management for custom agent integration.
 */
public final class AgentConfiguration {

  private static final String PATH_SEPARATOR = FileSystems.getDefault().getSeparator();
  private static final String CONFIG_DIR = "config";
  private static final String AGENT_CONFIG = "agent.json";
  private static final String DEFAULT_SERVICE_NAME = "unknown_service";

  // Agent configuration paths
  public static final String AGENT_RUNNING_STATUS_PATH = "/agent/agent.service.status";
  public static final String AGENT_STATE_PATH = "/agent/agent.state";

  // Directory paths
  public static final String AGENT_INSTALL_DIR = Optional.ofNullable(
      System.getProperty("otel.javaagent.configuration-file"))
      .map(Paths::get)
      .map(Path::getParent)
      .map(Path::getParent)
      .orElseThrow(() -> new IllegalStateException("Invalid configuration file path"))
      .toString() + PATH_SEPARATOR;

  public static final String DATA_DIR = AGENT_INSTALL_DIR + "cache" + PATH_SEPARATOR;
  public static final String CONFIG_FILE_PATH = AGENT_INSTALL_DIR + CONFIG_DIR + PATH_SEPARATOR + AGENT_CONFIG;

  // Default values
  public static final String DEFAULT_SERVICE_NAME_VALUE = DEFAULT_SERVICE_NAME;
  public static final int DEFAULT_CHECK_INTERVAL_SECONDS = 30;
  public static final int MIN_CHECK_INTERVAL_SECONDS = 30;
  public static final int MAX_CHECK_INTERVAL_SECONDS = 120;

  private AgentConfiguration() {
    // Utility class
  }

  /**
   * Resolves the service check time from system properties or environment variables.
   *
   * @param propertyName the system property name to check
   * @return the resolved check interval in seconds, bounded between min and max values
   */
  public static int resolveServiceCheckTime(String propertyName) {
    String time = System.getProperty(propertyName);

    if (time == null) {
      time = System.getenv(propertyName.toLowerCase(Locale.ROOT).replaceAll("\\.", "_"));
    }

    if (time == null) {
      return DEFAULT_CHECK_INTERVAL_SECONDS;
    }

    try {
      int parsedTime = Integer.parseInt(time);
      return Integer.min(Integer.max(parsedTime, MIN_CHECK_INTERVAL_SECONDS), MAX_CHECK_INTERVAL_SECONDS);
    } catch (NumberFormatException e) {
      return DEFAULT_CHECK_INTERVAL_SECONDS;
    }
  }

  /**
   * Signal-specific configuration for different telemetry types.
   */
  public static final class SignalConfig {
    private final String agentStatusPath;
    private final String serviceStatePath;
    private final String filePrefix;
    private final String checkTimeProperty;

    public SignalConfig(String signalType) {
      this.agentStatusPath = String.format("/agent/%s.agent.status", signalType);
      this.serviceStatePath = String.format("/%s.agent/%%s/service.%s.state", signalType, signalType);
      this.filePrefix = signalType;
      this.checkTimeProperty = String.format("motadata.%s.service.check.time.sec", signalType);
    }

    public String getAgentStatusPath() {
      return agentStatusPath;
    }

    public String getServiceStatePath(String serviceName) {
      return String.format(serviceStatePath, serviceName);
    }

    public String getFilePrefix() {
      return filePrefix;
    }

    public String getCheckTimeProperty() {
      return checkTimeProperty;
    }

    public String getFileFormat() {
      return filePrefix + "-%s-%s.cache";
    }
  }

  // Pre-configured signal types
  public static final SignalConfig TRACE_CONFIG = new SignalConfig("trace");
  public static final SignalConfig METRIC_CONFIG = new SignalConfig("metric");
}
