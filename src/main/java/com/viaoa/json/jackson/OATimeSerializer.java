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

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.viaoa.datetime.OATime;

/**
 * Jackson {@link com.fasterxml.jackson.databind.JsonSerializer JsonSerializer}
 * for {@link OATime}.
 * <p>
 * This serializer writes an {@link OATime} value as a JSON string using
 * {@link OATime#JsonFormat}. A {@code null} value is written as JSON
 * {@code null}.
 * </p>
 * <p>
 * This serializer is typically registered through {@link OAJacksonModule}
 * so that OA temporal types round-trip cleanly when using Jackson for
 * JSON I/O.
 * </p>
 */
public class OATimeSerializer extends JsonSerializer<OATime> {

	/**
	 * Serializes an {@link OATime} value into its JSON representation.
	 * <p>
	 * If {@code value} is {@code null}, a JSON {@code null} is written.
	 * Otherwise, the time is formatted using {@link OATime#JsonFormat} and written
	 * as a JSON string.
	 *
	 * @param value        the {@link OATime} instance to serialize; may be null
	 * @param gen          JSON generator used to write the output
	 * @param serializers  provider for accessing serialization configuration
	 * @throws IOException if writing to the JSON generator fails
	 */
	@Override
	public void serialize(OATime value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
		if (value == null) {
			gen.writeNull();
		} else {
			gen.writeString(value.toString(OATime.JsonFormat));
		}
	}
}
