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
package com.viaoa.yaml;

import java.io.BufferedReader;
import java.io.StringReader;

import com.viaoa.datetime.OADate;
import com.viaoa.datetime.OADateTime;
import com.viaoa.datetime.OATime;
import com.viaoa.lang.OAString;
import com.viaoa.object.OAObject;
import com.viaoa.xml.OAXMLReader;

/**
 * Lightweight YAML-to-OAObject reader that supports a very small subset of
 * YAML and converts it into OA's XML format before delegating to
 * {@link OAXMLReader}. This class is intended for simple configuration-style
 * YAML files where each top-level entry corresponds to an instance of a root
 * object type. <p>
 *
 * The converter reads line-oriented key/value pairs, performs minimal
 * indentation detection, and generates an {@code <OAXML>} document containing
 * one or more elements named after the configured {@code rootObjectName}. The
 * resulting XML is parsed by a customized {@link OAXMLReader} that supports
 * overridable hooks for class-name resolution, property-name mapping, value
 * transformation, and end-of-object notification. <p>
 *
 * Only a limited YAML subset is supported and no general YAML parsing is
 * performed. The class is not thread-safe; a new instance should be used for
 * each parse operation.
 */
public class OAYamlReader {
	
	/**
	 * Length of the input text being processed.
	 */
	private int len;
	
	/**
	 * Current position within the input text.
	 */
	private int pos;
	
	/**
	 * Buffer used to build the generated XML output.
	 */
	private StringBuilder sb;
	
	/**
	 * Class representing the root object type for parsed YAML content.
	 */
	private Class rootClass;
	
	/**
	 * Property names used to map top-level YAML entries to root object properties.
	 */
	private String rootPropertyName, rootPropertyName2;
	
	/**
	 * XML element name used for each root object created from the YAML input.
	 */
	private String rootObjectName;

	/**
	 * Creates a new YAML reader configured for the specified root object mapping.
	 *
	 * @param rootObjectName the XML element name for root objects
	 * @param rootPropertyName the primary property name for top-level YAML entries
	 * @param rootPropertyName2 the secondary property name for top-level YAML entries
	 */
	public OAYamlReader(String rootObjectName, String rootPropertyName, String rootPropertyName2) {
		this.rootObjectName = rootObjectName;
		this.rootPropertyName = rootPropertyName;
		this.rootPropertyName2 = rootPropertyName2;
	}

	/**
	 * Parses YAML text into OAObjects of the specified root class.
	 * <p>
	 * The YAML is first converted to XML and then parsed using {@link OAXMLReader}.
	 *
	 * @param yaml the YAML text to parse
	 * @param rootClass the class of the root object
	 * @return an array of parsed objects
	 */
	public Object[] parse(String yaml, Class rootClass) {
		try {
			String xml = convertToXML(yaml, rootClass);
			OAXMLReader xmlReader = new OAXMLReader() {
				@Override
				public Object convertToObject(String propertyName, String value, Class propertyClass) {
					if ("null".equals(value)) {
						return null;
					}
					if (OADate.class.equals(propertyClass)) {
						return new OADate(value, "yyyy-MM-dd");
					}
					if (OATime.class.equals(propertyClass)) {
						return new OATime(value, "HH:mm:ss");
					}
					if (OADateTime.class.equals(propertyClass)) {
						return new OADate(value, "yyyy-MM-dd'T'HH:mm:ss");
					}
					return super.convertToObject(propertyName, value, propertyClass);
				}

				@Override
				protected String resolveClassName(String className) {
					return OAYamlReader.this.getClassName(className);
				}

				@Override
				public Object getValue(OAObject obj, String name, Object value) {
					return OAYamlReader.this.getValue(obj, name, value);
				}

				@Override
				protected String getPropertyName(OAObject obj, String propName) {
					return OAYamlReader.this.getPropertyName(obj, propName);
				}

				@Override
				public void endObject(OAObject obj, boolean hasParent) {
					OAYamlReader.this.endObject(obj, hasParent);
				}
			};
			xmlReader.parseString(xml);

			Object[] objs = xmlReader.readXML(xml);

			return objs;
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * Returns the class name to use when resolving a property class.
	 *
	 * @param className the class name found in the XML
	 * @return the resolved class name
	 */
	protected String getClassName(String className) {
		//System.out.println("getClassName className="+className);//qqqqqqqqq
		//        className = "com.viaoa.object.OAObject";
		return className;
	}

	/**
	 * Returns the property name to use when setting a value on an object.
	 *
	 * @param obj the target object
	 * @param propName the property name from the XML
	 * @return the resolved property name
	 */
	protected String getPropertyName(OAObject obj, String propName) {
		//System.out.println("getPropertyName obj="+obj+", propName="+propName);//qqqqqqqqq
		//propName = null;
		return propName;
	}

	/**
	 * Returns the value to use when setting a property on an object.
	 *
	 * @param obj the target object
	 * @param name the property name
	 * @param value the parsed value
	 * @return the value to assign
	 */
	protected Object getValue(OAObject obj, String name, Object value) {
		//System.out.println("getValue obj="+obj+", propName="+name+", value="+value);//qqqqqqqqq        
		return value;
	}

	/**
	 * Callback invoked when an object has finished being read.
	 *
	 * @param obj the object that was completed
	 * @param hasParent {@code true} if the object has a parent object
	 */
	protected void endObject(OAObject obj, boolean hasParent) {
	}

	/**
	 * Converts YAML text into an XML representation compatible with {@link OAXMLReader}.
	 *
	 * @param text the YAML text to convert
	 * @param rootClass the class of the root object
	 * @return the generated XML string
	 */
	public String convertToXML(String text, Class rootClass) {
		this.rootClass = rootClass;
		pos = 0;
		len = text.length();
		sb = new StringBuilder(len * 3);

		sb.append("<?xml version='1.0' encoding='utf-8'?>\n");
		sb.append("<OAXML VERSION='2.0' DATETIME='9/9/15 9:08 AM'>\n");
		//sb.append("<com.viaoa.hub.Hub ObjectClass=\""+rootClass.getName()+"\">\n");

		BufferedReader br = new BufferedReader(new StringReader(text));
		try {
			boolean indented = false;
			int cntObject = 0;
			for (int i = 0;; i++) {
				String line = br.readLine();
				if (line == null) {
					break;
					// System.out.println(i+") "+line);
				}

				String name = OAString.field(line, ':', 1);
				if (name.trim().length() == 0) {
					continue;
				}

				if (name.trim().charAt(0) == '#') {
					continue;
				}

				String value = OAString.field(line, ':', 2, 999);

				if (name.length() > 0 && name.charAt(0) == ' ') {
					indented = true;
				} else {
					indented = false;
				}

				name = name.trim();
				if (value != null) {
					value = value.trim();
				}

				if (!indented) {
					if (cntObject++ > 0) {
						sb.append("</" + rootObjectName + ">\n");
					}
					sb.append("<" + rootObjectName + ">\n");

					/*
					    te:    << rootPropertyName value
					      order: 6      << name/value props
					      login: impact
					      packages: [te, teconfig]
					      type: te
					
					
					    pdk-st-ixmts-01: [mts]   <<  rootPropertyName value and rootPropertyName2 value 
					*/
					if (!OAString.isEmpty(value)) {
						sb.append("  <" + rootPropertyName + ">" + name + "</" + rootPropertyName + ">\n");
						name = rootPropertyName2;
					} else {
						value = name;
						name = rootPropertyName;
					}
				}
				sb.append("  <" + name + ">" + value + "</" + name + ">\n");
			}
			if (cntObject > 0) {
				sb.append("</" + rootObjectName + ">\n");
			}
		} catch (Exception e) {
			System.out.println("error: " + e);
			e.printStackTrace();
		}

		//sb.append("</com.viaoa.hub.Hub>\n");
		sb.append("</OAXML>\n");
		return new String(sb);
	}

}
