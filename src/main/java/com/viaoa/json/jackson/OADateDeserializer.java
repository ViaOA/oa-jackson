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

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.viaoa.datetime.OADate;

/**
 * Jackson {@link JsonDeserializer} for {@link OADate}.
 * <p>
 * This deserializer expects the JSON value to be a {@code String} that matches
 * {@link OADate#JsonFormat}. It converts the text into an {@link OADate} instance
 * when reading JSON into OA-aware objects or POJOs.
 * <p>
 * This is typically registered by {@link OAJacksonModule} and used automatically
 * by Jackson {@code ObjectMapper} instances configured for OA.
 */
public class OADateDeserializer extends JsonDeserializer<OADate> {

	/**
	 * Deserializes the current JSON value into an {@link OADate}.
	 * <p>
	 * The method reads the text from the {@link JsonParser}. If the text is
	 * {@code null}, it returns {@code null}. Otherwise, it creates a new
	 * {@link OADate} using the text and {@link OADate#JsonFormat}.
	 *
	 * @param jp   the JSON parser positioned at the value to deserialize
	 * @param ctxt the deserialization context supplied by Jackson
	 * @return the parsed {@link OADate}, or {@code null} if the JSON text is {@code null}
	 * @throws IOException      if an I/O error occurs while reading from the parser
	 * @throws JacksonException if a Jackson-specific parsing error occurs
	 */
	@Override
	public OADate deserialize(JsonParser jp, DeserializationContext ctxt) throws IOException, JacksonException {
		String s = jp.getText();
		if (s == null) {
			return null;
		}

		OADate d = new OADate(s, OADate.JsonFormat);

		return d;
	}
}
