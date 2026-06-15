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

import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.xml.*;
import com.fasterxml.jackson.dataformat.xml.annotation.*;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.viaoa.json.OAJson;
import com.viaoa.json.jackson.OAJacksonModule;

/* xml NOTES:

    @JacksonXmlElementWrapper(useWrapping=false)
    @JacksonXmlProperty(localName = "TaxableGroup")

*/

/**
 * XML-based serialization implementation for OAObject graphs using Jackson's
 * {@link com.fasterxml.jackson.dataformat.xml.XmlMapper}.  
 * <p>
 * {@code OAXml} extends {@link com.viaoa.json.OAJson} but replaces the normal
 * JSON ObjectMapper with an XML ObjectMapper configured with:
 * <ul>
 *   <li>{@link com.fasterxml.jackson.datatype.jsr310.JavaTimeModule} for OA temporal types,</li>
 *   <li>{@link com.viaoa.json.jackson.OAJacksonModule} for OAObject identity and link handling,</li>
 *   <li>human-readable INDENT_OUTPUT,</li>
 *   <li>property inclusion rules compatible with OAJson.</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * The shared XML {@link ObjectMapper} instance is created lazily and guarded by
 * the lock inherited from {@link OAJson}, ensuring safe concurrent use.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * OAXml xml = new OAXml();
 * String s = xml.toXml(myObject);
 * }</pre>
 *
 * <p>
 * For one-off operations requiring a fresh mapper instance, callers may use
 * {@link #getUnsharedObjectMapper()}.
 */
public class OAXml extends OAJson {
	
	/**
	 * Shared XML {@link ObjectMapper} instance used for serialization and
	 * deserialization.
	 */
	private static ObjectMapper xmlObjectMapper;
	
	/**
	 * Returns the shared XML {@link ObjectMapper}, creating and configuring it
	 * lazily if necessary.
	 *
	 * @return the shared XML {@link ObjectMapper}
	 */
	public ObjectMapper getXmlObjectMapper() {
		if (xmlObjectMapper == null) {
			synchronized (lock) {
				if (xmlObjectMapper == null) {
				    xmlObjectMapper = createXmlObjectMapper();
				}				
			}
		}
		return xmlObjectMapper;
	}
	
	/**
	 * Creates and configures a new XML {@link ObjectMapper}.
	 * <p>
	 * The mapper is configured with OA-specific modules and serialization
	 * settings.
	 *
	 * @return a newly created XML {@link ObjectMapper}
	 */
	public ObjectMapper createXmlObjectMapper() {
		XmlMapper objectMapperx = new XmlMapper();
		objectMapperx.registerModule(new JavaTimeModule());
		objectMapperx.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
		objectMapperx.disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
		objectMapperx.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
	
		objectMapperx.setDefaultPropertyInclusion(Include.ALWAYS);
		// objectMapperx.setSerializationInclusion(Include.NON_NULL);
	
		objectMapperx.registerModule(new OAJacksonModule());
		objectMapperx.enable(SerializationFeature.INDENT_OUTPUT);
		return objectMapperx;
	}
	
	
	/**
	 * Returns the {@link ObjectMapper} used by this instance.
	 * <p>
	 * If not already set, this delegates to {@link #getXmlObjectMapper()}.
	 *
	 * @return the active {@link ObjectMapper}
	 */
	public ObjectMapper getObjectMapper() {
		if (objectMapper == null) {
			objectMapper = getXmlObjectMapper();
		}
		return objectMapper;
	}

	/**
	 * Creates and returns a new, unshared XML {@link ObjectMapper}.
	 * <p>
	 * This replaces the current mapper reference with a newly created instance.
	 *
	 * @return a new XML {@link ObjectMapper}
	 */
	public ObjectMapper getUnsharedObjectMapper() {
		objectMapper = createXmlObjectMapper();
		return objectMapper;
	}

	/**
	 * Serializes the supplied object to an XML string.
	 *
	 * @param obj the object to serialize
	 * @return the XML representation of the object
	 * @throws JsonProcessingException if serialization fails
	 */
	public String toXml(Object obj) throws JsonProcessingException {
		return write(obj);
	}
}
