package org.odk.collect.util;

import java.io.File;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.xml.transform.*;
import javax.xml.transform.stream.*;
import javax.xml.transform.dom.*;
import org.apache.commons.text.StringEscapeUtils;

import org.w3c.dom.*;
import javax.xml.parsers.*;

import timber.log.Timber;

public class XmlUtility {

    private static String FIELDNAME = "wigleReport";
    private static String TEMP_PATH = "wigle_temp_path";
    public static List<Node> asList(NodeList n) {
        return n.getLength()==0?
                Collections.<Node>emptyList(): new NodeListWrapper(n);
    }
    static final class NodeListWrapper extends AbstractList<Node>
            implements RandomAccess {
        private final NodeList list;
        NodeListWrapper(NodeList l) {
            list=l;
        }
        public Node get(int index) {
            return list.item(index);
        }
        public int size() {
            return list.getLength();
        }
    }
    public static class Head {
        public String getName() { return "head"; }
    }
    public static class Model {
        public String getName() { return "model"; }
    }
    private static void sanitize (NodeList root) {
        for(Node currentNode : asList(root)) {
            if (currentNode.getNodeType() == Node.ELEMENT_NODE) {
                if (currentNode.getNodeName().equals("bind")) {
                    Element bind = (Element) currentNode;
                    if (bind.hasAttribute("calculate")) {
                        String escaped = StringEscapeUtils.escapeHtml3(bind.getAttribute("calculate"));
                        bind.setAttribute("calculate", escaped);
                    }
                }

                Node child = currentNode.getFirstChild();
                while(child != null) {
                    if (child.getNodeType() == Node.TEXT_NODE) {
                        child.setNodeValue(StringEscapeUtils.escapeXml10(child.getNodeValue()));
                    }
                    child = child.getNextSibling();
                }
            }
        }
    }
    public static File mutate(File file) throws Exception {

        DocumentBuilderFactory documentBuilderFactory = DocumentBuilderFactory.newInstance();
        DocumentBuilder documentBuilder = documentBuilderFactory.newDocumentBuilder();
        Document document = documentBuilder.parse(file);
        Element root = document.getDocumentElement();

        //double check for wigle meta element
        NodeList nodes = root.getElementsByTagName(FIELDNAME);
        if (nodes.getLength() > 0 ) {
            return file;
        }

        //Insert field on first data node
        nodes = root.getElementsByTagName("meta");
        if(nodes.getLength() >= 1) {
            Node meta = nodes.item(0);
            Element newData = document.createElement(FIELDNAME);
            newData.setTextContent("wigle_temp_path");
            meta.getParentNode().insertBefore(newData, meta);
        }

        //Insert bind on first model node
        nodes = root.getElementsByTagName("model");
        if(nodes.getLength() >= 1) {
            Node data = nodes.item(0);
            Element newBind = document.createElement("bind");
            newBind.setAttribute("nodeset", "/data/wigleReport");
            newBind.setAttribute("type", "binary");
            data.appendChild(newBind);
        }

        Timber.i("Escaping Nodes");

        NodeList nodeList = root.getElementsByTagName("bind");
        sanitize(nodeList);

        String s = getStringFromDocument(document);

        DOMSource source = new DOMSource(document);
        TransformerFactory transformerFactory = TransformerFactory.newInstance();
        Transformer transformer = transformerFactory.newTransformer();
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        Timber.i(file.getAbsolutePath());
        StreamResult result = new StreamResult(file.getAbsolutePath());
        transformer.transform(source, result);
        return new File(file.getAbsolutePath());
    }

    private static String getStringFromDocument(Document doc) throws TransformerException {
        DOMSource domSource = new DOMSource(doc);
        StringWriter writer = new StringWriter();
        StreamResult result = new StreamResult(writer);
        TransformerFactory tf = TransformerFactory.newInstance();
        Transformer transformer = tf.newTransformer();
        transformer.transform(domSource, result);
        return writer.toString();
    }

    public static byte[] mutatePayload(byte[] payload, String targetPath) {
        String toStr = new String(payload, StandardCharsets.UTF_8);
        toStr.replaceAll("(.*)"+TEMP_PATH+"(.*)", targetPath);
        return toStr.getBytes();
    }
}
