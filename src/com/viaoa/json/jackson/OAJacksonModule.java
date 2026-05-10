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

import com.fasterxml.jackson.core.Version;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.viaoa.datetime.OADate;
import com.viaoa.datetime.OADateTime;
import com.viaoa.datetime.OATime;
import com.viaoa.object.OAObject;

/**
 * Jackson {@link com.fasterxml.jackson.databind.Module Module} that registers
 * OA-specific serializers and deserializers with an {@code ObjectMapper}.
 * <p>
 * The module installs:
 * <ul>
 *   <li>{@link OAJacksonSerializer} and {@link OAJacksonDeserializer} for
 *       {@link com.viaoa.object.OAObject} graphs.</li>
 *   <li>{@link OADateSerializer} / {@link OADateDeserializer} for
 *       {@link OADate}.</li>
 *   <li>{@link OADateTimeSerializer} / {@link OADateTimeDeserializer} for
 *       {@link OADateTime}.</li>
 *   <li>{@code OATimeSerializer} / {@code OATimeDeserializer} for
 *       {@link OATime}.</li>
 * </ul>
 * An OA-aware {@code ObjectMapper} can be created by registering this module,
 * allowing OA temporal types and {@link OAObject} graphs to round-trip through
 * Jackson JSON.
 */
public class OAJacksonModule extends SimpleModule {

	/**
	 * Constructs a new OA-aware Jackson module and registers serializers and
	 * deserializers for OAObject and temporal OA types.
	 * <p>
	 * The module is initialized with a fixed {@link Version}. Serializer and
	 * deserializer instances are then added for:
	 * <ul>
	 *   <li>{@link OAObject} — using {@link OAJacksonSerializer} and
	 *       {@link OAJacksonDeserializer}</li>
	 *   <li>{@link OADateTime} — using {@link OADateTimeSerializer} and
	 *       {@link OADateTimeDeserializer}</li>
	 *   <li>{@link OADate} — using {@link OADateSerializer} and
	 *       {@link OADateDeserializer}</li>
	 *   <li>{@link OATime} — using {@link OATimeSerializer} and
	 *       {@code OATimeDeserializer}</li>
	 * </ul>
	 *
	 * @see com.fasterxml.jackson.databind.ObjectMapper#registerModule
	 */
	public OAJacksonModule() {
		super("OAJackson", new Version(1, 0, 0, "RELEASE", "com.viaoa", "jackson"));

		addSerializer(OAObject.class, new OAJacksonSerializer());
		addDeserializer(OAObject.class, new OAJacksonDeserializer());

		addSerializer(OADateTime.class, new OADateTimeSerializer());
		addDeserializer(OADateTime.class, new OADateTimeDeserializer());

		addSerializer(OADate.class, new OADateSerializer());
		addDeserializer(OADate.class, new OADateDeserializer());

		addSerializer(OATime.class, new OATimeSerializer());
		addDeserializer(OATime.class, new OATimeDeserializer());
	}

}
