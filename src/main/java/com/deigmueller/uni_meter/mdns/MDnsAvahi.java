package com.deigmueller.uni_meter.mdns;

import com.typesafe.config.Config;
import org.apache.commons.lang3.StringUtils;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.DispatcherSelector;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.ReceiveBuilder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.concurrent.ExecutionContextExecutor;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public class MDnsAvahi extends MDnsKind {
  // Class members
  private static final Logger LOGGER = LoggerFactory.getLogger("uni-meter.mdns.avahi");
  private static final Logger LOGGER_AVAHI_PUBLISH = LoggerFactory.getLogger("uni-meter.mdns.avahi.publish");
  public static final String TYPE = "avahi";
  public static final String AVAHI_SERVICES_DIR = "/etc/avahi/services";

  // Instance members
  private final Set<NameAndIpAddress> registeredServers = new HashSet<>();
  private final Set<NameAndIpAddress> startedPublishers = new HashSet<>();
  private final boolean enableAvahiPublish;
  private String avahiPublishBinary;
  
  public static Behavior<Command> create(@NotNull Config config) {
    return Behaviors.setup(context -> new MDnsAvahi(context, config));
  }

  protected MDnsAvahi(@NotNull ActorContext<Command> context,
                      @NotNull Config config) {
    super(context, config);
    
    enableAvahiPublish = config.getBoolean("enable-avahi-publish");
    avahiPublishBinary = config.getString("avahi-publish");
    if (enableAvahiPublish && StringUtils.isBlank(avahiPublishBinary)) {
      findAvahiPublishBinary();
    }
  }

  @Override
  public ReceiveBuilder<Command> newReceiveBuilder() {
    return super.newReceiveBuilder()
          .onMessage(AvahiPublishSearchFinished.class, this::onAvahiPublishSearchFinished)
          .onMessage(RestartAvahiPublish.class, this::onRestartAvahiPublish)
          .onMessage(AvahiPublishFinished.class, this::onAvahiPublishFinished)
          .onMessage(AvahiPublishFailed.class, this::onAvahiPublishFailed);
  }
  

  @Override
  protected Behavior<Command> onRegisterService(@NotNull RegisterService registerService) {
    LOGGER.trace("MDnsAvahi.onRegisterService()");
    
    final String directory = AVAHI_SERVICES_DIR;
    final String file = directory + "/" + registerService.name() + registerService.type() + ".service";
    
    Path directoryPath = Paths.get(directory);
    if (Files.exists(directoryPath)) {
      if (Files.isDirectory(directoryPath)) {
        if (Files.isWritable(directoryPath)) {
          try (FileWriter myWriter = new FileWriter(file)) {
            myWriter.write(
                  getAvahiService(
                        registerService.type(), 
                        registerService.name(), 
                        registerService.port(), 
                        registerService.properties()));
            LOGGER.info("successfully registered mdns service {}/{}", registerService.name(), registerService.type());
          } catch (IOException ioException) {
            LOGGER.error("could not write avahi service file {}: {}", file, ioException.getMessage());
          }
          
          File avahiServicesFile = new File(file);
          avahiServicesFile.deleteOnExit();
        } else {
          LOGGER.error("{} directory exists but is not writable (not running as root?)", directory);
        }
      } else {
        LOGGER.error("{}} directory exists but is not a directory", directory);
      }
    } else {
      LOGGER.error("no avahi services directory {} found (avahi daemon not installed?)", directory); 
    }
    
    registerServer(registerService);
    
    return Behaviors.same();
  }
  
  private Behavior<Command> onAvahiPublishSearchFinished(AvahiPublishSearchFinished message) {
    LOGGER.trace("MDnsAvahi.onAvahiPublishSearchFinished()");

    avahiPublishBinary = message.path() != null ? message.path() : "";
    if (StringUtils.isBlank(avahiPublishBinary)) {
      LOGGER.info("avahi-publish binary not found");
    } else {
      LOGGER.info("avahi-publish binary found: {}", avahiPublishBinary);
      startAvahiPublishing();
    }
    
    return Behaviors.same();
  }
  
  private Behavior<Command> onRestartAvahiPublish(RestartAvahiPublish message) {
    LOGGER.trace("MDnsAvahi.onRestartAvahiPublish()");
    
    startAvahiPublishing();
    
    return Behaviors.same();
  }
  
  private Behavior<Command> onAvahiPublishFinished(AvahiPublishFinished message) {
    LOGGER.trace("MDnsAvahi.onAvahiPublishFinished()");
    
    startedPublishers.remove(new NameAndIpAddress(message.name(), message.ip()));
    
    LOGGER.info("avahi-publish finished for {}/{}", message.name(), message.ip());
    
    getContext().getSystem().scheduler().scheduleOnce(
          Duration.ofSeconds(60),
          () -> getContext().getSelf().tell(RestartAvahiPublish.INSTANCE),
          getContext().getExecutionContext());
    
    return Behaviors.same();
  }
  
  private Behavior<Command> onAvahiPublishFailed(AvahiPublishFailed message) {
    LOGGER.trace("MDnsAvahi.onAvahiPublishFailed()");

    startedPublishers.remove(new NameAndIpAddress(message.name(), message.ip()));

    LOGGER.info("avahi-publish failed for {}/{}: {}", message.name(), message.ip(), message.throwable().getMessage());

    getContext().getSystem().scheduler().scheduleOnce(
          Duration.ofSeconds(60),
          () -> getContext().getSelf().tell(RestartAvahiPublish.INSTANCE),
          getContext().getExecutionContext());

    return Behaviors.same();
  }
  
  
  private @NotNull String getAvahiService(@NotNull String type,
                                          @NotNull String name,
                                          int port,
                                          @NotNull Map<String,String> properties) {
    LOGGER.trace("MDnsAvahi.getAvahiService()");

    StringBuilder sb = new StringBuilder();
    
    sb.append("<?xml version=\"1.0\" standalone='no'?>\n");
    sb.append("<service-group>\n");
    sb.append("  <name replace-wildcards=\"no\">"); sb.append(name); sb.append("</name>\n");
    sb.append("  <service protocol=\"ipv4\">\n");
    sb.append("    <type>"); sb.append(type); sb.append("._tcp</type>\n");
    sb.append("    <port>"); sb.append(port); sb.append("</port>\n");
    
    for (Map.Entry<String,String> entry : properties.entrySet()) {
      sb.append("    <txt-record>"); sb.append(entry.getKey()); sb.append("="); sb.append(entry.getValue()); sb.append("</txt-record>\n");
    }

    sb.append("  </service>\n");
    sb.append("</service-group>\n");

    return sb.toString();
  }

  /**
   * Publish the DNS information of the specified server via mDNS 
   * @param registerService Server to publish
   */
  private void registerServer(@NotNull RegisterService registerService) {
    LOGGER.trace("MDnsAvahi.registerServer()");

    if (registeredServers.add(new NameAndIpAddress(registerService.name(), registerService.ipAddress()))) {
      if (enableAvahiPublish && !StringUtils.isAllBlank(avahiPublishBinary)) {
        startAvahiPublish(registerService.server(), registerService.ipAddress());
      }
    }
  }
  
  private void startAvahiPublishing() {
    LOGGER.trace("MDnsAvahi.startServers()");
    
    if (enableAvahiPublish) {
      registeredServers.forEach(server -> {
        if (!startedPublishers.contains(server)) {
          startedPublishers.add(server);
          startAvahiPublish(server.name(), server.ipAddress());
        }
      });
    }
  }

  /**
   * Start the avahi-publish process to publish the specified server via mDNS 
   * @param server Name of the server to publish
   * @param ipAddress IP address of the server to publish
   */
  private void startAvahiPublish(@NotNull String server, @NotNull String ipAddress) {
    ExecutionContextExecutor executor = getContext().getSystem().dispatchers().lookup(DispatcherSelector.blocking());

    try {
      CompletableFuture.runAsync(() -> {
              try {
                Process process = new ProcessBuilder(avahiPublishBinary, "-a", "-R", server, ipAddress)
                      .redirectErrorStream(true)
                      .start();
                
                LOGGER_AVAHI_PUBLISH.info("[{}] {} -a -R {} {} started", process.pid(), avahiPublishBinary, server, ipAddress);
  
                BufferedReader inputReader = new BufferedReader(new InputStreamReader(process.getInputStream()));
  
                try {
                  String line;
                  while ((line = inputReader.readLine ()) != null) {
                    LOGGER_AVAHI_PUBLISH.info("[{}] {}", process.pid(), line);
                  }
                } catch (Exception e) {
                  LOGGER_AVAHI_PUBLISH.error("[{}] {}", process.pid(), e.getMessage());
                }
                
                getContext().getSelf().tell(new AvahiPublishFinished(server, ipAddress));
              } catch (Exception e) {
                LOGGER_AVAHI_PUBLISH.error("avahi-publish failed: {}", e.getMessage());
                getContext().getSelf().tell(new AvahiPublishFailed(server, ipAddress, e));
              }
            },
            executor
      );
    } catch (Exception e) {
      LOGGER.error("avahi-publish failed: {}", e.getMessage());
      getContext().getSelf().tell(new AvahiPublishFailed(server, ipAddress, e));
    }
  }

  /**
   * Search for the avahi-publish binary
   */
  private void findAvahiPublishBinary() {
    final Set<String> directoriesToDo = new HashSet<>();
    directoriesToDo.add("/usr/sbin");
    directoriesToDo.add("/usr/bin");
    directoriesToDo.add("/sbin");
    directoriesToDo.add("/bin");
    directoriesToDo.add("/usr/local/sbin");
    directoriesToDo.add("/usr/local/bin");
    
    String avahiPublishBinaryPath = null;
    while (avahiPublishBinaryPath == null && !directoriesToDo.isEmpty()) {
      String directory = directoriesToDo.iterator().next();
      
      avahiPublishBinaryPath = scanDirectory(directory, 1);

      directoriesToDo.remove(directory);
    }
    
    getContext().getSelf().tell(new AvahiPublishSearchFinished(avahiPublishBinaryPath));
  }

  /**
   * Scan the specified directory recursively for the avahi-publish binary
   * @param directory Directory to scan
   * @param depth Current depth of the scan
   * @return Path to the avahi-publish binary or null if not found
   */
  private @Nullable String scanDirectory(@NotNull String directory, int depth) {
    try {
      File directoryFile = new File(directory);
      if (directoryFile.exists()) {
        File[] files = directoryFile.listFiles();
        if (files != null) {
          for (File file : files) {
            if (file.isFile() && file.getName().equals("avahi-publish")) {
              return file.getAbsolutePath();
            } else if (file.isDirectory() && depth < 10) {
              String path = scanDirectory(file.getAbsolutePath(), depth + 1);
              if (path != null) {
                return path;
              }
            }
          }
        }
      }
    } catch (Exception ignored) {
      // Ignore errors (most likely permission denied)
    }

    return null;
  }

  private record AvahiPublishSearchFinished(String path) implements Command {}

  private record AvahiPublishFailed(String name, String ip, Throwable throwable) implements Command {}

  private record AvahiPublishFinished(String name, String ip) implements Command {}
  
  private enum RestartAvahiPublish implements Command {
    INSTANCE
  }
  
  private record NameAndIpAddress(String name, String ipAddress) {}
}