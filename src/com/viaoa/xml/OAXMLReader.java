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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import com.viaoa.compare.OACompare;
import com.viaoa.converter.OAConv;
import com.viaoa.converter.OAConverter;
import com.viaoa.filter.OAFilter;
import com.viaoa.graph.OAGraph;
import com.viaoa.graph.OAGraphInternal;
import com.viaoa.graph.service.object.OAObjectCacheService;
import com.viaoa.graph.service.object.OAObjectInfoService;
import com.viaoa.graph.service.object.OAObjectKeyService;
import com.viaoa.hub.Hub;
import com.viaoa.lang.OAString;
import com.viaoa.metadata.OALinkInfo;
import com.viaoa.metadata.OAObjectInfo;
import com.viaoa.metadata.OAPropertyInfo;
import com.viaoa.object.OAObject;
import com.viaoa.object.OAObjectKey;
import com.viaoa.runtime.OARuntime;
import com.viaoa.runtime.OAThreadLocalService;
import com.viaoa.runtime.OAThreadService;
import com.viaoa.secure.Base64;
import com.viaoa.select.OASelect;

/**
 * SAX-based XML reader capable of reconstructing full OAObject graphs from the
 * XML format produced by {@link OAXMLWriter}.  
 * <p>
 * {@code OAXMLReader} supports:
 * <ul>
 *   <li>GUID-based identity resolution,</li>
 *   <li>ID-property reconstruction via {@link OAObjectKey},</li>
 *   <li>import-match rules defined on the OA model,</li>
 *   <li>Hub population for MANY-valued links,</li>
 *   <li>encrypted CDATA blocks (via {@link #setDecodeMessage}),</li>
 *   <li>polymorphic object creation based on XML class attributes.</li>
 * </ul>
 *
 * <h2>Loading Process</h2>
 * <ol>
 *   <li><b>Parse Phase</b>: Builds nested HashMaps representing XML hierarchy.</li>
 *   <li><b>Preload Pass</b>: Creates OAObjects, reads IDs, allocates GUIDs.</li>
 *   <li><b>Load Pass</b>: Populates properties, links, and Hub memberships.</li>
 * </ol>
 *
 * <h2>Legacy Compatibility</h2>
 * If the root element is {@code &lt;OAXML version="1.x"&gt;} the reader delegates
 * to {@link OAXMLReader1}.
 *
 * <h2>Customization Hooks</h2>
 * Subclasses may override:
 * <ul>
 *   <li>{@link #convertToObject(String, String, Class)}</li>
 *   <li>{@link #getObject(Class, HashMap)}</li>
 *   <li>{@link #beforeLoadObject(OAObject, HashMap)}</li>
 *   <li>{@link #afterLoadObject(OAObject, HashMap)}</li>
 *   <li>{@link #resolveClassName(String)}</li>
 * </ul>
 */
public class OAXMLReader {
	
	/**
	 * Name of the XML file currently being read.
	 */
	private String fileName;
	
	/**
	 * Accumulates character data read within the current XML element.
	 */
	protected String value;
	
	/**
	 * Current indentation level used to track XML element nesting.
	 */
	protected int indent;
	
	/**
	 * Counter used during XML parsing to track total elements processed.
	 */
	protected int total;
	
	/**
	 * Flag indicating whether the parser is currently within an XML element tag.
	 */
	protected boolean bWithinTag;
	
	/**
	 * Stack used to maintain parsing state and hierarchical element data.
	 */
	protected Object[] stack = new Object[10];
	
	/**
	 * Optional decode key used to decrypt encoded XML character data.
	 */
	private String decodeMessage;
	
	/**
	 * Internal key used to store XML object ID values.
	 */
	private static final String XML_ID = "XML_ID";
	
	/**
	 * Internal key used to store XML ID reference values.
	 */
	private static final String XML_IDREF = "XML_IDREF";
	
	/**
	 * Internal key used to store element text values.
	 */
	private static final String XML_VALUE = "XML_VALUE";
	
	/**
	 * Internal key used to store class name information from XML attributes.
	 */
	private static final String XML_CLASS = "XML_CLASS";
	
	/**
	 * Internal key used to store the instantiated OAObject for an XML element.
	 */
	private static final String XML_OBJECT = "XML_OBJECT";
	
	/**
	 * Internal key used to store Hub instances created during parsing.
	 */
	private static final String XML_HUB = "XML_HUB";
	
	/**
	 * Target class used when converting string values to typed property values.
	 */
	protected Class conversionClass; // type of class that value needs to be converted to

	/**
	 * Map of GUID values to OAObject instances for identity resolution.
	 */
	protected HashMap<String, OAObject> hashGuid;

	/**
	 * Delegate reader used for legacy OAXML version 1 documents.
	 */
	private OAXMLReader1 xmlReader1;

	/**
	 * Flag indicating whether import matching rules should be applied during loading.
	 */
	private boolean bImportMatching = true;

	/**
	 * SAX default handler used to receive XML parsing callbacks.
	 */
	private MyDefaultHandler myDefaultHandler;

	// flag to know if OAXMLWriter wrote the object, which adds an additional tag for the start of each object.
	/**
	 * Detected OAXML document version used to control parsing behavior.
	 */
	private int versionOAXML;

	
	
	/**
	 * Creates a new XML reader instance.
	 */
	public OAXMLReader() {
	}

	/**
	 * Reads XML data from a file and loads it into OAObjects.
	 *
	 * @param fileName the path or URI of the XML file to read
	 * @return an array of loaded root objects
	 * @throws Exception if parsing or loading fails
	 */
	public Object[] readFile(String fileName) throws Exception {
		parseFile(fileName);
		ArrayList al = process();
		Object[] objs = new Object[al.size()];
		al.toArray(objs);
		return objs;
	}

	/**
	 * Reads XML data from a {@link File} and loads it into OAObjects.
	 *
	 * @param file the file containing XML data
	 * @return an array of loaded root objects
	 * @throws Exception if parsing or loading fails
	 */
	public Object[] read(File file) throws Exception {
		return readFile(file.getPath());
	}

	/**
	 * Reads XML data from a string and loads it into OAObjects.
	 *
	 * @param xmlText the XML text to parse
	 * @return an array of loaded root objects
	 * @throws Exception if parsing or loading fails
	 */
	public Object[] readXML(String xmlText) throws Exception {
		parseString(xmlText);
		ArrayList al = process();
		Object[] objs = new Object[al.size()];
		al.toArray(objs);
		return objs;
	}

	/**
	 * Enables or disables import matching rules during object loading.
	 *
	 * @param b {@code true} to enable import matching; {@code false} to disable
	 */
	public void setImportMatching(boolean b) {
		this.bImportMatching = b;
		if (xmlReader1 != null) {
			xmlReader1.setImportMatching(b);
		}
	}

	/**
	 * Returns whether import matching rules are enabled.
	 *
	 * @return {@code true} if import matching is enabled
	 */
	public boolean getImportMatching() {
		return this.bImportMatching;
	}

	/**
	 * Sets the decode message used to decrypt encoded XML values.
	 *
	 * @param msg the decode message string
	 * @throws IllegalArgumentException if the message is an empty string
	 */
	public void setDecodeMessage(String msg) {
		if (msg != null && msg.length() == 0) {
			throw new IllegalArgumentException("DecodeMessage cant be an empty string");
		}
		decodeMessage = msg;
		if (xmlReader1 != null) {
			xmlReader1.setDecodeMessage(msg);
		}
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
	 * Resets all internal parsing state prior to a new parse operation.
	 */
	protected void reset() {
		indent = 0;
		total = 0;
		bWithinTag = false;
		hashGuid = new HashMap();
		versionOAXML = 0;
		xmlReader1 = null;
	}

	/**
	 * Parses an XML file and builds internal data structures for object loading.
	 *
	 * @param fileName the path or URI of the XML file
	 * @throws Exception if parsing fails
	 */
	protected void parseFile(String fileName) throws Exception {
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

		SAXParserFactory factory = SAXParserFactory.newInstance();
		SAXParser saxParser = factory.newSAXParser();
		saxParser.parse(uri.toString(), this.getDefaultHandler());

		Object[] objs = new Object[indent + 1];
		System.arraycopy(stack, 0, objs, 0, indent + 1);
		stack = objs;
	}

	/**
	 * Parses XML data from a string and builds internal data structures
	 * for object loading.
	 *
	 * @param xmlData the XML text to parse
	 * @throws Exception if parsing fails
	 */
	public void parseString(String xmlData) throws Exception {
		if (xmlData == null) {
			throw new IllegalArgumentException("xmlData is required");
		}
		reset();

		SAXParserFactory factory = SAXParserFactory.newInstance();
		SAXParser saxParser = factory.newSAXParser();

		saxParser.parse(new StringBufferInputStream(xmlData), this.getDefaultHandler());
	}

	/**
	 * Processes parsed XML data and constructs the final list of loaded objects.
	 *
	 * @return a list of loaded objects
	 * @throws Exception if object creation or linking fails
	 */
	protected ArrayList process() throws Exception {
		if (xmlReader1 != null) {
			ArrayList<OAObject> al = new ArrayList<OAObject>();
			for (Object objx : xmlReader1.getRootObjects()) {
				if (objx instanceof Hub) {
					for (Object obj : ((Hub) objx)) {
						al.add((OAObject) obj);
					}
					break;
				}
				al.add((OAObject) objx);
			}
			return al;
		}
		ArrayList<Object> al = _process();
		hashGuid = new HashMap();
		return al;
	}

	/**
	 * Performs a two-pass load of root-level XML elements to create
	 * and fully populate objects.
	 *
	 * @return a list of loaded objects
	 * @throws Exception if processing fails
	 */
	protected ArrayList<Object> _process() throws Exception {
		final ArrayList alReturn = new ArrayList<Object>();
		HashMap<String, Object> hm = (HashMap) stack[1];

		// uses a two pass, the 2nd is to match the idrefs and load the data
		for (int i = 0; i < 2; i++) {

			for (Map.Entry<String, Object> e : hm.entrySet()) {
				String key = e.getKey();
				Object v = e.getValue();
				if (v instanceof HashMap) {
					Object objx = _processRoot(key, (HashMap) v, i == 0);
					if (i > 0 && objx != null) {
						alReturn.add(objx);
					}
				} else if (v instanceof ArrayList) {
					for (HashMap<String, Object> hmx : (ArrayList<HashMap<String, Object>>) v) {
						Object objx = _processRoot(key, hmx, i == 0);
						if (i > 0 && objx != null) {
							alReturn.add(objx);
						}
					}
				}
			}
		}
		return alReturn;
	}

	/**
	 * Processes a root-level XML element and creates the corresponding object or hub.
	 *
	 * @param key the root XML element name
	 * @param hm the parsed data for the element
	 * @param bIsPreloading {@code true} if in preload pass
	 * @return the created root object or hub
	 * @throws Exception if object creation fails
	 */
	protected Object _processRoot(String key, HashMap<String, Object> hm, final boolean bIsPreloading) throws Exception {
		if (!"HUB".equalsIgnoreCase(key)) {
			OAObject oaObj = _processChildren(hm, null, bIsPreloading, 0);
			return oaObj;
		}

		Class toClass = null;
		String cname = (String) hm.get(XML_CLASS);
		if (!OAString.isEmpty(cname)) {
			cname = resolveClassName(cname);
			toClass = Class.forName(cname);
		}
		if (toClass == null) {
			toClass = OAObject.class;
		}

		Hub hub = (Hub) hm.get(XML_HUB);
		if (hub == null) {
			hub = new Hub(toClass);
		}

		for (Map.Entry<String, Object> e : hm.entrySet()) {
			Object v = e.getValue();
			if (v instanceof HashMap) {
				Object objx = _processChildren((HashMap) v, toClass, bIsPreloading, 0);
				if (!bIsPreloading && objx != null) {
					hub.add((OAObject) objx);
				}
			} else if (v instanceof ArrayList) {
				for (HashMap<String, Object> hmx : (ArrayList<HashMap<String, Object>>) v) {
					Object objx = _processChildren(hmx, toClass, bIsPreloading, 0);
					if (!bIsPreloading && objx != null) {
						hub.add((OAObject) objx);
					}
				}
			}
		}
		return hub;
	}

	/**
	 * Recursively processes child XML elements and populates object properties and links.
	 *
	 * @param hm parsed element data
	 * @param toClass target object class
	 * @param bIsPreloading {@code true} if in preload pass
	 * @param level current recursion depth
	 * @return the created or resolved {@link OAObject}
	 * @throws Exception if object creation or linking fails
	 */
	protected OAObject _processChildren(HashMap<String, Object> hm, Class<? extends OAObject> toClass, final boolean bIsPreloading,
			final int level) throws Exception {
		OAObject objNew = null;
		if (toClass == null) {
			toClass = OAObject.class;
		}

		String guid = (String) hm.get(XML_ID);
		boolean bKeyOnly = false;
		if (guid == null) {
			guid = (String) hm.get(XML_IDREF);
			if (guid != null) {
				bKeyOnly = true;
			}
		}

		objNew = hashGuid.get(guid);
		if (bKeyOnly) {
			return objNew;
		}

		if (objNew == null && !bIsPreloading) {
			objNew = (OAObject) hm.get(XML_OBJECT);
		}
		if (objNew != null) {
			toClass = objNew.getClass();
		} else {
			String cname = (String) hm.get(XML_CLASS);
			if (!OAString.isEmpty(cname)) {
				cname = resolveClassName(cname);
				toClass = (Class<? extends OAObject>) Class.forName(cname);
			}
		}

		final OAGraphInternal og = (OAGraphInternal) OARuntime.graph(toClass);
		OAObjectInfo oi = og.objectsInternal().callObjectInfoGetOAObjectInfo(toClass);

		if (objNew == null) {
			objNew = getObject(toClass, hm);
		}

		if (objNew == null) {
			// try to find using pkey props, AND remove pkey properties from hash
			String[] ids = oi.getIdProperties();
			Object[] values = new Object[ids == null ? 0 : ids.length];
			for (int i = 0; i < ids.length; i++) {
				String id = ids[i].toUpperCase();
				Class c2 = og.objectsInternal().callObjectInfoGetPropertyClass(toClass, id);
				values[i] = hm.get(id);
				if (values[i] instanceof String) {
					values[i] = OAConverter.convert(c2, values[i]);
				}
				hm.remove(id);
			}
			UUID iguid = null;
			if (guid != null && guid.length() > 1) {
// qqqqqqqq 20260110				
				if (guid.length() < 8) {
					iguid = UUID.randomUUID();
				}
				else {
					iguid = UUID.fromString(guid.substring(1));
				}
			}

			// try to find using matching props
			final String[] matchProps = getImportMatching() ? oi.getImportMatchPropertyNames() : null;
			final String[] matchPropPaths = getImportMatching() ? oi.getImportMatchPropertyPaths() : null;
			List<Object> al = new ArrayList<>();
			if (matchProps != null && matchProps.length > 0) {
				for (int i = 0; i < matchProps.length; i++) {
					if (matchPropPaths[i].indexOf('.') > 0) {
						continue;
					}
					String id = matchProps[i].toUpperCase();
					Class c2 = og.objectsInternal().callObjectInfoGetPropertyClass(toClass, id);

					Object val = hm.get(id);

					if (val instanceof HashMap) {
						val = _processChildren((HashMap) val, c2, true, level + 1);
					} else if (val instanceof String) {
						val = OAConverter.convert(c2, val);
					}
					al.add(val);
				}
				final Object[] matchValues = al.toArray(new Object[al.size()]);

				OASelect sel = new OASelect(toClass);
				sel.setFilter(new OAFilter() {
					@Override
					public boolean isUsed(Object obj) {
						if (!(obj instanceof OAObject)) {
							return false;
						}
						int pos = 0;
						for (int i = 0; i < matchProps.length; i++) {
							if (matchPropPaths[i].indexOf('.') > 0) {
								continue;
							}
							Object val1 = ((OAObject) obj).getProperty(matchProps[i]);
							if (!OACompare.isEqual(val1, matchValues[pos++])) {
								return false;
							}
						}
						return true;
					}
				});
				sel.select();
				objNew = sel.next();
				sel.close();
			} else {
				if (ids != null && ids.length > 0) {
					final OAObjectKey key = new OAObjectKey(values, iguid);
					objNew = og.objectsInternal().callObjectCacheGet(toClass, key);
				}
			}

			if (objNew == null) {
				final OAThreadLocalService srvcOAThreadLocal = ((OAThreadService) OARuntime.thread()).getThreadLocalService();  
				srvcOAThreadLocal.setLoading(true);
				try {
					objNew = createNewObject(toClass);
//qqqqqqqqqqqqqq 20260111
					if (objNew.getGuid() == null) {
						og.objectsInternal().callObjectGuidSetGuid(objNew, iguid);
					}
					
					
					// set property ids
					if (matchProps == null || matchProps.length == 0) {
						for (int i = 0; ids != null && i < ids.length; i++) {
							Object v = getValue(objNew, ids[i], values[i]); // hook method for subclass
							objNew.setProperty(ids[i], v);
						}
					}
				} finally {
					srvcOAThreadLocal.setLoading(false);
				}
//qqqqqqqqqqqqqq 20260111
				og.objectsInternal().callObjectInitializeInitializeAfterLoading(objNew);
				
				// 20181115
//qqqqqq 20260111 was:	OAObjectCacheDelegate.add(objNew);
			}
			if (guid != null && objNew != null) {
				hashGuid.put(guid, objNew);
			}
			hm.put(XML_OBJECT, objNew);
		}

		final boolean bLoadingNew = objNew.getNew() && !bIsPreloading;
		if (bLoadingNew) {
			final OAThreadLocalService srvcOAThreadLocal = ((OAThreadService) OARuntime.thread()).getThreadLocalService();  
			srvcOAThreadLocal.setLoading(true);
		}

		if (!bIsPreloading) {
			beforeLoadObject(objNew, hm);
		}
		for (Map.Entry<String, Object> e : hm.entrySet()) {
			String k = e.getKey();

			if (XML_VALUE.equals(k)) {
				continue;
			}
			if (XML_ID.equals(k)) {
				continue;
			}
			if (XML_IDREF.equals(k)) {
				continue;
			}
			if (XML_CLASS.equals(k)) {
				continue;
			}
			if (XML_OBJECT.equals(k)) {
				continue;
			}

			Object v = e.getValue();
			v = getValue(objNew, k, v); // hook method for subclass

			if (v instanceof String) {

				// convert to correct type in case of enum and method overloading
				OAPropertyInfo pi = oi.getPropertyInfo(k);
				if (pi != null) {
					v = OAConv.convert(pi.getClassType(), v);
				}

				// set prop
				if (!bIsPreloading) {
					objNew.setProperty(k, v);
				}
				continue;
			}

			OALinkInfo li = oi.getLinkInfo(k);

			if (v instanceof HashMap && (li == null || li.getType() == li.MANY)) {
				// check to see if it has an arrayList or a Many property, making this a hub prop
				//   and skip this tag (outer collection tag) to get the the objects in it.
				HashMap<String, Object> hmx = (HashMap<String, Object>) v;
				for (Map.Entry<String, Object> ex : hmx.entrySet()) {
					Object vx = ex.getValue();
					if (vx instanceof ArrayList) {
						v = vx;
						break;
					}
					if (vx instanceof HashMap) { // hub with only one
						ArrayList al = new ArrayList();
						al.add(vx);
						v = al;
						break;
					}
				}
			}

			final OAThreadLocalService srvcOAThreadLocal = ((OAThreadService) OARuntime.thread()).getThreadLocalService();  
			if (v instanceof ArrayList) {
				// load into Hub
				Hub h;
				if (bIsPreloading) {
					h = null;
				} else if (li == null) {
					h = new Hub(OAObject.class);
				} else {
					h = (Hub) li.getValue(objNew);
					if (h == null) {
						h = new Hub(OAObject.class);
					}
				}

				for (HashMap hmx : (ArrayList<HashMap>) v) {
					Object objx = null;
					try {
						if (bLoadingNew) {
							srvcOAThreadLocal.setLoading(false);
						}
						objx = _processChildren(hmx, li == null ? OAObject.class : li.getToClass(), bIsPreloading, level + 1);
					} finally {
						if (bLoadingNew) {
							srvcOAThreadLocal.setLoading(true);
						}
					}

					if (!bIsPreloading) {
						h.add((OAObject) objx);
					}
				}

				if (li == null && !bIsPreloading) {
					objNew.setProperty(k, h);
				}
			} else {
				// hashmap for another object
				HashMap<String, Object> hmx = (HashMap<String, Object>) v;
				Class c = li == null ? OAObject.class : li.getToClass();
				if (bLoadingNew) {
					srvcOAThreadLocal.setLoading(false);
				}
				Object objx = _processChildren(hmx, c, bIsPreloading, level + 1);
				if (bLoadingNew) {
					srvcOAThreadLocal.setLoading(true);
				}
				if (!bIsPreloading) {
					objNew.setProperty(k, objx);
				}
			}
		}
		if (bLoadingNew) {
			final OAThreadLocalService srvcOAThreadLocal = ((OAThreadService) OARuntime.thread()).getThreadLocalService();  
			srvcOAThreadLocal.setLoading(false);
		}
		if (!bIsPreloading) {
			objNew = getRealObject(objNew);
			if (objNew != null) {
				endObject(objNew, level > 0);
				afterLoadObject(objNew, hm);
			}
		}
		return objNew;
	}

	/**
	 * SAX callback invoked at the start of an XML element.
	 *
	 * @param namespaceURI the namespace URI
	 * @param sName the local name
	 * @param qName the qualified name
	 * @param attrs the element attributes
	 * @throws SAXException if parsing fails
	 */
	protected void startElement(String namespaceURI, String sName, String qName, Attributes attrs) throws SAXException {
		if (xmlReader1 != null) {
			xmlReader1.startElement(namespaceURI, sName, qName, attrs);
			return;
		}
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
				// ex:  <OAXML VERSION='2.0' DATETIME='08/12/2015 11:56AM'>
				String version = null;
				if (attrs != null) {
					for (int i = 0; i < attrs.getLength(); i++) {
						String aName = attrs.getLocalName(i);
						if (!"version".equalsIgnoreCase(aName)) {
							continue;
						}
						version = attrs.getValue(i);
						if ("2.0".equals(version)) {
							versionOAXML = 2;
						}
						break;
					}
				}
				if (versionOAXML == 1) {
					xmlReader1 = new OAXMLReader1() {
						@Override
						protected String resolveClassName(String className) {
							return OAXMLReader.this.resolveClassName(className);
						}

						@Override
						public Object convertToObject(String propertyName, String value, Class propertyClass) {
							return OAXMLReader.this.convertToObject(propertyName, value, propertyClass);
						}

						@Override
						public OAObject createNewObject(Class c) throws Exception {
							return OAXMLReader.this.createNewObject(c);
						}

						@Override
						public void endObject(OAObject obj, boolean hasParent) {
							OAXMLReader.this.endObject(obj, hasParent);
						}

						@Override
						protected String getPropertyName(OAObject obj, String propName) {
							return OAXMLReader.this.getPropertyName(obj, propName);
						}

						@Override
						protected Object getRealObject(OAObject object) {
							if (object == null) {
								return object;
							}
							final OAGraphInternal og = (OAGraphInternal) OARuntime.graph(object.getClass());
					    	
							OAObject obj = og.objectsInternal().callObjectCacheGetObject(object.getClass(), og.objectsInternal().callObjectKeyGetKey(object));
							if (obj != null) {
								object = obj;
							}
							return OAXMLReader.this.getRealObject(object);
						}

						@Override
						public Object getValue(OAObject obj, String name, Object value) {
							return OAXMLReader.this.getValue(obj, name, value);
						}
					};
					xmlReader1.reset();
					xmlReader1.setDecodeMessage(getDecodeMessage());
					xmlReader1.setImportMatching(getImportMatching());
					xmlReader1.startElement(namespaceURI, sName, qName, attrs);
					return;
				} else if (versionOAXML != 2) {
					throw new RuntimeException("version OAXML " + version + " not supported, current version is 2.0");
				}
			}
			HashMap hm = new HashMap();
			stack[indent] = hm;
			return;
		}

		if (stack.length <= indent + 4) {
			Object[] objs = new Object[indent + 20];
			System.arraycopy(stack, 0, objs, 0, stack.length);
			stack = objs;
		}

		stack[indent++] = eName;
		HashMap hm = new HashMap();
		stack[indent] = hm;

		if (attrs != null) {
			String guid = null;
			boolean bKeyOnly = false;
			for (int i = 0; i < attrs.getLength(); i++) {
				String aName = attrs.getLocalName(i); // Attr name
				if ("".equals(aName)) {
					aName = attrs.getQName(i);
				}
				aName = aName.toUpperCase();
				String aValue = attrs.getValue(i);

				if (aName.equalsIgnoreCase("id")) {
					hm.put(XML_ID, aValue);
				} else if (aName.equalsIgnoreCase("idref")) {
					hm.put(XML_IDREF, aValue);
				} else if (aName.equalsIgnoreCase("class")) {
					hm.put(XML_CLASS, aValue);
				} else if (aName.equalsIgnoreCase("keyonly")) {
					bKeyOnly = true;
				} else if (aName.equalsIgnoreCase("guid")) {
					guid = aValue;
				} else {
					if (aValue == null || aValue.length() == 0) {
						hm.put(aName, "true");
					} else {
						hm.put(aName, aValue);
					}
				}
			}
			if (guid != null) {
				if (!bKeyOnly) {
					hm.put(XML_ID, guid);
				} else {
					hm.put(XML_IDREF, guid);
				}
			}
		}

	}

	/**
	 * SAX callback invoked at the end of an XML element.
	 *
	 * @param namespaceURI the namespace URI
	 * @param sName the local name
	 * @param qName the qualified name
	 * @throws SAXException if parsing fails
	 */
	protected void endElement(String namespaceURI, String sName, String qName) throws SAXException {
		if (xmlReader1 != null) {
			xmlReader1.endElement(namespaceURI, sName, qName);
			return;
		}
		bWithinTag = false;
		String eName = sName; // element name
		if (eName == null || "".equals(eName)) {
			eName = qName; // not namespaceAware
		}
		eName = eName.toUpperCase();

		HashMap hm = (HashMap) stack[indent];

		if (decodeMessage != null && value != null && value.startsWith(decodeMessage)) {
			value = Base64.decode(value.substring(decodeMessage.length()));
		}

		Object insertValue = value;
		if (!hm.isEmpty()) {
			hm.put(XML_VALUE, value);
			insertValue = hm;
		}
		if (indent == 1) {
			return;
		}

		HashMap hmParent = (HashMap) stack[indent - 2];
		Object val = hmParent.get(eName);
		if (val != null) {
			ArrayList al;
			if (!(val instanceof ArrayList)) {
				al = new ArrayList();
				al.add(val);
				hmParent.put(eName, al);
			} else {
				al = (ArrayList) val;
			}
			al.add(insertValue);
		} else {
			hmParent.put(eName, insertValue);
		}
		indent -= 2;
	}

	/**
	 * SAX callback invoked when character data is encountered within an element.
	 *
	 * @param buf the character buffer
	 * @param offset the start offset
	 * @param len the number of characters
	 * @throws SAXException if parsing fails
	 */
	protected void characters(char buf[], int offset, int len) throws SAXException {
		if (xmlReader1 != null) {
			xmlReader1.characters(buf, offset, len);
			return;
		}
		if (bWithinTag && value != null) {
			String s = new String(buf, offset, len);
			value += OAString.decodeIllegalXml(s);
		}
	}

	/**
	 * Flag to enable debug output during XML parsing.
	 */
	public boolean debug;
	private int holdIndent;
	private String sIndent = "";

	/**
	 * Outputs debug information with indentation reflecting parse depth.
	 *
	 * @param s the message to output
	 */
	void p(String s) {
		if (!debug) {
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
	 * Returns the value to use when setting a property during XML loading.
	 * <p>
	 * Subclasses may override this method to transform or filter values.
	 *
	 * @param obj the target object being populated
	 * @param name the property name
	 * @param value the parsed value
	 * @return the value to assign to the property
	 */
	public Object getValue(OAObject obj, String name, Object value) {
		return value;
	}

	/**
	 * SAX callback invoked at the start of the XML document.
	 */
	protected void startDocument() throws SAXException {
	}
	
	/**
	 * SAX callback invoked at the end of the XML document.
	 */
	protected void endDocument() throws SAXException {
		if (xmlReader1 != null) {
			xmlReader1.endDocument();
			return;
		}
	}

	/**
	 * Creates a new instance of the specified OAObject class.
	 *
	 * @param c the class of object to create
	 * @return a new instance of the specified class
	 * @throws Exception if object creation fails
	 */
	public OAObject createNewObject(Class c) throws Exception {
		OAObject obj = (OAObject) c.newInstance();
		return obj;
	}

	/**
	 * Converts a string value to the specified property class type.
	 *
	 * @param propertyName the property name
	 * @param value the string value to convert
	 * @param propertyClass the target class for conversion
	 * @return the converted value, or {@code null} to skip setting the property
	 */
	public Object convertToObject(String propertyName, String value, Class propertyClass) {
		if (propertyClass == null) {
			return value;
		}
		if (String.class.equals(propertyClass)) {
			return value;
		}

		Object result = OAConverter.convert(conversionClass, value);

		return result;
	}

	/**
	 * Returns the real object instance to use after loading.
	 * <p>
	 * Subclasses may override to resolve proxies or cached instances.
	 *
	 * @param object the loaded object
	 * @return the resolved object instance
	 */
	protected OAObject getRealObject(OAObject object) {
		return object;
	}

	/**
	 * Returns an existing object to use instead of creating a new one.
	 *
	 * @param toClass the target object class
	 * @param hm parsed name/value data
	 * @return an existing object, or {@code null} to create a new one
	 */
	protected OAObject getObject(Class toClass, HashMap<String, Object> hm) {
		return null;
	}

	/**
	 * Callback invoked before properties are loaded into an object.
	 *
	 * @param obj the object being loaded
	 * @param hm parsed name/value data
	 */
	protected void beforeLoadObject(OAObject obj, HashMap<String, Object> hm) {
	}

	/**
	 * Callback invoked after an object has been fully loaded.
	 *
	 * @param obj the loaded object
	 * @param hm parsed name/value data
	 */
	protected void afterLoadObject(OAObject obj, HashMap<String, Object> hm) {
		// 20211117 was a no-op
		obj.afterLoad();
	}

	/**
	 * Callback invoked when an object has completed loading.
	 *
	 * @param obj the completed object
	 * @param hasParent {@code true} if the object has a parent
	 */
	protected void endObject(OAObject obj, boolean hasParent) {
	}

	/**
	 * Returns the property name to use when mapping XML elements to object properties.
	 *
	 * @param obj the target object
	 * @param propName the XML property name
	 * @return the resolved property name
	 */
	protected String getPropertyName(OAObject obj, String propName) {
		return propName;
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

	/**
	 * Returns the SAX {@link DefaultHandler} used for XML parsing.
	 *
	 * @return the default SAX handler
	 */
	protected DefaultHandler getDefaultHandler() {
		if (myDefaultHandler == null) {
			myDefaultHandler = new MyDefaultHandler();
		}
		return myDefaultHandler;
	}

	/**
	 * Internal SAX handler that delegates parsing callbacks to {@link OAXMLReader}.
	 */
	class MyDefaultHandler extends DefaultHandler {
		@Override
		public void endDocument() throws SAXException {
			OAXMLReader.this.endDocument();
		}

		@Override
		public void startElement(String uri, String localName, String qName, Attributes attributes) throws SAXException {
			OAXMLReader.this.startElement(uri, localName, qName, attributes);
		}

		@Override
		public void startDocument() throws SAXException {
			OAXMLReader.this.startDocument();
		}

		@Override
		public void characters(char buf[], int offset, int len) throws SAXException {
			OAXMLReader.this.characters(buf, offset, len);
		}

		@Override
		public void endElement(String namespaceURI, String sName, String qName) throws SAXException {
			OAXMLReader.this.endElement(namespaceURI, sName, qName);
			;
		}

	}

	/**
	 * Test entry point for debugging XML parsing and object loading.
	 */
	public static void main(String[] args) throws Exception {
		OAXMLReader r = new OAXMLReader();
		r.debug = true;
		Object[] objs = r.readFile("C:\\Projects\\java\\OABuilder_git\\models\\tsac.obx");

		int xx = 4;
		xx++;
	}

}
