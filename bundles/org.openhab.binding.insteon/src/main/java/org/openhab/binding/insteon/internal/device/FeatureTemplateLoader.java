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

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.smarthome.core.library.types.DecimalType;
import org.eclipse.smarthome.core.library.types.IncreaseDecreaseType;
import org.eclipse.smarthome.core.library.types.OnOffType;
import org.eclipse.smarthome.core.library.types.PercentType;
import org.eclipse.smarthome.core.library.types.QuantityType;
import org.eclipse.smarthome.core.library.types.StopMoveType;
import org.eclipse.smarthome.core.library.types.StringType;
import org.eclipse.smarthome.core.library.types.UpDownType;
import org.eclipse.smarthome.core.types.Command;
import org.openhab.binding.insteon.internal.device.FeatureTemplate.HandlerEntry;
import org.openhab.binding.insteon.internal.utils.ByteUtils;
import org.openhab.binding.insteon.internal.utils.ParsingException;
import org.w3c.dom.DOMException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * Class that loads the device feature templates from an xml stream
 *
 * @author Daniel Pfrommer - Initial contribution
 * @author Rob Nielsen - Port to openHAB 2 insteon binding
 * @author Jeremy Setton - Improvement to openHAB 2 insteon binding
 */
@NonNullByDefault
public class FeatureTemplateLoader {
    public static List<FeatureTemplate> readTemplates(InputStream input) throws IOException, ParsingException {
        List<FeatureTemplate> features = new ArrayList<>();
        try {
            DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
            DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
            // Parse it!
            Document doc = dBuilder.parse(input);
            doc.getDocumentElement().normalize();

            Element root = doc.getDocumentElement();

            NodeList nodes = root.getChildNodes();

            for (int i = 0; i < nodes.getLength(); i++) {
                Node node = nodes.item(i);
                if (node.getNodeType() == Node.ELEMENT_NODE) {
                    Element e = (Element) node;
                    if (e.getTagName().equals("feature-type")) {
                        features.add(parseFeature(e));
                    }
                }
            }
        } catch (SAXException e) {
            throw new ParsingException("Failed to parse XML!", e);
        } catch (ParserConfigurationException e) {
            throw new ParsingException("Got parser config exception! ", e);
        }
        return features;
    }

    private static FeatureTemplate parseFeature(Element e) throws ParsingException {
        String name = e.getAttribute("name");
        Map<String, @Nullable String> params = getParameters(e);
        FeatureTemplate feature = new FeatureTemplate(name, params);

        NodeList nodes = e.getChildNodes();

        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE) {
                Element child = (Element) node;
                if (child.getTagName().equals("message-handler")) {
                    parseMessageHandler(child, feature);
                } else if (child.getTagName().equals("command-handler")) {
                    parseCommandHandler(child, feature);
                } else if (child.getTagName().equals("message-dispatcher")) {
                    parseMessageDispatcher(child, feature);
                } else if (child.getTagName().equals("poll-handler")) {
                    parsePollHandler(child, feature);
                }
            }
        }

        return feature;
    }

    private static HandlerEntry makeHandlerEntry(Element e) throws ParsingException {
        String handler = e.getTextContent();
        if (handler == null) {
            throw new ParsingException("Could not find Handler for: " + e.getTextContent());
        }
        return new HandlerEntry(handler, getParameters(e));
    }

    private static Map<String, @Nullable String> getParameters(Element e) throws ParsingException {
        NamedNodeMap attributes = e.getAttributes();
        Map<String, @Nullable String> params = new HashMap<>();
        List<String> excludeList = Arrays.asList("name", "command", "default");
        for (int i = 0; i < attributes.getLength(); i++) {
            Node n = attributes.item(i);
            if (!excludeList.contains(n.getNodeName())) {
                params.put(n.getNodeName(), n.getNodeValue());
            }
        }
        return params;
    }

    private static void parseMessageHandler(Element e, FeatureTemplate f) throws DOMException, ParsingException {
        HandlerEntry he = makeHandlerEntry(e);
        if (e.getAttribute("default").equals("true")) {
            f.setDefaultMessageHandler(he);
        } else {
            int command = parseCommandHexValue(e.getAttribute("command"));
            f.addMessageHandler(command, he);
        }
    }

    private static void parseCommandHandler(Element e, FeatureTemplate f) throws ParsingException {
        HandlerEntry he = makeHandlerEntry(e);
        if (e.getAttribute("default").equals("true")) {
            f.setDefaultCommandHandler(he);
        } else {
            Class<? extends Command> command = parseCommandClass(e.getAttribute("command"));
            f.addCommandHandler(command, he);
        }
    }

    private static void parseMessageDispatcher(Element e, FeatureTemplate f) throws DOMException, ParsingException {
        HandlerEntry he = makeHandlerEntry(e);
        f.setMessageDispatcher(he);
    }

    private static void parsePollHandler(Element e, FeatureTemplate f) throws ParsingException {
        HandlerEntry he = makeHandlerEntry(e);
        f.setPollHandler(he);
    }

    private static int parseCommandHexValue(String h) throws ParsingException {
        try {
            return ByteUtils.hexStrToInt(h);
        } catch (NumberFormatException e) {
            throw new ParsingException("Unknown Command Hex Value");
        }
    }

    private static Class<? extends Command> parseCommandClass(String c) throws ParsingException {
        if (c.equals("OnOffType")) {
            return OnOffType.class;
        } else if (c.equals("PercentType")) {
            return PercentType.class;
        } else if (c.equals("DecimalType")) {
            return DecimalType.class;
        } else if (c.equals("IncreaseDecreaseType")) {
            return IncreaseDecreaseType.class;
        } else if (c.equals("QuantityType")) {
            return QuantityType.class;
        } else if (c.equals("StringType")) {
            return StringType.class;
        } else if (c.equals("UpDownType")) {
            return UpDownType.class;
        } else if (c.equals("StopMoveType")) {
            return StopMoveType.class;
        } else {
            throw new ParsingException("Unknown Command Type");
        }
    }
}
