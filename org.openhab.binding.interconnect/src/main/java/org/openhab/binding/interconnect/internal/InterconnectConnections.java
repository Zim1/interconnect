package org.openhab.binding.interconnect.internal;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.Properties;

import org.eclipse.smarthome.io.net.http.HttpUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implementation for Yahoo Weather url connection.
 *
 * @author Christoph Weitkamp - Changed use of caching utils to ESH ExpiringCacheMap
 *
 */
public class InterconnectConnections {

    private final Logger logger = LoggerFactory.getLogger(InterconnectConnections.class);

    private static final String WEBSERVICE_URL_BEGIN = "http://";
    private static final String WEBSERVICE_ITEMS_GET_URL_END = "/rest/items?recursive=false";
    private static final String WEBSERVICE_ITEMS_POST_GET_URL_END = "/rest/items/";
    private static final String WEBSERVICE_SITEMAP_ALL_GET_URL_END = "/rest/sitemaps";
    private static final String WEBSERVICE_SITEMAP_SINGLE_GET_URL_END = "/rest/sitemaps/";
    private static final String WEBSERVICE_SITEMAP_GET_URL_END = "?jsoncallback=callback";

    private static final String METHOD_GET = "GET";
    private static final String METHOD_POST = "POST";

    private static final int TIMEOUT = 1 * 1000; // 10s

    private static String ipAddress = "localhost";// 192.168.178.23:8080
    private static String port = "8080";

    public void setIPAddress(String ip) {
        ipAddress = ip;
    }

    public void setPort(String localport) {
        port = localport;
    }

    /**
     * Returns a string in JSON format which contains the data of all site maps, which exist on the
     * remote node.
     *
     * @return JSOn string
     * @throws IOException
     */
    public String getAllSitemapDatasFromNode() throws IOException {
        Properties reqProperties = new Properties();
        try {
            reqProperties.put("Accept", "application/json");

            return HttpUtil.executeUrl(METHOD_GET,
                    WEBSERVICE_URL_BEGIN + ipAddress + ":" + port + WEBSERVICE_SITEMAP_ALL_GET_URL_END, reqProperties,
                    null, null, TIMEOUT);
        } catch (IOException e) {
            // logger.warn("Communication error : {}", e.getMessage());
            throw e;
        }
    }

    /**
     * Returns a string in JSON format which contains the data of all items, which are referenced in the specific site
     * map on the remote node.
     *
     * @param sitemapName -- the name of the *.sitemap file on the remote node
     * @return JSOn string
     * @throws IOException
     */
    public String getSpecificSitemapDataFromNode(String sitemapName) throws IOException {
        Properties reqProperties = new Properties();
        try {
            reqProperties.put("Accept", "application/json");

            return HttpUtil.executeUrl(METHOD_GET, WEBSERVICE_URL_BEGIN + ipAddress + ":" + port
                    + WEBSERVICE_SITEMAP_SINGLE_GET_URL_END + sitemapName + WEBSERVICE_SITEMAP_GET_URL_END,
                    reqProperties, null, null, TIMEOUT);
        } catch (IOException e) {
            // logger.warn("Communication error : {}", e.getMessage());
            throw e;
        }
    }

    /**
     * Returns a string in JSON format which contains the data of all items on the remote node.
     *
     * @param query -- use null the query all items
     * @return JSOn string
     * @throws IOException
     */
    public String getAllItemsResponsefromNode(String query) throws IOException {
        Properties reqProperties = new Properties();
        try {
            reqProperties.put("Accept", "application/json");

            return HttpUtil.executeUrl(METHOD_GET,
                    WEBSERVICE_URL_BEGIN + ipAddress + ":" + port + WEBSERVICE_ITEMS_GET_URL_END, reqProperties, null,
                    null, TIMEOUT);
        } catch (IOException e) {
            // logger.warn("Communication error : {}", e.getMessage());
            throw e;
        }
    }

    /**
     * Returns a string in JSON format which contains the data of the specific item on the remote node.
     *
     * @param itemName -- name of the item
     * @return JSOn string
     * @throws IOException
     */
    public String getSpecificItemDataFromNode(String itemName) throws IOException {
        Properties reqProperties = new Properties();
        try {
            reqProperties.put("Accept", "application/json");

            return HttpUtil.executeUrl(METHOD_GET,
                    WEBSERVICE_URL_BEGIN + ipAddress + ":" + port + WEBSERVICE_ITEMS_POST_GET_URL_END + itemName,
                    reqProperties, null, null, TIMEOUT);
        } catch (IOException e) {
            // logger.warn("Communication error : {}", e.getMessage());
            throw e;
        }
    }

    /**
     * Tries to set the item state for the specific item on the remote node.
     *
     * @param itemName -- name of the item
     * @param value -- new value for the state of the item
     * @return status string
     * @throws IOException
     */
    public String setItemValueRemoteNode(String itemName, String value) throws IOException {
        Properties reqProperties = new Properties();
        try {
            reqProperties.put("Accept", "application/json");
            InputStream urlContent = new ByteArrayInputStream(value.getBytes(Charset.forName("UTF-8")));

            String returnFormExecution = null;

            int attempts;
            for (attempts = 0; attempts < 3; attempts++) {
                returnFormExecution = HttpUtil.executeUrl(METHOD_POST,
                        WEBSERVICE_URL_BEGIN + ipAddress + ":" + port + WEBSERVICE_ITEMS_POST_GET_URL_END + itemName,
                        reqProperties, urlContent, "text/plain", TIMEOUT);

                if (returnFormExecution != null) {
                    break;
                }
            }
            if (attempts != 3) {
                logger.info(returnFormExecution);
                return returnFormExecution;
            } else {
                logger.info("Http request was not executed successfully");
                return "Http request was not executed successfully";
            }
        } catch (IOException e) {
            // logger.warn("Communication error : {}", e.getMessage());
            throw e;
        }
    }
}
