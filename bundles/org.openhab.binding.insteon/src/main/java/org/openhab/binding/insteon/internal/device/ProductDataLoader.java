/**
 * Copyright (c) 2010-2020 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.insteon.internal.device;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * Reads the device products from an xml file.
 *
 * @author Jeremy Setton - Initial contribution
 */
@NonNullByDefault
@SuppressWarnings("null")
public class ProductDataLoader {
    private static final Logger logger = LoggerFactory.getLogger(ProductDataLoader.class);
    private Map<String, ProductData> products = new HashMap<>();
    private @Nullable static ProductDataLoader productDataLoader = null;

    /**
     * Finds the product data for a given dev/sub category or product key
     *
     * @param  devCat     device category to match
     * @param  subCat     sub category to match
     * @param  productKey product key to match
     * @return            product data matching provided parameters
     */
    public @Nullable ProductData getProductData(@Nullable String devCat, @Nullable String subCat,
            @Nullable String productKey) {
        String productId = getProductId(devCat, subCat);
        if (productId == null && productKey == null) {
            return null;
        }

        ProductData productData = new ProductData();
        if (productId != null && products.containsKey(productId)) {
            productData = products.get(productId).clone(); // use product id if available
        } else if (productKey != null && products.containsKey(productKey)) {
            productData = products.get(productKey).clone(); // use product key if available
        } else if (devCat != null && products.containsKey(devCat)) {
            productData = products.get(devCat).clone(); // use device category only as fallback if available
        }

        boolean mismatch = false;
        if (devCat != null && !devCat.equals(productData.getDeviceCategory())) {
            productData.setDeviceCategory(devCat);
            mismatch = true;
        }
        if (subCat != null && !subCat.equals(productData.getSubCategory())) {
            productData.setSubCategory(subCat);
            mismatch = true;
        }
        if (productKey != null && !productKey.equals(productData.getProductKey())) {
            productData.setProductKey(productKey);
            mismatch = true;
        }
        if (mismatch) {
            logger.warn("product mismatch for devCat:{} subCat:{} productKey:{} in device products xml file",
                    devCat, subCat, productKey);
        }

        return productData;
    }

    /**
     * Finds the product data for a given dev/sub category
     *
     * @param  devCat device category to match
     * @param  subCat sub category to match
     * @return        product data matching provided parameters
     */
    public @Nullable ProductData getProductData(@Nullable String devCat, @Nullable String subCat) {
        return getProductData(devCat, subCat, null);
    }

    /**
     * Returns product id based on dev/sub category
     *
     * @param  devCat device category to use
     * @param  subCat sub category to use
     * @return        product id
     */
    public @Nullable String getProductId(@Nullable String devCat, @Nullable String subCat) {
        return devCat == null ? null : subCat == null ? devCat : devCat + subCat.substring(2);
    }

    /**
     * Returns known products
     *
     * @return currently known products
     */
    public Map<String, ProductData> getProducts() {
        return products;
    }

    /**
     * Reads the device products from input stream and stores them in memory for
     * later access.
     *
     * @param stream the input stream from which to read
     * @throws ParserConfigurationException
     * @throws SAXException
     * @throws IOException
     */
    public void loadDeviceProductsXML(InputStream stream) throws ParserConfigurationException, SAXException, IOException {
        DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
        DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
        Document doc = dBuilder.parse(stream);
        doc.getDocumentElement().normalize();
        Node root = doc.getDocumentElement();
        NodeList nodes = root.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE && node.getNodeName().equals("product")) {
                processProduct((Element) node);
            }
        }
    }

    /**
     * Reads the device products from file and stores them in memory for later access.
     *
     * @param filename the name of the file to read from
     * @throws ParserConfigurationException
     * @throws SAXException
     * @throws IOException
     */
    public void loadDeviceProductsXML(String filename) throws ParserConfigurationException, SAXException, IOException {
        File file = new File(filename);
        InputStream in = new FileInputStream(file);
        loadDeviceProductsXML(in);
    }

    /**
     * Process product node
     *
     * @param e name of the element to process
     * @throws SAXException
     */
    private void processProduct(Element e) throws SAXException {
        String devCat = e.hasAttribute("devCat") ? e.getAttribute("devCat") : null;
        String subCat = e.hasAttribute("subCat") ? e.getAttribute("subCat") : null;
        String productKey = e.hasAttribute("productKey") ? e.getAttribute("productKey") : null;
        if (devCat == null) {
            throw new SAXException("product data in device_products file has no device category!");
        }

        String productId = getProductId(devCat, subCat);
        if (products.containsKey(productId)) {
            logger.warn("overwriting previous definition of product {}", products.get(productId));
        }

        ProductData productData = ProductData.makeInsteonProduct(devCat, subCat, productKey);
        NodeList nodes = e.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            Element subElement = (Element) node;
            if (subElement.getNodeName().equals("description")) {
                productData.setDescription(subElement.getTextContent());
            } else if (subElement.getNodeName().equals("model")) {
                productData.setModel(subElement.getTextContent());
            } else if (subElement.getNodeName().equals("device-type")) {
                processDeviceType(productData, subElement);
            }
        }
        products.put(productId, productData);
        if (productKey != null) {
            products.put(productKey, productData);
        }
    }

    /**
     * Process product device type element
     *
     * @param  productData product data to update
     * @param  e           name of the element to process
     * @throws SAXException
     */
    private void processDeviceType(ProductData productData, Element e) throws SAXException {
        String name = e.getTextContent();
        if (name == null) {
            return; // undefined device type
        }
        DeviceType deviceType = DeviceTypeLoader.instance().getDeviceType(name);
        if (deviceType == null) {
            logger.warn("unknown device type {} for devCat:{} subCat:{} productKey:{} in device products xml file",
                    name, productData.getDeviceCategory(), productData.getSubCategory(), productData.getProductKey());
        } else {
            productData.setDeviceType(deviceType);
        }
    }

    /**
     * Helper function for debugging
     */
    private void logProducts() {
        for (Map.Entry<String, ProductData> product : getProducts().entrySet()) {
            String msg = String.format("%s->", product.getKey()) + product.getValue();
            logger.debug("{}", msg);
        }
    }

    /**
     * Singleton instance function, creates ProductDataLoader
     *
     * @return ProductDataLoader singleton reference
     */
    @Nullable
    public static synchronized ProductDataLoader instance() {
        if (productDataLoader == null) {
            productDataLoader = new ProductDataLoader();
            InputStream input = ProductDataLoader.class.getResourceAsStream("/device_products.xml");
            try {
                productDataLoader.loadDeviceProductsXML(input);
            } catch (ParserConfigurationException e) {
                logger.warn("parser config error when reading device products xml file: ", e);
            } catch (SAXException e) {
                logger.warn("SAX exception when reading device products xml file: ", e);
            } catch (IOException e) {
                logger.warn("I/O exception when reading device products xml file: ", e);
            }
            if (logger.isDebugEnabled()) {
                logger.debug("loaded {} products: ", productDataLoader.getProducts().size());
                productDataLoader.logProducts();
            }
        }
        return productDataLoader;
    }
}
