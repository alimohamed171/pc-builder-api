package com.pcbuilder.bundle.entity;


public enum BundleType {

    GAMING("Gaming"),
    PROGRAMMING("Programming"),
    CREATOR("Content Creator"),
    OFFICE("Office "),
    AI_WORKSTATION("AI Workstation"),
    DREAM_BUILD("Dream Build");

    private final String displayName;

    BundleType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}