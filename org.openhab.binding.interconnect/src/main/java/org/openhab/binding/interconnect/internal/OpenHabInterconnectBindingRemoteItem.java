package org.openhab.binding.interconnect.internal;

import java.util.List;

import org.eclipse.jdt.annotation.Nullable;

/**
 * POJO for storing remote item data and methods to return them in openhab2 specific
 * item/site map file syntax.
 *
 * @author Marco Heilmann
 *
 */
public class OpenHabInterconnectBindingRemoteItem implements Comparable<OpenHabInterconnectBindingRemoteItem> {

    // data which will be parsed from the json string
    private String link;
    private String state;
    private String type;
    private String name;
    private String label;
    private List<String> tags;
    private List<String> groupNames;

    @Nullable
    private String remoteName;

    @Nullable
    private List<String> remoteGroupNames;

    public List<String> getRemoteGroupNames() {
        return remoteGroupNames;
    }

    public void setRemoteGroupNames(List<String> remoteGroupNames) {
        this.remoteGroupNames = remoteGroupNames;
    }

    public String getRemoteName() {
        return remoteName;
    }

    public void setRemoteName(String remoteName) {
        this.remoteName = remoteName;
    }

    public List<String> getGroupNames() {
        return groupNames;
    }

    public void setGroupNames(List<String> groupNames) {
        this.groupNames = groupNames;
    }

    public String getLink() {
        return link;
    }

    public void setLink(String link) {
        this.link = link;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    /**
     * Returns the local name of the remote item or null if not set.
     *
     * @return
     */
    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public List<String> getTags() {
        return tags;
    }

    public void setTags(List<String> tags) {
        this.tags = tags;
    }

    /**
     * Returns the data of this item as openhab2 conform item string, which can be written to a *.items file for
     * example.
     *
     * @param thingID -- the id of the binding which calls this method
     * @return a string with all item data in it
     */
    public String asItemString(String thingID) {
        StringBuilder builder = new StringBuilder();
        builder.append(getType());
        builder.append(InterconnectBindingConstants.SPACE);
        builder.append(getName());
        builder.append(InterconnectBindingConstants.SPACE);
        if (getLabel() != null) {
            builder.append("\"");
            builder.append(getLabel());
            builder.append("\"");
            builder.append(InterconnectBindingConstants.SPACE);
        }
        if (!getGroupNames().isEmpty()) {
            builder.append("(");
            getGroupNames().forEach(group -> {
                builder.append(group);
                builder.append(", ");
            });
            builder.delete(builder.length() - 2, builder.length());
            builder.append(")");
            builder.append(InterconnectBindingConstants.SPACE);
        }

        if (!InterconnectBindingConstants.CHANNEL_GROUP.equals(getType())) {
            builder.append("{ channel = \"");
            builder.append(thingID);
            builder.append(":");
            builder.append(getType());
            builder.append(" \"}");
        }
        builder.append(System.lineSeparator());
        return builder.toString();
    }

    /**
     * Returns the data of this item as openhab2 conform site map string, which can be written to a *.sitemap file for
     * example.
     *
     * @return a string with all item data in it
     */
    public String asSitemapString() {
        StringBuilder builder = new StringBuilder();
        builder.append(InterconnectBindingConstants.SPACE);
        builder.append(InterconnectBindingConstants.SPACE);
        // builder.append((getType().contentEquals("Switch") || getType().contentEquals("Slider")) ? "Default" :
        // "Text");
        if (InterconnectBindingConstants.CHANNEL_GROUP.equals(getType())) {
            builder.append(InterconnectBindingConstants.CHANNEL_GROUP);
        } else {
            builder.append("Default");
        }
        builder.append(InterconnectBindingConstants.SPACE);
        builder.append("item=");
        builder.append(getName());
        builder.append(InterconnectBindingConstants.SPACE);
        builder.append("label=");
        builder.append("\"");
        builder.append(getLabel());
        builder.append(" [%s]");
        builder.append("\"");
        builder.append(System.lineSeparator());
        return builder.toString();
    }

    @Override
    public int compareTo(OpenHabInterconnectBindingRemoteItem o) {
        return getName().compareTo(o.getName());
    }

    /**
     * Checks the item configuration is the same as the other object o.
     * This method will compare the type, label and group names.
     *
     * @param object to compare the configuration with
     * @return true if configuration is equal otherwise false
     */
    public boolean hasSameConfig(Object o) {
        if (o == null) {
            return false;
        }
        if (o instanceof OpenHabInterconnectBindingRemoteItem) {
            OpenHabInterconnectBindingRemoteItem item = (OpenHabInterconnectBindingRemoteItem) o;
            if (getType().contentEquals(item.getType()) && ((getLabel() == null && item.getLabel() == null)
                    || ((getLabel() != null && item.getLabel() != null)
                            && (getLabel().contentEquals(item.getLabel()))))) {
                if (getGroupNames().equals(item.getGroupNames())) {
                    return true;
                }
            }
        }
        return false;
    }
}
