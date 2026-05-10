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

import java.io.File;
import java.io.StringBufferInputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.List;
import java.util.UUID;
import java.util.Vector;

import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import com.viaoa.compare.OACompare;
import com.viaoa.converter.OAConverter;
import com.viaoa.datasource.OADataSource;
import com.viaoa.filter.OAFilter;
import com.viaoa.graph.OAGraphInternal;
import com.viaoa.graph.service.object.OAObjectCSService;
import com.viaoa.graph.service.object.OAObjectCacheService;
import com.viaoa.graph.service.object.OAObjectInfoService;
import com.viaoa.graph.service.object.OAObjectKeyService;
import com.viaoa.hub.Hub;
import com.viaoa.lang.OAString;
import com.viaoa.metadata.OALinkInfo;
import com.viaoa.metadata.OAObjectInfo;
import com.viaoa.object.OAObject;
import com.viaoa.object.OAObjectKey;
import com.viaoa.runtime.OARuntime;
import com.viaoa.runtime.OAThreadLocalService;
import com.viaoa.runtime.OAThreadService;
import com.viaoa.secure.Base64;
import com.viaoa.select.OASelect;

/**
 * Legacy XML reader for OA's original (v1.x) XML serialization format.
 * <p>
 * {@code OAXMLReader1} provides backward compatibility for older OA
 * applications and tools that produced or consumed the first-generation
 * OAXML structure. It is automatically used by {@link OAXMLReader} when an
 * input stream contains a root element with {@code version="1.x"}.
 *
 * <h2>Capabilities</h2>
 * This reader supports the full v1 XML feature set:
 * <ul>
 *   <li>creation of OAObjects using metadata (no reflection-based access),</li>
 *   <li>ID-, GUID-, and type-based object matching,</li>
 *   <li>two-phase loading for resolving references and many-side links,</li>
 *   <li>Hub population for many-valued relationships,</li>
 *   <li>property assignment using {@code OAPropertyInfo} and conversion rules,</li>
 *   <li>import matching for merging existing objects with XML data.</li>
 * </ul>
 *
 * <h2>Format Differences vs. Modern OA XML</h2>
 * The legacy format differs from the current OAXML in several ways:
 * <ul>
 *   <li>link and reference information is encoded using older attribute names,</li>
 *   <li>Hub elements are represented with simpler tag conventions,</li>
 *   <li>property type conversion is performed locally rather than via
 *       {@link com.viaoa.converter.OAConverter} plugins,</li>
 *   <li>CDATA and binary support is limited compared to modern readers.</li>
 * </ul>
 *
 * <h2>Lifecycle</h2>
 * Reading proceeds in two stages:
 * <ol>
 *   <li><b>Scan Phase</b> – creates objects, applies primitive properties,
 *       records unresolved references.</li>
 *   <li><b>Resolve Phase</b> – resolves all link-based relationships using the
 *       temporary reference maps built during scanning.</li>
 * </ol>
 *
 * <h2>Usage Notes</h2>
 * <ul>
 *   <li>This class is not intended for new development.</li>
 *   <li>It remains necessary for legacy data import and migration tools.</li>
 *   <li>Modern OA applications should rely on {@link OAXml} and
 *       {@link OAXMLReader} instead.</li>
 * </ul>
 *
 * <p>
 * Although the parsing logic uses older conventions, it remains stable and
 * fully compatible with OA's modern object graph and metadata system.
 */
public class OAXMLReader1 extends DefaultHandler {
	
	/**
	 * Name of the XML file currently being parsed.
	 */
	private String fileName;
	
	/**
	 * Accumulates character data read within the current XML element.
	 */
	String value;
	
	/**
	 * Current nesting depth of XML elements during parsing.
	 */
	int indent;
	
	/**
	 * Holds the current class name being processed during parsing.
	 */
	String className;
	
	/**
	 * Counter used to track total elements or objects processed.
	 */
	int total;
	
	/**
	 * Flag indicating whether the parser is currently within an XML element.
	 */
	boolean bWithinTag;
	
	/**
	 * Stack used to track parsing state, objects, properties, and hubs.
	 */
	Object[] stack = new Object[10];
	
	/**
	 * Optional decode key used to decrypt encoded XML values.
	 */
	private String decodeMessage;
	
	/**
	 * Flag indicating that a reference value should be used instead of a literal value.
	 */
	protected boolean bUseRef;
	
	/**
	 * Holds a referenced object or key value during reference resolution.
	 */
	protected Object refValue;
	
	/**
	 * The first root-level object encountered during parsing.
	 */
	protected Object firstObject;
	
	/**
	 * Sentinel object used to distinguish an explicit null root object.
	 */
	private Object nullObject = new Object();
	
	/**
	 * Internal key used to store class name information from XML.
	 */
	private static final String XML_CLASS = "XML_CLASS";
	
	/**
	 * Internal key indicating that an XML element represents a key-only reference.
	 */
	private static final String XML_KEYONLY = "XML_KEYONLY";
	
	/**
	 * Internal key used to store the instantiated OAObject for an element.
	 */
	private static final String XML_OBJECT = "XML_OBJECT";
	
	/**
	 * Internal key used to store GUID values from XML attributes.
	 */
	private static final String XML_GUID = "XML_GUID";
	
	/**
	 * Target class used when converting string values to typed property values.
	 */
	protected Class conversionClass; // type of class that value needs to be converted to
	
	/**
	 * Vectors holding incomplete objects and root-level objects during parsing.
	 */
	protected Vector vecIncomplete, vecRoot;
	
	/**
	 * Map of GUID strings to OAObject instances for reference resolution.
	 */
	protected HashMap<String, OAObject> hashGuid;
	
	/**
	 * Map used to track objects matched by class and object key during import
	 * matching operations.
	 */
	protected HashMap<Class, HashMap<OAObjectKey, OAObject>> hmMatch = new HashMap<Class, HashMap<OAObjectKey, OAObject>>();
	
	/**
	 * Flag indicating whether import matching rules are enabled during parsing.
	 */
	private boolean bImportMatching = true;

	/**
	 * Detected OAXML document version used to control parsing behavior.
	 */
	private int versionOAXML;

	// objects that have been removed from a Hub and might not have been saved
	//   these objects will then be checked and saved at the end of the import
	/**
	 * Collection of objects removed from hubs during import that may require
	 * post-processing or saving.
	 */
	protected Vector vecRemoved = new Vector();

	/**
	 * Creates a new legacy XML reader instance.
	 */
	public OAXMLReader1() {
	}

	/**
	 * Creates a new legacy XML reader instance and sets the XML file name.
	 *
	 * @param fileName the XML file to read
	 */
	public OAXMLReader1(String fileName) {
		setFileName(fileName);
	}

	/**
	 * Sets the name of the XML file to be parsed.
	 *
	 * @param fileName the XML file name
	 */
	public void setFileName(String fileName) {
		this.fileName = fileName;
	}

	/**
	 * Returns the name of the XML file being parsed.
	 *
	 * @return the XML file name
	 */
	public String getFileName() {
		return this.fileName;
	}

	/**
	 * Enables or disables import matching during object loading.
	 *
	 * @param b {@code true} to enable import matching; {@code false} to disable
	 */
	public void setImportMatching(boolean b) {
		this.bImportMatching = b;
	}

	/**
	 * Returns whether import matching is enabled.
	 *
	 * @return {@code true} if import matching is enabled
	 */
	public boolean getImportMatching() {
		return this.bImportMatching;
	}

	/**
	 * Parses an XML file and creates OAObjects from its contents.
	 *
	 * @param fileName the XML file to read
	 * @return the top-level object created from the XML
	 * @throws Exception if parsing or object creation fails
	 */
	public Object read(String fileName) throws Exception {
		setFileName(fileName);
		return read();
	}

	/**
	 * Parses the configured XML file and creates OAObjects from its contents.
	 *
	 * @return the top-level object created from the XML
	 * @throws Exception if parsing or object creation fails
	 */
	public Object read() throws Exception {
		return parse(this.fileName);
	}

	/**
	 * Parses the configured XML file and completes post-processing of removed
	 * objects.
	 *
	 * @return the top-level object created from the XML
	 * @throws Exception if parsing or object processing fails
	 */
	public Object parse() throws Exception {
		reset();
		Object obj = null;
		obj = parse(fileName);

		int x = vecRemoved.size();
		for (int i = 0; i < x; i++) {
			OAObject oa = (OAObject) vecRemoved.elementAt(i);
			if (oa.getNew()) {
				continue; // object was deleted
			}
			if (oa.getChanged()) {
				endObject(oa, false);
			}
		}
		vecRemoved.removeAllElements();

		return obj;
	}

	/**
	 * Resets all internal parsing state prior to a new parse operation.
	 */
	protected void reset() {
		indent = 0;
		total = 0;
		bWithinTag = false;
		vecRoot = new Vector();
		vecIncomplete = new Vector();
		firstObject = null;
		hashGuid = new HashMap();
		versionOAXML = 0;
	}

	/**
	 * Parses the specified XML file and creates OAObjects from its contents.
	 *
	 * @param fileName the XML file to parse
	 * @return the top-level object created from the XML
	 * @throws Exception if parsing or object creation fails
	 */
	public Object parse(String fileName) throws Exception {
		if (fileName == null) {
			throw new IllegalArgumentException("fileName is required");
		}
		reset();

		URI uri = null;
		File f = new File(OAString.convertFileName(fileName));
		if (f.exists()) {
			uri = f.toURI();
		} else {
			uri = new URI(fileName);
		}

		setFileName(fileName);
		SAXParserFactory factory = SAXParserFactory.newInstance();
		SAXParser saxParser = factory.newSAXParser();
		// saxParser.parse( new File(OAString.convertFileName(fileName)), this );
		saxParser.parse(uri.toString(), this);
		if (firstObject == nullObject) {
			firstObject = null;
		}
		if (firstObject == null && vecRoot != null && vecRoot.size() == 1) {
			firstObject = vecRoot.elementAt(0);
		}
		hashGuid = null;
		return firstObject;
	}

	/**
	 * Parses XML data from a string and creates OAObjects from its contents.
	 *
	 * @param xmlData the XML text to parse
	 * @return the top-level object created from the XML
	 * @throws Exception if parsing or object creation fails
	 */
	public Object parseString(String xmlData) throws Exception {
		if (xmlData == null) {
			throw new IllegalArgumentException("xmlData is required");
		}
		reset();

		SAXParserFactory factory = SAXParserFactory.newInstance();
		SAXParser saxParser = factory.newSAXParser();

		saxParser.parse(new StringBufferInputStream(xmlData), this);
		if (firstObject == nullObject) {
			firstObject = null;
		}
		if (firstObject == null && vecRoot != null && vecRoot.size() == 1) {
			firstObject = vecRoot.elementAt(0);
		}
		hashGuid = null;
		return firstObject;
	}

	/**
	 * Returns all root-level objects created during the last parse operation.
	 *
	 * @return an array of root objects
	 */
	public Object[] getRootObjects() {
		int x = vecRoot == null ? 0 : vecRoot.size();
		Object[] objects = new Object[x];
		if (vecRoot != null) {
			vecRoot.copyInto(objects);
		}
		return objects;
	}

	/**
	 * Replaces a root-level object with another instance.
	 *
	 * @param oldValue the existing root object
	 * @param newValue the replacement object
	 */
	private void replaceRootObject(Object oldValue, Object newValue) {
		if (vecRoot == null) {
			return;
		}
		int pos = vecRoot.indexOf(oldValue);
		if (pos >= 0) {
			vecRoot.set(pos, newValue);
		}
	}

	/**
	 * Sets the decode message used to decrypt encoded XML values.
	 *
	 * @param msg the decode message
	 * @throws IllegalArgumentException if the message is an empty string
	 */
	public void setDecodeMessage(String msg) {
		if (msg != null && msg.length() == 0) {
			throw new IllegalArgumentException("DecodeMessage cant be an empty string");
		}
		decodeMessage = msg;
	}

	/**
	 * Returns the decode message used for decrypting XML values.
	 *
	 * @return the decode message, or {@code null} if not set
	 */
	public String getDecodeMessage() {
		return decodeMessage;
	}

	/**
	 * SAX callback invoked at the start of an XML element.
	 *
	 * @param namespaceURI the namespace URI
	 * @param sName the local element name
	 * @param qName the qualified element name
	 * @param attrs the element attributes
	 * @throws SAXException if a parsing error occurs
	 */
	public void startElement(String namespaceURI, String sName, String qName, Attributes attrs) throws SAXException {
		value = "";
		bWithinTag = true;
		String eName = sName; // element name
		if ("".equals(eName)) {
			eName = qName; // not namespaceAware
		}

		p(eName);
		indent++;

		if (indent == 1) {
			versionOAXML = "OAXML".equalsIgnoreCase(eName) ? 1 : 0;
			if (versionOAXML > 0) {
				// ex:  <OAXML VERSION='1.0' DATETIME='08/12/2003 11:56AM'>
				if (attrs != null) {
					for (int i = 0; i < attrs.getLength(); i++) {
						String aName = attrs.getLocalName(i);
						if (!"version".equalsIgnoreCase(aName)) {
							continue;
						}
						String s = attrs.getValue(i);
						if ("2.0".equals(s)) {
							versionOAXML = 2;
						}
						break;
					}
				}
			}
			//todo later:  if (versionOAXML == ??) throw new RuntimeException("OAXML ??? not supported");

			stack[0] = null; // place holder
			stack[1] = null; // place holder
			return;
		}

		// stack ex:  null | null | Department object | "Employees" | Hub object | Employee | "name" ...
		// stack ex:  null | null | Employee object | "Department" | Department object | "Manager" | Employee ...

		if (stack.length <= indent) {
			Object[] objs = new Object[stack.length + 20];
			System.arraycopy(stack, 0, objs, 0, stack.length);
			stack = objs;
		}
		stack[indent] = eName;

		if (stack[indent - 1] == null || (stack[indent - 1] instanceof Vector) || (stack[indent - 1] instanceof Hub)
				|| (stack[indent - 1] instanceof String)) {
			// start of new object/hub
			// whenever startElement() is called and the previous stack element has a String in it,
			//   then the next property is the reference Object/Hub

			Class c = null;
			try {
				// Note: "INSERTCLASS" is used by JSON, and needs to be resolved by OAXMLReader here
				if ("INSERTCLASS".equalsIgnoreCase(eName)) {
					for (int i = indent - 1; i >= 0; i--) {
						if (stack[i] == null) {
							continue;
						}
						if (stack[i] instanceof Hub) {
							eName = ((Hub) stack[i]).getObjectClass().getName();
							stack[indent] = eName;
							break;
						}
						if (stack[i] instanceof Hashtable) {
							Class cx = (Class) ((Hashtable) stack[i]).get(XML_CLASS);
							// find className of property
							String prop = (String) stack[i + 1];
							final OAGraphInternal og = (OAGraphInternal) OARuntime.graph(cx);
							OAObjectInfo oi = og.objectsInternal().callObjectInfoGetOAObjectInfo(cx);
							cx = og.objectsInternal().callObjectInfoGetPropertyClass(oi, prop);

							if (Hub.class.equals(cx)) {
								OALinkInfo li = og.objectsInternal().callObjectInfoGetLinkInfo(oi, prop);
								if (li != null) {
									cx = li.getToClass();
								}
							}

							eName = cx.getName();
							stack[indent] = eName;
							break;
						}
					}
				}
				if ("com.viaoa.hub.Hub".equals(eName)) {
					c = Hub.class;
				} else {
					eName = resolveClassName(eName);
					if (eName == null) {
						eName = "com.viaoa.object.OAObject";
					}
					c = Class.forName(eName);
				}
			} catch (Exception e) {
				throw new SAXException("cant find class " + eName + " Error: " + e);
			}

			if (c.equals(Hub.class)) {
				String className = attrs.getValue("ObjectClass");
				// Note: "INSERTCLASS" is used by JSON, and needs to be resolved by OAXMLReader here
				if ("INSERTCLASS".equalsIgnoreCase(className)) {
					for (int i = indent - 1; i >= 0; i--) {
						if (stack[i] instanceof Hashtable) {
							Class cx = (Class) ((Hashtable) stack[i]).get(XML_CLASS);
							// find className of property
							String prop = (String) stack[i + 1];
							final OAGraphInternal og = (OAGraphInternal) OARuntime.graph(cx);
							OAObjectInfo oi = og.objectsInternal().callObjectInfoGetOAObjectInfo(cx);
							cx = og.objectsInternal().callObjectInfoGetPropertyClass(oi, prop);

							if (Hub.class.equals(cx)) {
								OALinkInfo li = og.objectsInternal().callObjectInfoGetLinkInfo(oi, prop);
								if (li != null) {
									cx = li.getToClass();
								}
							}
							className = cx.getName();
							break;
						}
					}
				}
				className = resolveClassName(className);

				int tot;
				String stot = attrs.getValue("total");
				if (OAString.isEmpty(stot)) {
					tot = 0;
				} else {
					tot = OAConverter.toInt(stot);
				}
				startHub(className, tot);
				if (indent > 3) {
					// get Hub from previous object
					Hashtable hash = (Hashtable) stack[indent - 2];
					Vector vec = (Vector) hash.get(stack[indent - 1]); // name of Hub property
					if (vec == null) {
						vec = new Vector(43, 25);
						hash.put(stack[indent - 1], vec); // propertyName, vector to hold objects
					}
					stack[indent] = vec; // add objects to this
				} else {
					try {
						Hub h = new Hub(Class.forName(resolveClassName(className)));
						stack[indent] = h;
						vecRoot.add(h);
					} catch (Exception e) {
						throw new SAXException("Error getting Class for Hub: " + e);
					}
				}
				if (firstObject == null && stack[indent - 1] == null) {
					firstObject = nullObject;
				}
			} else {
				// create hashtable to hold values
				Hashtable hash = new Hashtable(23, .75f);
				hash.put(XML_CLASS, c);

				stack[indent] = hash;
				if (attrs != null) {
					for (int i = 0; i < attrs.getLength(); i++) {
						String aName = attrs.getLocalName(i); // Attr name
						if ("".equals(aName)) {
							aName = attrs.getQName(i);
						}
						String aValue = attrs.getValue(i);
						if (aName.equalsIgnoreCase("keyonly")) {
							hash.put(XML_KEYONLY, XML_KEYONLY);
						} else if (aName.equalsIgnoreCase("guid")) {
							hash.put(XML_GUID, aValue);
						} else {
							processProperty(aName, aValue, null, hash);
						}
					}
				}
			}
		} else {
			// this needs to check to see if there is a "class" attribute
			conversionClass = null;
			String sclass = attrs.getValue("class");
			if (sclass != null) {
				try {
					conversionClass = Class.forName(resolveClassName(sclass));
				} catch (Exception e) {
					throw new SAXException("cant create class " + sclass + " Error:" + e);
				}
			}
		}
	}

	/**
	 * Internal holder used to temporarily store a class and object key pair
	 * for deferred reference resolution.
	 */
	class Holder {
		Class c;
		OAObjectKey key;

		public Holder(Class c, OAObjectKey key) {
			this.c = c;
			this.key = key;
		}
	}

	/**
	 * SAX callback invoked at the end of an XML element.
	 *
	 * @param namespaceURI the namespace URI
	 * @param sName the local element name
	 * @param qName the qualified element name
	 * @throws SAXException if a parsing error occurs
	 */
	public void endElement(String namespaceURI, String sName, String qName) throws SAXException {
		bWithinTag = false;
		String eName = sName; // element name
		if ("".equals(eName)) {
			eName = qName; // not namespaceAware
		}

		Object stackobj = stack[indent];
		if (stackobj instanceof Hashtable) {
			// ending an object

			/*
			    1: create OAObjectKey using propertyId values
			    2: call OAObjectCacheDelegate to find object
			    3: if not found, create new object
			    4: load/update property values
			*/

			final Hashtable hash = (Hashtable) stackobj;
			final boolean bKeyOnly = hash.remove(XML_KEYONLY) != null;
			final String guid = (String) hash.remove(XML_GUID);

			final Class c = (Class) hash.get(XML_CLASS);
			final OAGraphInternal og = (OAGraphInternal) OARuntime.graph(c);
			OAObjectInfo oi = og.objectsInternal().callObjectInfoGetOAObjectInfo(c);
			String[] ids = oi.getIdProperties();
			Object[] values = new Object[ids == null ? 0 : ids.length];

			for (int i = 0; i < ids.length; i++) {
				String id = ids[i].toUpperCase();
				Class c2 = og.objectsInternal().callObjectInfoGetPropertyClass(c, id);
				values[i] = hash.get(id);
				if (values[i] instanceof String) {
					values[i] = OAConverter.convert(c2, values[i]);
				}
				hash.remove(id);
			}
			final OAObjectKey key = new OAObjectKey(values, UUID.fromString(guid));
			final String[] matchProps = getImportMatching() ? oi.getImportMatchPropertyNames() : null;
			final String[] matchPropPaths = getImportMatching() ? oi.getImportMatchPropertyPaths() : null;
			List<Object> al = new ArrayList<>();
			if (matchProps != null && matchProps.length > 0) {
				for (int i = 0; i < matchProps.length; i++) {
					if (matchPropPaths[i].indexOf('.') > 0) {
						continue;
					}
					String id = matchProps[i].toUpperCase();
					Class c2 = og.objectsInternal().callObjectInfoGetPropertyClass(c, id);
					Object val = hash.get(id);
					if (val instanceof String) {
						val = OAConverter.convert(c2, val);
					}
					al.add(val);
				}
			}
			final Object[] matchValues = al.toArray(new Object[al.size()]);

			OAObject object = null;

			// 20150728
			if (matchProps != null && matchProps.length > 0) {
				if (bKeyOnly) {
					HashMap<OAObjectKey, OAObject> hm = hmMatch.get(c);
					if (hm != null) {
						object = hm.get(key);
					}
				} else {
					OASelect sel = new OASelect(c);
					sel.setFilter(new OAFilter() {
						@Override
						public boolean isUsed(Object obj) {
							if (!(obj instanceof OAObject)) {
								return false;
							}
							for (int i = 0; i < matchProps.length; i++) {
								Object val1 = ((OAObject) obj).getProperty(matchProps[i]);
								if (!OACompare.isEqual(val1, matchValues[i])) {
									return false;
								}
							}
							return true;
						}
					});
					sel.select();
					object = sel.next();
					sel.close();
					if (object != null) {
						HashMap<OAObjectKey, OAObject> hm = hmMatch.get(c);
						if (hm == null) {
							hm = new HashMap<OAObjectKey, OAObject>();
							hmMatch.put(c, hm);
						}
						hm.put(key, object);
					}
				}
			} else {
				if (ids != null && ids.length > 0) {
					// final OAGraphInternal og = (OAGraphInternal) OARuntime.graph(c);
					object = (OAObject) og.objectsInternal().callObjectCacheGet(c, key);
				}
			}
			if (object == null && guid != null) {
				object = (OAObject) hashGuid.get(guid);
			}

			if (bKeyOnly) {
				if (stack[indent - 1] instanceof Vector) {
					Vector vec = (Vector) stack[indent - 1];
					if (object != null) {
						vec.addElement(object);
					} else if (guid != null) {
						vec.addElement(XML_GUID + guid);
					} else if (matchProps == null || matchProps.length == 0) {
						vec.addElement(key);
					} else {
						// 20150730
						vec.add(new Holder(c, key));
					}
				} else if (stack[indent - 1] instanceof Hub) {
					Hub h = (Hub) stack[indent - 1];
					if (object != null) {
						h.add(object);
					} else {
						// note: should not ever need a Holder
//qqqqqqqqqqqqqqqqqqqqqq removing for 4.0						
//						h.add(key);
					}
				} else if (indent > 3) {
					// use this value when updating property
					bUseRef = true;
					if (object != null) {
						refValue = object;
					} else if (guid != null) {
						refValue = XML_GUID + guid;
					} else if (matchProps == null || matchProps.length == 0) {
						refValue = key;
					} else {
						// 20150730
						refValue = new Holder(c, key);
					}
				}
			} else {
				// create object, only load objectId properties
				if (matchProps == null || matchProps.length == 0) {
					if (object == null && ids != null && ids.length > 0) {
						if (object == null) {
							OADataSource ds = OARuntime.datasource().get(c);
							if (ds != null) object = ds.getObject(c, key);
						}
					}
				}

				if (object == null) {
					final OAThreadLocalService srvcOAThreadLocal = ((OAThreadService) OARuntime.thread()).getThreadLocalService();  
					try {
						srvcOAThreadLocal.setLoading(true);
						object = createNewObject(c);
						// set property ids
						if (matchProps == null || matchProps.length == 0) {
							for (int i = 0; ids != null && i < ids.length; i++) {
								values[i] = getValue(object, ids[i], values[i]); // hook method for subclass
								object.setProperty(ids[i], values[i]);
							}
						} else {
							// 20150730
							HashMap<OAObjectKey, OAObject> hm = hmMatch.get(c);
							if (hm == null) {
								hm = new HashMap<OAObjectKey, OAObject>();
								hmMatch.put(c, hm);
							}
							hm.put(key, object);
						}
					} catch (Exception e) {
						throw new SAXException("cant create object for class " + c.getName() + " Error:" + e, e);
					} finally {
						srvcOAThreadLocal.setLoading(false);
					}
				} else {
				}

				if (guid != null) {
					hashGuid.put(guid, object);
				}

				boolean bIncomplete = true;
				if (stack[indent - 1] == null) {
					bIncomplete = false;
				} else if (stack[indent - 1] instanceof Hub) {
					bIncomplete = false;
				}

				if (bIncomplete) {
					hash.put(XML_OBJECT, object);
					vecIncomplete.addElement(hash);
				} else {
					// 20150730 use two passes
					int x = vecIncomplete.size();
					Vector vec = new Vector();
					for (int i = 0; i < x; i++) {
						Hashtable hashx = (Hashtable) vecIncomplete.elementAt(i);
						OAObject oaobj = (OAObject) hashx.get(XML_OBJECT);
						if (!processProperties(oaobj, hashx)) {
							vec.add(hashx);
						}
					}
					// second pass
					x = vec.size();
					for (int i = 0; i < x; i++) {
						Hashtable hashx = (Hashtable) vec.elementAt(i);
						OAObject oaobj = (OAObject) hashx.get(XML_OBJECT);
						if (!processProperties(oaobj, hashx)) {
							System.out.println("OAXMLReader read error: did not process all props");
						}
					}

					if (!processProperties(object, hash)) {
						System.out.println("OAXMLReader read error: did not process all props 2");
					}

					x = vecIncomplete.size();
					for (int i = 0; i < x; i++) {
						Hashtable hashx = (Hashtable) vecIncomplete.elementAt(i);
						OAObject oaobj = (OAObject) hashx.get(XML_OBJECT);
						endObject(oaobj, true);

						Object objx = getRealObject(oaobj);
						if (firstObject == oaobj) {
							replaceRootObject(firstObject, objx);
							firstObject = objx;
						}
					}
					endObject(object, false);
					Object objx = getRealObject(object);
					if (firstObject == object) {
						replaceRootObject(firstObject, objx);
						firstObject = objx;
					}

					vecIncomplete.removeAllElements();
				}

				if (stack[indent - 1] == null) {
					vecRoot.add(object);
					if (firstObject == null) {
						firstObject = object;
					}
				}
				if (stack[indent - 1] instanceof Vector) {
					Vector vec = (Vector) stack[indent - 1];
					vec.addElement(object);
				} else if (stack[indent - 1] instanceof Hub) {
					Hub h = (Hub) stack[indent - 1];
					h.add(object);
				} else if (indent > 3) {
					// use this value when updating property
					bUseRef = true;
					refValue = object;
				}

			}
		} else if (stackobj == null) {
		} else if (stackobj instanceof Vector) {
		} else if (stackobj instanceof Hub) { // root level Hub
		} else { // String (Property Name)
			Hashtable hash = (Hashtable) stack[indent - 1];
			if (!(hash.get(eName) instanceof Vector)) {
				processProperty(eName, value, conversionClass, hash);
			} // else it was a Hub property
			conversionClass = null;
		}

		indent--;
		p("/" + eName);
	}

	/**
	 * Processes and stores a property value for the current object being parsed.
	 *
	 * @param eName the property name
	 * @param value the string value
	 * @param conversionClass optional class used for type conversion
	 * @param hash the hash table holding parsed values
	 */
	protected void processProperty(String eName, String value, Class conversionClass, Hashtable hash) {
		Object objValue = value;

		if (bUseRef) {
			bUseRef = false;
			objValue = refValue;
		} else {
			if (decodeMessage != null && value != null && value.startsWith(decodeMessage)) {
				objValue = Base64.decode(value.substring(decodeMessage.length()));
			}
		}
		// p(""+objValue);
		if (objValue != null) {
			hash.put(eName.toUpperCase(), objValue);
		}
	}

	/**
	 * Returns the property name to use when setting a value on an object.
	 *
	 * @param obj the target object
	 * @param propName the property name from XML
	 * @return the resolved property name, or {@code null} to ignore the property
	 */
	protected String getPropertyName(OAObject obj, String propName) {
		return propName;
	}

	/**
	 * Processes all stored properties and applies them to the specified object.
	 *
	 * @param object the target object
	 * @param hash the hash table containing parsed property values
	 * @return {@code true} if all properties were processed successfully
	 */
	protected boolean processProperties(OAObject object, Hashtable hash) {
		Class c = (Class) hash.get(XML_CLASS);
		Object objx = hash.remove(XML_OBJECT);
		String guid = (String) hash.remove(XML_GUID);

		boolean b = _processProperties(object, hash);
		hash.put(XML_CLASS, c);
		if (objx != null) {
			hash.put(XML_OBJECT, objx);
		}
		if (guid != null) {
			hash.put(XML_GUID, guid);
		}
		return b;
	}

	/**
	 * Internal method that applies parsed property values to an object.
	 *
	 * @param object the target object
	 * @param hash the hash table containing parsed property values
	 * @return {@code true} if all properties were processed successfully
	 */
	private boolean _processProperties(final OAObject object, Hashtable hash) {
		if (object == null) {
			return false;
		}
		boolean bResult = true;
		boolean bLoadingObject = false;
		boolean bWas = false;
		try {
			if (object.getNew()) {
				bLoadingObject = true;
				final OAThreadLocalService srvcOAThreadLocal = ((OAThreadService) OARuntime.thread()).getThreadLocalService();  

				final OAGraphInternal og = (OAGraphInternal) OARuntime.graph(object);
				if (og.objectsInternal().callObjectCSIsServer(object)) {
					bWas = srvcOAThreadLocal.getSendSyncMessages();
					srvcOAThreadLocal.setSendSyncMessages(false);
					// no, needs to have OAObjectEventDelegate.firePropertyChange() process property changes
					//   since it has already created the object w/o setLoading(true), which means that there are null primitive properties
					//     that would not be "unset" if firePropertyChange() was not ran.
				}
			}
			final Class c = (Class) hash.remove(XML_CLASS);
			final OAGraphInternal og = (OAGraphInternal) OARuntime.graph(c);
			OAObjectInfo oi = og.objectsInternal().callObjectInfoGetOAObjectInfo(c);
			
			Enumeration enumx = hash.keys();

			for (; enumx.hasMoreElements();) {
				Object k = enumx.nextElement();
				Object v = hash.get(k);
				if (v == object) {
					continue;
				}

				k = getPropertyName(object, (String) k);
				if (k == null) {
					continue;
				}
				// 20150730
				if (v instanceof Holder) {
					Holder h = (Holder) v;
					HashMap<OAObjectKey, OAObject> hm = hmMatch.get(h.c);
					if (hm != null) {
						v = hm.get(h.key);
					}
				}

				if (v instanceof Vector) {
					if (!bResult) {
						continue;
					}
					Vector vec = (Vector) v;

					// change guid objects to real objects
					int x = vec.size();
					for (int ix = 0; ix < x; ix++) {
						Object o = vec.elementAt(ix);
						if (o instanceof String && ((String) o).startsWith(XML_GUID)) {
							String guid = ((String) o).substring(XML_GUID.length());
							o = hashGuid.get(guid);
							if (o == null) {
								bResult = false;
								//System.out.println("Error: could not find object in hashGuid *****");//qqqqqqq
							} else {
								vec.set(ix, o); // replace
							}
						} else if (o instanceof Holder) {
							// 20150730
							Holder h = (Holder) o;
							HashMap<OAObjectKey, OAObject> hm = hmMatch.get(h.c);
							if (hm != null) {
								o = hm.get(h.key);
								if (o == null) {
									bResult = false;
								} else {
									vec.set(ix, o); // replace
								}
							}
						}
					}

					// 2006/05/22 was: Hub h = object.getHub((String)k);
					Hub h = (Hub) object.getProperty((String) k);
					if (h == null) {
						if (vec.size() > 0) {
							System.out.println("ERROR in OAXMLReader: Object:" + object + " Property:" + k
									+ "  error:returned null value, should be a Hub");
						}
					} else {
						h.loadAllData();
						// remove objects in Hub that are not in Vector
						for (int i = 0;; i++) {
							Object obj = h.elementAt(i);
							if (obj == null) {
								break;
							}
							if (vec.indexOf(obj) < 0) {
								h.remove(obj);
								vecRemoved.addElement(obj);
								i--;
							}
						}

						// add objects in Vector that are not in Hub
						x = vec.size();
						for (int ix = 0; ix < x; ix++) {
							Object o = vec.elementAt(ix);

							if (o instanceof Holder) {
								Holder hx = (Holder) o;
								HashMap<OAObjectKey, OAObject> hm = hmMatch.get(hx.c);
								if (hm != null) {
									o = hm.get(hx.key);
								} else {
									// 20150730 should not happen, this can be removed later
									System.out.println("OAXMLReader error, value was not in hmMatch");
									continue; //qqq
								}
							}

							if (h.getObject(o) == null) {
								h.add((OAObject)o);
							}
							// position objects in Hub to match order of objects in Vector
							int pos = h.getPos(o);
							if (pos != ix) {
								h.move(pos, ix);
							}
						}
					}
				} else if (og.objectsInternal().callObjectInfoIsHubProperty(oi, (String) k)) {
					// empty hub, otherwise "v" would have been a Vector
				} else if (v != null && (v instanceof String) && ((String) v).startsWith(XML_GUID)) {
					String guid = ((String) v).substring(XML_GUID.length());
					v = hashGuid.get(guid);
					if (v == null) {
						bResult = false;
						// System.out.println("Error: could not find object in hashGuid *****");//qqqqqqq
					} else {
						v = getValue(object, (String) k, v); // hook method for subclass
						object.setProperty((String) k, v);
					}
				} else if (v instanceof OAObjectKey) {
					// try to find "real" object
					Class cx = og.objectsInternal().callObjectInfoGetPropertyClass(c, (String) k);
					final OAGraphInternal og2 = (OAGraphInternal) OARuntime.graph(cx);
					v = og2.objectsInternal().callObjectCacheGet(cx, (OAObjectKey) v);
					if (v == null) {
						bResult = false;
					} else {
						v = getValue(object, (String) k, v); // hook method for subclass
						object.setProperty((String) k, v);
					}
				} else {
					if (v instanceof String) {
						Class cx = og.objectsInternal().callObjectInfoGetPropertyClass(c, (String) k);
						if (cx != null && !cx.equals(String.class)) {
							v = convertToObject((String) k, (String) v, cx);
						}
					}
					v = getValue(object, (String) k, v); // hook method for subclass
					object.setProperty((String) k, v);
				}
			}
		} finally {
			if (bLoadingObject) {
				if (bResult) {
					object.afterLoad();
				}
				final OAThreadLocalService srvcOAThreadLocal = ((OAThreadService) OARuntime.thread()).getThreadLocalService();  
				srvcOAThreadLocal.setLoading(false);
				final OAGraphInternal og = (OAGraphInternal) OARuntime.graph(object);
				if (og.objectsInternal().callObjectCSIsServer(object)) {
					srvcOAThreadLocal.setSendSyncMessages(bWas);
				}
			}
		}
		return bResult;
	}

	/**
	 * SAX callback invoked when character data is encountered within an element.
	 *
	 * @param buf the character buffer
	 * @param offset the start offset in the buffer
	 * @param len the number of characters
	 * @throws SAXException if a parsing error occurs
	 */
	public void characters(char buf[], int offset, int len) throws SAXException {
		if (bWithinTag && value != null) {
			String s = new String(buf, offset, len);
			value += OAString.decodeIllegalXml(s);
		}
	}

	private int holdIndent;
	private String sIndent = "";

	/**
	 * Outputs debug information for XML parsing with indentation.
	 *
	 * @param s the message to output
	 */
	void p(String s) {
		if (true) {
			return;
		}
		if (indent != holdIndent) {
			holdIndent = indent;
			sIndent = "";
			for (int i = 0; i < indent; i++) {
				sIndent += "  ";
			}
		}
		System.out.println(sIndent + s);
	}

	// ============== These methods can be overwritten to get status of parsing ================

	/**
	 * Returns the value to use when assigning a property to an object.
	 *
	 * @param obj the target object
	 * @param name the property name
	 * @param value the parsed value
	 * @return the value to assign
	 */
	public Object getValue(OAObject obj, String name, Object value) {
		return value;
	}

	/**
	 * Callback invoked when a Hub element is started.
	 *
	 * @param className the object class name for the hub
	 * @param total the expected number of elements in the hub
	 */
	public void startHub(String className, int total) {
	}

	/**
	 * Callback invoked when an object has completed parsing.
	 *
	 * @param obj the completed object
	 * @param hasParent {@code true} if the object has a parent
	 */
	public void endObject(OAObject obj, boolean hasParent) {
	}

	/**
	 * SAX callback invoked at the start of the XML document.
	 */
	public void startDocument() throws SAXException {
	}

	/**
	 * SAX callback invoked at the end of the XML document.
	 */
	public void endDocument() throws SAXException {
	}

	/**
	 * Creates a new instance of the specified OAObject class.
	 *
	 * @param c the class to instantiate
	 * @return a new OAObject instance
	 * @throws Exception if object creation fails
	 */
	public OAObject createNewObject(Class c) throws Exception {
		return (OAObject) c.newInstance();
	}

	/**
	 * Converts a string value to the specified property type.
	 *
	 * @param propertyName the property name
	 * @param value the string value
	 * @param propertyClass the target class for conversion
	 * @return the converted value, or {@code null} to skip assignment
	 */
	public Object convertToObject(String propertyName, String value, Class propertyClass) {
		if (propertyClass == null) {
			return value;
		}
		if (String.class.equals(propertyClass)) {
			return value;
		}

		Object result = OAConverter.convert(propertyClass, value);

		return result;
	}

	/**
	 * Returns the resolved object instance to use after loading.
	 *
	 * @param object the loaded object
	 * @return the resolved object instance
	 */
	protected Object getRealObject(OAObject object) {
		final OAGraphInternal og = (OAGraphInternal) OARuntime.graph(object.getClass());
		Object obj = og.objectsInternal().callObjectCacheGetObject(object.getClass(), og.objectsInternal().callObjectKeyGetKey(object));
		if (obj != null) {
			return obj;
		}
		return object;
	}

	/**
	 * Resolves or transforms a class name found in XML before object creation.
	 *
	 * @param className the class name from XML
	 * @return the resolved class name
	 */
	protected String resolveClassName(String className) {
		return className;
	}

}
