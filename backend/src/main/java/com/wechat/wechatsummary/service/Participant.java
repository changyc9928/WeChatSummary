package com.wechat.wechatsummary.service;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * A single chat participant, keyed by WeChat talker id (wxid) which is the sole stable identifier
 * across the whole pipeline. Display names observed in the export, plus operator-supplied aliases
 * and social-media handles, are all attached to this one identity so the summarization prompts can
 * refer to a person consistently instead of treating each nickname as a different human.
 */
public class Participant {

    /** WeChat talker id. When the person never sent a message (referenced only) this may be null. */
    private final String wxid;

    /** Canonical display name used in the summary (prefer the most frequent sender name). */
    private String canonicalName;

    /** Every display name this identity was seen sending under. */
    private final Set<String> displayNames = new LinkedHashSet<>();

    /** Operator-supplied / inferred extra names (nicknames, insults, references). */
    private final Set<String> aliases = new LinkedHashSet<>();

    /** Social-media handles, e.g. {"instagram": "james.karkit"}. */
    private final Map<String, String> socialHandles = new LinkedHashMap<>();

    public Participant(String wxid, String canonicalName) {
        this.wxid = wxid;
        this.canonicalName = canonicalName;
        if (canonicalName != null) {
            this.displayNames.add(canonicalName);
        }
    }

    public String getId() {
        return wxid != null ? wxid : canonicalName;
    }

    public String getWxid() {
        return wxid;
    }

    public String getCanonicalName() {
        return canonicalName;
    }

    public void setCanonicalName(String canonicalName) {
        this.canonicalName = canonicalName;
        if (canonicalName != null) {
            this.displayNames.add(canonicalName);
        }
    }

    public Set<String> getDisplayNames() {
        return displayNames;
    }

    public Set<String> getAliases() {
        return aliases;
    }

    public Map<String, String> getSocialHandles() {
        return socialHandles;
    }

    /** All known names (canonical + observed display names + aliases) for matching. */
    public Set<String> allNames() {
        Set<String> all = new LinkedHashSet<>(displayNames);
        all.addAll(aliases);
        if (canonicalName != null) {
            all.add(canonicalName);
        }
        return all;
    }
}
