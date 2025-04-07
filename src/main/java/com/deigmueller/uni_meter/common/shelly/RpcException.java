package com.deigmueller.uni_meter.common.shelly;

import lombok.Getter;
import org.jetbrains.annotations.NotNull;

@Getter
public class RpcException extends RuntimeException {
  private final int code;
  
  public RpcException(int code,
                      @NotNull String message) {
    super(message);
    this.code = code;
  }
}
