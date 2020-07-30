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
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.insteon.internal.device.DeviceType.FeatureEntry;
import org.openhab.binding.insteon.internal.device.DeviceType.FeatureGroup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * Reads the device types from an xml file.
 *
 * @author Daniel Pfrommer - Initial contribution
 * @author Bernd Pfrommer - openHAB 1 insteonplm binding
 * @author Rob Nielsen - Port to openHAB 2 insteon binding
 * @author Jeremy Setton - Improvement to openHAB 2 insteon binding
 */
@NonNullByDefault
@SuppressWarnings("null")
public class DeviceTypeLoader {
    private static final Logger logger = LoggerFactory.getLogger(DeviceTypeLoader.class);
    private Map<String, DeviceType> deviceTypes = new HashMap<>();
    private @Nullable static DeviceTypeLoader deviceTypeLoader = null;

    private DeviceTypeLoader() {
    } // private so nobody can call it

    /**
     * Finds the device type for a given name
     *
     * @param name device type name to search for
     * @return the device type, or null if not found
     */
    public @Nullable DeviceType getDeviceType(String name) {
        return deviceTypes.get(name);
    }

    /**
     * Must call loadDeviceTypesXML() before calling this function!
     *
     * @return currently known device types
     */
    public Map<String, DeviceType> getDeviceTypes() {
        return (deviceTypes);
    }

    /**
     * Reads the device types from input stream and stores them in memory for
     * later access.
     *
     * @param is the input stream from which to read
     */
    public void loadDeviceTypesXML(InputStream in) throws ParserConfigurationException, SAXException, IOException {
        DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
        DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
        Document doc = dBuilder.parse(in);
        doc.getDocumentElement().normalize();
        Node root = doc.getDocumentElement();
        NodeList nodes = root.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE && node.getNodeName().equals("device-type")) {
                processDeviceType((Element) node);
            }
        }
    }

    /**
     * Reads the device types from file and stores them in memory for later access.
     *
     * @param aFileName The name of the file to read from
     * @throws ParserConfigurationException
     * @throws SAXException
     * @throws IOException
     */
    public void loadDeviceTypesXML(String aFileName) throws ParserConfigurationException, SAXException, IOException {
        File file = new File(aFileName);
        InputStream in = new FileInputStream(file);
        loadDeviceTypesXML(in);
    }

    /**
     * Process device type node
     *
     * @param e name of the element to process
     * @throws SAXException
     */
    private void processDeviceType(Element e) throws SAXException {
        String name = e.getAttribute("name");
        if (name.equals("")) {
            throw new SAXException("device type in device_types file has no name!");
        }
        if (deviceTypes.containsKey(name)) {
            logger.warn("overwriting previous definition of device type {}", name);
            deviceTypes.remove(name);
        }
        Map<String, Boolean> flags = getFlags(e);
        DeviceType devType = new DeviceType(name, flags);

        NodeList nodes = e.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            Element subElement = (Element) node;
            if (subElement.getNodeName().equals("feature")) {
                processFeature(devType, subElement);
            } else if (subElement.getNodeName().equals("feature-group")) {
                processFeatureGroup(devType, subElement);
            }
        }
        deviceTypes.put(name, devType);
    }

    private String processFeature(DeviceType devType, Element e) throws SAXException {
        String name = e.getAttribute("name");
        if (name.equals("")) {
            throw new SAXException("feature " + e.getNodeName() + " has no name attribute!");
        }
        String type = e.getTextContent();
        if (type == null) {
            throw new SAXException("feature " + e.getNodeName() + " has no text content!");
        }
        Map<String, @Nullable String> params = getParameters(e);
        FeatureEntry fe = new FeatureEntry(name, type, params);
        if (!devType.addFeature(name, fe)) {
            throw new SAXException("duplicate feature: " + name);
        }
        return (name);
    }

    private String processFeatureGroup(DeviceType devType, Element e) throws SAXException {
        String name = e.getAttribute("name");
        if (name.equals("")) {
            throw new SAXException("feature group " + e.getNodeName() + " has no name attribute!");
        }
        String type = e.getAttribute("type");
        if (type.equals("")) {
            throw new SAXException("feature group " + e.getNodeName() + " has no type attribute!");
        }
        Map<String, @Nullable String> params = getParameters(e);
        FeatureGroup fg = new FeatureGroup(name, type, params);
        NodeList nodes = e.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            Element subElement = (Element) node;
            if (subElement.getNodeName().equals("feature")) {
                fg.addConnectedFeature(processFeature(devType, subElement));
            } else if (subElement.getNodeName().equals("feature-group")) {
                fg.addConnectedFeature(processFeatureGroup(devType, subElement));
            }
        }
        if (!devType.addFeatureGroup(name, fg)) {
            throw new SAXException("duplicate feature group " + name);
        }
        return (name);
    }

    private Map<String, Boolean> getFlags(Element e) throws SAXException {
        NamedNodeMap attributes = e.getAttributes();
        Map<String, Boolean> flags = new HashMap<>();
        for (int i = 0; i < attributes.getLength(); i++) {
            Node n = attributes.item(i);
            if (n.getNodeValue().equals("true") || n.getNodeValue().equals("false")) {
                flags.put(n.getNodeName(), n.getNodeValue().equals("true"));
            }
        }
        return flags;
    }

    private Map<String, @Nullable String> getParameters(Element e) throws SAXException {
        NamedNodeMap attributes = e.getAttributes();
        Map<String, @Nullable String> params = new HashMap<>();
        List<String> excludeList = Arrays.asList("name", "type");
        for (int i = 0; i < attributes.getLength(); i++) {
            Node n = attributes.item(i);
            if (!excludeList.contains(n.getNodeName())) {
                params.put(n.getNodeName(), n.getNodeValue());
            }
        }
        return params;
    }

    /**
     * Helper function for debugging
     */
    private void logDeviceTypes() {
        for (DeviceType dt : getDeviceTypes().values()) {
            logger.debug("{}", dt);
        }
    }

    /**
     * Singleton instance function, creates DeviceTypeLoader
     *
     * @return DeviceTypeLoader singleton reference
     */
    @Nullable
    public static synchronized DeviceTypeLoader instance() {
        if (deviceTypeLoader == null) {
            deviceTypeLoader = new DeviceTypeLoader();
            InputStream input = DeviceTypeLoader.class.getResourceAsStream("/device_types.xml");
            try {
                deviceTypeLoader.loadDeviceTypesXML(input);
            } catch (ParserConfigurationException e) {
                logger.warn("parser config error when reading device types xml file: ", e);
            } catch (SAXException e) {
                logger.warn("SAX exception when reading device types xml file: ", e);
            } catch (IOException e) {
                logger.warn("I/O exception when reading device types xml file: ", e);
            }
            if (logger.isDebugEnabled()) {
                logger.debug("loaded {} device types: ", deviceTypeLoader.getDeviceTypes().size());
                deviceTypeLoader.logDeviceTypes();
            }
        }
        return deviceTypeLoader;
    }
}
