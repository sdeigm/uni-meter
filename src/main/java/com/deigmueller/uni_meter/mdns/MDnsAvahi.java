package com.deigmueller.uni_meter.mdns;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public class MDnsAvahi implements MDnsKind {
  // Class members
  public static final String TYPE = "avahi";
  public static final String AVAHI_SERVICES_DIR = "/etc/avahi/services";
  private final static Logger LOGGER = LoggerFactory.getLogger("uni-meter.mdns.avahi");

  @Override
  public CompletionStage<MDnsHandle> register(@NotNull String type,
                                              @NotNull String name,
                                              int port,
                                              @NotNull Map<String,String> properties) {
    CompletionStage<MDnsHandle> returnValue = CompletableFuture.completedFuture(new AvahiMDnsHandle(null));
    
    final String directory = AVAHI_SERVICES_DIR;
    final String file = directory + "/" + name + ".service";
    
    Path directoryPath = Paths.get(directory);
    if (Files.exists(directoryPath)) {
      if (Files.isDirectory(directoryPath)) {
        if (Files.isWritable(directoryPath)) {
          try (FileWriter myWriter = new FileWriter(file)) {
            myWriter.write(getAvahiService(type, name, port, properties));
            LOGGER.info("successfully registered mdns service {}", name);
          } catch (IOException ioException) {
            LOGGER.error("could not write avahi service file {}: {}", file, ioException.getMessage());
          }
          
          File avahiServicesFile = new File(file);
          avahiServicesFile.deleteOnExit();

          returnValue = CompletableFuture.completedFuture(new AvahiMDnsHandle(file));
        } else {
          LOGGER.error("{} directory exists but is not writable (not running as root?)", directory);
        }
      } else {
        LOGGER.error("{}} directory exists but is not a directory", directory);
      }
    } else {
      LOGGER.error("no avahi services directory {} found (avahi daemon not installed?)", directory); 
    }
    
    return returnValue;
  }

  @Override
  public void unregister(@NotNull MDnsHandle handle) {
    if (handle instanceof AvahiMDnsHandle avahiMDnsHandle) {
      if (avahiMDnsHandle.fileName() != null) {
        try {
          Files.delete(Paths.get(avahiMDnsHandle.fileName()));
        } catch (Exception ignored) {}
      }
    }
  }

  private @NotNull String getAvahiService(@NotNull String type,
                                          @NotNull String name,
                                          int port,
                                          @NotNull Map<String,String> properties) {
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
  
  private record AvahiMDnsHandle(
        @Nullable String fileName
  ) implements MDnsHandle {} 
}