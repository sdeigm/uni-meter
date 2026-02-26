package com.deigmueller.uni_meter.application;

public final class Version {

  private static final String VERSION = "${project.version}";
  private static final String GROUP_ID = "${project.groupId}";
  private static final String ARTIFACT_ID = "${project.artifactId}";
  private static final String BUILD_TIME = "${buildTime}";

  public static String getVersion() {
    return VERSION;
  }

  public static String getGroupId() {
    return GROUP_ID;
  }
  
  public static String getArtifactId() {
    return ARTIFACT_ID;
  }

  public static String getBuildTime() {
    return BUILD_TIME;
  }
}
