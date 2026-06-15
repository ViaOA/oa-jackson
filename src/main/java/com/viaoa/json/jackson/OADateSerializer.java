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
import com.viaoa.datetime.OADate;

/**
 * Jackson {@link com.fasterxml.jackson.databind.JsonSerializer JsonSerializer}
 * for {@link OADate}.
 * <p>
 * This serializer writes dates as JSON strings using
 * {@link OADate#JsonFormat}. A {@code null} {@link OADate} value is written
 * as JSON {@code null}.
 * <p>
 * It is typically registered by {@link OAJacksonModule} so that OA-aware
 * {@code ObjectMapper} instances can transparently serialize {@link OADate}
 * properties.
 */
public class OADateSerializer extends JsonSerializer<OADate> {

	/**
	 * Serializes an {@link OADate} value into JSON.
	 * <p>
	 * If the supplied {@code value} is {@code null}, the method writes a JSON
	 * {@code null}. Otherwise, it writes the date as a formatted string using
	 * {@link OADate#toString(String)} with {@link OADate#JsonFormat}.
	 *
	 * @param value        the {@link OADate} to serialize; may be {@code null}
	 * @param gen          the JSON generator used to write output
	 * @param serializers  the provider that can access serializer configuration
	 * @throws IOException if writing to the generator fails
	 */
	@Override
	public void serialize(OADate value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
		if (value == null) {
			gen.writeNull();
		} else {
			gen.writeString(value.toString(OADate.JsonFormat));
		}
	}
}
