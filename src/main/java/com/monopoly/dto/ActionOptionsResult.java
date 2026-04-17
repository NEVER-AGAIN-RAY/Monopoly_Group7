package com.monopoly.dto;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** {@code ACTION_OPTIONS} 查询结果。 */
public final class ActionOptionsResult {

    private boolean ok;
    private String error;
    private String effectCode;
    private boolean truncated;
    private List<ActionOptionRow> options = new ArrayList<>();

    public boolean isOk() {
        return ok;
    }

    public void setOk(boolean ok) {
        this.ok = ok;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public String getEffectCode() {
        return effectCode;
    }

    public void setEffectCode(String effectCode) {
        this.effectCode = effectCode;
    }

    public boolean isTruncated() {
        return truncated;
    }

    public void setTruncated(boolean truncated) {
        this.truncated = truncated;
    }

    public List<ActionOptionRow> getOptions() {
        return options == null ? Collections.emptyList() : Collections.unmodifiableList(options);
    }

    public void setOptions(List<ActionOptionRow> options) {
        this.options = options != null ? new ArrayList<>(options) : new ArrayList<>();
    }

    public void addOption(ActionOptionRow row) {
        if (row != null && options != null) {
            options.add(row);
        }
    }
}
