package com.deigmueller.uni_meter.mdns;

import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public class MDnsNone implements MDnsKind {
  @Override
  public CompletionStage<MDnsHandle> register(@NotNull String type, 
                                              @NotNull String name, 
                                              int port, 
                                              @NotNull Map<String, String> properties) {
    return CompletableFuture.completedFuture(null);
  }

  @Override
  public void unregister(@NotNull MDnsHandle handle) {
  }
}
