/*
 * Copyright (C) 2018-2023 layline.io GmbH <http://www.layline.io>
 */

package com.deigmueller.uni_meter.input.device.common.generic;

import com.typesafe.config.Config;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

public class ValueChannelReader extends BaseChannelReader {
  public ValueChannelReader(@NotNull Config config) {
    super(config);
  }

  @Override
  public @Nullable Double getValue(@NotNull Logger logger,
                                   @NotNull String payload) {
    try {
      return Double.parseDouble(StringUtils.strip(payload, "\"'")) * getScale();
    } catch (NumberFormatException exception) {
      logger.debug("Failed to parse value <{}>: {}", payload, exception.getMessage());
      return null;
    }
  }
}
