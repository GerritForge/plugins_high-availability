// Copyright (C) 2015 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.ericsson.gerrit.plugins.highavailability;

import static java.util.concurrent.TimeUnit.HOURS;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import com.google.common.base.CharMatcher;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.flogger.FluentLogger;
import com.google.gerrit.common.Nullable;
import com.google.gerrit.extensions.annotations.PluginName;
import com.google.gerrit.server.config.ConfigUtil;
import com.google.gerrit.server.config.PluginConfigFactory;
import com.google.gerrit.server.config.SitePaths;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.eclipse.jgit.lib.Config;

@Singleton
public class Configuration {
  private static final FluentLogger log = FluentLogger.forEnclosingClass();

  // common parameter to peerInfo section
  static final String PEER_INFO_SECTION = "peerInfo";

  // common parameters to cache and index sections
  static final String THREAD_POOL_SIZE_KEY = "threadPoolSize";
  static final int DEFAULT_INDEX_MAX_TRIES = 2;
  static final int DEFAULT_INDEX_RETRY_INTERVAL = 30000;
  static final int DEFAULT_THREAD_POOL_SIZE = 4;
  static final String NUM_STRIPED_LOCKS = "numStripedLocks";
  static final int DEFAULT_NUM_STRIPED_LOCKS = 10;

  private final Main main;
  private final AutoReindex autoReindex;
  private final PeerInfo peerInfo;
  private final JGroups jgroups;
  private final Http http;
  private final Cache cache;
  private final Event event;
  private final Index index;
  private final Websession websession;
  private PeerInfoStatic peerInfoStatic;
  private PeerInfoJGroups peerInfoJGroups;
  private HealthCheck healthCheck;

  public enum PeerInfoStrategy {
    JGROUPS,
    STATIC
  }

  @Inject
  Configuration(
      PluginConfigFactory pluginConfigFactory, @PluginName String pluginName, SitePaths site) {
    Config cfg = pluginConfigFactory.getGlobalPluginConfig(pluginName);
    main = new Main(site, cfg);
    autoReindex = new AutoReindex(cfg);
    peerInfo = new PeerInfo(cfg);
    switch (peerInfo.strategy()) {
      case STATIC:
        peerInfoStatic = new PeerInfoStatic(cfg);
        break;
      case JGROUPS:
        peerInfoJGroups = new PeerInfoJGroups(cfg);
        break;
      default:
        throw new IllegalArgumentException("Not supported strategy: " + peerInfo.strategy);
    }
    jgroups = new JGroups(site, cfg);
    http = new Http(cfg);
    cache = new Cache(cfg);
    event = new Event(cfg);
    index = new Index(cfg);
    websession = new Websession(cfg);
    healthCheck = new HealthCheck(cfg);
  }

  public Main main() {
    return main;
  }

  public AutoReindex autoReindex() {
    return autoReindex;
  }

  public PeerInfo peerInfo() {
    return peerInfo;
  }

  public PeerInfoStatic peerInfoStatic() {
    return peerInfoStatic;
  }

  public PeerInfoJGroups peerInfoJGroups() {
    return peerInfoJGroups;
  }

  public JGroups jgroups() {
    return jgroups;
  }

  public Http http() {
    return http;
  }

  public Cache cache() {
    return cache;
  }

  public Event event() {
    return event;
  }

  public Index index() {
    return index;
  }

  public Websession websession() {
    return websession;
  }

  public HealthCheck healthCheck() {
    return healthCheck;
  }

  private static int getInt(Config cfg, String section, String name, int defaultValue) {
    try {
      return cfg.getInt(section, name, defaultValue);
    } catch (IllegalArgumentException e) {
      log.atSevere().log("invalid value for %s; using default value %d", name, defaultValue);
      log.atFine().withCause(e).log("Failed to retrieve integer value");
      return defaultValue;
    }
  }

  public static class Main {
    static final String MAIN_SECTION = "main";
    static final String SHARED_DIRECTORY_KEY = "sharedDirectory";
    static final String DEFAULT_SHARED_DIRECTORY = "shared";

    private final Path sharedDirectory;

    private Main(SitePaths site, Config cfg) {
      String shared = Strings.emptyToNull(cfg.getString(MAIN_SECTION, null, SHARED_DIRECTORY_KEY));
      if (shared == null) {
        shared = DEFAULT_SHARED_DIRECTORY;
      }
      Path p = Paths.get(shared);
      if (p.isAbsolute()) {
        sharedDirectory = p;
      } else {
        sharedDirectory = site.resolve(shared);
      }
    }

    public Path sharedDirectory() {
      return sharedDirectory;
    }
  }

  public static class AutoReindex {

    static final String AUTO_REINDEX_SECTION = "autoReindex";
    static final String ENABLED = "enabled";
    static final String DELAY = "delay";
    static final String POLL_INTERVAL = "pollInterval";
    static final boolean DEFAULT_AUTO_REINDEX = false;
    static final long DEFAULT_DELAY = 10L;
    static final long DEFAULT_POLL_INTERVAL = 0L;

    private final boolean enabled;
    private final long delaySec;
    private final long pollSec;

    public AutoReindex(Config cfg) {
      enabled = cfg.getBoolean(AUTO_REINDEX_SECTION, ENABLED, DEFAULT_AUTO_REINDEX);
      delaySec =
          ConfigUtil.getTimeUnit(
              cfg, AUTO_REINDEX_SECTION, null, DELAY, DEFAULT_DELAY, TimeUnit.SECONDS);
      pollSec =
          ConfigUtil.getTimeUnit(
              cfg,
              AUTO_REINDEX_SECTION,
              null,
              POLL_INTERVAL,
              DEFAULT_POLL_INTERVAL,
              TimeUnit.SECONDS);
    }

    public boolean enabled() {
      return enabled;
    }

    public long delaySec() {
      return delaySec;
    }

    public long pollSec() {
      return pollSec;
    }
  }

  public static class PeerInfo {
    static final PeerInfoStrategy DEFAULT_PEER_INFO_STRATEGY = PeerInfoStrategy.STATIC;
    static final String STRATEGY_KEY = "strategy";

    private final PeerInfoStrategy strategy;

    private PeerInfo(Config cfg) {
      strategy = cfg.getEnum(PEER_INFO_SECTION, null, STRATEGY_KEY, DEFAULT_PEER_INFO_STRATEGY);
      log.atFine().log("Strategy: %s", strategy.name());
    }

    public PeerInfoStrategy strategy() {
      return strategy;
    }
  }

  public static class PeerInfoStatic {
    static final String STATIC_SUBSECTION = PeerInfoStrategy.STATIC.name().toLowerCase();
    static final String URL_KEY = "url";

    private final Set<String> urls;

    private PeerInfoStatic(Config cfg) {
      urls =
          Arrays.stream(cfg.getStringList(PEER_INFO_SECTION, STATIC_SUBSECTION, URL_KEY))
              .filter(Objects::nonNull)
              .filter(s -> !s.isEmpty())
              .map(s -> CharMatcher.is('/').trimTrailingFrom(s))
              .collect(Collectors.toSet());
      log.atFine().log("Urls: %s", urls);
    }

    public Set<String> urls() {
      return ImmutableSet.copyOf(urls);
    }
  }

  public static class PeerInfoJGroups {
    static final String JGROUPS_SUBSECTION = PeerInfoStrategy.JGROUPS.name().toLowerCase();
    static final String MY_URL_KEY = "myUrl";

    private final String myUrl;

    private PeerInfoJGroups(Config cfg) {
      myUrl = trimTrailingSlash(cfg.getString(PEER_INFO_SECTION, JGROUPS_SUBSECTION, MY_URL_KEY));
      log.atFine().log("My Url: %s", myUrl);
    }

    public String myUrl() {
      return myUrl;
    }

    @Nullable
    private static String trimTrailingSlash(@Nullable String in) {
      return in == null ? in : CharMatcher.is('/').trimTrailingFrom(in);
    }
  }

  public static class JGroups {
    static final String JGROUPS_SECTION = "jgroups";
    static final String SKIP_INTERFACE_KEY = "skipInterface";
    static final String CLUSTER_NAME_KEY = "clusterName";
    static final String PROTOCOL_STACK_KEY = "protocolStack";
    static final ImmutableList<String> DEFAULT_SKIP_INTERFACE_LIST =
        ImmutableList.of("lo*", "utun*", "awdl*");
    static final String DEFAULT_CLUSTER_NAME = "GerritHA";

    private final ImmutableList<String> skipInterface;
    private final String clusterName;
    private final Optional<Path> protocolStack;

    private JGroups(SitePaths site, Config cfg) {
      String[] skip = cfg.getStringList(JGROUPS_SECTION, null, SKIP_INTERFACE_KEY);
      skipInterface = skip.length == 0 ? DEFAULT_SKIP_INTERFACE_LIST : ImmutableList.copyOf(skip);
      log.atFine().log("Skip interface(s): %s", skipInterface);
      clusterName = getString(cfg, JGROUPS_SECTION, null, CLUSTER_NAME_KEY, DEFAULT_CLUSTER_NAME);
      log.atFine().log("Cluster name: %s", clusterName);
      protocolStack = getProtocolStack(cfg, site);
      log.atFine().log(
          "Protocol stack config %s",
          protocolStack.isPresent() ? protocolStack.get() : "not configured, using default stack.");
    }

    private static String getString(
        Config cfg, String section, String subSection, String name, String defaultValue) {
      String value = cfg.getString(section, subSection, name);
      return value == null ? defaultValue : value;
    }

    private static Optional<Path> getProtocolStack(Config cfg, SitePaths site) {
      String location = cfg.getString(JGROUPS_SECTION, null, PROTOCOL_STACK_KEY);
      return location == null ? Optional.empty() : Optional.of(site.etc_dir.resolve(location));
    }

    public Optional<Path> protocolStack() {
      return protocolStack;
    }

    public ImmutableList<String> skipInterface() {
      return skipInterface;
    }

    public String clusterName() {
      return clusterName;
    }
  }

  public static class Http {
    static final String HTTP_SECTION = "http";
    static final String USER_KEY = "user";
    static final String PASSWORD_KEY = "password";
    static final String CONNECTION_TIMEOUT_KEY = "connectionTimeout";
    static final String SOCKET_TIMEOUT_KEY = "socketTimeout";
    static final String MAX_TRIES_KEY = "maxTries";
    static final String RETRY_INTERVAL_KEY = "retryInterval";

    static final int DEFAULT_TIMEOUT_MS = 5000;
    static final int DEFAULT_MAX_TRIES = 360;
    static final int DEFAULT_RETRY_INTERVAL = 10000;

    private final String user;
    private final String password;
    private final int connectionTimeout;
    private final int socketTimeout;
    private final int maxTries;
    private final int retryInterval;

    private Http(Config cfg) {
      user = Strings.nullToEmpty(cfg.getString(HTTP_SECTION, null, USER_KEY));
      password = Strings.nullToEmpty(cfg.getString(HTTP_SECTION, null, PASSWORD_KEY));
      connectionTimeout = getInt(cfg, HTTP_SECTION, CONNECTION_TIMEOUT_KEY, DEFAULT_TIMEOUT_MS);
      socketTimeout = getInt(cfg, HTTP_SECTION, SOCKET_TIMEOUT_KEY, DEFAULT_TIMEOUT_MS);
      maxTries = getInt(cfg, HTTP_SECTION, MAX_TRIES_KEY, DEFAULT_MAX_TRIES);
      retryInterval = getInt(cfg, HTTP_SECTION, RETRY_INTERVAL_KEY, DEFAULT_RETRY_INTERVAL);
    }

    public String user() {
      return user;
    }

    public String password() {
      return password;
    }

    public int connectionTimeout() {
      return connectionTimeout;
    }

    public int socketTimeout() {
      return socketTimeout;
    }

    public int maxTries() {
      return maxTries;
    }

    public int retryInterval() {
      return retryInterval;
    }
  }

  /** Common parameters to cache, event, index and websession */
  public abstract static class Forwarding {
    static final boolean DEFAULT_SYNCHRONIZE = true;
    static final String SYNCHRONIZE_KEY = "synchronize";

    private final boolean synchronize;

    private Forwarding(Config cfg, String section) {
      synchronize = getBoolean(cfg, section, SYNCHRONIZE_KEY, DEFAULT_SYNCHRONIZE);
    }

    private static boolean getBoolean(
        Config cfg, String section, String name, boolean defaultValue) {
      try {
        return cfg.getBoolean(section, name, defaultValue);
      } catch (IllegalArgumentException e) {
        log.atSevere().log("invalid value for %s; using default value %s", name, defaultValue);
        log.atFine().withCause(e).log("Failed to retrieve boolean value");
        return defaultValue;
      }
    }

    public boolean synchronize() {
      return synchronize;
    }
  }

  public static class Cache extends Forwarding {
    static final String CACHE_SECTION = "cache";
    static final String PATTERN_KEY = "pattern";

    private final int threadPoolSize;
    private final List<String> patterns;

    private Cache(Config cfg) {
      super(cfg, CACHE_SECTION);
      threadPoolSize = getInt(cfg, CACHE_SECTION, THREAD_POOL_SIZE_KEY, DEFAULT_THREAD_POOL_SIZE);
      patterns = Arrays.asList(cfg.getStringList(CACHE_SECTION, null, PATTERN_KEY));
    }

    public int threadPoolSize() {
      return threadPoolSize;
    }

    public List<String> patterns() {
      return Collections.unmodifiableList(patterns);
    }
  }

  public static class Event extends Forwarding {
    static final String EVENT_SECTION = "event";

    private Event(Config cfg) {
      super(cfg, EVENT_SECTION);
    }
  }

  public static class Index extends Forwarding {
    static final String INDEX_SECTION = "index";
    static final String MAX_TRIES_KEY = "maxTries";
    static final String RETRY_INTERVAL_KEY = "retryInterval";

    private final int threadPoolSize;
    private final int retryInterval;
    private final int maxTries;

    private final int numStripedLocks;

    private Index(Config cfg) {
      super(cfg, INDEX_SECTION);
      threadPoolSize = getInt(cfg, INDEX_SECTION, THREAD_POOL_SIZE_KEY, DEFAULT_THREAD_POOL_SIZE);
      numStripedLocks = getInt(cfg, INDEX_SECTION, NUM_STRIPED_LOCKS, DEFAULT_NUM_STRIPED_LOCKS);
      retryInterval = getInt(cfg, INDEX_SECTION, RETRY_INTERVAL_KEY, DEFAULT_INDEX_RETRY_INTERVAL);
      maxTries = getInt(cfg, INDEX_SECTION, MAX_TRIES_KEY, DEFAULT_INDEX_MAX_TRIES);
    }

    public int threadPoolSize() {
      return threadPoolSize;
    }

    public int numStripedLocks() {
      return numStripedLocks;
    }

    public int retryInterval() {
      return retryInterval;
    }

    public int maxTries() {
      return maxTries;
    }
  }

  public static class Websession extends Forwarding {
    static final String WEBSESSION_SECTION = "websession";
    static final String CLEANUP_INTERVAL_KEY = "cleanupInterval";
    static final String DEFAULT_CLEANUP_INTERVAL = "24 hours";
    static final long DEFAULT_CLEANUP_INTERVAL_MS = HOURS.toMillis(24);

    private final long cleanupInterval;

    private Websession(Config cfg) {
      super(cfg, WEBSESSION_SECTION);
      cleanupInterval =
          ConfigUtil.getTimeUnit(
              Strings.nullToEmpty(cfg.getString(WEBSESSION_SECTION, null, CLEANUP_INTERVAL_KEY)),
              DEFAULT_CLEANUP_INTERVAL_MS,
              MILLISECONDS);
    }

    public long cleanupInterval() {
      return cleanupInterval;
    }
  }

  public static class HealthCheck {
    static final String HEALTH_CHECK_SECTION = "healthCheck";
    static final String ENABLE_KEY = "enable";
    static final boolean DEFAULT_HEALTH_CHECK_ENABLED = true;

    private final boolean enabled;

    private HealthCheck(Config cfg) {
      enabled = cfg.getBoolean(HEALTH_CHECK_SECTION, ENABLE_KEY, DEFAULT_HEALTH_CHECK_ENABLED);
    }

    public boolean enabled() {
      return enabled;
    }
  }
}
