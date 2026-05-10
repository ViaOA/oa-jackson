/*
 * Copyright 1999–2025 ViaOA (info@viaoa.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.viaoa.xml;

import java.io.StringReader;
import java.io.StringWriter;

import javax.xml.transform.OutputKeys;
import javax.xml.transform.Source;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;

/**
 * Utility class used to pretty-print XML text using JAXP {@link Transformer}.
 * <p>
 * The formatter applies indentation using the Xalan-specific
 * {@code indent-amount} property and returns a trimmed, human-readable XML form.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * OAXMLFormatter f = new OAXMLFormatter();
 * String out = f.formatXML(xmlString, 4);
 * }</pre>
 *
 * <p>
 * This class does not validate or modify semantic content; it only reflows
 * whitespace and line breaks.
 */
public class OAXMLFormatter {
	
	/**
	 * Formats the supplied XML text using indentation and line breaks.
	 * <p>
	 * A {@link Transformer} is created to output indented XML and the result
	 * is returned as a trimmed string.
	 *
	 * @param input the XML text to format
	 * @param indent the number of spaces to use for indentation
	 * @return the formatted XML string
	 * @throws Exception if an error occurs while transforming the XML
	 */
    public String formatXML(String input, int indent) throws Exception {
        Source xmlInput = new StreamSource(new StringReader(input));
        StringWriter stringWriter = new StringWriter();
        TransformerFactory transformerFactory = TransformerFactory.newInstance();
        Transformer transformer = transformerFactory.newTransformer();
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        transformer.setOutputProperty(OutputKeys.DOCTYPE_PUBLIC, "yes");
        transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", indent+"");
        transformer.transform(xmlInput, new StreamResult(stringWriter));

        return stringWriter.toString().trim();
    }    

}
