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

import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.Stack;
import java.util.UUID;

import com.viaoa.cascade.OACascade;
import com.viaoa.datetime.OADateTime;
import com.viaoa.graph.OAGraph;
import com.viaoa.hub.Hub;
import com.viaoa.lang.OAString;
import com.viaoa.metadata.OALinkInfo;
import com.viaoa.metadata.OAObjectInfo;
import com.viaoa.metadata.OAPropertyInfo;
import com.viaoa.object.OAObject;
import com.viaoa.object.OAObjectKey;
import com.viaoa.runtime.OARuntime;
import com.viaoa.secure.Base64;
import com.viaoa.serialize.OASerializeContext;
import com.viaoa.serialize.OASerializeWriter;
import com.viaoa.xml.hub.HubXMLService;
import com.viaoa.xml.object.OAObjectXMLService;

/**
 * Generates XML output for OAObject graphs in a format consumable by
 * {@link OAXMLReader}.  
 * <p>
 * {@code OAXMLWriter} supports:
 * <ul>
 *   <li>full object serialization with cascade control,</li>
 *   <li>key-only serialization for already-emitted objects,</li>
 *   <li>Hub serialization for MANY-valued links,</li>
 *   <li>CDATA output with optional Base64 encryption,</li>
 *   <li>import-match–only mode for migration scenarios,</li>
 *   <li>pretty-print indentation.</li>
 * </ul>
 *
 * <h2>Writing Modes</h2>
 * <ul>
 *   <li>{@link #WRITE_YES} – write full object,</li>
 *   <li>{@link #WRITE_KEYONLY} – write only key/GUID,</li>
 *   <li>{@link #WRITE_NO} – skip property entirely,</li>
 *   <li>{@link #WRITE_NONEW_KEYONLY} – suppress new objects in Hub writes.</li>
 * </ul>
 *
 * <h2>Encryption Support</h2>
 * If {@link #setEncodeMessage(String)} is set, CDATA values are encoded
 * as: {@code encodeMessage + Base64.encode(data)}.
 *
 * <h2>Customization Hooks</h2>
 * Subclasses may override:
 * <ul>
 *   <li>{@link #convertToString(String, Object)}</li>
 *   <li>{@link #shouldWriteProperty(Object, String, Object)}</li>
 *   <li>{@link #writing(Object)}</li>
 * </ul>
 *
 * <p>
 * This class is retained for backward compatibility.  
 * New code should use {@link OAXml} (Jackson-based).
 */
public class OAXMLWriter implements OASerializeWriter {
	
	protected PrintWriter pw;
	
	public int indent;
	
	private String pad = "";
	
	private int indentLast;
	
	public int indentAmount = 2;
	
	private boolean bInit;
	
	private String encodeMessage;

	/** param used to write an object */
	public static final int WRITE_YES = 0;
	/** param used to only write an object key */
	public static final int WRITE_KEYONLY = 1;
	/** param used to not write an object */
	public static final int WRITE_NO = 2;
	/** param used to not write any new objects in a hub, so that Hub does not write new objects. Used in OAObject.log() for M2M links */
	public static final int WRITE_NONEW_KEYONLY = 3;
	protected Hashtable hash; // used for storing objects without an Object Key - updated by OAObject

	private boolean bIncludeEmptyHubs;
	private boolean bIncludeNullProperties;
	private boolean bIncludeOnlyImportMatchProperties;
	private OACascade cascade;
	
	private OAObjectXMLService srvcObjectXML;
	private HubXMLService srvcHubXML;
	

	public OAXMLWriter(String fname) {
		setFileName(fname);
	}

	public OAXMLWriter(PrintWriter pw) {
		setPrintWriter(pw);
	}

	public void setPrintWriter(PrintWriter pw) {
		close();
		this.pw = pw;
	}

	public void setFileName(String fname) {
		try {
			setPrintWriter(new PrintWriter(new FileWriter(fname)));
		} catch (Exception e) {
		}
	}

	public void setIncludeOnlyImportMatchProperties(boolean b) {
		bIncludeOnlyImportMatchProperties = b;
	}

	public boolean getIncludeOnlyImportMatchProperties() {
		return bIncludeOnlyImportMatchProperties;
	}

	/** saves OAObject as XML */
	public void write(OAObject obj) {
		if (cascade == null) {
			cascade = new OACascade();
		}
		getOAObjectXMLService().write(obj, this, null, false, cascade);
	}

	/** saves Hub as XML */
	public void write(Hub hub) {
		if (cascade == null) {
			cascade = new OACascade();
		}
		final OAGraph og =  OARuntime.graph(hub);
		getHubXMLService().write(hub, this, null, false, cascade);
	}

	/**
	 * callback used to know if property should be written
	 * 
	 * @deprecated use {@link #shouldWriteProperty(Object, String, Object)}
	 */
	public int writeProperty(Object obj, String propertyName, Object value) {
		return shouldWriteProperty(obj, propertyName, value);
	}

	/**
	 * called by OAObject.write to know if object should be written for a property that references another OAObject. This can be overwritten
	 * to control which properties are saved. default: if o default = WRITE_YES;
	 */
	public int shouldWriteProperty(Object obj, String propertyName, Object value) {
		if (bIncludeOnlyImportMatchProperties && obj instanceof OAObject) {
			OAObject oaObj = (OAObject) obj;
			final OAGraph og =  OARuntime.graph(oaObj);
			OAObjectInfo io = og.internal().objects().info().getOAObjectInfo(oaObj);
			if (!io.hasImportMatchProperties()) {
				return WRITE_NO;
			}
		}
		return WRITE_YES;
	}

	private ArrayList<OAObject> alWillBeWriting = new ArrayList<OAObject>();;

	public void addWillBeWriting(OAObject obj) {
		alWillBeWriting.add(obj);
	}

	public void removeWillBeWriting(OAObject obj) {
		alWillBeWriting.remove(obj);
	}

	/**
	 * returns true if this object will be included by another parent object.
	 */
	public boolean willBeIncludedLater(OAObject oaObj) {
		if (oaObj == null) {
			return false;
		}
		if (cascade.wasCascaded(oaObj, false)) {
			return false; // already included
		}

		if (alWillBeWriting.contains(oaObj)) {
			return true;
		}

		final OAGraph og =  OARuntime.graph(oaObj);
		OAObjectInfo oi = og.internal().objects().info().getOAObjectInfo(oaObj);
		OALinkInfo li = oi.getOwnedByOne();
		if (li == null) {
			return false;
		}

		Object objx = og.internal().objects().property().getProperty(oaObj, li.getName(), false, true);
		if (objx == null) {
			return false;
		}
		if (!(objx instanceof OAObject)) {
			return false;
		}

		if (cascade.wasCascaded((OAObject) objx, false)) {
			return false;
		}

		// parent has not yet been written
		// now need to find if a parent is already include
		if (isAnyOwnerAlreadyIncluded((OAObject) objx)) {
			return true;
		}

		return false;
	}

	private boolean isAnyOwnerAlreadyIncluded(OAObject oaObj) {
		if (oaObj == null) {
			return false;
		}

		final OAGraph og =  OARuntime.graph(oaObj);
		OAObjectInfo oi = og.internal().objects().info().getOAObjectInfo(oaObj);
		OALinkInfo li = oi.getOwnedByOne();
		if (li == null) {
			return false;
		}

		Object objx = og.internal().objects().property().getProperty(oaObj, li.getName(), false, true);
		if (objx == null) {
			return false;
		}

		if (cascade.wasCascaded((OAObject) objx, false)) {
			return true;
		}

		return isAnyOwnerAlreadyIncluded((OAObject) objx);
	}

	/**
	 * hook used to know when an object is being saved.
	 */
	public void writing(Object obj) {
	}

	protected void finalize() throws Throwable {
		close();
		super.finalize();
	}

	public void setIndentAmount(int x) {
		this.indentAmount = x;
	}

	public void flush() {
		if (pw != null) {
			pw.flush();
		}
	}

	public void close() {
		if (pw != null) {
			end();
			pw.flush();
			pw.close();
			pw = null;
		}
		bEnd = false;
	}

	private boolean bEnd;

	public void end() {
		if (!bEnd) {
			bEnd = true;
			indent--;
			println("</OAXML>");
		}
	}

	/**
	 * Used to encrypt all data.
	 * <p>
	 * NOTE: currently only works with printCDATA. This needs to be changed so that all printXX methods encrypt data.
	 * 
	 * @see OAXMLReader#setDecodeMessage
	 */
	public void setEncodeMessage(String msg) {
		if (msg != null && msg.length() == 0) {
			throw new IllegalArgumentException("EncodeMessage cant be an empty string");
		}
		encodeMessage = msg;
	}

	public String getEncodeMessage() {
		return encodeMessage;
	}

	/** encloses line in XML CDATA tag and internally encodes illegal XML characters */
	public void printCDATA(String line) {
		if (pw == null) {
			throw new NullPointerException("PrintWrite is null");
		}
		if (!bInit) {
			init();
		}
		if (encodeMessage != null) {
			line = encodeMessage + Base64.encode(line);
		}
		pw.print("<![CDATA[" + OAString.convertToXml(line, true) + "]]>");
	}

	/** converts XML codes and encodes illegal XML characters */
	public void printXML(String line) {
		if (pw == null) {
			throw new NullPointerException("PrintWrite is null");
		}
		if (!bInit) {
			init();
		}
		pw.print(OAString.convertToXml(line, false));
	}

	public void print(String line) {
		if (pw == null) {
			throw new NullPointerException("PrintWriter is null");
		}
		if (!bInit) {
			init();
		}
		pw.print(line);
	}

	public void println(String line) {
		if (pw == null) {
			throw new NullPointerException("PrintWriter is null");
		}
		if (!bInit) {
			init();
		}
		pw.println(line);
	}

	public void indent() {
		if (pw == null) {
			throw new NullPointerException("PrintWriter is null");
		}
		if (!bInit) {
			init();
		}

		if (indent != indentLast) {
			indentLast = indent;
			pad = "";
			int x = indent * indentAmount;
			for (int i = 0; i < x; i++) {
				pad += " ";
			}
		}
		pw.print(pad);
	}

	protected void init() {
		bInit = true;
		// println("<?xml version='1.0'?>");
		println("<?xml version='1.0' encoding='utf-8'?>");
		//was:  println("<?xml version='1.0' encoding='windows-1252'?>");
		println("<OAXML VERSION='2.0' DATETIME='" + (new OADateTime()) + "'>");
	}

	/**
	 * Method that can be overwritten to provide custom conversion to String. Called to convert a value to a String when there does not
	 * exist an OAConverter for a class.
	 * 
	 * @return null to skip property.
	 */
	public String convertToString(String property, Object value) {
		return null;
	}

	public String getClassName(Class c) {
		return c.getName();
	}

	public void setIncludeEmptyHubs(boolean b) {
		bIncludeEmptyHubs = b;
	}

	public boolean getIncludeEmptyHubs() {
		return bIncludeEmptyHubs;
	}

	public void setIncludeNullProperties(boolean b) {
		bIncludeNullProperties = b;
	}

	public boolean getIncludeNullProperties() {
		return bIncludeNullProperties;
	}

	private Stack<String> stack = new Stack<String>();

	/**
	 * Shows the curret list of objects that are being saved.
	 */
	public String getCurrentPropertyPath() {
		int x = stack.size();
		String s = "";
		for (int i = 0; i < x; i++) {
			if (i > 0) {
				s += ".";
			}
			s += stack.get(i);
		}
		return s;
	}

	/**
	 * Used internally when writing objects to show the objects that are being written.
	 * 
	 * @see #getCurrentPropertyPath()
	 */
	public void push(String name) {
		stack.push(name);
	}

	public void pop() {
		if (!stack.isEmpty()) {
			stack.pop();
		}
	}
	
	public OAObjectXMLService getOAObjectXMLService() {
		if (srvcObjectXML != null) return srvcObjectXML;
		srvcObjectXML = new OAObjectXMLService() {
			OAGraph og;
			public OAGraph getOG(Class<? extends OAObject> c) {
				if (og == null) og = OARuntime.graph(c);
				return og;
			}
			public OAGraph getOG(OAObject obj) {
				if (og == null) og = OARuntime.graph(obj.getClass());
				return og;
			}
			public OAGraph getOG(Hub<?> hub) {
				if (og == null) og = OARuntime.graph(hub.getObjectClass());
				return og;
			}
			
			@Override
			public Object callReflectGetProperty(OAObject oaObj, String propPath) {
				return getOG(oaObj).internal().objects().reflect().getProperty(oaObj, propPath);
			}
			
			@Override
			public String[] callPropertyGetPropertyNames(OAObject oaObj) {
				return getOG(oaObj).internal().objects().property().getPropertyNames(oaObj);
			}
			
			@Override
			public Object callPropertyGetProperty(OAObject oaObj, String name, boolean bReturnNotExist, boolean bConvertWeakRef) {
				return getOG(oaObj).internal().objects().property().getProperty(oaObj, name, bReturnNotExist, bConvertWeakRef);
			}
			
			@Override
			public OAObjectInfo callInfoGetOAObjectInfo(OAObject obj) {
				return getOG(obj).internal().objects().info().getOAObjectInfo(obj);
			}
			
			@Override
			public OALinkInfo callInfoGetLinkInfo(OAObjectInfo oi, String propertyName) {
				return oi.getLinkInfo(propertyName);
			}
			
			@Override
			public void callHubXMLWrite(Hub<?> thisHub, OAXMLWriter ow, String tagName, int writeType, OACascade cascade) {
				getHubXMLService().write(thisHub, ow, tagName, bEnd, cascade);
			}
			
			@Override
			public UUID callGuidGetGuid(OAObject oaObj) {
				return getOG(oaObj).internal().objects().guid().getGuid(oaObj);
			}
		};
        return srvcObjectXML;
	}

	
	public HubXMLService getHubXMLService() {
		if (srvcHubXML != null) return srvcHubXML;
		srvcHubXML = new HubXMLService() {
			OAGraph og;
			public OAGraph getOG(OAObject obj) {
				if (og == null) og = OARuntime.graph(obj.getClass());
				return og;
			}
			@Override
			public void callObjectXMLWrite(OAObject oaObj, OAXMLWriter ow, String tagName, boolean bKeyOnly, OACascade cascade) {
				getOAObjectXMLService().write(oaObj, ow, tagName, bKeyOnly, cascade, bKeyOnly);
			}
		};
        return srvcHubXML;
	}
}
