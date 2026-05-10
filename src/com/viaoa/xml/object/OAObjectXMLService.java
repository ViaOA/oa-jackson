package com.viaoa.xml.object;


import java.lang.reflect.Modifier;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import com.viaoa.cascade.OACascade;
import com.viaoa.converter.OAConv;
import com.viaoa.converter.OAConverter;
import com.viaoa.datetime.OADate;
import com.viaoa.datetime.OADateTime;
import com.viaoa.datetime.OATime;
import com.viaoa.hub.Hub;
import com.viaoa.lang.OAString;
import com.viaoa.metadata.OALinkInfo;
import com.viaoa.metadata.OAObjectInfo;
import com.viaoa.metadata.OAPropertyInfo;
import com.viaoa.object.OAObject;
import com.viaoa.xml.OAXMLWriter;

public abstract class OAObjectXMLService {
	private static final Logger LOG = Logger.getLogger(OAObjectXMLService.class.getName());

    public OAObjectXMLService() {
    }

	/**
	 * Writes the specified {@link OAObject} to XML using the supplied
	 * {@link OAXMLWriter}. This is a convenience method that delegates
	 * to the overloaded {@code write(..., boolean bWriteClassName)} version
	 * with {@code bWriteClassName} set to {@code false}.
	 *
	 * @param oaObj the object to serialize
	 * @param ow the XML writer receiving output
	 * @param tagName the tag name to wrap the serialized object
	 * @param bKeyOnly whether to write key-only references
	 * @param cascade the cascade controller used for recursion checks
	 */
	public void write(final OAObject oaObj, final OAXMLWriter ow, final String tagName, boolean bKeyOnly, final OACascade cascade) {
		write(oaObj, ow, tagName, bKeyOnly, cascade, false);
	}

	/**
	 * Serializes an {@link OAObject} to XML using the provided
	 * {@link OAXMLWriter}. Wraps the output in the specified tag and
	 * delegates full object serialization to the internal {@link #_write}
	 * implementation.
	 * <p>
	 * Behavior visible in this method:
	 * <ul>
	 *   <li>Returns immediately if the object or writer is {@code null}.</li>
	 *   <li>Pushes and later pops the tag name around the generated XML.</li>
	 *   <li>Calls the private {@code _write(...)} method to perform
	 *       attribute creation, property output, and recursive link writing.</li>
	 * </ul>
	 *
	 * @param oaObj the object to serialize
	 * @param ow the XML writer receiving output
	 * @param tagName the XML tag name to write
	 * @param bKeyOnly whether to write only object identifiers
	 * @param cascade cascade controller for recursion and key-only decisions
	 * @param bWriteClassName whether to include the class attribute
	 */
	public void write(final OAObject oaObj, final OAXMLWriter ow, String tagName, boolean bKeyOnly, final OACascade cascade,
			final boolean bWriteClassName) {
		if (oaObj == null || ow == null) {
			return;
		}
		try {
			if (tagName != null) {
				ow.push(tagName);
			}
			_write(oaObj, ow, tagName, bKeyOnly, cascade, bWriteClassName);
		} finally {
			if (tagName != null) {
				ow.pop();
			}
		}
	}

	/**
	 * Internal implementation method that performs full XML serialization of
	 * the specified {@link OAObject}. This method writes object identifiers,
	 * property values, link references, nested objects, and hub contents.
	 * <p>
	 * Behavior visible in this method:
	 * <ul>
	 *   <li>Determines whether the object should be output key-only based on
	 *       cascade rules and {@link OAXMLWriter#willBeIncludedLater}.</li>
	 *   <li>Constructs XML attributes including {@code id} or {@code idref},
	 *       and optionally {@code class}.</li>
	 *   <li>Outputs regular properties from {@link OAObjectInfo} using
	 *       {@link OAObjectReflectDelegate#getProperty} and formats values
	 *       using {@link OAXMLWriter} and OA temporal classes.</li>
	 *   <li>Writes link properties according to metadata flags and
	 *       {@link OAXMLWriter#shouldWriteProperty} rules.</li>
	 *   <li>Writes additional dynamic properties via
	 *       {@link OAObjectPropertyDelegate#getProperty} when permitted.</li>
	 *   <li>Handles nested objects and hubs through recursive calls and
	 *       {@link HubXMLDelegate}.</li>
	 * </ul>
	 *
	 * @param oaObj the object being serialized
	 * @param ow the XML writer receiving output
	 * @param tagName name of the XML element to create
	 * @param bKeyOnly whether the output should contain only identifiers
	 * @param cascade cascade state controller
	 * @param bWriteClassName whether to include the class attribute
	 */
	private void _write(final OAObject oaObj, final OAXMLWriter ow, String tagName, boolean bKeyOnly, final OACascade cascade,
			final boolean bWriteClassName) {
		Class<? extends OAObject> c = oaObj.getClass();
		OAObjectInfo oi = callInfoGetOAObjectInfo(oaObj);

		// 20150909
		if (!bKeyOnly) {
			if (ow.willBeIncludedLater(oaObj)) {
				bKeyOnly = true;
			} else if (cascade.wasCascaded(oaObj, true)) {
				bKeyOnly = true;
			}
		}
		String attrib = " ";
		if (bKeyOnly) {
			attrib += "idref=\"g" + callGuidGetGuid(oaObj) + "\"";
		} else {
			attrib += "id=\"g" + callGuidGetGuid(oaObj) + "\"";
		}

		if ((bWriteClassName && !bKeyOnly) || tagName == null) {
			attrib += " class=\"" + ow.getClassName(c) + "\"";
		}

		String[] ids = oi.getIdProperties();
		if (bKeyOnly) {
			attrib += "/";
			//if (ids == null || ids.length == 0) attrib += "/";
		}

		if (tagName == null) {
			tagName = c.getSimpleName();
		}

		ow.indent();
		ow.println("<" + tagName + attrib + ">");

		ow.writing(oaObj); // hook to let oaxmlwriter subclass know when objects are being written
		if (bKeyOnly) {
			return;
			//if (bKeyOnly && (ids == null || ids.length == 0)) return;
		}

		ow.indent++;

		List<OAPropertyInfo> alProp = oi.getPropertyInfos(); // reg props, not link props
		for (OAPropertyInfo pi : alProp) {

			String propName = pi.getName();
			Object value = callReflectGetProperty(oaObj, propName);
			if (value == null) {
				continue;
			}

			if (OAConverter.getConverter(value.getClass()) == null && !(value instanceof String)) {
				if (value instanceof OAObject) {
					write(((OAObject) value), ow, propName, false, cascade, true);
					continue;
				}
				Class cval = value.getClass();
				value = ow.convertToString(propName, value);
				if (value == null) {
					continue;
				}
				ow.indent();
				ow.print("<" + propName + " class=\"" + ow.getClassName(cval) + "\">");
			} else {
				ow.indent();
				ow.print("<" + propName + ">");
			}

			if (value instanceof String) {
				if (OAString.isLegalXml((String) value)) {
					ow.printXML((String) value);
				} else {
					ow.printCDATA((String) value);
				}
			} else if (value instanceof OADate) {
				ow.print(((OADate) value).toString("yyyy-MM-dd"));
			} else if (value instanceof OATime) {
				ow.print(((OATime) value).toString("HH:mm:ss"));
			} else if (value instanceof OADateTime) {
				ow.print(((OADateTime) value).toString("yyyy-MM-dd HH:mm:ss"));
			} else {
				value = OAConv.toString(value);
				if (OAString.isLegalXml((String) value)) {
					ow.printXML((String) value);
				} else {
					ow.printCDATA((String) value);
				}
			}
			ow.println("</" + propName + ">");
		}

		// Save link properties
		List<OALinkInfo> alLink = oi.getLinkInfos();
		for (OALinkInfo li : alLink) {
			if (li.getTransient()) {
				continue;
			}
			if (li.getCalculated()) {
				continue;
			}
			if (li.getPrivateMethod()) {
				continue;
			}
			if (!li.getUsed()) {
				continue;
			}

			// Method m = oi.getPropertyMethod(c, "get"+li.getProperty());
			// if (m == null) continue;
			Object obj = callReflectGetProperty(oaObj, li.getName());
			// Object obj = ClassModifier.getPropertyValue(this, m);
			if (obj == null && !ow.getIncludeNullProperties()) {
				continue;
			}

			if (bKeyOnly && !isObjectKey(li.getName(), ids)) {
				continue;
			}

			int x = ow.shouldWriteProperty(oaObj, li.getName(), obj);
			if (x != ow.WRITE_NO) {
				if (obj instanceof OAObject) {
					boolean b = Modifier.isAbstract(li.getToClass().getModifiers());
					write(((OAObject) obj), ow, li.getName(), (x == ow.WRITE_KEYONLY), cascade, b);
				} else if (obj instanceof Hub) {
					Hub<?> h = (Hub) obj;
					if (h.getSize() > 0 || ow.getIncludeEmptyHubs()) {
						callHubXMLWrite(h, ow, li.getName(), x, cascade); // 2006/09/26
					}
				}
			}
		}
		if (!bKeyOnly) {
			String[] propNames = callPropertyGetPropertyNames(oaObj);
			for (int i = 0; propNames != null && i < propNames.length; i++) {
				String key = propNames[i];
				if (callInfoGetLinkInfo(oi, key) != null) {
					continue;
				}
				Object value = callPropertyGetProperty(oaObj, key, false, true);
				if (value == null) {
					continue;
				}

				if (ow.writeProperty(oaObj, key, value) != ow.WRITE_YES) {
					continue;
				}

				Class<?> cval = value.getClass();
				if (value instanceof String) {
					;
				} else if (value instanceof OADate) {
					value = ((OADate) value).toString("yyyy-MM-dd");
				} else if (value instanceof OATime) {
					value = ((OATime) value).toString("HH:mm:ss");
				} else if (value instanceof OADateTime) {
					value = ((OADateTime) value).toString("yyyy-MM-dd HH:mm:ss");
				} else {
					if (OAConverter.getConverter(value.getClass()) == null && !(value instanceof String)) {
						value = ow.convertToString((String) key, value);
						if (value == null) {
							continue;
						}
					}
					value = OAConv.toString(value);
				}

				ow.indent();
				if (cval.equals(String.class)) {
					ow.print("<" + key + ">");
				} else {
					ow.print("<" + key + " class=\"" + ow.getClassName(cval) + "\">");
				}
				if (OAString.isLegalXml((String) value)) {
					ow.printXML((String) value);
				} else {
					ow.printCDATA((String) value);
				}
				ow.println("</" + key + ">");
			}
		}

		ow.indent--;
		ow.indent();
		ow.println("</" + tagName + ">");
	}

	/**
	 * Determines whether the specified property name matches one of the
	 * object's identifier properties.
	 *
	 * @param propertyName the name to test
	 * @param propIds the list of identifier property names
	 * @return {@code true} if the name matches an identifier property,
	 *         otherwise {@code false}
	 */
	private boolean isObjectKey(String propertyName, String[] propIds) {
		if (propertyName == null || propIds == null) {
			return false;
		}
		for (int i = 0; i < propIds.length; i++) {
			if (propertyName.equalsIgnoreCase(propIds[i])) {
				return true;
			}
		}
		return false;
	}

	public abstract OAObjectInfo callInfoGetOAObjectInfo(OAObject obj);
	public abstract UUID callGuidGetGuid(OAObject oaObj); 
	public abstract Object callReflectGetProperty(OAObject oaObj, String propPath);
	public abstract String[] callPropertyGetPropertyNames(OAObject oaObj);
	public abstract OALinkInfo callInfoGetLinkInfo(OAObjectInfo oi, String propertyName); 
	public abstract Object callPropertyGetProperty(OAObject oaObj, String name, boolean bReturnNotExist, boolean bConvertWeakRef);
	public abstract void callHubXMLWrite(Hub<?> thisHub, OAXMLWriter ow, final String tagName, int writeType, OACascade cascade);
}
