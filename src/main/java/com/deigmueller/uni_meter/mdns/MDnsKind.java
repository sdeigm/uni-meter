package com.deigmueller.uni_meter.mdns;

import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.concurrent.CompletionStage;

public interface MDnsKind {
  CompletionStage<MDnsHandle> register(@NotNull String type,
                                       @NotNull String name,
                                       int port,
                                       @NotNull Map<String,String> properties);
  
  void unregister(@NotNull MDnsHandle handle);
}
