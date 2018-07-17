/**
 * Copyright (c) 2014,2018 by the respective copyright holders.
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.interconnect.internal;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.smarthome.core.thing.ThingTypeUID;

/**
 * The {@link InterconnectBindingConstants} class defines common constants, which are
 * used across the whole binding.
 *
 * @author Zim - Initial contribution
 */
@NonNullByDefault
public class InterconnectBindingConstants {

    private static final String BINDING_ID = "interconnect";

    public static final String THING_TYPE_ID = "Knoten";

    // List of all Thing Type UIDs
    public static final ThingTypeUID THING_TYPE_SAMPLE = new ThingTypeUID(BINDING_ID, "Knoten");

    // List of all Channel ids -- see thing-types.xml
    public static final String CHANNEL_SWITCH = "Switch";
    public static final String CHANNEL_NUMBER = "Number";
    public static final String CHANNEL_GROUP = "Group";
    public static final String CHANNEL_GROUP_PAGE = "linkedPage";

    // Thing and item constants
    public static final String OPENHAB_NAME = "name";
    public static final String OPENHAB_LABEL = "label";
    public static final String OPENHAB_LINK = "link";
    public static final String OPENHAB_STATE = "state";
    public static final String OPENHAB_TYPE = "type";
    public static final String OPENHAB_TAGS = "tags";
    public static final String OPENHAB_GROUP_NAMES = "groupNames";
    public static final String OPENHAB_SITEMAP_HOMEPAGE = "homepage";
    public static final String OPENHAB_WIDGETS = "widgets";
    public static final String OPENHAB_ITEM = "item";

    public static final String SITE_MAP_START = "sitemap %ID% label=\"%NAME%\"" + System.lineSeparator() + "{"
            + System.lineSeparator() + "\tFrame label=\"Interconnect\"{" + System.lineSeparator();
    public static final String SITE_MAP_END = "\t}" + System.lineSeparator() + "}";
    public static final String SITE_MAP_ID_TOKEN = "ID";
    public static final String SITE_MAP_NAME_TOKEN = "NAME";

    public static final String ITEM_SELECTION_DEFAULT = "0";
    public static final String ITEM_SELECTION_SITEMAP_ALL = "1";

    // much space, wow
    public static final String SPACE = "     ";
}
