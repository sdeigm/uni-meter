/*
 * Copyright (C) 2018-2023 layline.io GmbH <http://www.layline.io>
 */

package com.deigmueller.uni_meter.input.device.common.generic;

import com.typesafe.config.Config;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class ValueChannelReader extends BaseChannelReader {
  public ValueChannelReader(@NotNull Config config) {
    super(config);
  }

  @Override
  public @Nullable Double getValue(@NotNull String payload) {
    try {
      return Double.parseDouble(payload) * getScale();
    } catch (NumberFormatException exception) {
      return null;
    }
  }
}
