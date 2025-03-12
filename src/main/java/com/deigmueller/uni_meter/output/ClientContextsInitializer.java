package com.deigmueller.uni_meter.output;

import com.typesafe.config.Config;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.net.InetAddress;
import java.util.List;
import java.util.Map;

public interface ClientContextsInitializer {
  void initClientContexts(@NotNull Logger logger,
                          @NotNull List<? extends Config> remoteContexts,
                          @NotNull Map<InetAddress, ClientContext> clientContextMap);
}
