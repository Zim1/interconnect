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

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.UnsupportedEncodingException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.smarthome.core.items.GenericItem;
import org.eclipse.smarthome.core.items.Item;
import org.eclipse.smarthome.core.items.ItemNotUniqueException;
import org.eclipse.smarthome.core.items.ItemRegistry;
import org.eclipse.smarthome.core.thing.ChannelUID;
import org.eclipse.smarthome.core.thing.Thing;
import org.eclipse.smarthome.core.thing.ThingStatus;
import org.eclipse.smarthome.core.thing.binding.BaseThingHandler;
import org.eclipse.smarthome.core.types.Command;
import org.eclipse.smarthome.core.types.State;
import org.eclipse.smarthome.core.types.TypeParser;
import org.eclipse.smarthome.core.types.UnDefType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The {@link InterconnectHandler} is responsible for handling commands, which are
 * sent to one of the channels.
 *
 * @author Zim - Initial contribution
 */
@NonNullByDefault
public class InterconnectHandler extends BaseThingHandler {

    // map for storing remote items
    private Map<String, OpenHabInterconnectBindingRemoteItem> itemsLocal;
    // list for storing remote items (their names are the keys) which were recognized
    private List<String> usedKeys;
    private JsonParser parser;
    private Gson itemConverter;
    // openhab2's registry for items
    private ItemRegistry itemRegistry;
    private File itemsFile;
    private File sitemapFile;
    // id of this binding/thing instance, given by openhab2
    private String localThingID;

    // mutex
    private Object lock = new Object();

    private final Logger logger = LoggerFactory.getLogger(InterconnectHandler.class);

    private final InterconnectConnections connections = new InterconnectConnections();

    ScheduledFuture<?> refreshJob;

    private final String itemsFileFolder = "/items/";
    private final String sitemapsFileFolder = "/sitemaps/";
    private final String itemsFileEnding = ".items";
    private final String sitemapsFileEnding = ".sitemap";
    // header for generated site map
    private String sitemapStart;

    private List<String> remoteSitemaps;

    private enum ItemSelection {
        DEFAULT,
        SITEMAPS_ALL,
        SITEMAPS_SELECTION
    }

    private ItemSelection selConf;

    @Nullable
    private InterconnectConfiguration config;

    public InterconnectHandler(Thing thing, ItemRegistry itemRegistry) {
        super(thing);
        this.itemRegistry = itemRegistry;
    }

    @Override
    public void initialize() {
        config = getConfigAs(InterconnectConfiguration.class);
        itemsLocal = new ConcurrentHashMap<>();
        usedKeys = new ArrayList<>();
        parser = new JsonParser();
        itemConverter = new Gson();

        logger.info(config.sitemapName);
        logger.info(config.refreshTime);
        logger.info(config.nodeIPAddress);
        logger.info(config.port);
        logger.info(config.systemFolderPath);

        // get Binding UID
        localThingID = thing.getUID().getAsString();
        localThingID = localThingID.replace(':', '_');
        sitemapStart = setTokenData();

        // validate item selection configuration
        if (!validateItSelConf()) {
            logger.error("No valid item selection configuration for binding Interconnect.");
            updateStatus(ThingStatus.UNINITIALIZED);
            return;
        }

        // validate files and paths
        if (!validateThingPathConf(config.systemFolderPath)) {
            logger.error("No valid openhab2 path configured for interconnect binding.");
            updateStatus(ThingStatus.UNINITIALIZED);
            return;
        }

        // config Connection
        if (config.nodeIPAddress != null) {
            connections.setIPAddress(config.nodeIPAddress);
        }
        if (config.port != null) {
            connections.setPort(config.port);
        }

        updateStatus(ThingStatus.ONLINE);

        long refreshTimeIntervall = Long.parseLong(config.refreshTime);
        if (refreshTimeIntervall == 0) {
            refreshTimeIntervall = 60;
        }

        try {
            logger.info(connections.getSpecificItemDataFromNode("interconnect_test_for_sitemap"));
            logger.info(connections.getAllSitemapDatasFromNode());
            logger.info(connections.getSpecificSitemapDataFromNode("test"));
        } catch (Exception e) {
            logger.error(e.getMessage());
        }
        // schedule job for node synchronization with remote
        refreshJob = scheduler.scheduleWithFixedDelay(() -> {
            synchronized (lock) {
                synchronizeLocaleNode();
            }
        }, 20, refreshTimeIntervall, TimeUnit.SECONDS);
    }

    /**
     * Validates the user configuration for the items selection.
     *
     * @return true if a valid configuration has been made, else false
     */
    private boolean validateItSelConf() {
        String conf = this.config.itemSelection;
        if (conf == null) {
            return false;
        }
        if (conf.contains(",")) {
            String[] sitemapNames = conf.split(",");
            if (sitemapNames.length == 0) {
                return false;
            }
            this.remoteSitemaps = new ArrayList<>(Arrays.asList(sitemapNames));
            this.remoteSitemaps.replaceAll(String::trim);
            this.remoteSitemaps.removeIf(name -> name.isEmpty());
            if (this.remoteSitemaps.isEmpty()) {
                return false;
            }
            this.selConf = ItemSelection.SITEMAPS_SELECTION;
            return true;
        }
        switch (conf) {
            case InterconnectBindingConstants.ITEM_SELECTION_DEFAULT:
                this.selConf = ItemSelection.DEFAULT;
                return true;
            case InterconnectBindingConstants.ITEM_SELECTION_SITEMAP_ALL:
                this.selConf = ItemSelection.SITEMAPS_ALL;
                return true;
            // a single sitemap is configured
            default:
                this.selConf = ItemSelection.SITEMAPS_SELECTION;
                this.remoteSitemaps = new ArrayList<>();
                this.remoteSitemaps.add(conf);
        }
        return true;
    }

    /**
     * Validates the path and files for the thing. Creates new folder and files if necessary.
     *
     * TODO: We are currently only checking if the files already exist or if we were able to create the files.
     * It would be important to check, if the path configured by the user is also the real configuration path for the
     * current
     * openhab2 instance.
     *
     * @param path
     * @return true if the items exist or were created else false
     */
    private boolean validateThingPathConf(String path) {
        String thingPath = path + itemsFileFolder;
        String sitemapPath = path + sitemapsFileFolder;
        this.itemsFile = new File(thingPath + File.separator + localThingID + itemsFileEnding);
        try {
            if (this.itemsFile.exists() == false) {
                if (this.itemsFile.createNewFile()) {
                    logger.info("Items file created successully.");
                } else {
                    logger.error("Items file creation failed.");
                    return false;
                }
            }
        } catch (IOException e) {
            logger.error(e.getMessage());
            return false;
        }

        this.sitemapFile = new File(sitemapPath + File.separator + localThingID + sitemapsFileEnding);
        try {
            if (this.sitemapFile.exists() == false) {
                if (this.sitemapFile.createNewFile()) {
                    logger.info("Sitemap file created successully.");
                } else {
                    logger.error("Sitemap file creation failed.");
                    return false;
                }
            }
        } catch (IOException e) {
            logger.error(e.getMessage());
            return false;
        }
        return true;
    }

    /**
     * Synchronizes local node with remote node according to the item selection configuration.
     */
    private void synchronizeLocaleNode() {
        boolean excaptionThrowed = false;
        List<OpenHabInterconnectBindingRemoteItem> remoteItems = null;
        try {
            // check item selection configuration and pull necessary items from remote
            switch (this.selConf) {
                case DEFAULT:
                    remoteItems = getAllItemsFromRemote();
                    break;
                case SITEMAPS_ALL:
                    remoteItems = getItemsFromAllSitemaps();
                    break;
                case SITEMAPS_SELECTION:
                    remoteItems = new ArrayList<>();
                    for (String remoteSitemap : this.remoteSitemaps) {
                        List<OpenHabInterconnectBindingRemoteItem> itemsToAdd = new ArrayList<>();
                        List<OpenHabInterconnectBindingRemoteItem> rIt = getItemsFromSitemap(remoteSitemap);
                        if (rIt == null || rIt.isEmpty()) {
                            logger.warn("No items for sitemap [" + remoteSitemap + "] found.");
                            continue;
                        }
                        boolean add = true;
                        if (!remoteItems.isEmpty()) {
                            for (OpenHabInterconnectBindingRemoteItem it1 : rIt) {
                                for (OpenHabInterconnectBindingRemoteItem it2 : remoteItems) {
                                    if (it1.getRemoteName().contentEquals(it2.getRemoteName())) {
                                        add = false;
                                        break;
                                    }
                                }
                                if (add) {
                                    itemsToAdd.add(it1);
                                } else {
                                    add = true;
                                }
                            }
                            remoteItems.addAll(itemsToAdd);
                        } else {
                            remoteItems.addAll(rIt);
                        }
                    }
                    break;
                default:
                    updateStatus(ThingStatus.UNINITIALIZED);
                    throw new IllegalStateException(
                            "Internal error in binding Interconnect during local synchronization. Setting binding state to UNINITIALIZED.");
            }
            // start synchronization of local node by adding or updating the items
            if (remoteItems != null && !remoteItems.isEmpty()) {
                if (itemsLocal.isEmpty()) {
                    addRemoteItemsToLocalNode(remoteItems);
                } else {
                    computeRemoteData(remoteItems);
                }
                synchronizeLocalStates();
                printRemoteItemsFromRegistry();
            } else {
                logger.info("*** No items on remote node [" + config.nodeIPAddress + "] found. ***");
            }

        } catch (IOException ioException) {
            excaptionThrowed = true;
            logger.error("Communication Error: " + ioException.getMessage());
        } catch (IllegalStateException illelgalStateExcaption) {
            excaptionThrowed = true;
            logger.error("Parsing Error: " + illelgalStateExcaption.getMessage());
        } catch (Exception e) {
            logger.error(
                    "Unexpected error occurred during synchronization. Please check if your remote item and sitemap files have any invalid lines(typos)",
                    e);
            logger.info(
                    "=== Stopping synchronization. Reconfigure or rebind the Interconnect Binding to restart the synchronization. ===");
        } finally {
            if (excaptionThrowed) {
                if (this.getThing().getStatus() != ThingStatus.OFFLINE) {
                    updateStatus(ThingStatus.OFFLINE);
                }
            } else {
                if (this.getThing().getStatus() != ThingStatus.ONLINE) {
                    updateStatus(ThingStatus.ONLINE);
                }
            }
        }
    }

    /**
     * Returns all items from the remote node as a list.
     *
     * @return list of all remote items
     * @throws IOException
     */
    private List<OpenHabInterconnectBindingRemoteItem> getAllItemsFromRemote() throws IOException {
        List<OpenHabInterconnectBindingRemoteItem> items = new ArrayList<>();
        String response = connections.getAllItemsResponsefromNode(null);
        if (response == null) {
            return null;
        }
        JsonArray msg = parser.parse(response).getAsJsonArray();
        if (msg.isJsonNull()) {
            return null;
        }
        for (JsonElement item : msg) {

            // convert json string to item
            OpenHabInterconnectBindingRemoteItem remoteItem = itemConverter.fromJson(item.toString(),
                    OpenHabInterconnectBindingRemoteItem.class);
            remoteItem.setRemoteName(new String(remoteItem.getName()));
            remoteItem.setName(createItemName(remoteItem.getName()));
            remoteItem.setRemoteGroupNames(new ArrayList<>(remoteItem.getGroupNames()));
            remoteItem.setGroupNames(createGroupNames(remoteItem.getRemoteGroupNames()));
            items.add(remoteItem);
        }
        return items;

    }

    /**
     * Returns all items, referenced by *.sitemap files, from the remote node as a list.
     *
     * @return list of remote items or null
     * @throws IOException
     */
    private List<OpenHabInterconnectBindingRemoteItem> getItemsFromAllSitemaps() throws IOException {
        List<OpenHabInterconnectBindingRemoteItem> items = new ArrayList<>();
        String response = connections.getAllSitemapDatasFromNode();
        if (response == null) {
            return null;
        }
        JsonArray msg = parser.parse(response).getAsJsonArray();
        if (msg.isJsonNull()) {
            return null;
        }
        for (JsonElement item : msg) {

            JsonObject jObject = item.getAsJsonObject();
            items.addAll(getItemsFromSitemap(jObject.get(InterconnectBindingConstants.OPENHAB_NAME).getAsString()));
        }
        return items;
    }

    /**
     * Returns all items, referenced by the specific *.sitemap file, from the remote node as a list.
     *
     * @param aSitemapname -- name of the specific *.sitemap file
     * @return list of remote items
     * @throws IOException
     */
    private @Nullable List<OpenHabInterconnectBindingRemoteItem> getItemsFromSitemap(String aSitemapname)
            throws IOException {
        String response = connections.getSpecificSitemapDataFromNode(aSitemapname);
        if (response == null) {
            return null;
        }
        JsonObject jObj = parser.parse(response).getAsJsonObject();
        if (!jObj.isJsonObject()) {
            return null;
        }
        JsonElement homepage = jObj.get(InterconnectBindingConstants.OPENHAB_SITEMAP_HOMEPAGE);
        JsonArray sitemapWidgets = homepage.getAsJsonObject()
                .getAsJsonArray(InterconnectBindingConstants.OPENHAB_WIDGETS);
        List<OpenHabInterconnectBindingRemoteItem> items = new ArrayList<>();
        int itemCount = 0;
        for (JsonElement widget : sitemapWidgets) {
            itemCount += getItemsFromWidget(items, widget.getAsJsonObject());
        }
        logger.info("===================");
        logger.info("=== Items collected from sitemap [" + aSitemapname + "]:" + itemCount + " ===");
        items.stream().forEach(item -> logger.info("=== Found [" + item.asItemString(this.localThingID) + "] ==="));
        logger.info("===================");
        return items;
    }

    /**
     * Simply adds all remote items as local items in the item registry of this openhab2 instance.
     *
     * @param remoteItems
     */
    private void addRemoteItemsToLocalNode(List<OpenHabInterconnectBindingRemoteItem> remoteItems) {
        try (Writer writer = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(this.itemsFile, false), "UTF-8"))) {

            for (OpenHabInterconnectBindingRemoteItem remoteItem : remoteItems) {
                itemsLocal.put(remoteItem.getName(), remoteItem);
                String uid = thing.getUID().getAsString();
                writer.write(remoteItem.asItemString(uid));
                logger.info(remoteItem.getName() + " : " + remoteItem.asItemString(uid));
                logger.info("-----------");
            }
            updateSiteMap();

        } catch (Exception e) {
            logger.error("Error while writing items tp item file:", e);
        }
    }

    /**
     * Adds, removes or updates the local representations of the remote items according to the remote node. The
     * generated *.items and *.sitemap files will be updated as well if necessary.
     *
     * @param remoteItems
     * @throws Exception
     */
    private void computeRemoteData(List<OpenHabInterconnectBindingRemoteItem> remoteItems) throws Exception {
        if (hasDuplicate(remoteItems)) {
            logger.warn("=== Synchronizing of local node aborted! ===");
            throw new Exception("Duplicate Items detected");
        }
        boolean sitemapUpdateNeeded = false;
        OpenHabInterconnectBindingRemoteItem localItem;
        // stores items that need to be edited in the items file
        // applies if the tag of an item has changed or an item with the same item name, but with another type has been
        // added, while the old item with the same name has been deleted
        List<OpenHabInterconnectBindingRemoteItem> changedItems = new ArrayList<>();

        try (Writer writer = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(this.itemsFile, true), "UTF-8"))) {
            for (OpenHabInterconnectBindingRemoteItem remoteItem : remoteItems) {
                if (itemsLocal.containsKey(remoteItem.getName())) {
                    localItem = itemsLocal.get(remoteItem.getName());
                    // check if item has the same type and same label
                    if (localItem.hasSameConfig(remoteItem)) {
                        // check if local and remote items have equal state
                        if (!localItem.getState().contentEquals(remoteItem.getState())) {
                            localItem.setState(remoteItem.getState());
                            itemsLocal.put(localItem.getName(), localItem);
                        }
                        // TODO or just always put remote item in map ??? same functionality but would trigger observers
                        // even when we do not have any changes in remote item => worse performance ???
                        // itemsLocal.put(localItem.getName(), remoteItem);

                        usedKeys.add(remoteItem.getName());
                    } else {
                        changedItems.add(remoteItem);
                    }

                } else {
                    logger.info("---New Item added---");
                    itemsLocal.put(remoteItem.getName(), remoteItem);
                    // create item in items file
                    writer.write(remoteItem.asItemString(thing.getUID().getAsString()));
                    sitemapUpdateNeeded = true;
                    usedKeys.add(remoteItem.getName());
                }
                // usedKeys.add(remoteItem.getName());
                logger.info(remoteItem.getLabel() + ": " + remoteItem.getType() + " = " + remoteItem.getState());
                logger.info("-----------");
            }
            // remove deleted items from items file
            removeDeletedItems();
            // append changed items
            for (OpenHabInterconnectBindingRemoteItem remoteItem : changedItems) {
                itemsLocal.put(remoteItem.getName(), remoteItem);
                writer.write(remoteItem.asItemString(thing.getUID().getAsString()));
                sitemapUpdateNeeded = true;
            }
            // add changed items to items file
            // expensive task only needed if new item has been added to items file
            if (sitemapUpdateNeeded) {
                updateSiteMap();
            }

        } catch (Exception e) {
            logger.error("Error while writing items to item file:", e);
        }
        usedKeys.clear();
        logger.info("===================");
    }

    /**
     * Synchronizes the item states in openhab2's item registry with the remote item states.
     *
     * @return number of items synchronized
     */
    private int synchronizeLocalStates() {
        Set<String> keys = this.itemsLocal.keySet();
        int i = 0;
        if (keys.isEmpty()) {
            return i;
        }
        for (String key : keys) {
            Item item;
            // we check if the file observer has already added the item to the item registry
            int maxWait = 0;
            while ((item = this.itemRegistry.get(key)) == null) {
                try {
                    if (maxWait++ >= 10) {
                        logger.error("Local item was not added to registry in time.");
                        return i;
                    }
                    logger.info("=== Waiting for file observer to add the item to registry... ===");
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    logger.warn("???", e);
                }
            }
            if (item instanceof GenericItem) {
                GenericItem gItem = (GenericItem) item;
                String localState = this.itemsLocal.get(key).getState();
                if (localState.contentEquals("NULL")) {
                    gItem.setState(UnDefType.NULL.as(State.class));
                } else {
                    gItem.setState(createState(item, this.itemsLocal.get(key).getState()));
                }
                // this.itemRegistry.update(item);
                ++i;
            }

        }
        return i;
    }

    @Override
    public void dispose() {
        if (refreshJob != null) {
            refreshJob.cancel(true);
        }
        if (this.itemsFile != null) {
            // delete Files
            if (this.itemsFile.delete()) {
                logger.info("items File deleted successully");
            } else {
                logger.warn("items File deletion failed");
            }
        }
        if (this.sitemapFile != null) {
            if (this.sitemapFile.delete()) {
                logger.info("sitemap File deleted successully");
            } else {
                logger.warn("sitemap File deletion failed");
            }
        }

    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        synchronized (lock) {
            if (!command.toString().contentEquals("REFRESH")) {
                synchronizeRemote(command);
                synchronizeLocaleNode();
            }
        }
    }

    /**
     * Tries to set the state of the remote item according to the command.
     *
     * Known issue:
     * This method might fail, if the user is changing the state of the same item too fast.
     * We assume, that in the moment this method has been called, the item registry of openhab2 already has the new item
     * state updated. But this is not always the case. If this happens this method will throw an exception.
     *
     * @param command
     * @return status code
     */
    private String synchronizeRemote(Command command) {
        // compare the states of all items inside our localItems map with the same items stored inside the item
        // registry.
        for (int tryInterations = 0; tryInterations < 3; tryInterations++) {
            Set<String> keys = itemsLocal.keySet();
            for (String key : keys) {
                Item item = this.itemRegistry.get(key);

                OpenHabInterconnectBindingRemoteItem it = this.itemsLocal.get(key);
                String st1 = item.getState().toString();
                String st2 = it.getState();

                logger.info("key: " + key);
                logger.info("Itemname: " + item.getName());
                logger.info("zustand 1: " + st1);
                logger.info("zustand 2: " + st2);
                // we found an remote item in the item registry with the same item name, which has a different state
                // ergo we know that the user has changed the items state via the GUI
                if (!st1.contentEquals(st2)) {
                    it.setState(st2);
                    this.itemsLocal.put(key, it);
                    try {
                        return connections.setItemValueRemoteNode(it.getRemoteName(), st1);
                    } catch (IOException e) {
                        logger.error(e.getMessage(), e);
                    }

                }
            }

            logger.warn("interation " + String.valueOf(tryInterations));

            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }

        }
        throw new IllegalStateException("A new state change " + command.toString()
                + " has been recognized, but no item could be found to apply the state to.");

    }

    /**
     * Deletes all references of deleted items in the generated *.sitemap and *.items file.
     */
    private void removeDeletedItems() {
        Iterator<String> it = itemsLocal.keySet().iterator();
        List<String> keysToDeleteFromFile = new ArrayList<>();
        while (it.hasNext()) {
            String key = it.next();
            if (!usedKeys.contains(key)) {
                itemsLocal.remove(key);
                keysToDeleteFromFile.add(key);

            }
        }
        if (!keysToDeleteFromFile.isEmpty()) {
            try {

                OpenHabInterconnectBindingFileUtil.deleteItemsFromFile(keysToDeleteFromFile, this.itemsFile);
                OpenHabInterconnectBindingFileUtil.deleteItemsFromFile(keysToDeleteFromFile, this.sitemapFile);
            } catch (IOException e) {
                logger.error("Error while deleting items from file:", e);
            }

        }
    }

    /**
     * Replaces the tokens of the site map header string with the releavant data.
     *
     * @return the site map header
     */
    private String setTokenData() {
        String basicData = InterconnectBindingConstants.SITE_MAP_START;
        basicData = basicData.replace("%" + InterconnectBindingConstants.SITE_MAP_ID_TOKEN + "%", this.localThingID);
        return basicData.replace("%" + InterconnectBindingConstants.SITE_MAP_NAME_TOKEN + "%", config.sitemapName);
    }

    /**
     * Generates the local item name of the remote item. The local item name will be a combination of the binding/thing
     * id and the remote item name to ensure a unique item name.
     *
     * @param remoteName
     * @return local item name
     */
    private String createItemName(String remoteName) {
        StringBuilder builder = new StringBuilder();
        builder.append(Character.toUpperCase(this.localThingID.charAt(0)));
        builder.append(this.localThingID.substring(1));
        builder.append("_");
        builder.append(remoteName);
        return builder.toString();
    }

    /**
     * Generates the local group names for the remote groups. The local group names will be a combination of the
     * binding/thing id and the remote group names to ensure a unique group name.
     *
     * @param remoteGroupNames
     * @return
     */
    protected List<String> createGroupNames(List<String> remoteGroupNames) {
        List<String> localGroupNames = new ArrayList<>();
        if (remoteGroupNames.isEmpty()) {
            return localGroupNames;
        }
        StringBuilder builder = new StringBuilder();
        for (String groupName : remoteGroupNames) {
            builder.append(Character.toUpperCase(this.localThingID.charAt(0)));
            builder.append(this.localThingID.substring(1));
            builder.append("_");
            builder.append(groupName);
            localGroupNames.add(builder.toString());
            builder.setLength(0);
        }
        return localGroupNames;
    }

    // only for debug
    private void printRemoteItemsFromRegistry() {
        Set<String> keys = this.itemsLocal.keySet();
        if (keys.isEmpty()) {
            return;
        }
        for (String key : keys) {
            Item item = this.itemRegistry.get(key);
            logger.info(item == null ? "Item with key " + key + " missing" : item.toString());
        }
    }

    /**
     * Generic creation of an item state according to the item and the item value.
     *
     * @param item
     * @param value
     * @return the state for the item
     */
    protected @Nullable State createState(Item item, String value) {
        List<Class<? extends State>> cs = item.getAcceptedDataTypes();
        for (Class<?> c : cs) {
            try {
                State st = (State) TypeParser.parseType(c.getSimpleName(), value);
                return st;
            } catch (Exception e) {
                continue;
            }
        }
        return null;
    }

    /**
     * Rewrites the generated *.sitemap file with the updated items.
     *
     * @return true if for success else false
     * @throws UnsupportedEncodingException
     * @throws FileNotFoundException
     * @throws IOException
     * @throws InterruptedException
     */
    private boolean updateSiteMap()
            throws UnsupportedEncodingException, FileNotFoundException, IOException, InterruptedException {
        OpenHabInterconnectBindingRemoteItem[] items = this.itemsLocal.values()
                .toArray(new OpenHabInterconnectBindingRemoteItem[0]);
        if (items.length > 0) {
            List<OpenHabInterconnectBindingRemoteItem> list = new ArrayList<>(Arrays.asList(items));
            Collections.sort(list);
            writeItemsToSitemap(list);
            return true;
        }
        return false;

    }

    /**
     * Writes all remote items to the generated *.sitemap.
     *
     * @param items
     * @return number of items written to file
     * @throws UnsupportedEncodingException
     * @throws FileNotFoundException
     * @throws IOException
     * @throws InterruptedException
     */
    private int writeItemsToSitemap(Iterable<OpenHabInterconnectBindingRemoteItem> items)
            throws UnsupportedEncodingException, FileNotFoundException, IOException, InterruptedException {
        Path sitemapPtah = Paths.get(this.sitemapFile.getPath());
        Path tmpFile = Paths.get(sitemapPtah.getParent().toString() + "/" + this.localThingID + "sitemap.tmp");
        File file = new File(tmpFile.toString());
        if (!file.exists()) {
            file.createNewFile();
        }
        int i = 0;
        try (Writer writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file, false), "UTF-8"))) {
            writer.write(this.sitemapStart);
            for (OpenHabInterconnectBindingRemoteItem item : items) {
                writer.write(item.asSitemapString());
                ++i;
            }
            writer.write(InterconnectBindingConstants.SITE_MAP_END);
        } catch (IOException e) {
            if (file.exists()) {
                file.delete();
            }
            logger.error("Unable to update sitemap file of interconnect binding!", e);
            return 0;
        }
        Files.delete(sitemapPtah);
        Thread.sleep(1500);
        Files.move(tmpFile, sitemapPtah, StandardCopyOption.REPLACE_EXISTING);
        return i;

    }

    // private int writeItemsToSitemap(Iterable<OpenHabInterconnectBindingItem> items)
    // throws UnsupportedEncodingException, FileNotFoundException, IOException {
    // try (Writer writer = new BufferedWriter(
    // new OutputStreamWriter(new FileOutputStream(this.sitemapFile, false), "UTF-8"))) {
    // // Files.delete(Paths.get(this.sitemapFile.getPath()));
    // // validateThingPathConf(this.config.SystemFolderPath);
    //
    // writer.write(this.sitemapStart);
    // int i = 0;
    // for (OpenHabInterconnectBindingItem item : items) {
    // writer.write(item.asSitemapString());
    // ++i;
    // }
    // writer.write(OpenHabInterconnectBindingConstants.SITE_MAP_END);
    // return i;
    // }
    // }

    /**
     * Checks if the remote node has non unique items configured.
     *
     * @param remoteItems
     * @return true if non unique items have been detected, else false
     */
    private boolean hasDuplicate(List<OpenHabInterconnectBindingRemoteItem> remoteItems) {
        Set<String> set = new HashSet<>();
        for (OpenHabInterconnectBindingRemoteItem remoteItem : remoteItems) {
            if (!set.add(remoteItem.getName())) {
                String err = "At least one duplicate item name on remote detected:" + System.lineSeparator()
                        + remoteItem.getName() + System.lineSeparator()
                        + "Remove items with duplicate names from remote openhab node.";
                logger.error(err,
                        new ItemNotUniqueException(err, Arrays.asList(this.itemRegistry.get(remoteItem.getName()))));
                return true;
            }
        }
        return false;
    }

    /**
     * Recursively parses for items to the JSON meta data of a site map and returns them as a list of items.
     *
     * @param items -- list of items collected
     * @param jObject -- JSON meta data of a site map
     * @return -- list of items collected
     */
    private int getItemsFromWidget(List<OpenHabInterconnectBindingRemoteItem> items, JsonObject jObject) {
        int itemsCollected = 0;
        JsonElement itemData;
        // check if we find an item
        if ((itemData = jObject.get(InterconnectBindingConstants.OPENHAB_ITEM)) != null) {
            JsonObject itemObject = itemData.getAsJsonObject();
            OpenHabInterconnectBindingRemoteItem remoteItem = itemConverter.fromJson(itemData,
                    OpenHabInterconnectBindingRemoteItem.class);
            remoteItem.setRemoteName(new String(remoteItem.getName()));
            remoteItem.setName(createItemName(remoteItem.getName()));
            if (!items.stream().anyMatch(item -> item.getRemoteName().contentEquals(remoteItem.getRemoteName()))) {
                remoteItem.setRemoteGroupNames(new ArrayList<>(remoteItem.getGroupNames()));
                remoteItem.setGroupNames(createGroupNames(remoteItem.getRemoteGroupNames()));
                items.add(remoteItem);
                ++itemsCollected;
                if (InterconnectBindingConstants.CHANNEL_GROUP
                        .contentEquals(itemObject.get(InterconnectBindingConstants.OPENHAB_TYPE).getAsString())) {
                    // set the data of the linked group page as current data
                    JsonElement linkedPage = jObject.get(InterconnectBindingConstants.CHANNEL_GROUP_PAGE);
                    // group is inside a sitemap but no items are attached to this group --> no items to collect
                    if (linkedPage == null) {
                        return itemsCollected;
                    }
                    jObject = linkedPage.getAsJsonObject();
                }
                // if the item is a normal item like a switch
                else {
                    return itemsCollected;
                }
            }

        }
        // we need to step into the nested widgets
        JsonArray sitemapWidgets = jObject.getAsJsonArray(InterconnectBindingConstants.OPENHAB_WIDGETS);
        if (sitemapWidgets == null || sitemapWidgets.isJsonNull() || sitemapWidgets.size() == 0) {
            logger.warn(
                    "!!!!!!!!!!!!-- Widget or Group detected that does not have nested widgets or does reference an item. ---!!!!!!!!!!!!");
            return itemsCollected;
        }
        for (JsonElement widget : sitemapWidgets) {
            itemsCollected += getItemsFromWidget(items, widget.getAsJsonObject());
        }
        return itemsCollected;
    }
}
