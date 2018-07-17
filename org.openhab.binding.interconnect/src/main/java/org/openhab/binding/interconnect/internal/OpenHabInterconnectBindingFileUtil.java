package org.openhab.binding.interconnect.internal;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

public class OpenHabInterconnectBindingFileUtil {

    /**
     * Deletes all items in file, which are referenced by itemNames.
     * TODO reduce RAM usage
     *
     * @param itemNames -- list of names of items to delete
     * @param file -- file object in which the items are configured
     * @return number of deleted items
     * @throws IOException
     */
    public static int deleteItemsFromFile(List<String> itemNames, File file) throws IOException {
        List<String> lines = Files.readAllLines(Paths.get(file.getPath()), Charset.forName("UTF-8"));
        int linesDeleted = 0;
        for (int i = 0; i < lines.size(); i++) {
            for (String name : itemNames) {
                if (containsItemName(lines.get(i), name)) {
                    lines.remove(i);
                    ++linesDeleted;
                    break;
                }
            }
        }
        Files.write(Paths.get(file.getPath()), lines, Charset.forName("UTF-8"));
        return linesDeleted;
    }

    /**
     * Checks if the itemMetaData String contains the itemName for the "item=" property.
     *
     * @param itemMetaData -- the meta data for an item (syntax assumed as in *.item files)
     * @param itemName -- the item name to search for
     * @return true if the meta data do contain the specified item name
     */
    private static boolean containsItemName(String itemMetaData, String itemName) {
        itemMetaData = itemMetaData.trim();
        itemMetaData = itemMetaData.replaceAll(InterconnectBindingConstants.SPACE, " ");
        String[] metaData = itemMetaData.split(" ");
        if (metaData.length > 2) {
            if (metaData[1].contentEquals(itemName) || metaData[1].contentEquals("item=" + itemName)) {
                return true;
            }
        }
        return false;
    }
}
