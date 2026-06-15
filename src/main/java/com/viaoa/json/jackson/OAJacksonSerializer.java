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
package com.viaoa.json.jackson;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.viaoa.converter.OAConv;
import com.viaoa.converter.OAConverter;
import com.viaoa.datetime.OADate;
import com.viaoa.datetime.OADateTime;
import com.viaoa.datetime.OATime;
import com.viaoa.graph.api.internal.OAGraphInternal;
import com.viaoa.graph.service.object.OAObjectInfoService;
import com.viaoa.graph.service.object.OAObjectPropertyService;
import com.viaoa.graph.service.object.OAObjectReflectService;
import com.viaoa.hub.Hub;
import com.viaoa.json.OAJson;
import com.viaoa.json.OAJson.StackItem;
import com.viaoa.lang.OAString;
import com.viaoa.metadata.OALinkInfo;
import com.viaoa.metadata.OAObjectInfo;
import com.viaoa.metadata.OAPropertyInfo;
import com.viaoa.object.OAObject;
import com.viaoa.object.OAObjectKey;
import com.viaoa.path.OAPath;
import com.viaoa.metadata.pojo.*;
import com.viaoa.runtime.OARuntime;
import com.viaoa.runtime.OAThreadLocalService;
import com.viaoa.runtime.OAThreadService;

// todo: needs to use "compoundKey"
// qqqqqq concat string, separated by '-'

/**
 * Used by OAJson to convert OAObject(s) & Hub to JSON. Includes mapping to work with POJO classes.
 * <p>
 */
public class OAJacksonSerializer extends JsonSerializer<OAObject> {

	/**
	 * Serializes the supplied {@link OAObject} into JSON.
	 * <p>
	 * Establishes the root {@link StackItem} when necessary, then delegates
	 * serialization to {@link #_serialize(OAJson, OAObject, OAObjectInfo, OAObject, JsonGenerator, SerializerProvider)}.
	 *
	 * @param value        the OAObject to serialize
	 * @param gen          JSON generator for writing output
	 * @param serializers  serializer provider
	 * @throws IOException if writing fails
	 */
	@Override
	public void serialize(final OAObject value, final JsonGenerator gen, final SerializerProvider serializers) throws IOException {
		final OAThreadLocalService srvcOAThreadLocal = ((OAThreadService) OARuntime.thread()).getThreadLocalService();  

		OAJson oaj = (OAJson) serializers.getAttribute("oajson");
	    if (oaj == null) {
	        throw new IllegalStateException("OAJson serializer context missing");
	    }		
		
		final OAObject oaObj = (OAObject) value;

		final OAGraphInternal og = (OAGraphInternal) OARuntime.graph(oaObj);
		final OAObjectInfo oi = og.objectsInternal().callObjectInfoGetObjectInfo(oaObj.getClass());

		gen.writeStartObject();

		boolean b = (oaj.getStackItem() == null);
		if (b) {
			StackItem stackItem = new StackItem();
			stackItem.parent = null;
			stackItem.oi = oi;
			stackItem.li = null;
			stackItem.obj = value;
			oaj.setStackItem(stackItem);

		}
		try {
			_serialize(oaj, oaObj, oi, value, gen, serializers);
		} finally {
			if (b) {
				oaj.setStackItem(null);
			}
		}
	}

	/**
	 * Core serialization routine for writing OAObject and Hub data structures.
	 * <p>
	 * Writes ID properties, non-ID properties, applies property callbacks,
	 * includes POJO-specific fields when configured, and serializes link-one
	 * and link-many associations according to cascade and inclusion rules.
	 *
	 * @param oaj         the current OAJson context
	 * @param oaObj       the object being serialized
	 * @param oi          OAObject metadata
	 * @param value       the root object value
	 * @param gen         JSON output generator
	 * @param serializers provider for additional serializers
	 * @throws IOException if writing fails
	 */
	protected void _serialize(final OAJson oaj, final OAObject oaObj, final OAObjectInfo oi, final OAObject value, final JsonGenerator gen,
			final SerializerProvider serializers) throws IOException {

		//qqqqqq if writeAsPojo, then this needs to us OAObjectInfo.pojo to determine Id properties
		//qqq  if more than one, use compoundKey as string with '-'

		// write id props
		boolean bNullId = true;
		for (OAPropertyInfo pi : oi.getPropertyInfos()) {
			if (!pi.getId()) {
				continue;
			}

			if (oaj.getWriteAsPojo() && pi.getAutoAssign()) {
				if (OAString.isNotEmpty(oi.getImportMatchPropertyNames())) {
					boolean b = false;
					for (String pp : oi.getImportMatchPropertyPaths()) {
						OAPath ppx = new OAPath(oi.getForClass(), pp);
						if (pp.indexOf('.') < 0 && pp.equalsIgnoreCase(pi.getName())) {
							b = true;
							break;
						}
					}
					if (!b) {
						continue;
					}
				}
			}

			String propertyName = pi.getLowerName();
			Object objx = pi.getValue(oaObj);

			if (!oaj.getUsePropertyCallback(oaObj, propertyName)) {
				continue;
			}
			propertyName = oaj.getPropertyNameCallback(oaObj, propertyName);
			objx = oaj.getPropertyValueCallback(oaObj, propertyName, objx);

			if (objx == null) {
				gen.writeNullField(pi.getLowerName());
			} else {
				writeProperty(pi, gen, oaObj);
				bNullId = false;
			}
		}

		if (bNullId && !oaj.getWriteAsPojo()) {
			gen.writeStringField("guid", oaObj.getGuid().toString());
		}

		// write (non-id) props
		for (OAPropertyInfo pi : oi.getPropertyInfos()) {
			if (pi.getId()) {
				continue;
			}
			if (pi.getIsFkeyOnly() && oaj.getWriteAsPojo()) {
				continue;
			}
			if (pi.getEnumPropertyName() != null) {
			    if (pi.getName().endsWith("String")) continue;
			}
			writeProperty(pi, gen, oaObj);
		}

		if (oaj.getWriteAsPojo()) {
			Pojo pojo = oi.getPojo();
			writeExtraPojoProperties(oaj, oi, oaObj, gen);
		}

		final ArrayList<String> alPropertyPaths = oaj == null ? null : oaj.getPropertyPaths();
		final boolean bIncludeOwned = oaj == null ? true : oaj.getIncludeOwned();

		// write one references
		for (OALinkInfo li : oi.getLinkInfos()) {
			if (li.getType() != li.TYPE_ONE) {
				continue;
			}
			if (li.getPrivateMethod()) {
				continue;
			}

			if (li.getCalculated()) {
				continue;
			}

			/*
			if (oaj.getWriteAsPojo()) {
				//qqqq make this dynamic and use propertyPaths to include
				if (!li.getOwner()) {
					continue;
				}
			}
			*/

			String propertyName = li.getLowerName();
			if (!oaj.getUsePropertyCallback(oaObj, propertyName)) {
				continue;
			}

			boolean bSerialized = false;

			if ((oaj != null && oaj.getIncludeAll()) || shouldInclude(oaj, li, bIncludeOwned, alPropertyPaths)) {
				propertyName = oaj.getPropertyNameCallback(oaObj, propertyName);
				StackItem si = new StackItem();
				si.parent = oaj.getStackItem();
				si.li = li;
				si.obj = oaObj;
				oaj.setStackItem(si);

				try {
					OAObject objx = (OAObject) li.getValue(oaObj);
					objx = (OAObject) oaj.getPropertyValueCallback(oaObj, propertyName, objx);

					if (objx == null) {
						gen.writeNullField(propertyName);
						bSerialized = true;
					} else {
						if (oaj != null && !oaj.getCascade().wasCascaded(objx, true)) {
							bSerialized = true;
							gen.writeObjectField(li.getLowerName(), objx);
						} else {
							bSerialized = false;
						}
					}

				} finally {
					oaj.setStackItem(si.parent);
				}
			}

			if (!bSerialized) {
				OAObjectKey key = null;
				final OAGraphInternal og = (OAGraphInternal) OARuntime.graph(oaObj);
				Object obj = og.objectsInternal().callObjectPropertyGetProperty(oaObj, li.getName(), false, true);

				obj = oaj.getPropertyValueCallback(oaObj, li.getLowerName(), obj);

				if (obj instanceof OAObject) {
					key = ((OAObject) obj).getObjectKey();
				} else if (obj instanceof OAObjectKey) {
					key = (OAObjectKey) obj;
				}

				if (key == null) {
					gen.writeNullField(propertyName);
				} else {
					String id = OAJson.convertObjectKeyToJsonSinglePartId(key);

					if (id.indexOf('-') >= 0 || id.indexOf("guid.") == 0) {
						gen.writeStringField(li.getLowerName(), id);
					} else {
						if (OAString.isNumber(id)) {
							gen.writeNumberField(li.getLowerName(), OAConv.toLong(id));
						} else {
							gen.writeStringField(li.getLowerName(), id);
						}
					}
				}
			}
		}

		// write many references
		for (OALinkInfo li : oi.getLinkInfos()) {
			if (li.getType() != li.TYPE_MANY) {
				continue;
			}
			if (li.getPrivateMethod()) {
				continue;
			}
			if (li.getCalculated()) {
				continue;
			}

			String propertyName = li.getLowerName();
			if (!oaj.getUsePropertyCallback(oaObj, propertyName)) {
				continue;
			}

			if (oaj.getWriteAsPojo()) {
				if (!li.getOwner()) {
					continue;
				}
			}


			if ((oaj != null && oaj.getIncludeAll()) || shouldInclude(oaj, li, bIncludeOwned, alPropertyPaths)) {
				StackItem si = new StackItem();
				si.parent = oaj.getStackItem();
				si.li = li;
				oaj.setStackItem(si);
				try {
					Hub hub = (Hub) li.getValue(oaObj);

					gen.writeArrayFieldStart(propertyName);

					for (OAObject objx : (Hub<OAObject>) li.getValue(oaObj)) {
						si.obj = objx;

						// check cascade to see if its been sent ...if so, then only output key (/guid)
						// note:  deserializer needs to check array values for object, string, number to "know" how to get it

						if (oaj != null && !oaj.getCascade().wasCascaded(objx, true)) {
							gen.writeObject(objx);
						} else {

							OAObjectKey key = objx.getObjectKey();
							String id = OAJson.convertObjectKeyToJsonSinglePartId(key);

							if (id.indexOf('-') >= 0 || id.indexOf("guid.") == 0) {
								gen.writeString(id);
							} else {
								gen.writeNumber(OAConv.toLong(id));
							}
						}
					}

				} finally {
					oaj.setStackItem(si.parent);
					gen.writeEndArray();
				}
			} else {
				// if hub is loaded and it is empty, then send empty array (for convenience only)
				final OAGraphInternal og = (OAGraphInternal) OARuntime.graph(oaObj);
				Object obj = og.objectsInternal().callObjectPropertyGetProperty(oaObj, li.getName(), false, true);
				if (obj instanceof Hub) {
					if (((Hub) obj).isEmpty()) {
						gen.writeArrayFieldStart(li.getLowerName());
						gen.writeEndArray();
					}
				}

			}
		}

		// todo: want to add any calcs ??

		gen.writeEndObject();
	}

	/**
	 * Writes additional POJO-related fields not directly represented as OAObject
	 * properties. Includes foreign-key, import-match, and unique-property values
	 * derived from POJO metadata.
	 *
	 * @param oaj    OAJson context
	 * @param oi     OAObject metadata
	 * @param oaObj  object being serialized
	 * @param gen    JSON generator
	 * @throws IOException if writing fails
	 */
	protected void writeExtraPojoProperties(final OAJson oaj, final OAObjectInfo oi, final OAObject oaObj, final JsonGenerator gen)
			throws IOException {
		Pojo pojo = oi.getPojo();
		for (PojoLink pl : pojo.getPojoLinks()) {
			PojoLinkOne plo = pl.getPojoLinkOne();
			if (plo != null) {
				writePojoLinkOne(oaj, oi, oaObj, gen, plo);
			}
		}
	}

	/**
	 * Writes POJO link-one auxiliary properties used to support various uniqueness,
	 * import-match, and foreign-key lookup strategies.
	 *
	 * @param oaj    OAJson context
	 * @param oi     OAObject metadata
	 * @param oaObj  source object
	 * @param gen    JSON generator
	 * @param plo    POJO link-one metadata
	 * @throws IOException if writing fails
	 */
	protected void writePojoLinkOne(final OAJson oaj, final OAObjectInfo oi, final OAObject oaObj, final JsonGenerator gen,
			final PojoLinkOne plo) throws IOException {

		// fkeys
		for (PojoLinkFkey plf : plo.getPojoLinkFkeys()) {
			PojoProperty pjp = plf.getPojoProperty();
			writePojoProperty(oaj, oi, oaObj, gen, pjp);
		}

		// importMatches
		for (PojoImportMatch pim : plo.getPojoImportMatches()) {
			PojoProperty pjp = pim.getPojoProperty();
			if (pim != null) {
				writePojoProperty(oaj, oi, oaObj, gen, pjp);
			}

			PojoLinkOneReference plor = pim.getPojoLinkOneReference();
			if (plor == null) {
				continue;
			}
			PojoLinkOne plox = plor.getPojoLinkOne();
			writePojoLinkOne(oaj, oi, oaObj, gen, plo);
		}

		// link with unique property
		PojoLinkUnique plu = plo.getPojoLinkUnique();
		if (plu != null) {
			PojoProperty pjp = plu.getPojoProperty();
			if (pjp != null) {
				writePojoProperty(oaj, oi, oaObj, gen, pjp);
			}

			PojoLinkOneReference plor = plu.getPojoLinkOneReference();
			if (plor != null) {
				PojoLinkOne plox = plor.getPojoLinkOne();
				writePojoLinkOne(oaj, oi, oaObj, gen, plox);
			}
		}
	}

	/**
	 * Writes a single POJO property determined by {@link PojoProperty}.
	 * <p>
	 * Resolves the OA property via {@link OAPath}, applies callbacks,
	 * and writes the value in JSON format.
	 *
	 * @param oaj    OAJson context
	 * @param oi     OAObject metadata
	 * @param oaObj  object containing the property
	 * @param gen    JSON generator
	 * @param pjp    POJO property metadata
	 * @throws IOException if writing fails
	 */
	protected void writePojoProperty(final OAJson oaj, final OAObjectInfo oi, final OAObject oaObj, final JsonGenerator gen,
			final PojoProperty pjp) throws IOException {
		String propertyName = pjp.getName();
		String pp = pjp.getPropertyPath();
		OAPath ppx = new OAPath(oi.getForClass(), pp);
		OAPropertyInfo pi = ppx.getEndPropertyInfo();

		Object objx = oaObj.getProperty(pp);

		propertyName = oaj.getPropertyNameCallback(oaObj, propertyName);
		objx = oaj.getPropertyValueCallback(oaObj, propertyName, objx);

		if (objx == null) {
			gen.writeNullField(propertyName);
		} else {
			writeProperty(pi, propertyName, objx, gen, oaObj);
		}
	}

	/**
	 * Determines whether a link should be included in serialization based on
	 * OAJson property-path filters, ownership, and cascade rules.
	 *
	 * @param oaj             OAJson context
	 * @param li              link metadata
	 * @param bIncludeOwned   whether owned objects should be included
	 * @param alPropertyPaths property-path inclusion filters
	 * @return true if the link should be serialized; otherwise false
	 */
	protected boolean shouldInclude(OAJson oaj, OALinkInfo li, boolean bIncludeOwned, ArrayList<String> alPropertyPaths) {
		if (li == null) {
			return false;
		}

		OAJson.StackItem si = oaj.getStackItem();
		
		if (alPropertyPaths == null || alPropertyPaths.size() == 0) {
	        if (bIncludeOwned && (li.getOwner() || li.getAutoCreateNew())) {
	            boolean bx = si == null || si.parent == null; // root only
	            return bx;
	        }
			return false;
		}
		
		
		String cpp = oaj.getCurrentPropertyPath();
        if (cpp != null) cpp = cpp.toLowerCase();

		String cpp2 = OAString.append(cpp, li.getName().toLowerCase(), ".");

		for (String pp : alPropertyPaths) {
            if (pp.toLowerCase().indexOf(cpp2) == 0) {
                return true;
            }
		    
		    if (!bIncludeOwned) continue;
		    
            if (cpp != null && pp.toLowerCase().indexOf(cpp) != 0) continue;
            if (li.getOwner() || li.getAutoCreateNew()) {
                return true;
            }
		}

		return false;
	}

	/**
	 * Writes a scalar OA property using its lower-case name and the property's
	 * current value from the object.
	 *
	 * @param pi     property metadata
	 * @param gen    JSON generator
	 * @param oaObj  object containing the property
	 * @throws IOException if writing fails
	 */
	protected void writeProperty(OAPropertyInfo pi, JsonGenerator gen, OAObject oaObj) throws IOException {
		writeProperty(pi, pi.getLowerName(), null, gen, oaObj);
	}

	/**
	 * Writes a property value using the supplied field name, performing type
	 * conversions, null handling, format application, and raw-JSON output for
	 * properties marked as JSON.
	 *
	 * @param pi         metadata for the property
	 * @param lowerName  output JSON field name
	 * @param value      pre-computed value or null to auto-resolve
	 * @param gen        JSON generator
	 * @param oaObj      object containing the property
	 * @throws IOException if writing fails
	 */
	protected void writeProperty(OAPropertyInfo pi, final String lowerName, Object value, JsonGenerator gen, OAObject oaObj)
			throws IOException {
		boolean bCheckValue = (value == null);

		if (bCheckValue) {
			value = pi.getValue(oaObj);
			final OAGraphInternal og = (OAGraphInternal) OARuntime.graph(oaObj);
			if (pi.getIsPrimitive() && pi.getTrackPrimitiveNull() && og.objectsInternal().callObjectReflectGetPrimitiveNull(oaObj, lowerName)) {
				value = null;
			}
		}
		if (value == null) {
			gen.writeNullField(lowerName);
			return;
		}

		if (value != null && !(value instanceof String) && OAConverter.getConverter(value.getClass()) == null) {
			gen.writeNullField(lowerName);
			return;
		}

		/* there are now prop*Enum and prop*String properties that will be a String
		if (pi.isNameValue() && (value instanceof Integer)) {
			value = (String) pi.getNameValues().get((Integer) value);
		}
		*/

		if (value instanceof String) {
			if (pi.isJson()) {
				gen.writeFieldName(lowerName);
				gen.writeRawValue((String) value);
			} else {
				gen.writeStringField(lowerName, (String) value);
			}
		} else if (value instanceof Boolean) {
			gen.writeBooleanField(lowerName, (boolean) value);
		} else if (value instanceof BigDecimal) {
			gen.writeNumberField(lowerName, (BigDecimal) value);
		} else if (value instanceof Double) {
			BigDecimal bd = OAConv.toBigDecimal((Double) value, pi.getDecimalPlaces());
			gen.writeNumberField(lowerName, bd);
		} else if (value instanceof Float) {
			BigDecimal bd = OAConv.toBigDecimal((Float) value, pi.getDecimalPlaces());
			gen.writeNumberField(lowerName, bd);
		} else if (value instanceof Long) {
			gen.writeNumberField(lowerName, (Long) value);
		} else if (value instanceof Integer) {
			gen.writeNumberField(lowerName, (Integer) value);
		} else if (value instanceof Short) {
			gen.writeNumberField(lowerName, (Short) value);
		} else if (value instanceof OADate) {
			String fmt = pi.getFormat();
			if (OAString.isEmpty(fmt)) {
				fmt = "yyyy-MM-dd";
			}
			String result = ((OADate) value).toString(fmt);
			gen.writeStringField(lowerName, result);
		} else if (value instanceof OATime) {
			String fmt = pi.getFormat();
			if (OAString.isEmpty(fmt)) {
				fmt = "HH:mm:ss";
			}
			String result = ((OATime) value).toString(fmt);
			gen.writeStringField(lowerName, result);
		} else if (value instanceof OADateTime) {
			String fmt = pi.getFormat();
			if (OAString.isEmpty(fmt)) {
				fmt = "yyyy-MM-dd'T'HH:mm:ss";
			}
			String result = ((OADateTime) value).toString(fmt); // "2020-12-26T19:21:09"
			gen.writeStringField(lowerName, result);
		} else if (value instanceof byte[]) {
			gen.writeBinaryField(lowerName, (byte[]) value);
		} else {
			String result = OAConv.toString(value);
			gen.writeStringField(lowerName, result);
		}
	}

}
